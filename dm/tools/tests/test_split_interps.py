"""shader_split DM_SPLIT_INTERPS (InterpPool): with J=1 (the phone's packer, whose process may not fork) the scan's batches run in subinterpreters, each with its own GIL;
the outputs are those of the scan in this process (InProcess) byte for byte, red lines of a worker's scan included, and the scan cache (DM_SPLIT_RESUME) a worker run
writes resumes a run in this process. A batch whose files need a module that cannot load in a subinterpreter (python-lz4: a single-phase extension
module, bundled in the phone's Python) is scanned again in the main interpreter, with the same outputs: tested with the real lz4 (LZ4 GFPAK entries, a
size-prefixed LZ4 frame) and with a stand-in lz4 package that imports _testsinglephase, which does not need lz4 to fail in subinterpreters. No game files needed;
skipped on a Python without concurrent.interpreters (before 3.14). The fuzz builders need lz4 (the tools' venv)."""
import importlib.util
import os
import shutil
import struct
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

# A stand-in lz4 package: its import loads _testsinglephase (CPython's own single-phase test module), so it fails in a subinterpreter as python-lz4 4.4 does;
# block.decompress is LZ4's block format in Python
SHIM_INIT = 'import _testsinglephase   # single-phase: "does not support loading in subinterpreters", as python-lz4\n'
SHIM_BLOCK = """
class LZ4BlockError(Exception): pass
def decompress(src, uncompressed_size=-1):
    src, out, i = bytes(src), bytearray(), 0
    while i < len(src):
        t = src[i]; i += 1; n = t >> 4
        if n == 15:
            while True:
                b = src[i]; i += 1; n += b
                if b != 255: break
        out += src[i:i + n]; i += n
        if i >= len(src): break
        off = src[i] | src[i + 1] << 8; i += 2; m = t & 15
        if m == 15:
            while True:
                b = src[i]; i += 1; m += b
                if b != 255: break
        for _ in range(m + 4): out.append(out[-off])
    if uncompressed_size >= 0 and len(out) != uncompressed_size: raise LZ4BlockError('size')
    return bytes(out)
"""


def lz4_literals(d):   # an LZ4 block of literals only (one sequence, no match): what any LZ4 block decoder reads back as d
    n = len(d); t = bytearray([min(n, 15) << 4])
    if n >= 15:
        r = n - 15
        while r >= 255: t.append(255); r -= 255
        t.append(r)
    return bytes(t) + d


def gfpak(entries):   # Game Freak's GFPAK (shader_split.gfpak_members) of (compression, stored, size) entries: 2 is LZ4
    files = 0x30; data = files + 0x18 * len(entries)
    head = bytearray(b'GFLXPACK' + b'\0\x10\0\0' + bytes(4)) + struct.pack('<IIQQQ', len(entries), 0, files, 0, 0)
    tab, blob = bytearray(), bytearray()
    for ct, stored, size in entries:
        tab += struct.pack('<HHII4xQ', 0, ct, size, len(stored), data + len(blob)); blob += stored
    return bytes(head + tab + blob)


