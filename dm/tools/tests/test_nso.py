"""dm/tools/nso.py, the one loader of code modules: a damaged NSO or NRO is an nso.Error before anything its header claims is allocated (GitHub issue #451), never a short image.
The ZBIC segments of system 22.0.0+ modules (NSO flag bit 7) are decoded by libzbicdec (dm/third_party/zstd-zbic), which these tests build once with this
machine's C compiler (nso.zbic_build, from a copy of its sources in a temporary folder) and test on ZBIC streams made here from compression.zstd's frames
(zstd_to_zbic): no game data. Without a C compiler or Python 3.14's compression.zstd those tests are skipped."""
import hashlib
import os
from pathlib import Path
import random
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import tracemalloc
import unittest
from unittest import mock

import lz4.block

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import nso

TEXT, RO, DATA = bytes(range(256)) * 20, b'read-only ' * 300, b'\x01\x02' * 0x900


def page(x): return (x + 0xfff) & ~0xfff


def make_nso(segs=(TEXT, RO, DATA), compress=(1, 0, 1), bss=0x5000, pack=lambda s: lz4.block.compress(s, store_size=False)):
    """An NSO of the three segments, back to back on pages from 0 with their hashes, as Nintendo's tools write them (pack compresses a segment: LZ4, or ZBIC with
    flag bit 7)"""
    hdr, body, mo = bytearray(0x100), b'', 0
    hdr[:4] = b'NSO0'; hdr[0x40:0x60] = bytes(range(0x20))
    for i, s in enumerate(segs):
        stored = pack(s) if compress[i] else s
        struct.pack_into('<III', hdr, 0x10 + i * 0x10, 0x100 + len(body), mo, len(s)); struct.pack_into('<I', hdr, 0x60 + 4 * i, len(stored))
        hdr[0xa0 + 0x20 * i:0xc0 + 0x20 * i] = hashlib.sha256(s).digest()
        body += stored; mo = page(mo + len(s))
    struct.pack_into('<I', hdr, 0xc, 0x38 | sum(c << i for i, c in enumerate(compress))); struct.pack_into('<I', hdr, 0x3c, bss)
    return bytearray(hdr + body)


def make_nro(segs=(TEXT, RO, DATA)):
    """An NRO as nn::ro takes it: whole pages from 0, the file as long as the module"""
    img, at = bytearray(), []
    for s in segs:
        o = len(img); img += s; img += bytes(page(len(img)) - len(img)); at.append((o, len(img) - o))
    img[0x10:0x14] = b'NRO0'; struct.pack_into('<I', img, 0x18, len(img))
    for i, (o, sz) in enumerate(at): struct.pack_into('<II', img, 0x20 + 8 * i, o, sz)
    struct.pack_into('<I', img, 0x38, 0x3000); img[0x40:0x60] = bytes(0x20)
    return img, at


def seg(d, i, **kw):   # rewrite text (0), ro (1) or data (2) in the header: fo, mo, sz, stored
    fo, mo, sz = struct.unpack_from('<III', d, 0x10 + i * 0x10)
    fo, mo, sz = kw.get('fo', fo), kw.get('mo', mo), kw.get('sz', sz)
    struct.pack_into('<III', d, 0x10 + i * 0x10, fo, mo, sz)
    if 'stored' in kw: struct.pack_into('<I', d, 0x60 + 4 * i, kw['stored'])
    return d


def flags(d, set_=0, clear=0):
    f = struct.unpack_from('<I', d, 0xc)[0]; struct.pack_into('<I', d, 0xc, (f | set_) & ~clear); return d


