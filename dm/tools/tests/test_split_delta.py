"""The split of a delta update (dm/gfx/tools/split_delta.py, shader_split.py DM_SPLIT_DELTA; android/SHADERS.md#the-split-of-an-update): which shaders the build will
translate (needed: as the build's delta decides it, from small libraries written with the header layout of dmsh_abi_vk.h), which RomFS files hold them (files_for,
after Banana Native's targeted split), and the proof that the scan found each where a full split finds it first (unproven). No game files: the whole split of
Ocarina of Time's update, byte for byte against a full split's build, is the PC simulation in android/SHADERS.md."""
import os
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import split_delta   # noqa: E402
import vk_android_delta as delta   # noqa: E402
from test_android_shader_delta import GAME, spirv, write_lib, zstd   # noqa: E402

A, B, C, D = '00000000000000aa', '00000000000000bb', '00000000000000cc', '00000000000000dd'


class FilesFor(unittest.TestCase):
    FILES = {'Shader/a.bars', 'Shader/b.pack', 'Effect/c.zs'}
    SRC = {A: '/Shader/a.bars#7', B: '/Shader/b.pack/inner/x.bfsha#3', C: '/Effect/c.zs', D: 'main#12'}

    def is_file(self, p): return p in self.FILES

    def test_shortest_prefix_that_is_a_file(self):
        self.assertEqual(split_delta.files_for([A, B, C], self.SRC, self.is_file), ({'Shader/a.bars', 'Shader/b.pack', 'Effect/c.zs'}, ''))

    def test_main_is_read_anyway(self):   # main's records (and captures beside it) are scanned on every run: no RomFS file to name
        src = dict(self.SRC, **{B: '/glslc_capture/x#1'})
        self.assertEqual(split_delta.files_for([D, B], src, self.is_file), (set(), ''))

    def test_unknown_or_missing_falls_back(self):
        files, why = split_delta.files_for([A, 'ffffffffffffffff'], self.SRC, self.is_file)
        self.assertIsNone(files); self.assertIn('ffffffffffffffff', why)
        files, why = split_delta.files_for([A], {A: '/Shader/gone.bars#1'}, self.is_file)
        self.assertIsNone(files); self.assertIn('no such file', why)


class Unproven(unittest.TestCase):
    def test_found_where_the_map_says(self):
        src = {A: '/Shader/a.bars#7', B: 'main#1'}
        self.assertEqual(split_delta.unproven([A, B], src, {A: '/Shader/a.bars#7', B: 'main#1', C: '/x'}), [])

    def test_missing_or_elsewhere(self):
        src = {A: '/Shader/a.bars#7', B: 'main#1'}
        bad = split_delta.unproven([A, B], src, {A: '/Shader/other.bars#2'})
        self.assertEqual(len(bad), 2)
        self.assertIn('found first at /Shader/other.bars#2', bad[0]); self.assertIn('not found', bad[1])


class Game:
    def __init__(self, d): self.id, self.d = GAME, Path(d)
    def table(self, n): return str(self.d / n)


@unittest.skipIf(zstd is None, 'no zstd in this Python')
class Needed(unittest.TestCase):
    """needed(): the profile's keys less those an older library holds with the manifest's hash (each entry hashed); "-" keys always; a library of this version
    and these inputs, or another game's, never counts"""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); d = Path(self.tmp.name)
        self.game = Game(d / 'game'); (d / 'game').mkdir()
        self.v = split_delta.tree_version()
        import dmsh_chain
        self.real_inputs = dmsh_chain.inputs; dmsh_chain.inputs = lambda g: 'a6b20a286c27'
        self.sh = {A: [spirv(10, 1)], B: [spirv(12, 2)], C: [spirv(14, 3)], D: [spirv(16, 4)]}
        (d / 'game/shader_map.tsv').write_text(''.join(f'{i}.bin\tvs\t0\t{k}\t/Shader/f{i}.bars#{i}\n' for i, k in enumerate(self.sh)))
        # the hashes of the old library's entries: A and B unchanged, C changed, D did not build there
        self.old = d / 'pack/shaders/v-111111111111/dmsh_vk.lib'
        write_lib(self.old, '111111111111', shaders=[(k, m, b'') for k, m in self.sh.items() if k != D])
        L, hs = delta.library_hashes(str(self.old), zstd); L.close()
        hashes = {A: hs[A], B: hs[B], C: '0123456789abcdef', D: '-'}
        (d / 'game' / delta.MANIFEST).write_text(delta.format_manifest(self.v, GAME, 'a6b20a286c27', hashes))
        self.roots = [str(d / 'pack/shaders')]

    def tearDown(self):
        import dmsh_chain
        dmsh_chain.inputs = self.real_inputs
        self.tmp.cleanup()

    def needed(self, roots=None):
        return split_delta.needed(self.game, self.roots if roots is None else roots, 2, log=lambda *a, **k: None)

    def test_kept_and_translated(self):
        self.assertEqual(self.needed(), ({C, D}, ''))

    def test_damaged_entry_is_translated(self):   # an entry the build cannot read is not kept: needed errs on the side of more
        data = bytearray(self.old.read_bytes()); data[-8:] = b'\xff' * 8; self.old.write_bytes(bytes(data))
        keys, why = self.needed()
        self.assertEqual(why, ''); self.assertTrue({C, D} <= keys)

    def test_no_library_falls_back(self):
        self.assertEqual(self.needed([])[0], None)
        self.assertEqual(self.needed([str(Path(self.tmp.name) / 'nothing')])[0], None)

    def test_same_version_and_inputs_not_taken(self):
        own = Path(self.tmp.name) / f'own/v-{self.v}/dmsh_vk.lib'
        write_lib(own, self.v, shaders=[(k, m, b'') for k, m in self.sh.items()])   # header inputs 5dd843bed8d1: other inputs, so taken
        keys, _ = self.needed([str(own)])
        self.assertEqual(keys, {C, D})
        import dmsh_chain
        dmsh_chain.inputs = lambda g: '5dd843bed8d1'   # the build's own inputs now: its own library is no source; and the manifest is of other inputs
        self.assertEqual(self.needed([str(own)])[0], None)

    def test_other_game_not_taken(self):
        other = Path(self.tmp.name) / 'other/v-111111111111/dmsh_vk.lib'
        write_lib(other, '111111111111', game='0100000000001000', shaders=[(k, m, b'') for k, m in self.sh.items()])
        self.assertEqual(self.needed([str(other)])[0], None)

    def test_manifest_of_other_inputs(self):
        import dmsh_chain
        dmsh_chain.inputs = lambda g: '4610dec2c950'
        keys, why = self.needed()
        self.assertIsNone(keys); self.assertIn('inputs', why)


class Wiring(unittest.TestCase):
    def test_split_uses_it(self):
        s = (REPO / 'dm/gfx/tools/shader_split.py').read_text(encoding='utf-8')
        self.assertIn("os.environ.get('DM_SPLIT_DELTA') == '1'", s)
        self.assertIn('split_delta.unproven(', s)
        self.assertIn('every file is scanned', s)   # the fallback's line

    def test_credit(self):
        n = (REPO / 'NOTICE.md').read_text(encoding='utf-8')
        self.assertIn('BananaNative', n)


if __name__ == '__main__':
    unittest.main()
