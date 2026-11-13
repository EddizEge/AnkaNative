"""The shaders step's use of the phone's cores and memory (ShaderWorkers.java, android/app/src/ondevice; android/SHADERS.md#build-speed): the subinterpreter workers
vk_android_build.py gets (DMSH_VK_INTERPS) for the cores the job may use and the memory the phone reports, and the translator's first generation (DOTNET_GCgen0size).
PackService sets both for the shaders step. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/ShaderWorkers.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/ShaderWorkersJvmTest.java'
SERVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackService.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Workers(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='shworkers_'))
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(cls.tmp / 'classes'), str(SRC), str(TEST)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def ask(self, *pairs):
        r = subprocess.run(['java', '-cp', str(self.tmp / 'classes'), 'dev.nxnative.android.ShaderWorkersJvmTest', *[str(x) for p in pairs for x in p]], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return {(int(w), float(g)): (int(n), gen0) for w, g, n, gen0 in (l.split() for l in r.stdout.splitlines())}

    def test_memory_caps_the_workers(self):
        got = self.ask((6, 7.3), (6, 11.2), (6, 15.0), (6, 5.5), (2, 15.0), (1, 15.0), (6, 2.0))
        self.assertEqual(got[(6, 7.3)][0], 4)    # an 8 GB phone (issue 13's): 4 workers, not 6
        self.assertEqual(got[(6, 11.2)][0], 6)   # 12 GB: every core the job may use
        self.assertEqual(got[(6, 15.0)][0], 6)
        self.assertEqual(got[(6, 5.5)][0], 1)    # 6 GB: the threads as before
        self.assertEqual(got[(2, 15.0)][0], 2)   # never more than the cores
        self.assertEqual(got[(1, 15.0)][0], 1)
        self.assertEqual(got[(6, 2.0)][0], 1)    # at least one, whatever the memory

    def test_translator_generation(self):
        got = self.ask((6, 7.3), (6, 15.0))
        self.assertEqual({v[1] for v in got.values()}, {'4000000'})   # 64 MiB in .NET's hexadecimal, on every phone


class Service(unittest.TestCase):
    def test_shaders_step_environment(self):
        """PackService gives the shaders step the workers and the translator's generation before env.txt (which may override them), and only the shaders step"""
        s = SERVICE.read_text(encoding='utf-8')
        i, g, e = s.find('"DMSH_VK_INTERPS"'), s.find('"DOTNET_GCgen0size"'), s.find('File envFile = new File(od, "env.txt")')
        self.assertTrue(0 < i < e and 0 < g < e, 'set before env.txt is read')
        self.assertRegex(s, r'ShaderWorkers\.interps\(')
        self.assertRegex(s, r'ShaderWorkers\.gen0\(\)')
        sp = s.find('"DM_SPLIT_INTERPS"')
        self.assertTrue(0 < sp < e, 'the split\'s workers set before env.txt is read')


class Tool(unittest.TestCase):
    def test_tool_reads_the_switch(self):
        """vk_android_build.py reads DMSH_VK_INTERPS, default 1 (threads, as before), and needs the library forms for it"""
        t = (REPO / 'dm/gfx/tools/vk_android_build.py').read_text(encoding='utf-8')
        self.assertIn("INTERPS = max(1, int(os.environ.get('DMSH_VK_INTERPS') or 1))", t)
        self.assertTrue(re.search(r'if INTERPS > 1 and \(PROCS > 1 or not XLATE_LIB or not isinstance\(VALIDATE, SpirvValLib\)\)', t))


if __name__ == '__main__':
    unittest.main()
