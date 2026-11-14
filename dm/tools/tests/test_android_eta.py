"""The live estimate of the time a Prepare has left (Eta.java, android/app/src/ondevice; android/README.md, "Time left while preparing"): EtaJvmTest runs it on the JVM through a
steady rate, a falling one (a phone that heats up), a pause and resume, a service restart in the middle of the shaders step, a stall, a total that changes, a
shader update of 584 shaders, the change from the split to the shaders step and the shaders step's two stages (translate, then the library), the rounding and
the texts, and replays the two full Ocarina of Time Prepares measured on the POCO F8 Pro (android/app/src/jvmtest/prepare-eta-*.trace.txt: timestamps and
counts only), printing the estimate's error at 10, 25, 50, 75 and 90 % of the run. Service: PackService feeds it and sends its minutes to the launcher and the
notification. The JVM part is skipped without a JDK."""
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
# the full 6-worker Prepare (the default): the estimate's error at these points of the run may not exceed this share of the true time left; at 10 % the split's
# scan has just begun and there is no estimate yet (none is better than a guess). At 75 % the vertex phase has just begun: the window still holds the slower phase 1
BOUND_6W = {25: 0.10, 50: 0.10, 75: 0.15, 90: 0.10}


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
        for case in ('steady', 'falling', 'pause and resume', 'service restart', 'stall', 'total changes', 'tiny update', 'step change', 'two-stage shaders step', 'words', 'mix'):
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
        self.assertEqual(len(got), 24)

    def test_replay(self):
        rows = {(n, int(p)): (None if e == '-' else float(e), float(t)) for n, p, e, t, _ in re.findall(r'^replay (\S+) (\d+) (\S+) (\S+) (-?\d+)$', self.out, re.M)}
        self.assertEqual(len(rows), 10, self.out)
        self.assertIsNone(rows[('6w', 10)][0], 'an estimate 44 s into the split\'s scan')
        for p, bound in BOUND_6W.items():
            est, true = rows[('6w', p)]
            self.assertIsNotNone(est, f'no estimate at {p} %')
            self.assertLessEqual(abs(est - true) / true, bound, f'6 workers at {p} %: {est:.0f} s against {true:.0f} s')
        # the 8-worker run's vertex phase was a third slower than the 6-worker one's (I/O bound): the estimate is short there, but within a quarter
        for p in (25, 50, 75, 90):
            est, true = rows[('8w', p)]
            self.assertLessEqual(abs(est - true) / true, 0.25, f'8 workers at {p} %')


class Service(unittest.TestCase):
    def test_wiring(self):
        """PackService gives Eta every progress line and the running state, sends its minutes to the launcher ("eta_min", -1 for none) and the notification
        and asks it every 10 s; the launcher reads the value with a default, so an older service (no value) shows none"""
        s = (ONDEVICE / 'PackService.java').read_text(encoding='utf-8')
        o = (ONDEVICE / 'Ondevice.java').read_text(encoding='utf-8')
        self.assertIn('est.progress(index, d, t, m.group(3), SystemClock.elapsedRealtime())', s)   # Tail: every progress line
        self.assertEqual(s.count('est.running(false, SystemClock.elapsedRealtime())'), 2)   # the worker's end, a wait for the battery or the heat
        self.assertEqual(s.count('est.running(true, SystemClock.elapsedRealtime())'), 2)    # the worker's start, the end of a wait
        self.assertIn('est.step(i, SystemClock.elapsedRealtime())', s)
        self.assertIn('b.putInt("eta_min", etaMin)', s)
        self.assertIn('d.getInt("eta_min", -1)', o)
        self.assertIn('Eta.shaderMix(', s)
        self.assertIn('ShaderLibs.SHADER_S_HI', s)


if __name__ == '__main__':
    unittest.main()
