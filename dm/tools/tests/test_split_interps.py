"""shader_split DM_SPLIT_INTERPS (InterpPool): with J=1 (the phone's packer, whose process may not fork) the scan's batches run in subinterpreters, each with its own GIL;
the outputs are those of the scan in this process (InProcess) byte for byte, red lines of a worker's scan included, and the scan cache (DM_SPLIT_RESUME) a worker run
writes resumes a run in this process. No game files needed; skipped on a Python without concurrent.interpreters (before 3.14)."""
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools/fuzz'))
import fuzz_shader_split as fz   # its builders (SARC, BNSH, Yaz0)
sys.path.insert(0, str(REPO / 'dm/tools/tests'))
from test_split_unreadable import index   # an index of a layout nlg_dict refuses: a red line from the worker that scans it

OUTS = ('pkg', 'map.tsv', 'facts.tsv', 'pairs.tsv', 'pso.tsv')


def shader(i):   # a BNSH whose pixel shader's last instruction carries i: a key of its own
    b = bytearray(fz.bnsh()); b[-8] = i % 256; b[-7] = i // 256
    return bytes(b)


@unittest.skipUnless(importlib.util.find_spec('concurrent.interpreters'), 'no concurrent.interpreters (Python 3.14)')
class Interps(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='split_interps_'))
        rom = cls.rom = cls.tmp / 'RomFS'
        for i in range(120):   # more files than one batch of 16, in folders, some shaders twice (the first place found is the map's)
            p = rom / f'd{i % 7}' / f'f{i:03d}.bnsh'; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(shader(i % 90))
        (rom / 'x/arc.sarc').parent.mkdir(); (rom / 'x/arc.sarc').write_bytes(fz.yaz0(fz.sarc([(b'a.bnsh', shader(500)), (b'b.bnsh', shader(3))])))
        (rom / 'sub').mkdir(); (rom / 'sub/a.dict').write_bytes(index(5, 2)[:0x28]); (rom / 'sub/a.data').write_bytes(bytes(64))
        cls.main = cls.tmp / 'main'; cls.main.write_bytes(b'NSO0' + bytes(0xfc))   # an NSO header of three empty segments

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def split(self, name, **env):
        d = self.tmp / name; d.mkdir()
        r = subprocess.run([sys.executable, REPO / 'dm/gfx/tools/shader_split.py', self.rom, self.main, *[d / n for n in OUTS]], capture_output=True, text=True,
                           env=dict(os.environ, DM_GAME='040074A01BF12000', J='1', **env))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return d, r.stdout

    def files(self, d):
        return {str(p.relative_to(d)): p.read_bytes() for p in sorted(d.rglob('*')) if p.is_file()}

    def test_same_outputs(self):
        a, out_a = self.split('inproc')
        b, out_b = self.split('interps', DM_SPLIT_INTERPS='3')
        fa, fb = self.files(a), self.files(b)
        self.assertGreater(len(fa['map.tsv']), 0)
        self.assertEqual(fa, fb)
        for out in (out_a, out_b):   # the worker's red line reaches this process's output, once, and the summary names the file
            self.assertEqual(out.count('[RED] /sub/a.dict: the index'), 1, out)
            self.assertIn('[RED] 1 file(s) could not be read', out)
        self.assertIn('shaders: ', out_b)

    def test_resume_across_forms(self):
        """A scan cache written by subinterpreter workers is taken whole by a split in this process (same results, nothing scanned again)"""
        keep = self.tmp / 'scan'
        a, _ = self.split('cache_write', DM_SPLIT_INTERPS='3', DM_SPLIT_RESUME=str(keep))
        self.assertTrue(any(keep.glob('*.pkl')))
        b, out = self.split('cache_read', DM_SPLIT_RESUME=str(keep))
        self.assertIn('resuming the split: ', out)
        self.assertEqual(self.files(a), self.files(b))


if __name__ == '__main__': unittest.main()
