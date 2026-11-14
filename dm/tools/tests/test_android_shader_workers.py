"""The shaders step's use of the phone's cores and memory (ShaderWorkers.java, android/app/src/ondevice; android/SHADERS.md#build-speed): the subinterpreter workers
vk_android_build.py gets (DMSH_VK_INTERPS) for the cores the job may use and the memory the phone reports, and the translator's first generation (DOTNET_GCgen0size).
PackService sets both for the shaders step, and the same count for the split (DM_SPLIT_INTERPS). The JVM part is skipped without a JDK.
Build: vk_android_build.py with 2 subinterpreter workers gives the library, index.json and _vk_build.json of its threads, byte for byte, and where no worker can
be set up, one line and the threads; the app's Pause ends it at the shader in hand, and the run that resumes makes the same library. It needs the phone's form on this computer, so it runs only when these are set (skipped otherwise):
DMSH_TEST_XLATE_LIB the translator library built for this computer (dmsh_xlate_android, `dotnet publish dmsh_xlate_android.csproj -c Release
-p:RuntimeIdentifier=linux-x64 -p:NativeLib=Shared -p:OutputType=Library`), DMSH_TEST_VK_PKG Ocarina of Time's split package (the shader_split.py package of
the dump the profile's shader_map.tsv was made from); SPIRV-Tools' libSPIRV-Tools-shared from DMSH_TEST_SPIRV_LIB or the system's."""
import ctypes.util
import os
import re
import sys
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
        """PackService gives the shaders and split steps their workers, and the translator's generation, before env.txt (which may override them); the tools'
        own 'subinterpreters: ' lines (how many run, or that none can here) go to logcat"""
        s = SERVICE.read_text(encoding='utf-8')
        i, g, e = s.find('"DMSH_VK_INTERPS"'), s.find('"DOTNET_GCgen0size"'), s.find('File envFile = new File(od, "env.txt")')
        self.assertTrue(0 < i < e and 0 < g < e, 'set before env.txt is read')
        self.assertRegex(s, r'ShaderWorkers\.interps\(')
        self.assertRegex(s, r'ShaderWorkers\.gen0\(\)')
        sp = s.find('"DM_SPLIT_INTERPS"')
        self.assertTrue(0 < sp < e, 'the split\'s workers set before env.txt is read')
        self.assertRegex(s, r'else if \(line\.startsWith\("subinterpreters: "\)\) Log\.i\(TAG, step \+ ": " \+ line\);')


class Tool(unittest.TestCase):
    def test_tool_reads_the_switch(self):
        """vk_android_build.py reads DMSH_VK_INTERPS, default 1 (threads, as before), and needs the library forms for it"""
        t = (REPO / 'dm/gfx/tools/vk_android_build.py').read_text(encoding='utf-8')
        self.assertIn("INTERPS = max(1, int(os.environ.get('DMSH_VK_INTERPS') or 1))", t)
        self.assertTrue(re.search(r'if INTERPS > 1 and \(PROCS > 1 or not XLATE_LIB or not isinstance\(VALIDATE, SpirvValLib\)\)', t))


XLATE_LIB, PKG = os.environ.get('DMSH_TEST_XLATE_LIB'), os.environ.get('DMSH_TEST_VK_PKG')
SPIRV_LIB = os.environ.get('DMSH_TEST_SPIRV_LIB') or ctypes.util.find_library('SPIRV-Tools-shared')
# Run the tool with concurrent.interpreters hidden: no worker can be set up, as on a Python without them
NO_INTERPS = """import os, runpy, sys
sys.modules['concurrent.interpreters'] = None
sys.argv = sys.argv[1:]; sys.path.insert(0, os.path.dirname(sys.argv[0]))
runpy.run_path(sys.argv[0], run_name='__main__')
"""
# Run the tool as the phone's packer does (android/ondevice/dm_pack.c): off Python's main thread, its output to a file, and the app's Pause as a KeyboardInterrupt
# raised in its thread (PyThreadState_SetAsyncExc) once its first result file is written. Prints the seconds from the interrupt to the tool's end, and 130 or 0
PAUSE = """import ctypes, os, runpy, sys, threading, time
res, log, out = sys.argv[1], sys.argv[2], sys.stdout; sys.argv = sys.argv[3:]; sys.path.insert(0, os.path.dirname(sys.argv[0]))
code = [None]
def run():
    sys.stdout = sys.stderr = open(log, 'w', buffering=1)
    try: runpy.run_path(sys.argv[0], run_name='__main__'); code[0] = 0
    except KeyboardInterrupt: code[0] = 130
    except SystemExit as e: code[0] = e.code
t = threading.Thread(target=run); t.start()
while t.is_alive() and not (os.path.isdir(res) and any(n.endswith('.r') for n in os.listdir(res))): time.sleep(0.02)
t0 = time.monotonic()
ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(t.ident), ctypes.py_object(KeyboardInterrupt))
t.join()
print(f'{time.monotonic() - t0:.2f} {code[0]}', file=out)
"""