class Load(unittest.TestCase):
    def test_nso(self):
        m = nso.load(make_nso())
        ro, data = page(len(TEXT)), page(page(len(TEXT)) + len(RO))
        self.assertEqual(m.segments, ((0, len(TEXT)), (ro, len(RO)), (data, len(DATA))))
        self.assertEqual(len(m.image), data + len(DATA))   # bss is a size only
        self.assertEqual((m.image[:len(TEXT)], m.image[ro:ro + len(RO)], m.image[data:]), (TEXT, RO, DATA))
        self.assertEqual((m.bss, m.text_end, m.build_id), (0x5000, len(TEXT), bytes(range(0x20))))

    def test_nro(self):
        d, at = make_nro()
        m = nso.load(d)
        self.assertEqual(m.segments, tuple(at)); self.assertEqual(bytes(m.image), bytes(d)); self.assertEqual(m.bss, 0x3000)

    def error(self, d, *words):
        with self.assertRaises(nso.Error) as c: nso.load(d, 'main')
        for w in ('main',) + words: self.assertIn(w, str(c.exception))
        return c.exception

    def test_huge_segment_is_refused_before_allocating(self):   # the fuzzing case: a header that lays out gigabytes in a few hundred bytes
        tracemalloc.start()
        try:
            self.error(seg(make_nso(), 0, sz=0xFFFFF000), 'text segment', 'more than Horizon loads')
            self.error(seg(seg(make_nso(), 0, sz=0x7FFFF000), 1, mo=0x80000000), 'text segment', 'more than LZ4 can produce')
            self.error(seg(make_nso(), 2, mo=0xFFFFE000), 'span', 'more than a file of')   # a gap Horizon allows, but bigger than the file could fill
            self.assertLess(tracemalloc.get_traced_memory()[1], 1 << 20)
        finally: tracemalloc.stop()

    def test_layout_as_horizon(self):   # pages, text at 0, ro then data without overlap; a gap is allowed
        ro = page(len(TEXT))
        self.error(seg(make_nso(), 1, mo=ro + 0x10), 'ro segment', 'not on a page')
        self.error(seg(make_nso(), 0, mo=0x1000), 'text segment', 'not at 0')
        self.error(seg(make_nso(), 1, mo=0), 'ro segment', 'overlaps the text segment')
        self.error(seg(make_nso(), 2, mo=ro), 'data segment', 'overlaps the ro segment')
        m = nso.load(seg(make_nso(), 2, mo=page(ro + len(RO)) + 0x2000))
        self.assertEqual(m.image[page(ro + len(RO)) + 0x2000:], DATA); self.assertFalse(any(m.image[ro + len(RO):page(ro + len(RO)) + 0x2000]))

    def test_short_segment_is_an_error(self):   # the fuzzing case: an LZ4 stream that decompresses to less than its size, which used to shrink the image without a word
        d = make_nso()
        self.error(seg(d, 2, sz=len(DATA) + 0x100), 'data segment', 'decompresses to')
        d = make_nso()
        fo = struct.unpack_from('<I', d, 0x10)[0]; stored = struct.unpack_from('<I', d, 0x60)[0]
        d[fo + stored - 8:fo + stored] = b'\xff' * 8   # a damaged stream
        self.error(d, 'text segment')

    def test_damaged_bytes_of_the_right_size(self):   # caught by the segment hash when its flag is set (every retail module), as on the Switch
        d = make_nso(); fo = struct.unpack_from('<I', d, 0x20)[0]
        d[fo + 5] ^= 1
        self.error(d, 'ro segment', 'SHA-256')
        m = nso.load(flags(d, clear=0x10))   # flag cleared, as in a modified main: not checked
        self.assertEqual(m.image[page(len(TEXT)) + 5], RO[5] ^ 1)

    def test_cut_short(self):
        d = make_nso()
        self.error(d[:-10], 'data segment', 'past the end of the file')
        self.error(d[:0x80], 'header is cut short')
        d, at = make_nro()
        self.error(d[:-1], 'the file is cut short')
        self.error(b'NSP0' + bytes(0x100), 'not an NSO or NRO')

    def test_nro_layout_as_nn_ro(self):
        d, at = make_nro()
        struct.pack_into('<I', d, 0x2c, at[1][1] - 0x10); self.error(d, 'ro segment', 'whole pages')
        d, at = make_nro(); struct.pack_into('<I', d, 0x18, len(d) + 0x1000); self.error(d, "the module's size")

    def test_uncompressed_is_read_at_its_size(self):   # Horizon ignores the stored size of an uncompressed segment
        d = make_nso()
        self.assertEqual(nso.load(seg(d, 1, stored=0)).image, nso.load(make_nso()).image)
        d = make_nso(); c = struct.unpack_from('<I', d, 0x68)[0]
        self.error(seg(d, 2, sz=c - 1), 'data segment', 'more than its')   # a compressed one is never larger stored than loaded

    def test_compression_that_fails_is_named(self):
        # flag bit 7 makes every compressed segment ZBIC (system 22.0.0 on): these segments are LZ4, so the Error names ZBIC, with the decoder or without one
        self.error(flags(make_nso(), set_=0x80), 'ZBIC')
        d, _ = make_nro(); struct.pack_into('<I', d, 0x1c, 2); self.error(d, 'compressed NRO')


