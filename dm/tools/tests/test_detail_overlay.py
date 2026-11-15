"""Game settings › Plants and effects (dm/host/detail.h): the overlay the host builds at start, checked with tools other than the host's own. dm/host/tests/t_detail.c
(built here with zstd's decoder, as the Android host is) builds it over a RomFS folder made here: hand-made BYML files, loose and in a SARC compressed with a
zstd dictionary trained here, and the dictionary in a pack of dictionaries as the game keeps them (Pack/ZsDic.pack.zs). Python's own zstd decoder and a BYML
reader written here then read the overlay: only the values the rows name have changed, as the rows say; sizes and the other members are as they were; the
rewritten pack's frame names the dictionary (or none with DM_DETAIL_PACK_DICTID=none). Full builds nothing. No game files."""
from pathlib import Path
import os
import struct
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
try:
    from compression import zstd
except ImportError:   # Python before 3.14
    try: from backports import zstd
    except ImportError: zstd = None


def byml(root, be=False):
    """A BYML version 7 file of a dictionary whose values are bool, int, float, str or a dictionary of those (keys sorted, as the format wants)"""
    E = '>' if be else '<'
    keys, strs = set(), set()
    def walk(d):
        for k, v in d.items():
            keys.add(k)
            if isinstance(v, dict): walk(v)
            elif isinstance(v, str): strs.add(v)
    walk(root); keys, strs = sorted(keys), sorted(strs)
    out = bytearray(b'BY' if be else b'YB') + struct.pack(E + 'H', 7) + bytes(12)
    def u24(n): return n.to_bytes(3, 'big' if be else 'little')
    def table(ss):
        o = len(out); out.extend(bytes([0xC2]) + u24(len(ss))); offs = len(out); out.extend(bytes(4 * (len(ss) + 1)))
        for i, s in enumerate(ss):
            struct.pack_into(E + 'I', out, offs + 4 * i, len(out) - o); out.extend(s.encode() + b'\0')
        struct.pack_into(E + 'I', out, offs + 4 * len(ss), len(out) - o)
        while len(out) % 4: out.append(0)
        return o
    struct.pack_into(E + 'I', out, 4, table(keys)); struct.pack_into(E + 'I', out, 8, table(strs) if strs else 0)
    def node(d):
        kids = {k: node(v) for k, v in d.items() if isinstance(v, dict)}
        o = len(out); out.extend(bytes([0xC1]) + u24(len(d)))
        for k in sorted(d):
            v = d[k]
            if isinstance(v, dict): t, w = 0xC1, kids[k]
            elif isinstance(v, bool): t, w = 0xD0, int(v)
            elif isinstance(v, int): t, w = 0xD1, struct.unpack(E + 'I', struct.pack(E + 'i', v))[0]
            elif isinstance(v, float): t, w = 0xD2, struct.unpack(E + 'I', struct.pack(E + 'f', v))[0]
            else: t, w = 0xA0, strs.index(v)
            out.extend((u24(keys.index(k)) + bytes([t])) if not be else (u24(keys.index(k)) + bytes([t])))
            out.extend(struct.pack(E + 'I', w))
        return o
    struct.pack_into(E + 'I', out, 12, node(root))
    return bytes(out)


def read_byml(b):
    """An independent reader of what byml() writes (and of the game's files of these node types)"""
    E = '<' if b[:2] == b'YB' else '>'
    _, kt, st, root = struct.unpack_from(E + 'HIII', b, 2)
    def u24(o): return int.from_bytes(b[o:o + 3], 'little' if E == '<' else 'big')
    def table(o):
        n = u24(o + 1); offs = struct.unpack_from(E + f'{n + 1}I', b, o + 4)
        return [b[o + offs[i]:b.index(b'\0', o + offs[i])].decode() for i in range(n)]
    keys, strs = table(kt), (table(st) if st else [])
    def node(o):
        d = {}
        for i in range(u24(o + 1)):
            e = o + 4 + 8 * i; k, t = keys[u24(e)], b[e + 3]
            d[k] = {0xC1: lambda: node(struct.unpack_from(E + 'I', b, e + 4)[0]), 0xD0: lambda: bool(struct.unpack_from(E + 'I', b, e + 4)[0]),
                    0xD1: lambda: struct.unpack_from(E + 'i', b, e + 4)[0], 0xD2: lambda: struct.unpack_from(E + 'f', b, e + 4)[0],
                    0xA0: lambda: strs[struct.unpack_from(E + 'I', b, e + 4)[0]]}[t]()
        return d
    return node(root)


