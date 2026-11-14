"""The live estimate of the time a Prepare has left (Eta.java, android/app/src/ondevice; android/README.md, "Time left while preparing"): EtaJvmTest runs it on the JVM through a
steady rate, a falling one (a phone that heats up), a pause and resume (with the tool's 70 s set-up after it), a service restart in the middle of the shaders
step, a stall (the value after it), a total that changes, totals of 0, a trickle, a shader update of 584 shaders, the change from the split to the shaders step
and the fixed figure's hand-over to the measured rate, the shaders step's two stages (translate, then the library) and a library that runs over, the clock,
the rounding and the texts, and the profile map's mix. It replays the three full Ocarina of Time Prepares measured on the POCO F8 Pro
(android/app/src/jvmtest/prepare-eta-*.trace.txt: timestamps and counts only), printing the estimate's error at 10, 25, 50, 75 and 90 % of the run, and again
at the tool's own cadence with stalls of 90 s to 10 minutes and an hour's pause put in at 40 minutes. Service: PackService feeds it and sends its minutes to
the launcher and the notification. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
JVMTEST = REPO / 'android/app/src/jvmtest'
TEST = JVMTEST / 'java/dev/nxnative/android/EtaJvmTest.java'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'
# The current build's full 6-worker Prepare (the default; prepare-eta-6w-current.trace.txt, the figures Eta ships with): the estimate's error at these points of
# the run may not exceed this share of the true time left. At 25 % the window still holds the translation's first, faster minutes; at 90 % less than 6 minutes
# are left and the vertex shaders' cost is the guess (VERTEX_COST)
BOUND_CURRENT = {10: 0.10, 25: 0.15, 50: 0.10, 75: 0.10, 90: 0.30}
# the two runs of the morning's build (results on shared storage), replayed with that build's library and pack figures (their "build" line): the translation's
# behaviour with heat and with 8 workers, whose vertex phase was a third slower (I/O bound, a vertex cost of 0.67 against Eta's 0.4)
BOUND_OLD = {25: 0.30, 50: 0.30, 75: 0.15, 90: 0.10}


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Estimator(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='eta_'))
        subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', str(cls.tmp / 'classes'), str(ONDEVICE / 'Eta.java'), *map(str, TR), str(TEST)],
                       check=True, capture_output=True, text=True)
        r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', TR_RES, '-cp', str(cls.tmp / 'classes'), 'dev.nxnative.android.EtaJvmTest',
                            str(JVMTEST)], capture_output=True, text=True, encoding='utf-8')
        cls.out, cls.code, cls.err = r.stdout, r.returncode, r.stderr
        print(cls.out)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_cases(self):
        self.assertEqual(self.code, 0, self.out + self.err)
        self.assertIn('all ok', self.out)
        for case in ('steady', 'falling', 'pause and resume', 'service restart', 'stall', 'total changes', 'tiny update', 'step change', 'two-stage shaders step', 'words', 'mix', 'zero', 'trickle',
                     'unweighted', 'takeover', 'gaps 6w', 'gaps 8w', 'gaps 6w-current'):
            self.assertIn(f'ok {case}\n', self.out)

    def test_languages(self):
        """Every form in each language, plain "sen" Turkish as the other Prepare texts"""
        got = dict(re.findall(r'^text (\S+ \d+): (.*)$', self.out, re.M))
        self.assertEqual(got['tr 45'], 'Yaklaşık 45 dakika kaldı')
        self.assertEqual(got['tr 75'], 'Yaklaşık 1 saat 15 dakika kaldı')
        self.assertEqual(got['tr 0'], 'Bir dakikadan az kaldı')
        self.assertEqual(got['pt-BR 1'], 'Falta cerca de 1 minuto')
        self.assertEqual(got['pt-BR 125'], 'Faltam cerca de 2 horas e 5 minutos')
        self.assertEqual(got['es-419 45'], 'Quedan unos 45 minutos')
        self.assertEqual(got['es-419 60'], 'Queda alrededor de 1 hora')
        self.assertEqual(got['es-419 75'], 'Quedan alrededor de 1 hora y 15 minutos')   # the plural subject "1 hora y 15 minutos"
        self.assertEqual(len(got), 24)

    def test_replay(self):
        rows = {(n, int(p)): (None if e == '-' else float(e), float(t)) for n, p, e, t, _ in re.findall(r'^replay (\S+) (\d+) (\S+) (\S+) (-?\d+)$', self.out, re.M)}
        self.assertEqual(len(rows), 15, self.out)
        for name, bounds in (('6w-current', BOUND_CURRENT), ('6w', BOUND_OLD), ('8w', BOUND_OLD)):
            for p, bound in bounds.items():
                est, true = rows[(name, p)]
                self.assertIsNotNone(est, f'{name}: no estimate at {p} %')
                self.assertLessEqual(abs(est - true) / true, bound, f'{name} at {p} %: {est:.0f} s against {true:.0f} s')
        self.assertIsNone(rows[('6w', 10)][0], 'an estimate 44 s into the split\'s scan')

class Service(unittest.TestCase):
    def test_wiring(self):
        """PackService gives Eta every progress line and the running state, sends its minutes to the launcher ("eta_min", -1 for none) and the notification
        and asks it every 10 s; the launcher reads the value with a default, so an older service (no value) shows none"""
        s = (ONDEVICE / 'PackService.java').read_text(encoding='utf-8')
        o = (ONDEVICE / 'Ondevice.java').read_text(encoding='utf-8')
        self.assertIn('est.progress(index, d, t, m.group(3), SystemClock.elapsedRealtime())', s)   # Tail: every progress line (and a log line when the mix does not fit)
        self.assertEqual(s.count('est.running(false, SystemClock.elapsedRealtime())'), 2)   # the worker's end, a wait for the battery or the heat
        self.assertEqual(s.count('est.running(true, SystemClock.elapsedRealtime())'), 2)    # the worker's start, the end of a wait
        self.assertIn('est.step(i, SystemClock.elapsedRealtime())', s)
        self.assertIn('b.putInt("eta_min", etaMin)', s)
        self.assertIn('d.getInt("eta_min", -1)', o)
        self.assertIn('Eta.shaderMix(profileMap(j))', s)   # the map the tool takes its shaders and total from
        self.assertIn('Eta.SHADER_S)', s)


if __name__ == '__main__':
    unittest.main()