class Tools(unittest.TestCase):
    """The scripts that read a module stop on a damaged one with a [RED] line, not a traceback"""
    def test_red_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / 'main'; bad.write_bytes(seg(make_nso(), 2, sz=len(DATA) + 0x100))
            for argv in (['dm/tools/patch_nso.py', bad, tmp, 'main'], ['dm/tools/nso.py', bad, Path(tmp) / 'main.bin'], ['dm/tools/insn_census.py', bad],
                         ['dm/tools/nso2elf.py', bad, Path(tmp) / 'main.elf'], ['dm/tools/loader_census.py', bad], ['dm/tools/nso_code.py', bad]):
                r = subprocess.run([sys.executable, *argv], cwd=REPO, capture_output=True, text=True, env=dict(os.environ, DM_GAME=''))
                self.assertNotEqual(r.returncode, 0, argv[0])
                self.assertNotIn('Traceback', r.stderr, argv[0]); self.assertTrue(r.stderr.startswith('[RED] '), (argv[0], r.stderr))

    def test_image_with_sparse_bss(self):   # nso.py's output file: the whole memory image, bss as a zero tail it never allocates
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp) / 'main', Path(tmp) / 'main.bin'; src.write_bytes(make_nso())
            subprocess.run([sys.executable, 'dm/tools/nso.py', src, out], cwd=REPO, check=True, capture_output=True)
            img = out.read_bytes(); m = nso.read(src)
            self.assertEqual(len(img), len(m.image) + 0x5000); self.assertEqual(img[:len(m.image)], m.image); self.assertFalse(any(img[len(m.image):]))


# ---- ZBIC (NSO flag bit 7): libzbicdec on streams made here ----

_LIB = {}   # 'path': libzbicdec built for these tests (None: no C compiler), 'why': the reason when there is none, 'tmp': its folder


def setUpModule():   # every test of this file, its subprocesses included, decodes with the library built here: the repository's build/ is not written
    tmp = _LIB['tmp'] = tempfile.TemporaryDirectory()
    src = Path(tmp.name) / 'zstd-zbic'
    shutil.copytree(nso.ZBIC_SRC, src, ignore=shutil.ignore_patterns('build'))
    with mock.patch.object(nso, 'ZBIC_SRC', str(src)):
        try: _LIB['path'] = nso.zbic_build()
        except nso.Error as e: _LIB['path'], _LIB['why'] = None, str(e)
    _LIB['env'] = mock.patch.dict(os.environ, {nso.ZBIC_LIB_ENV: _LIB['path']} if _LIB['path'] else {})
    _LIB['env'].start()


def tearDownModule():
    _LIB['env'].stop(); _LIB['tmp'].cleanup()


def zstd_module():
    try: from compression import zstd
    except ImportError: return None
    return zstd


ZSTD_MAGIC, ZBIC_MAGIC = b'\x28\xb5\x2f\xfd', b'ZBIC'