def sarc(members):
    """A little-endian SARC of {name: bytes}, members in name order, data aligned to 8"""
    names = sorted(members); pool = b''.join(n.encode() + b'\0' * (4 - len(n) % 4) for n in names)
    head = 0x20 + 16 * len(names) + 8 + len(pool); data = (head + 0x7F) & ~0x7F
    body, nodes, name_off = bytearray(), bytearray(), 0
    for n in names:
        while len(body) % 8: body.append(0)
        nodes += struct.pack('<IIII', 0, 0x01000000 | name_off // 4, len(body), len(body) + len(members[n])); body += members[n]
        name_off += len(n) + 4 - len(n) % 4
    hdr = b'SARC' + struct.pack('<HHIIHH', 0x14, 0xFEFF, data + len(body), data, 0x100, 0) + b'SFAT' + struct.pack('<HHI', 0xC, len(names), 0x65)
    out = hdr + bytes(nodes) + b'SFNT' + struct.pack('<HH', 8, 0) + pool
    return out + bytes(data - len(out)) + bytes(body)


def read_sarc(b):
    n = struct.unpack_from('<H', b, 0x1A)[0]; data = struct.unpack_from('<I', b, 12)[0]; pool = 0x20 + 16 * n + 8; out = {}
    for i in range(n):
        _, attr, s, e = struct.unpack_from('<IIII', b, 0x20 + 16 * i); no = pool + 4 * (attr & 0xFFFFFF)
        out[b[no:b.index(b'\0', no)].decode()] = (data + s, data + e)
    return out


SCENE_A = {'TurfDensity': 150.0, 'TurfMinBlades': 16, 'IsEnableMoss': True, 'IsEnableTurf': True, 'Name': 'kokiri',
           'LodSetting_hh': {'Dist_LowTree': 50.0, 'Dist_Undergrowth0': 20.0, 'Range_LowTree01': 5.0}}
SCENE_B = {'IsEnableMoss': True, 'IsEnableStone': True}
ROWS = '''detail_level\tfull\tshown\tFull (as the game draws it)
detail_level\treduced\tshown\tReduced
detail_zsdic\tPack/ZsDic.pack.zs
detail_param\treduced\tFoliage/ResFoliageScene/*.bgyml\tTurfDensity\tx0.5
detail_param\treduced\tpack:Pack/Foliage.pack.zs:Foliage/ResFoliageScene/*.bgyml\tTurfDensity\tx0.5
detail_param\treduced\tpack:Pack/Foliage.pack.zs:Foliage/ResFoliageScene/*.bgyml\tTurfMinBlades\tx0.5
detail_param\treduced\tpack:Pack/Foliage.pack.zs:Foliage/ResFoliageScene/*.bgyml\tLodSetting_hh.Dist_*\tx0.5
detail_param\treduced\tpack:Pack/Foliage.pack.zs:Foliage/ResFoliageScene/*.bgyml\tIsEnableMoss\t=false
'''


@unittest.skipIf(zstd is None, 'no zstd module (Python 3.14 compression.zstd or backports.zstd)')
class DetailOverlay(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(); cls.addClassCleanup(cls.tmp.cleanup); t = Path(cls.tmp.name)
        game = os.environ.get('DM_GAME', '0400486024DEC000')
        subprocess.run(['python3', str(REPO / 'dm/games/game.py'), 'header', game, str(t / 'gen/game_profile.h')], check=True)
        cls.tool = t / 't_detail'
        subprocess.run(['cc', '-std=gnu11', '-O1', '-w', '-I', str(REPO / 'dm/host'), '-I', str(t / 'gen'), str(REPO / 'dm/host/tests/t_detail.c'),
                        str(REPO / 'dm/third_party/zstd/zstddeclib.c'), '-lm', '-o', str(cls.tool)], check=True)
        (t / 'rows.tsv').write_text(ROWS)
        rom = t / 'romfs'; cls.rom = rom
        a, b = byml(SCENE_A), byml(SCENE_B, be=True)
        for rel, data in (('Foliage/ResFoliageScene/A.bgyml', a), ('Foliage/ResFoliageScene/B.bgyml', b)):
            (rom / rel).parent.mkdir(parents=True, exist_ok=True); (rom / rel).write_bytes(data)
        # a pack compressed with a dictionary trained here, and the pack of dictionaries (another dictionary first, so the ID picks)
        samples = [byml({**SCENE_A, 'TurfMinBlades': i}) + bytes(range(i % 40)) for i in range(400)]
        cls.dict = zstd.train_dict(samples, 4096); other = zstd.train_dict(samples[::-1][:300], 2048)
        cls.members = {'Foliage/ResFoliageScene/A.bgyml': a, 'Foliage/ResFoliageScene/B.bgyml': byml(SCENE_B), 'Other/Unrelated.bgyml': a}
        cls.sarc = sarc(cls.members)
        (rom / 'Pack').mkdir()
        (rom / 'Pack/Foliage.pack.zs').write_bytes(zstd.compress(cls.sarc, zstd_dict=cls.dict))
        (rom / 'Pack/ZsDic.pack.zs').write_bytes(zstd.compress(sarc({'a.zsdic': other.dict_content, 'pack.zsdic': cls.dict.dict_content})))
        cls.env = {**os.environ, 'DETAIL_ROWS': str(t / 'rows.tsv')}

    def build(self, level, *args):
        temp = Path(tempfile.mkdtemp(dir=self.tmp.name))
        r = subprocess.run([str(self.tool), 'build', str(self.rom), str(temp), level, *args], capture_output=True, text=True, env=self.env)
        over = r.stdout.split('overlay: ')[-1].split('\n')[0].strip()
        return r, temp, (Path(over) if over and over != '(none)' else None)

    def check_pack(self, over, want_id):
        raw = (over / 'Pack/Foliage.pack.zs').read_bytes()
        self.assertEqual(zstd.get_frame_info(raw).dictionary_id, want_id)
        out = zstd.decompress(raw, zstd_dict=self.dict) if want_id else zstd.decompress(raw)
        self.assertEqual(len(out), len(self.sarc))
        m, m0 = read_sarc(out), read_sarc(self.sarc)
        self.assertEqual(m, m0)   # the members where they were
        a = read_byml(out[slice(*m['Foliage/ResFoliageScene/A.bgyml'])])
        self.assertEqual((a['TurfDensity'], a['TurfMinBlades'], a['IsEnableMoss'], a['IsEnableTurf'], a['Name']), (75.0, 8, False, True, 'kokiri'))
        self.assertEqual(a['LodSetting_hh'], {'Dist_LowTree': 25.0, 'Dist_Undergrowth0': 10.0, 'Range_LowTree01': 5.0})
        self.assertEqual(read_byml(out[slice(*m['Foliage/ResFoliageScene/B.bgyml'])]), {**SCENE_B, 'IsEnableMoss': False})
        self.assertEqual(out[slice(*m['Other/Unrelated.bgyml'])], self.members['Other/Unrelated.bgyml'])
        changed = sum(x != y for x, y in zip(out, self.sarc))
        self.assertLessEqual(changed, 4 * 6)   # six values of four bytes, nothing else

    def test_reduced_keeps_the_dictionary_id(self):
        r, temp, over = self.build('reduced')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotIn('[RED]', r.stdout)
        self.assertIn('built: 2 files (1 loose, 1 packs)', r.stdout)
        self.check_pack(over, self.dict.dict_id)
        loose = (over / 'Foliage/ResFoliageScene/A.bgyml').read_bytes()
        self.assertEqual(len(loose), len((self.rom / 'Foliage/ResFoliageScene/A.bgyml').read_bytes()))
        self.assertEqual(read_byml(loose), {**SCENE_A, 'TurfDensity': 75.0})
        self.assertFalse((over / 'Foliage/ResFoliageScene/B.bgyml').exists())   # B has no TurfDensity: not edited, not in the overlay
        self.assertEqual(sorted(p.name for p in temp.iterdir()), [over.name])

    def test_without_the_dictionary_id(self):
        r, _, over = self.build('reduced', 'both', 'none')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.check_pack(over, 0)
        self.assertEqual(zstd.decompress((over / 'Pack/Foliage.pack.zs').read_bytes(), zstd_dict=self.dict), zstd.decompress((over / 'Pack/Foliage.pack.zs').read_bytes()))

    def test_full_builds_nothing(self):
        r, temp, over = self.build('full')
        self.assertEqual((r.returncode, over, list(temp.iterdir())), (0, None, []))
        self.assertNotIn('[detail]', r.stdout)

    def test_a_missing_dictionary_is_a_red_line(self):
        env = dict(self.env); rows = Path(self.tmp.name) / 'rows-nodict.tsv'
        rows.write_text(ROWS.replace('detail_zsdic\tPack/ZsDic.pack.zs\n', '')); self.env, saved = {**env, 'DETAIL_ROWS': str(rows)}, self.env
        try: r, _, over = self.build('reduced')
        finally: self.env = saved
        self.assertIn('[RED] [detail] Pack/Foliage.pack.zs: its frame names dictionary', r.stdout)
        self.assertFalse((over / 'Pack/Foliage.pack.zs').exists())   # the pack stays the game's own; the loose file is still edited
        self.assertTrue((over / 'Foliage/ResFoliageScene/A.bgyml').exists())


if __name__ == '__main__':
    unittest.main()
