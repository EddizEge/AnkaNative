"""A Prepare job whose process died (JobDeath.java, JobWatch.java, PackService.java, Ondevice.java, PrepareGate.java; android/app/src/ondevice),. The phone test of 2026-11-14 (POCO F8 Pro, HyperOS): Android never restarted the service after its process
died ("MIUILOG- Reject RestartService"), the notification went with it, and the job stood still until the app was opened and Resume tapped. The app now starts
it again itself where Android lets it (its main process's watch, Home's open, a game's end) and otherwise posts a notification that it stopped. The decisions
run on the JVM (JobDeathJvmTest); the rest checks that the service, the watch and Home use them as written. Skipped without a JDK (the JVM part)."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
MAIN = REPO / 'android/app/src/main/java/dev/nxnative/android'
RES = REPO / 'android/app/src/main/res'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/JobDeathJvmTest.java'


def code(p):
    return p.read_text(encoding='utf-8')


def body(s, start, end='\n    }\n'):
    return s[s.index(start):s.index(end, s.index(start))]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Decide(unittest.TestCase):
    def test_decisions(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(d / 'classes'), str(ONDEVICE / 'JobDeath.java'),
                                str(ONDEVICE / 'JobForm.java'), str(MAIN / 'GameGate.java'), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.JobDeathJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('all ok', r.stdout)


class Service(unittest.TestCase):
    s = code(ONDEVICE / 'PackService.java')

    def test_start_rule(self):
        """A sticky restart and the app's own start (EXTRA_AUTO) resume only a job that should be working, and count its starts of a step (MAX_STARTS)"""
        start = body(self.s, 'public int onStartCommand(')
        self.assertIn('boolean auto = intent != null && intent.getBooleanExtra(EXTRA_AUTO, false);', start)
        self.assertIn('JobDeath.go(intent != null, auto, j.state(), j.reason(), j.apkChanged())', start)
        self.assertIn('return begin(j, intent != null && !auto, startId);', start)

    def test_pid(self):
        """MSG_STATE carries the service's pid, for the watch's exit record"""
        pub = body(self.s, 'private void publish(Job j, boolean force)')
        self.assertEqual(pub.count('b.putInt("pid", Process.myPid());'), 2)


class Watch(unittest.TestCase):
    w = code(ONDEVICE / 'JobWatch.java')

    def test_binds_without_starting(self):
        self.assertIn('c.bindService(new Intent(c, PackService.class), this, 0)', self.w)
        self.assertIn('JobWatch.start(a);', code(ONDEVICE / 'Ondevice.java'))

    def test_death_decides_by_job_and_exit(self):
        died = body(self.w, 'private void died(int p, boolean worker)')
        self.assertIn('JobDeath.after(j.state(), j.reason(), false, exit == null ? JobDeath.EXIT_UNKNOWN : exit.getReason(), PrepareGate.gameRunning(c))', died)
        self.assertIn('restart(c, j, "its process ended")', died)

    def test_refused_start_tells_the_user(self):
        r = body(self.w, 'static void restart(Context c, Job j, String why)')
        self.assertIn('.putExtra(PackService.EXTRA_AUTO, true)', r)
        self.assertLess(r.index('catch (RuntimeException e)'), r.rindex('stopped(c, j);'))   # Android refused the start
        self.assertLess(r.index('if (!JobDeath.mayStart(now, lastAuto))'), r.index('stopped(c, j);'))   # it ended again right after one: no loop
        self.assertIn('postDelayed(watch.verify, VERIFY_MS)', r)
        v = body(self.w, 'private void verify()')
        self.assertIn('JobDeath.notBack(j.state(), j.reason(), alive)', v)
        self.assertIn('stopped(c, j);', v)

    def test_stopped_note(self):
        n = body(self.w, 'static void stopped(Context c, Job j)')
        self.assertIn('nm.notify(PackService.NOTE, n);', n)   # in the job's notification's place
        self.assertIn('.putExtra(Ondevice.EXTRA_RESUME, j.file.getPath()).putExtra(Ondevice.EXTRA_TOKEN, token)', n)
        self.assertIn('putString("resume_token", token)', n)
        self.assertIn('R.string.prepare_note_stopped', n)
        self.assertIn('PendingIntent.getForegroundService(', n)


class Home(unittest.TestCase):
    o = code(ONDEVICE / 'Ondevice.java')

    def test_after_death(self):
        show = body(self.o, '        void show() {', '\n        }\n')
        self.assertIn('afterDeath();', show)
        ad = body(self.o, '        void afterDeath() {', '\n        }\n')
        self.assertIn('now - resumedAt < 2500', ad)   # the binding found no service
        self.assertIn('JobDeath.mayStart(now, JobWatch.lastAuto)', ad)
        self.assertIn('key.equals(afterDeathKey)', ad)
        self.assertIn('startService(job, true);', ad)
        self.assertNotIn('afterGame', self.o.replace('PrepareGate.afterGame', ''))

    def test_resume_from_note(self):
        self.assertIn('panel.resumeFromNote(resume, i.getStringExtra(EXTRA_TOKEN));', self.o)
        r = body(self.o, '        void resumeFromNote(String path, String token) {', '\n        }\n')
        self.assertLess(r.index('want.equals(token)'), r.index('startService(job);'))
        self.assertIn('remove("resume_token")', r)
        m = code(MAIN / 'MainActivity.java')
        self.assertIn('i.hasExtra(Ondevice.EXTRA_RESUME)', m)


class Game(unittest.TestCase):
    def test_game_end_restarts_a_waiting_job(self):
        g = code(ONDEVICE / 'PrepareGate.java')
        ag = body(g, 'static void afterGame(Activity a)')
        self.assertIn('GameGate.REASON.equals(j.reason())', ag)
        self.assertIn('.putExtra(PackService.EXTRA_AUTO, true)', ag)
        self.assertIn('PrepareGate.afterGame(this);', body(code(MAIN / 'GameActivity.java'), 'private void end()'))
        self.assertIn('PrepareGate.afterGame(act);', code(MAIN / 'SidePanel.java'))


class Texts(unittest.TestCase):
    KEYS = ('prepare_note_stopped', 'prepare_note_stopped_text')

    def test_four_languages(self):
        for d in ('values', 'values-tr', 'values-pt-rBR', 'values-b+es+419'):
            t = (RES / d / 'strings_prepare.xml').read_text(encoding='utf-8')
            for k in self.KEYS:
                self.assertIn(f'name="{k}"', t, f'{d}: {k}')


if __name__ == '__main__':
    unittest.main()