class Unfit(ValueError):
    """A frame zstd_to_zbic cannot write as ZBIC (a size field that the rewritten table would overflow): the test picks other data"""


def read_ncount(b):
    """Zstandard's normalized-count table description at the start of b (RFC 8878 4.1.1) → (the counts of symbols 0..S, the table log, the bytes used)"""
    bits = int.from_bytes(bytes(b[:1024]), 'little')
    tl = (bits & 15) + 5; pos = 4
    remaining, threshold, nb = (1 << tl) + 1, 1 << tl, tl + 1
    counts, prev0 = [], False
    while remaining > 1:
        if prev0:
            while True:
                r = bits >> pos & 3; pos += 2; counts += [0] * r
                if r != 3: break
        mx = (2 * threshold - 1) - remaining
        low = bits >> pos & (threshold - 1)
        if low < mx: v = low; pos += nb - 1
        else:
            v = bits >> pos & (2 * threshold - 1)
            if v >= threshold: v -= mx
            pos += nb
        c = v - 1
        remaining -= abs(c); counts.append(c); prev0 = c == 0
        while remaining < threshold: nb -= 1; threshold >>= 1
    return counts, tl, (pos + 7) // 8


def digits_bytes(digits):
    """The payload that ZBIC's table reader decodes into these (digit, radix) pairs in order, using every byte: its 64-bit state is refilled a byte at a time
    while below 2^40, from the payload's last byte down to its first (zbic_ncount.c). Written backwards from a final state; a state with no predecessor in
    range (rare: 2^40 is no multiple of the radix) is passed over by starting from the next final state"""
    lo = 1 << 40
    for end in range(lo, lo + (1 << 16)):
        x, out = end, bytearray()
        for d, m in reversed(digits):
            k = 0
            while (x >> 8 * k) * m + d >= lo << 8: k += 1
            y = (x >> 8 * k) * m + d
            if y < lo: break
            out += bytes(x >> 8 * j & 0xff for j in range(k)); x = y
        else:
            return bytes(out + x.to_bytes(6, 'little'))
    raise Unfit('no final state fits')


def zbic_ncount(counts, tl):
    """counts (symbols 0..S, -1 = less than one) and the table log as a ZBIC table description, as zbic_ncount.c reads it: L (set when a count is -1) and the
    payload's size in one byte, then the mixed-radix payload: S - 1, table log - 5, the total less one, the cumulative sums of (count + L) by interpolative
    coding, and the closing interval's digit (its value is not used: 0)"""
    counts = list(counts) + [0] * (len(counts) < 2)   # S >= 1: the first digit is S - 1
    s, low = len(counts) - 1, int(-1 in counts)
    n = 1
    while n <= s: n <<= 1
    cum = [0] * (n + 1)
    for x in range(1, n + 1): cum[x] = cum[x - 1] + (counts[x - 1] + low if x - 1 <= s else 0)
    digits, stack = [(s - 1, 52), (tl - 5, 8), (sum(counts) - 1, 1 << tl)], [(0, n)]
    while stack:
        a, b = stack.pop()
        if b - a < 2: continue
        mid = (a + b) // 2
        if cum[a] != cum[b]: digits.append((cum[mid] - cum[a], cum[b] - cum[a] + 1))
        stack += [(a, mid), (mid, b)]
    if cum[0] != cum[1]: digits.append((0, cum[1] - cum[0] + 1))
    p = digits_bytes(digits)
    if len(p) > 127: raise Unfit('a table description longer than 127 bytes')
    return bytes([low << 7 | len(p)]) + p


def _table(b, stats):   # a Zstandard table description at the start of b → (its ZBIC form, the bytes it used)
    counts, tl, used = read_ncount(b)
    stats['tables'] += 1; stats['low'] += -1 in counts
    return zbic_ncount(counts, tl), used


