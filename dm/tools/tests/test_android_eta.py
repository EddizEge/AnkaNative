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
        compact = dict(re.findall(r'^compact (\S+ \d+): (.*)$', self.out, re.M))
        self.assertEqual(compact['tr 45'], '45 dk')
        self.assertEqual(compact['tr 75'], '1 sa 15 dk')
        self.assertEqual(compact['tr 0'], '<1 dk')
        self.assertEqual(compact['pt-BR 1'], '1 min')
        self.assertEqual(compact['pt-BR 125'], '2 h 5 min')
        self.assertEqual(compact['es-419 45'], '45 min')
        self.assertEqual(compact['es-419 60'], '1 h')
        self.assertEqual(len(compact), 21)
        # after the state in a collapsed notification's title: on the POCO F8 Pro (HyperOS, the phone check of rc1) the old title kept 13 characters
        # ("45 dk kaldı ·…") beside the subText and the time; the longest form (Eta says nothing above 10 hours) stays within 10 characters
        self.assertLessEqual(max(len(v) for v in compact.values()), 10, compact)

    def test_carried(self):
        """A new process goes on from the rate its process before kept (job.json "eta_carry"): at once the old process's estimate, and as near afterwards"""
        rows = re.findall(r'^carried (\S+) (\d+) (\d+) (\d+) (\d+)$', self.out, re.M)
        self.assertEqual(len(rows), 9, self.out)
        for name, p, got, old, true in rows:
            self.assertLessEqual(abs(int(got) - int(old)) / int(old), 0.05, f'{name} at {p} %')
        for case in ('carried', 'carried 6w', 'carried 8w', 'carried 6w-current'):
            self.assertIn(f'ok {case}\n', self.out)

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

    def test_collapsed_title(self):
        """Collapsed, Android 12+ shows a notification as one row of title, subText and time (with a progress bar, no text under it); on HyperOS (the phone
        check of rc1, 2026-11-15) the title "45 dk kaldı · Hazırlanıyor: <game>" kept 13 characters, "45 dk kaldı ·…", beside "Adım 4 / 5…" and "şimdi". Now
        the ongoing forms have no subText and no time, the running title is the state and the time left in short, and the game is in the text and the
        expanded title"""
        s = (ONDEVICE / 'PackService.java').read_text(encoding='utf-8')
        note = s[s.index('private Notification note(Job j, int etaMin, boolean live)'):s.index('private String simulated(')]
        self.assertIn('getString(R.string.prepare_note_running_short, Eta.compactText(etaMin).text())', note)
        self.assertIn('.setContentText(name)', note)
        self.assertIn('setBigContentTitle(simulated(getString(R.string.prepare_note_running, name), sim))', note)
        self.assertIn('getString(R.string.prepare_note_running_eta_big, steps, step, Eta.text(etaMin).text())', note)
        self.assertNotIn('setSubText(', note)
        self.assertEqual(note.count('.setShowWhen(false)'), 2)   # waiting; running and pausing

    def test_collapsed_titles_fit(self):
        """Each language's collapsed titles, with the longest time left the notification shows (under 10 h) and the longest state word: the running title
        at most 25 characters (the old one was cut at 13 beside a subText and a time, which the ongoing forms no longer have), and every other form starts
        with its state, so the cut, if any, takes the game's name"""
        import xml.etree.ElementTree as ET
        res = REPO / 'android/app/src/main/res'
        for d in ('values', 'values-tr', 'values-pt-rBR', 'values-b+es+419'):
            t = {e.get('name'): (e.text or '') for e in ET.parse(res / d / 'strings_prepare.xml').getroot() if e.tag == 'string'}
            run = t['prepare_note_running_short']
            for m in (0, 1, 45, 59, 60, 75, 595):
                left = (t['prepare_eta_compact_less'] if m == 0 else t['prepare_eta_compact_minutes'].replace('%1$d', str(m)) if m < 60
                        else t['prepare_eta_compact_hours'].replace('%1$d', str(m // 60)) if m % 60 == 0
                        else t['prepare_eta_compact_hours_minutes'].replace('%1$d', str(m // 60)).replace('%2$d', str(m % 60)))
                title = run.replace('%1$s', left)
                self.assertLessEqual(len(title), 25, f'{d}: "{title}"')
            for k in ('prepare_note_running', 'prepare_note_waiting', 'prepare_note_pausing', 'prepare_note_paused', 'prepare_note_failed', 'prepare_note_stopped',
                      'prepare_note_done'):
                self.assertFalse(t[k].startswith('%1$s'), f'{d}: {k} "{t[k]}" starts with the game')
                self.assertLessEqual(t[k].index('%1$s'), 20, f'{d}: {k} "{t[k]}": the state is long')

    def test_carry_wiring(self):
        """The rate goes into job.json with the count (Tail), and a new estimator of the job is offered it (etaFor)"""
        s = (ONDEVICE / 'PackService.java').read_text(encoding='utf-8')
        self.assertIn('j.etaCarry(est.carry());', s)
        self.assertIn('double[] carry = j.etaCarry();', s)
        self.assertIn('eta.carried(carry);', s)
        j = (ONDEVICE / 'Job.java').read_text(encoding='utf-8')
        self.assertIn('e.optInt("version_code", -1) != BuildConfig.VERSION_CODE', j)   # another build's rate is not taken


if __name__ == '__main__':
    unittest.main()
