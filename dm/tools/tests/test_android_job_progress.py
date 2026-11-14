"""How a Prepare step's progress lines go into job.json's count and what the card shows of it (JobProgress.java, Job.java, Ondevice.java, PackService.java;
android/app/src/ondevice), and the percent sign in each language. The phone test of 2026-11-14: a paused split that resumed printed its start's stage lines
again and the bar fell from 17 % to 6 % for 39 s; a shader update's card said "shader 0 / 42.854" before its tool said it translates 584; English wrote
"32 %". The rules run on the JVM (JobProgressJvmTest); the rest checks the wiring. Skipped without a JDK (the JVM part)."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
RES = REPO / 'android/app/src/main/res'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/JobProgressJvmTest.java'


def code(p):
    return p.read_text(encoding='utf-8')


def body(s, start, end='\n    }\n'):
    return s[s.index(start):s.index(end, s.index(start))]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Rules(unittest.TestCase):
    def test_rules(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(d / 'classes'), str(ONDEVICE / 'JobProgress.java'), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.JobProgressJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('all ok', r.stdout)


class Wiring(unittest.TestCase):
    def test_job_uses_the_rules(self):
        j = code(ONDEVICE / 'Job.java')
        self.assertIn('if (JobProgress.keepCount(j.optLong("total"), j.optString("stage"), done, total, stage)) return;', j)
        self.assertIn('return JobProgress.shadersTotal(stepTotal(SHADERS), step(STEPS[SHADERS]).optString("stage"));', j)
        self.assertIn('return t < 0 ? stepName(i) : new Msg("prepare_step_shaders_progress", shadersDone(), t);', j)

    def test_split_restarts_clear_its_count(self):
        """Where the split starts over from nothing (its scan results gone), its count is cleared, so its start's stage lines are taken as they come"""
        s = code(ONDEVICE / 'PackService.java')
        self.assertEqual(s.count('j.restartStep("split")'), 3)
        self.assertNotIn('j.stepState("split", "pending")', s)

    def test_card_shows_no_count_before_the_total(self):
        o = code(ONDEVICE / 'Ondevice.java')
        show = body(o, '        void show() {', '\n        }\n')
        self.assertIn('shaders < 0 ? a.getString(R.string.prepare_card_percent', show)


class Texts(unittest.TestCase):
    def test_percent_by_language(self):
        """English and pt-BR write 32%, es-419 32 %, Turkish %32: each language's own texts did so already, but English had a space"""
        want = {'values': '%1$d%%', 'values-tr': '%%%1$d', 'values-pt-rBR': '%1$d%%', 'values-b+es+419': '%1$d %%'}
        for d, w in want.items():
            t = (RES / d / 'strings_prepare.xml').read_text(encoding='utf-8')
            self.assertIn(f'<string name="prepare_card_percent">{w}</string>', t, d)
        en = ''.join(p.read_text(encoding='utf-8') for p in (RES / 'values').glob('strings_*.xml'))
        self.assertNotIn(' %%', en)


if __name__ == '__main__':
    unittest.main()