def _block(b, stats):   # a compressed block with its literals' and sequences' table descriptions rewritten (RFC 8878 3.1.1.3)
    t, sf = b[0] & 3, b[0] >> 2 & 3
    if t < 2:   # raw or RLE literals
        hs = (1, 2, 1, 3)[sf]
        size = b[0] >> 3 if hs == 1 else (b[0] >> 4) + (b[1] << 4) + ((b[2] << 12) if hs == 3 else 0)
        end = hs + (size if t == 0 else 1); lit = bytes(b[:end])
    else:   # compressed (2, with its Huffman tree) or treeless (3)
        hs, nbits = ((3, 10), (3, 10), (4, 14), (5, 18))[sf]
        h = int.from_bytes(b[:hs], 'little'); regen, comp = h >> 4 & (1 << nbits) - 1, h >> 4 + nbits & (1 << nbits) - 1
        body = bytes(b[hs:hs + comp]); end = hs + comp
        if t == 2 and body[0] < 128:   # Huffman weights compressed with FSE: their table description comes first
            w = body[1:1 + body[0]]; nc, used = _table(w, stats); w = nc + w[used:]
            if len(w) >= 128: raise Unfit('the Huffman weights header')
            body = bytes([len(w)]) + w + body[1 + body[0]:]
        if len(body) >= 1 << nbits: raise Unfit('the literals size field')
        lit = (t | sf << 2 | regen << 4 | len(body) << 4 + nbits).to_bytes(hs, 'little') + body
    p = end; n0 = b[p]
    if n0 == 0: return lit + bytes(b[p:])
    p += 1 if n0 < 128 else 2 if n0 < 255 else 3
    modes = b[p]; p += 1; out = lit + bytes(b[end:p])
    for shift in (6, 4, 2):   # literal lengths, offsets, match lengths: an FSE_Compressed one (mode 2) has a table description
        mode = modes >> shift & 3
        if mode == 1: out += bytes(b[p:p + 1]); p += 1
        elif mode == 2: nc, used = _table(b[p:], stats); out += nc; p += used
    return out + bytes(b[p:])


def zstd_to_zbic(frame, stats):
    """One Zstandard frame → the same frame as ZBIC: the magic `ZBIC`, and every FSE table description (an FSE_Compressed sequence table, the FSE header of
    compressed Huffman weights) in ZBIC's coding; the bitstreams and the checksum unchanged, so it decodes to the same bytes. stats gets 'tables' (tables
    rewritten) and 'low' (those with a -1 count, which ZBIC codes with its flag L)"""
    stats.setdefault('tables', 0); stats.setdefault('low', 0)
    d = memoryview(frame)
    assert bytes(d[:4]) == ZSTD_MAGIC
    fhd = d[4]; single = fhd >> 5 & 1
    p = 5 + (not single) + (0, 1, 2, 4)[fhd & 3] + ((1 if single else 0), 2, 4, 8)[fhd >> 6]
    out = bytearray(ZBIC_MAGIC) + d[4:p]
    while True:
        h = int.from_bytes(d[p:p + 3], 'little'); p += 3
        last, kind, size = h & 1, h >> 1 & 3, h >> 3
        if kind == 0: body = bytes(d[p:p + size]); p += size
        elif kind == 1: body = bytes(d[p:p + 1]); p += 1
        else:
            body = _block(d[p:p + size], stats); p += size
            if len(body) > 1 << 17: raise Unfit('the block size')
            size = len(body)
        out += (last | kind << 1 | size << 3).to_bytes(3, 'little') + body
        if last: break
    if fhd >> 2 & 1: out += d[p:p + 4]; p += 4
    assert p == len(d)
    return bytes(out)


def synthetic(n, seed):
    """Text-like bytes from a fixed generator (words, line ends, now and then a stray byte), whose zstd tables include counts of -1"""
    words = [b'the', b'zbic', b'frame', b'table', b'count', b'segment', b'module', b'x', b'qq', b'lorem', b'ipsum']
    x, out = seed, bytearray()
    while len(out) < n:
        x = (x * 6364136223846793005 + 1442695040888963407) & (2**64 - 1)
        out += words[(x >> 33) % len(words)] + (b' ' if (x >> 20) & 7 else b'\n') + (bytes([(x >> 40) & 0xff]) if (x >> 13) & 31 == 0 else b'')
    return bytes(out[:n])


