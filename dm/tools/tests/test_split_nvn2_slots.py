"""shader_split: an NVN2 BNSH program's stage slots past the six NVN ones (7 task, 8 mesh: NVN2_SLOTS) are read only for task and mesh shaders. Anything else in such a
slot (a shader of another stage, a control section that does not match its code area) is a red line and left out, and a program record that ends before those slots
(an NVN program's) is no damaged file: neither ends the split, which every game's split goes through. Synthetic BNSH programs, no game files."""
import contextlib
import io
import os
import struct
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '040074A01BF12000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss  # noqa: E402
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had


def nvn2(stage, ncode=0x80, claim=None):   # an NVN2 control section (0xd8 bytes): version 0xc9, its length at +0x30, the code length at +0x44, the stage at +0x56
    c = bytearray(0xd8); c[:4] = ss.CTL_MAGIC; c[4:8] = b'\xc9\0\0\0'
    struct.pack_into('<I', c, 0x30, len(c)); struct.pack_into('<I', c, 0x44, ncode if claim is None else claim); c[0x56] = stage
    return bytes(c)


def bnsh(slots, cut=False):
    """A BNSH of one variation whose program has {slot: (control, code)}; cut: the file ends right after the program's six NVN slot pointers"""
    d = bytearray(0x100); d[0x60:0x64] = b'grsc'
    struct.pack_into('<IQ', d, 0x7c, 1, 0x100)          # one variation, its table at 0x100
    d += bytearray(0x40); struct.pack_into('<Q', d, 0x110, 0x140)   # the variation's program at 0x140
    prog = len(d); d += bytearray(8 + 8 * 9)
    for si, (ctl, code) in sorted(slots.items()):
        rec = len(d); d += bytearray(0x20)
        pctl = len(d); d += ctl; pcode = len(d); d += code
        struct.pack_into('<QQII', d, rec + 8, pctl, pcode, len(code), len(ctl))
        struct.pack_into('<Q', d, prog + 8 + 8 * si, rec)
    if cut:
        assert all(si < 6 for si in slots)
        tail = bytes(d[prog + 8 + 8 * 6:prog + 8 + 8 * 9]); assert tail == bytes(len(tail))
        d = d[:prog + 8 + 8 * 6] + d[prog + 8 + 8 * 9:]   # (no record of these tests lies past the program: nothing moves)
    return bytes(d)


class Nvn2Slots(unittest.TestCase):
    def split(self, d):
        out = io.StringIO()
        with contextlib.redirect_stdout(out): got = ss.bnsh_shaders(d, 0, '/t.bfsha')
        return [(s, len(c)) for _, s, c, _ in got], out.getvalue()

    def test_task_and_mesh_taken(self):
        got, log = self.split(bnsh({7: (nvn2(6), bytes(0x80)), 8: (nvn2(7), bytes(0x80))}))
        self.assertEqual(got, [('ts', 0xd8), ('ms', 0xd8)]); self.assertEqual(log, '')

    def test_other_stage_left_out(self):
        got, log = self.split(bnsh({6: (nvn2(5), bytes(0x80)), 8: (nvn2(7), bytes(0x80))}))
        self.assertEqual(got, [('ms', 0xd8)], 'the mesh shader is still taken')
        self.assertIn('[RED] /t.bfsha BNSH @0x0 variation 0: NVN2 stage slot 6 holds a cs shader, not a task or mesh shader; left out', log)

    def test_bad_section_left_out(self):
        got, log = self.split(bnsh({7: (nvn2(6, claim=0x40), bytes(0x80)), 8: (nvn2(7), bytes(0x80))}))
        self.assertEqual(got, [('ms', 0xd8)])
        self.assertIn('(NVN2 stage slot 7): control +0x44 does not match the code area length; left out', log)

    def test_program_record_ends_before_the_extra_slots(self):
        got, log = self.split(bnsh({}, cut=True))
        self.assertEqual((got, log), ([], ''))


if __name__ == '__main__':
    unittest.main()
