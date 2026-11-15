"""A Pause during Prepare (JobForm.java, PackService.java, Ondevice.java; android/app/src/ondevice). The phone test of 2026-11-14 paused the shaders step: the tool
did not stop within 20 s, PackService ended its process, and the notification it left was the battery / heat wait ("Waiting", a Pause button, no Resume, ongoing)
for the whole pause, because it took "paused while the worker lives" for that wait (also in 0.2.2); for those 20 s the card and the notification went on saying
running with a time left. The form is now decided by JobForm (run on the JVM: JobFormJvmTest); the rest checks that the service and the card use it as written.
Skipped without a JDK (the JVM part)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/JobFormJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Decide(unittest.TestCase):
    def test_forms(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(d / 'classes'), str(ONDEVICE / 'JobForm.java'), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.JobFormJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('all ok', r.stdout)


def body(s, start, end):
    return s[s.index(start):s.index(end, s.index(start))]


class Wiring(unittest.TestCase):
    s = (ONDEVICE / 'PackService.java').read_text(encoding='utf-8')

    def test_pause_writes_the_job_first(self):
        """requestStop writes the job paused (user, timeout) before it interrupts the tool: a process that dies while the tool stops leaves it paused"""
        rs = body(self.s, 'private void requestStop(String reason)', '\n    }\n')
        self.assertLess(rs.index('j.state("paused", reason, null);'), rs.index('engine.interrupt();'))
        self.assertIn('if (pausable(j))', rs)

    def test_fallback_leaves_the_paused_note(self):
        """The 20-s fallback and onTimeout leave the paused job's notification (live false: Resume), which the worker's updates do not replace"""
        rs = body(self.s, 'private void requestStop(String reason)', 'private static boolean pausable(Job j)')
        fb = rs[rs.index('main.postDelayed('):]
        self.assertLess(fb.index('leaveNote(jj);'), fb.index('Process.killProcess(Process.myPid());'))
        self.assertNotIn('nm.notify(NOTE, note(j));', fb)
        leave = body(self.s, 'private void leaveNote(Job j)', '\n    }\n')
        self.assertIn('noteLeft = true;', leave)
        self.assertIn('nm.notify(NOTE, note(j, -1, false));', leave)
        self.assertIn('leaveNote(j);', body(self.s, 'public void onTimeout(', '\n    }\n'))
        pub = body(self.s, 'private void publish(Job j, boolean force)', '\n    }\n')
        self.assertIn('if (worker != null && !noteLeft && (force || now - lastNote >= 1000))', pub)

    def test_note_by_form(self):
        note = body(self.s, 'private Notification note(Job j, int etaMin, boolean live)', 'private String simulated(')
        self.assertIn('JobForm.Form form = JobForm.of(state, j.reason(), live, stopReason != null);', note)
        self.assertNotRegex(note, r'state\.equals\("paused"\) && worker != null')   # the old wait rule
        self.assertIn('R.string.prepare_note_pausing', note)

    def test_stopping_has_no_time_left(self):
        pub = body(self.s, 'private void publish(Job j, boolean force)', '\n    }\n')
        self.assertIn('boolean stopping = worker != null && stopReason != null;', pub)
        self.assertRegex(pub, r'int etaMin = est != null && worker != null && !stopping && "running"\.equals\(j\.state\(\)\)')
        self.assertIn('b.putBoolean("stopping", stopping);', pub)

    def test_card_says_pausing(self):
        o = (ONDEVICE / 'Ondevice.java').read_text(encoding='utf-8')
        self.assertIn('jobStopping = d.getBoolean("stopping");', o)
        show = body(o, '        void show() {', '\n        }\n')
        self.assertIn('JobForm.of(st, job.reason(), jobAlive && service != null, jobStopping) == JobForm.Form.STOPPING', show)
        self.assertIn('status.setText(job.statusMsg(alive, stopping).text());', show)
        self.assertIn('&& !stopping);', show)   # Resume waits for the tool to stop
        j = (ONDEVICE / 'Job.java').read_text(encoding='utf-8')
        self.assertIn('new Msg("prepare_status_pausing", stepMsg())', j)
        self.assertTrue(re.search(r'onServiceDisconnected\(ComponentName n\) \{[^}]*jobAlive = jobStopping = false;', o))


if __name__ == '__main__':
    unittest.main()
