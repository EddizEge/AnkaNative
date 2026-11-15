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

    def test_pause_without_worker(self):
        """A Pause that finds no worker (Home's card in its first 2.5 s on a job whose process died) pauses the remembered job instead of being dropped, so Home's
        automatic resume (afterDeath) and the app's own starts leave it alone"""
        start = body(self.s, 'public int onStartCommand(')
        self.assertLess(start.index('pauseDead();'), start.index('if (ACTION_GAME.equals(action))'))
        p = body(self.s, 'private void pauseDead() {')
        self.assertIn('if (!pausable(j)) return;', p)
        self.assertLess(p.index('j.state("paused", "user", null);'), p.index('j.save();'))
        self.assertIn('postNote(j, -1, false);', p)

    def test_alarm(self):
        """The job alarm (JobAlarm): set when a worker starts, moved on by the tick while it runs, cancelled when it ends cleanly; the receiver is declared"""
        self.assertIn('JobAlarm.arm(this, true);', body(self.s, 'private int begin(Job j, boolean user, int startId) {'))
        self.assertIn('JobAlarm.arm(PackService.this, false);', body(self.s, 'private final Runnable tick = new Runnable() {', '\n    };\n'))
        self.assertIn('JobAlarm.cancel(this);', body(self.s, 'private void finished() {'))
        for f in ('private void endForGame(Job j) {', 'private void gamePause() {', 'private void requestStop(String reason) {'):
            self.assertNotIn('JobAlarm.cancel', body(self.s, f), f)   # a process that ends itself keeps its alarm
        m = code(REPO / 'android/app/src/ondevice/AndroidManifest.xml')
        self.assertRegex(m, r'<receiver\s+android:name="\.JobAlarm"\s+android:exported="false"')
        a = code(ONDEVICE / 'JobAlarm.java')
        self.assertIn('public final class JobAlarm extends BroadcastReceiver', a)
        self.assertIn('public JobAlarm() {}', a)
        r = body(a, '    @Override public void onReceive(Context c, Intent i) {')
        self.assertIn('JobDeath.after(j.state(), j.reason(), pack, exit == null ? JobDeath.EXIT_UNKNOWN : exit.getReason(), game)', r)
        self.assertIn('if (act == JobDeath.Act.RESUME) JobWatch.restart(c, j,', r)
        self.assertIn('if (again) arm(c, true);', r)
        self.assertIn('set(AlarmManager.ELAPSED_REALTIME,', a)   # inexact and not waking: no permission
        self.assertNotIn('setExact', a)
        self.assertIn('else if (JobDeath.checkAgain(j.state(), j.reason(), false, PrepareGate.gameRunning(c))) JobAlarm.arm(c, true);',
                      body(code(ONDEVICE / 'JobWatch.java'), 'private void died(int p, boolean worker)'))

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

    def test_death_is_the_binders(self):
        """The phone check of rc1 (2026-11-15): "the job's process N ended (no exit record)" after every clean stop of the service (23 Pauses, 3 finished
        jobs) while the process lived on. A clean stop also calls onServiceDisconnected (and onBindingDied) for a binding without BIND_AUTO_CREATE; only the
        death of the service's binder, which lives in the job's process, is that process's end"""
        dis = body(self.w, '@Override public void onServiceDisconnected(ComponentName n)')
        self.assertNotIn('died(', dis)
        self.assertNotIn('postDelayed', dis)
        con = body(self.w, '@Override public void onServiceConnected(ComponentName n, IBinder b)')
        self.assertIn('b.linkToDeath(d, 0);', con)
        self.assertIn('link.b.unlinkToDeath(link, 0);', con)
        self.assertIn('h.post(() -> ended(d));', con)   # already dead when linked
        self.assertIn('@Override public void binderDied() { h.post(() -> ended(this)); }', self.w)
        self.assertIn('h.postDelayed(() -> died(d.pid, d.worker), 500);', body(self.w, 'private void ended(Death d)'))
        import re
        self.assertEqual(len(re.findall(r'\bdied\(', re.sub(r'/\*.*?\*/|//[^\n]*', '', self.w, flags=re.S))), 2)   # the method and its one call, from ended

    def test_exit_record_of_the_jobs_process(self):
        """The phone check of rc1: Home took the latest :pack exit record of any process (an older Force stop) 0.5 s after a kill -9. The record must be the
        job's last process's (PackService.begin writes its pid and start into job.json; JobDeath.fits), and an end the watch saw is the watch's to decide"""
        s = code(ONDEVICE / 'PackService.java')
        self.assertIn('j.process(Process.myPid(), System.currentTimeMillis());', body(s, 'private int begin(Job j, boolean user, int startId) {'))
        self.assertIn('JobWatch.exitOf(this, j)', s)
        self.assertNotIn('exitOf(this, 0)', s)
        self.assertIn('JobWatch.exitOf(c, j)', code(ONDEVICE / 'JobAlarm.java'))
        ex = body(self.w, 'static ApplicationExitInfo exitOf(Context c, Job j)')
        self.assertIn('JobDeath.fits(i.getPid(), i.getTimestamp(), pid, j.pidStart(), j.updated())', ex)
        o = code(ONDEVICE / 'Ondevice.java')
        ad = body(o, '        void afterDeath() {', '\n        }\n')
        self.assertIn('if (JobWatch.decides(job.pid())) return;', ad)
        self.assertLess(ad.index('JobWatch.decides(job.pid())'), ad.index('afterDeathKey = key;'))
        self.assertIn('JobWatch.exitOf(a, job)', ad)
        self.assertNotIn('exitOf(a, 0)', o)
        d = body(self.w, 'static boolean decides(int pid)')
        self.assertIn('w.link != null && w.link.pid == pid || w.deciding == pid', d)
        self.assertIn('deciding = d.pid;', body(self.w, 'private void ended(Death d)'))
        self.assertIn('if (deciding == p) deciding = -1;', body(self.w, 'private void died(int p, boolean worker)'))
        j = code(ONDEVICE / 'Job.java')
        self.assertIn('o.put("pid", pid).put("pid_start", start);', j)

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