def can_load_in_subinterpreter(mod):
    from concurrent import interpreters
    it = interpreters.create()
    try: it.exec(f'import {mod}'); return True
    except interpreters.ExecutionFailed: return False
    finally: it.close()


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

    def split(self, name, rom=None, **env):
        d = self.tmp / name; d.mkdir()
        r = subprocess.run([sys.executable, REPO / 'dm/gfx/tools/shader_split.py', rom or self.rom, self.main, *[d / n for n in OUTS]], capture_output=True, text=True,
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
        self.assertIn('subinterpreters: 3 split workers', out_b)
        self.assertNotIn('subinterpreter', out_a)

    def lz4_rom(self, name, block, frame=None):
        """A RomFS of plain shaders (batches the workers scan) and LZ4 files in other batches: a GFPAK of LZ4 entries (block: the compressor), and with frame a
        size-prefixed LZ4 frame (HAL's .cmp, shader_split.unsized)"""
        rom = self.tmp / name
        for i in range(40):
            p = rom / f'a{i // 16}' / f'f{i:03d}.bnsh'; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(shader(1000 + i))
        (rom / 'z').mkdir()
        (rom / 'z/pak.gfpak').write_bytes(gfpak([(2, block(shader(700)), len(shader(700))), (0, shader(701), len(shader(701))), (2, block(shader(702)), len(shader(702)))]))
        if frame: (rom / 'z/m.cmp').write_bytes(struct.pack('<I', len(shader(703))) + frame(shader(703)))
        return rom

    def check_main_scans(self, rom, tag, env=None, need=('lz4.block',)):
        """The split with 3 workers equals the split in one interpreter, the LZ4 files' shaders are in the map, and the lines say which batches were scanned in
        the main interpreter"""
        env = env or {}
        a, out_a = self.split(f'{tag}_inproc', rom, **env)
        b, out_b = self.split(f'{tag}_interps', rom, DM_SPLIT_INTERPS='3', **env)
        self.assertEqual(self.files(a), self.files(b))
        m = self.files(b)['map.tsv'].decode()
        for src in ['/z/pak.gfpak#0', '/z/pak.gfpak#2'] + (['/z/m.cmp'] if 'lz4.frame' in need else []): self.assertIn(src, m)
        for out in (out_a, out_b): self.assertNotIn('[RED]', out)
        self.assertIn(f'subinterpreters: 3 split workers; they cannot load {", ".join(need)} (single-phase extension modules): the files that need them are scanned in the main interpreter', out_b)
        self.assertRegex(out_b, r'subinterpreters: [1-9]\d* split batch\(es\) scanned in the main interpreter')

    def test_lz4_in_the_main_interpreter(self):
        """python-lz4 (the tools' venv, and the phone's Python): its import fails in a subinterpreter, so the batches with LZ4 GFPAK entries or an LZ4 frame are
        scanned in the main interpreter; before, a worker's ImportError left those files' shaders out ('unreadable') or read the frame as it is"""
        try: import lz4.block, lz4.frame
        except ImportError: self.skipTest('no lz4')
        if can_load_in_subinterpreter('lz4.block'): self.skipTest('this lz4 loads in subinterpreters: nothing goes to the main interpreter')
        rom = self.lz4_rom('rom_lz4', lambda d: lz4.block.compress(d, store_size=False), lambda d: lz4.frame.compress(d))
        self.check_main_scans(rom, 'lz4', need=('lz4.block', 'lz4.frame'))

    def test_single_phase_stand_in(self):
        """The same with a stand-in lz4 package that cannot load in a subinterpreter (no lz4 needed)"""
        if not importlib.util.find_spec('_testsinglephase'): self.skipTest('no _testsinglephase')
        shim = self.tmp / 'shim/lz4'; shim.mkdir(parents=True)
        (shim / '__init__.py').write_text(SHIM_INIT); (shim / 'block.py').write_text(SHIM_BLOCK)
        rom = self.lz4_rom('rom_shim', lz4_literals)
        env = {'PYTHONPATH': os.pathsep.join([str(shim.parent)] + [p for p in os.environ.get('PYTHONPATH', '').split(os.pathsep) if p])}
        self.check_main_scans(rom, 'shim', env)   # (the stand-in has no lz4.frame)

    def test_no_subinterpreters(self):
        """No worker can be made or set up (no concurrent.interpreters; a set-up that fails, as an import of the tool would): one line says so, and pool() gives
        the scan in this interpreter, as before"""
        import contextlib, io
        from unittest import mock
        sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
        with mock.patch.dict(os.environ, DM_GAME='040074A01BF12000'): import shader_split as ss
        for what, patch in (('no concurrent.interpreters', mock.patch.dict(sys.modules, {'concurrent.interpreters': None})),
                            ('a set-up that fails', mock.patch.object(ss.InterpPool, 'BOOT', 'import _testsinglephase\n'))):
            with self.subTest(what), patch, mock.patch.dict(os.environ, DM_SPLIT_INTERPS='3'), contextlib.redirect_stdout(io.StringIO()) as out:
                with ss.pool(1, None, {}, {}) as ex: self.assertIsInstance(ex, ss.InProcess)
                self.assertIsNone(ss._STOP_FD)
            self.assertEqual(out.getvalue().count('subinterpreters: not usable here ('), 1, out.getvalue())
            self.assertIn('DM_SPLIT_INTERPS=3 ignored, the scan runs in one interpreter as before', out.getvalue())


    def test_stop(self):
        """An interrupt (the app's Pause, a thermal stop) while the workers scan: pooled stops the pool, so the batches in hand end at their next file and those
        not started are dropped; the batches that finished go into the scan cache, which is flushed; the interrupt goes on"""
        import contextlib, io
        from unittest import mock
        sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
        with mock.patch.dict(os.environ, DM_GAME='040074A01BF12000'): import shader_split as ss
        jobs = sorted((str(p), p.relative_to(self.rom).as_posix()) for p in self.rom.rglob('*.bnsh'))

        class Cache:   # the first result's add is where the interrupt arrives
            def __init__(c): c.added, c.flushed = {}, False
            def cached(c, first, n): return None
            def add(c, first, r):
                if not c.added and not hasattr(c, 'hit'): c.hit = first; raise KeyboardInterrupt
                c.added[first] = r
            def flush(c): c.flushed = True

        with mock.patch.dict(os.environ, DM_SPLIT_INTERPS='2', DM_GAME='040074A01BF12000'), contextlib.redirect_stdout(io.StringIO()):   # (a worker imports the tool: it reads DM_GAME)
            with ss.pool(1, None, {}, {}) as ex:
                self.assertIsInstance(ex, ss.InterpPool)
                ok = ex.submit(ss.scan_batch, jobs[:16]).result()
                self.assertEqual(len(ok), 16)
                cache = Cache()
                with self.assertRaises(KeyboardInterrupt): ss.pooled(ex, jobs, len(jobs), chunk=4, cache=cache)
                self.assertTrue(cache.flushed)
                self.assertIn(cache.hit, cache.added)   # the result in hand when the interrupt came is kept too
                self.assertLess(len(cache.added), len(jobs) // 4 - 1)   # not every batch ran on
                for first, r in cache.added.items(): self.assertEqual([x[0] for x in r], [rel for _, rel in jobs[first:first + 4]])
                with self.assertRaises(ss.Stopped): ex.submit(ss.scan_batch, jobs[:16]).result()   # stopped: a batch ends before its first file
            self.assertIsNone(ss._STOP_FD)


if __name__ == '__main__': unittest.main()
