"""A Prepare job made from other profile inputs than the app's (JobInputs.java, android/app/src/ondevice): a new app version may change a game's shader tables without
changing any chain version (Ocarina of Time's 508 task and mesh shaders, inputs 4610dec2c950 -> a6b20a286c27, library version 0dd4341ae6ce both), so Job.apkChanged sees
nothing. A job 0.2.2 left paused after its split resumed with the old split, and the shaders step told the user the dump may be modified. The decision runs on the JVM
(JobInputsJvmTest); the rest checks that the service, the job and the tools use it as written: the job records its inputs, the service asks before its steps run,
the delta split's marker and the build's missing-shader line are the ones the service looks for. Skipped without a JDK (the JVM part)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
MAIN = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/JobInputsJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Decide(unittest.TestCase):
    def test_redo_from(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            srcs = [ONDEVICE / 'JobInputs.java', ONDEVICE / 'Outputs.java', MAIN / 'ShaderLibs.java']
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-d', str(d / 'classes'), *map(str, srcs), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            (d / 'run').mkdir()
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.JobInputsJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('all ok', r.stdout)


class Wiring(unittest.TestCase):
    def test_job_records_and_resets(self):
        s = (ONDEVICE / 'Job.java').read_text(encoding='utf-8')
        self.assertIn('.put("shader_inputs", inputs(r.titleId))', s)   # a new job is made for this APK's inputs
        self.assertIn('JobInputs.redoFrom(o.optString("shader_inputs", "")', s)
        self.assertIn('o.put("notice", JobInputs.NOTICE)', s)   # the user is told why the split runs again (statusText)
        self.assertIn('return n.isEmpty() ? statusText0(serviceAlive) : statusText0(serviceAlive) + " " + n;', s)

    def test_service_asks_before_the_steps(self):
        s = (ONDEVICE / 'PackService.java').read_text(encoding='utf-8')
        work = s[s.index('private void work()'):s.index('private void finished()')]
        self.assertLess(work.index('j.inputsChanged(profileMap(j))'), work.index('while ((i = j.next()) >= 0)'))
        self.assertIn('Outputs.delete(new File(j.dir(), "split"))', work)   # the old split goes, and its kept scan results
        self.assertIn('Job.deleteTree(j.splitScan(this))', work)
        self.assertIn('PackEngine.setenv("DM_SPLIT_DELTA", s.equals("split") && !j.splitFull() ? "1" : "0")', s)

    def test_markers_match_the_tools(self):
        java = (ONDEVICE / 'JobInputs.java').read_text(encoding='utf-8')
        split = (REPO / 'dm/gfx/tools/shader_split.py').read_text(encoding='utf-8')
        build = (REPO / 'dm/gfx/tools/vk_android_build.py').read_text(encoding='utf-8')
        marker = re.search(r'DELTA_MARKER = "([^"]+)"', java).group(1)
        self.assertIn(f"DELTA_MARKER = '{marker}'", split)
        phrase = re.search(r'NOT_IN_PACKAGE = "([^"]+)"', (ONDEVICE / 'PackService.java').read_text(encoding='utf-8')).group(1)
        self.assertIn(phrase, build)   # the build's locate line, which sends a delta split's job to a full split


if __name__ == '__main__':
    unittest.main()