def zbic(data, level=3, checksum=True, stats=None):
    zstd = zstd_module()
    f = zstd.compress(data, options={zstd.CompressionParameter.compression_level: level, zstd.CompressionParameter.checksum_flag: int(checksum)})
    return zstd_to_zbic(f, {} if stats is None else stats)


class Zbic(unittest.TestCase):
    """libzbicdec through nso.py on ZBIC streams made here: what they decode to, and every damaged form an Error, never a crash, a hang or a wrong image"""
    def setUp(self):
        if not _LIB['path']: self.skipTest(f'no libzbicdec ({_LIB.get("why")})')
        if not zstd_module(): self.skipTest('no compression.zstd (Python 3.14)')

    def error(self, d, *words):
        with self.assertRaises(nso.Error) as c: nso.load(d, 'main')
        for w in ('main',) + words: self.assertIn(w, str(c.exception))
        return c.exception

    def module(self, text, z=None, **kw):   # an NSO whose text segment is the ZBIC stream z (zbic(text) when None), ro and data uncompressed
        return flags(make_nso(segs=(text, RO, DATA), compress=(1, 0, 0), pack=lambda s: z if z is not None else zbic(s, **kw)), set_=0x80)

    def test_streams_decode(self):   # sizes, kinds of data and levels: each module's image is that of the same module uncompressed
        rnd, stats, n = random.Random(1), {}, 0
        for size in (0, 1, 100, 5000, 24000, 300000):
            for data in (synthetic(size, size), bytes(rnd.choice(b'aaaaabbbcdefg\x00\x01') for _ in range(size)), rnd.randbytes(size // 4) * 4,
                         bytes((i * i >> 3) & 0xff for i in range(size))):
                for level in (1, 3, 19):
                    try: z = zbic(data, level, checksum=level != 1, stats=stats)
                    except Unfit: continue
                    if len(z) > len(data): continue   # stored larger than loaded: Horizon refuses it (test_uncompressed_is_read_at_its_size)
                    d = self.module(data, z)
                    self.assertEqual(nso.load(d).image, nso.load(make_nso(segs=(data, RO, DATA), compress=(0, 0, 0))).image, (size, level)); n += 1
        self.assertGreater(n, 40)
        self.assertGreater(stats['tables'], 100); self.assertGreater(stats['low'], 30)   # the table reader ran, with and without its flag L

    def test_all_segments_and_the_decoder_alone(self):   # three ZBIC segments in one module; the decoder's own answer for a stream
        d = flags(make_nso(compress=(1, 1, 1), pack=zbic), set_=0x80)
        self.assertEqual(nso.load(d).image, nso.load(make_nso(compress=(0, 0, 0))).image)
        decode = nso.zbic_decoder()
        text = synthetic(50000, 7)
        out, err = decode(zbic(text), len(text)); self.assertIsNone(err); self.assertEqual(out, text)
        out, err = decode(zbic(text), len(text) - 1); self.assertIsNone(out); self.assertEqual(err[0], nso.ZSTD_DST_TOO_SMALL)

    def test_truncated(self):   # cut at every byte of a stream (its tables, literals, sequences, checksum): the decoder's Error or the short length's
        text = synthetic(6000, 3); z = zbic(text)
        for cut in range(len(z)):
            with self.subTest(cut=cut): self.error(self.module(text, z[:cut]), 'text segment')

    def test_corrupted(self):
        """Every byte of the stream changed, one at a time: an Error (the decoder's, the length's or the segment hash's) or, where the change is in bits the
        decoder does not use (the unused digit that closes a table description, a bitstream's padding), the same image: never a wrong one"""
        text = synthetic(6000, 4); z = zbic(text, checksum=False); want, same = nso.load(self.module(text, z)).image, 0
        for at in range(len(z)):
            for x in (0x01, 0x80, 0xff):
                bad = bytearray(z); bad[at] ^= x
                with self.subTest(at=at, x=x):
                    try: m = nso.load(self.module(text, bytes(bad)), 'main')
                    except nso.Error as e: self.assertIn('main: text segment', str(e)); continue
                    self.assertEqual(m.image, want); same += 1
        self.assertLess(same, len(z) // 20)
        rnd = random.Random(2)
        for i in range(300):   # ZBIC's magic and a valid frame header, then random bytes
            junk = z[:6] + rnd.randbytes(rnd.randrange(0, 400))
            with self.subTest(junk=i): self.error(self.module(text, junk), 'text segment')

    def test_oversized(self):   # a stream that holds more than the header says stops at the header's size; a frame claiming 2^40 bytes allocates nothing
        text = synthetic(20000, 5); z = zbic(text)
        self.error(seg(self.module(text, z), 0, sz=len(text) - 0x10), 'text segment', 'more than the')
        self.error(seg(self.module(text, z), 0, sz=len(text) + 0x10), 'text segment', 'decompresses to')
        huge = ZBIC_MAGIC + bytes([0xe0]) + (1 << 40).to_bytes(8, 'little') + (1 | 0 << 1 | 16 << 3).to_bytes(3, 'little') + b'A' * 16   # FCS 2^40, one raw block
        tracemalloc.start()
        try:
            self.error(self.module(text, huge), 'text segment', 'ZBIC')
            self.assertLess(tracemalloc.get_traced_memory()[1], 4 * len(text) + (1 << 20))
        finally: tracemalloc.stop()

    def test_other_formats(self):   # a plain Zstandard frame and an LZ4 block are no ZBIC: the decoder's own error, named; an empty stream decodes to nothing
        zstd = zstd_module(); text = synthetic(3000, 6)
        for raw in (zstd.compress(text), lz4.block.compress(text, store_size=False)):
            self.error(self.module(text, raw), 'text segment', 'ZBIC does not decompress (Unknown frame descriptor)')
        self.error(self.module(text, b''), 'text segment', 'decompresses to 0x0 bytes')

    def test_in_a_subinterpreter(self):   # ctypes loads in a subinterpreter (the phone's split workers, shader_split.InterpPool): no NeedsMain for ZBIC
        try: from concurrent import interpreters
        except ImportError: self.skipTest('no concurrent.interpreters (Python 3.14)')
        with tempfile.TemporaryDirectory() as tmp:
            text = synthetic(9000, 8); p = Path(tmp) / 'main'; p.write_bytes(self.module(text))
            it = interpreters.create()
            try: it.exec(f'import hashlib, sys; sys.path.insert(0, {str(REPO / "dm/tools")!r}); import nso\n'
                         f'assert bytes(nso.read({str(p)!r}).image[:{len(text)}]) == {text!r}')
            finally: it.close()


class ZbicLibrary(unittest.TestCase):
    """Where nso.py takes libzbicdec from: never another decoder, and an Error that says what is missing"""
    def test_named_library_that_does_not_load(self):
        with mock.patch.dict(os.environ, {nso.ZBIC_LIB_ENV: '/nonexistent/libzbicdec.so'}):
            with self.assertRaises(nso.Error) as c: nso.load(flags(make_nso(), set_=0x80), 'main')
        for w in ('main: text segment', '/nonexistent/libzbicdec.so', 'does not load', nso.ZBIC_LIB_ENV): self.assertIn(w, str(c.exception))

    def test_no_compiler(self):   # not built yet and no C compiler: the Error names what to install, or the library to point at
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / 'zstd-zbic'; shutil.copytree(nso.ZBIC_SRC, src, ignore=shutil.ignore_patterns('build'))
            env = {k: v for k, v in os.environ.items() if k not in (nso.ZBIC_LIB_ENV, 'CC')}
            with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(nso, 'ZBIC_SRC', str(src)), mock.patch('shutil.which', return_value=None):
                with self.assertRaises(nso.Error) as c: nso.load(flags(make_nso(), set_=0x80), 'main')
        for w in ('main: text segment', 'libzbicdec', 'no C compiler', nso.ZBIC_LIB_ENV): self.assertIn(w, str(c.exception))

    def test_compiler_that_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / 'zstd-zbic'; shutil.copytree(nso.ZBIC_SRC, src, ignore=shutil.ignore_patterns('build'))
            env = {k: v for k, v in os.environ.items() if k != nso.ZBIC_LIB_ENV}
            with mock.patch.dict(os.environ, dict(env, CC=f'{sys.executable} -c "import sys; sys.exit(3)"'), clear=True), mock.patch.object(nso, 'ZBIC_SRC', str(src)):
                with self.assertRaises(nso.Error) as c: nso.zbic_decoder()
            self.assertIn('does not build', str(c.exception)); self.assertFalse(list((src / 'build').glob('*/*zbicdec*')))   # nothing half-built left as the library

    def test_compiler_that_does_not_run(self):   # $CC names no program: an Error naming it, not an OSError from subprocess
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / 'zstd-zbic'; shutil.copytree(nso.ZBIC_SRC, src, ignore=shutil.ignore_patterns('build'))
            env = {k: v for k, v in os.environ.items() if k != nso.ZBIC_LIB_ENV}
            with mock.patch.dict(os.environ, dict(env, CC=str(Path(tmp) / 'no-such-cc')), clear=True), mock.patch.object(nso, 'ZBIC_SRC', str(src)), \
                 mock.patch.object(nso.sys, 'platform', 'win32'):
                with self.assertRaises(nso.Error) as c: nso.zbic_decoder()
            for w in ('does not run', 'no-such-cc', nso.ZBIC_LIB_ENV): self.assertIn(w, str(c.exception))

    def test_cc_on_windows_keeps_backslashes(self):   # CC=C:\llvm-mingw\bin\clang.exe is one word with its backslashes; quotes group a path with spaces
        with mock.patch.object(nso.sys, 'platform', 'win32'):
            self.assertEqual(nso.cc_words(r'C:\llvm-mingw\bin\clang.exe'), [r'C:\llvm-mingw\bin\clang.exe'])
            self.assertEqual(nso.cc_words(r'"C:\Program Files\LLVM\bin\clang.exe" --target=x86_64-w64-mingw32'),
                             [r'C:\Program Files\LLVM\bin\clang.exe', '--target=x86_64-w64-mingw32'])
        with mock.patch.object(nso.sys, 'platform', 'linux'): self.assertEqual(nso.cc_words("ccache 'my cc'"), ['ccache', 'my cc'])

    def test_windows_line_is_build_sh(self):   # nso.py's compile line for Windows (no sh) has build.sh's flags and files
        sh = (Path(nso.ZBIC_SRC) / 'build.sh').read_text()
        self.assertEqual([f for f in nso.ZBIC_FLAGS if f.startswith('-D')], re.findall(r'(-D\w+(?:=\w*)?)', sh))
        for f in nso.ZBIC_FLAGS: self.assertIn(f.split('=')[0], sh)
        self.assertEqual(nso.ZBIC_SOURCES, re.findall(r'"\$Z/([\w/]+\.c)"', sh))

    def test_sources_are_the_reader_and_upstream(self):   # every .c the library is built from exists, and the build's folder is ignored by git
        for f in nso.ZBIC_SOURCES: self.assertTrue((Path(nso.ZBIC_SRC) / f).is_file(), f)
        self.assertIn('dm/third_party/zstd-zbic/build/', (REPO / '.gitignore').read_text().splitlines())


if __name__ == '__main__':
    unittest.main()