@unittest.skipUnless(XLATE_LIB and PKG and SPIRV_LIB and sys.version_info >= (3, 14), 'DMSH_TEST_XLATE_LIB, DMSH_TEST_VK_PKG and SPIRV-Tools\' library (Python 3.14)')
class Build(unittest.TestCase):
    """vk_android_build.py in the phone's form (both tools as libraries, one translator call at a time) on a sample of Ocarina of Time's shaders"""
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='shworkers_build_'))
        rows = [l.split('\t') for l in (REPO / 'dm/games/0400486024DEC000/shader_map.tsv').read_text().splitlines() if l and not l.startswith(('#', 'package'))]
        cls.keys = [r[3] for r in rows[::1200]]   # 36 keys over the whole map, of every package; the tool adds their pairs
        (cls.tmp / 'no_interps.py').write_text(NO_INTERPS)
        (cls.tmp / 'pause.py').write_text(PAUSE)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def build(self, name, interps, wrap=(), fail=False, **more):
        d = self.tmp / name
        env = dict(os.environ, DM_GAME='0400486024DEC000', XLATE=XLATE_LIB, SPIRV_VAL=SPIRV_LIB, J='2', DMSH_VK_XLATE_J='1', DMSH_VK_INTERPS=str(interps),
                   DMSH_VK_RESULTS=str(d / 'results'), TMPDIR=str(self.tmp), **more)
        env.pop('DMSH_VK_BUILD_CACHE', None)
        r = subprocess.run([sys.executable, *wrap, str(REPO / 'dm/gfx/tools/vk_android_build.py'), '--target', 'vulkan', PKG, str(d / 'out'), *self.keys],
                           capture_output=True, text=True, env=env)
        if fail: return r
        self.assertEqual(r.returncode, 0, r.stdout[-3000:] + r.stderr[-3000:])
        lib = next((d / 'out').glob('v-*'))
        return {n: (lib / n).read_bytes() for n in ('dmsh_vk.lib', 'index.json', '_vk_build.json')}, r.stdout

    def test_workers_same_library(self):
        threads, out_t = self.build('threads', 1)
        workers, out_w = self.build('workers', 2)
        self.assertNotIn('subinterpreters: ', out_t)
        self.assertIn('subinterpreters: 2 translation workers', out_w)
        self.assertGreater(len(threads['dmsh_vk.lib']), 4096)
        for n in threads: self.assertEqual(threads[n], workers[n], n)
        fallback, out_f = self.build('fallback', 2, (str(self.tmp / 'no_interps.py'),))
        self.assertEqual(out_f.count('subinterpreters: not usable here ('), 1, out_f)   # once, for both phases
        self.assertIn('DMSH_VK_INTERPS=2 ignored, the shaders run in 2 threads of one interpreter as before', out_f)
        for n in threads: self.assertEqual(threads[n], fallback[n], n)

    def test_pause(self):
        """The app's Pause while the workers translate (subinterpreters, and threads): the tool ends at the shader in hand, not at the end of the batches in hand
        (here one batch a phase, so a stop at its end would leave every shader of the phase translated). The shaders finished before the stop keep their results,
        and the run that resumes from them makes the library of an uninterrupted run, byte for byte"""
        whole, _ = self.build('pause_whole', 1)
        for name, interps in (('pause_workers', 2), ('pause_threads', 1)):
            with self.subTest(name):
                d = self.tmp / name
                r = self.build(name, interps, (str(self.tmp / 'pause.py'), str(d / 'results'), str(self.tmp / f'{name}.log')), fail=True, DMSH_VK_BATCH='1000')
                self.assertEqual(r.returncode, 0, r.stderr[-3000:])
                secs, code = r.stdout.split()
                log = (self.tmp / f'{name}.log').read_text()
                self.assertEqual(code, '130', log[-3000:])
                kept = [p.name for p in (d / 'results').glob('*.r')]
                phase1 = len(re.findall(r'^progress \d+ \d+ translate$', log, re.M))
                self.assertEqual(phase1, 1, log[-2000:])   # the interrupt came in the first phase's one batch: no batch was counted done
                self.assertGreater(len(kept), 0)
                total = int(re.search(r'^translating (\d+) shaders', log, re.M).group(1))
                self.assertLess(len(kept), total // 2, f'{len(kept)} of {total} results after the stop: the batch in hand ran on')
                self.assertLess(float(secs), 30.0)
                again, out = self.build(name, interps)
                self.assertIn(f'({len(kept)} already in ', out)
                for n in whole: self.assertEqual(whole[n], again[n], n)

    def test_cut_short_result(self):
        """A result file cut short (its head whole, a module's bytes not) stops the library before any of it is written: no dmsh_vk.lib.tmp is left"""
        self.build('whole', 1)
        shutil.copytree(self.tmp / 'whole/results', self.tmp / 'cut/results')
        r = max((self.tmp / 'cut/results').glob('*.r'), key=lambda p: p.stat().st_size)
        r.write_bytes(r.read_bytes()[:-1])
        p = self.build('cut', 1, fail=True)
        self.assertEqual(p.returncode, 1, p.stdout[-2000:])
        self.assertIn(f'[RED] {r} is cut short: delete it and run again', p.stdout + p.stderr)
        self.assertEqual([x.name for x in (self.tmp / 'cut/out').rglob('dmsh_vk.lib*')], [])


if __name__ == '__main__':
    unittest.main()
