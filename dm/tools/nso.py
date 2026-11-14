#!/usr/bin/env python3
"""The one loader of Switch code modules: an NSO (ExeFS) or an NRO (loaded at run time with nn::ro) → its memory image of text + ro + data, bss not included.
Every tool that reads a module's code goes through load(). It accepts what Horizon accepts and checks it the same way, before anything the header claims is allocated; a damaged or
truncated file raises Error (a ValueError):
  · NSO, as Horizon's loader (Atmosphere's ldr_process_creation.cpp, ValidateNsoHeaders, LoadAutoLoadModuleSegment, CheckSegmentHash): memory offsets on pages, text at 0, then ro,
    then data, none overlapping (a gap is allowed); sizes below 2 GiB; an uncompressed segment is read at its size, a compressed one is no larger stored than loaded and must
    decompress to exactly its size (a short LZ4 stream decompresses without an error, and the image came out shorter or with the later segments shifted); a segment whose
    check-hash flag is set (every retail module: 93 of 93 surveyed, all 279 hashes matching) must match its SHA-256 in the header, so damaged bytes of the right size are caught too.
    A module with the flags cleared (a modified main) is not checked, as on the Switch.
  · NRO, as nn::ro (Atmosphere's ro_service_impl.cpp, ValidateNro): sizes in whole pages, text at 0, ro and data right after, data ending at the module's size (40 of 40 surveyed).
  · Beyond Horizon, so a small file cannot claim gigabytes: a compressed segment can be no more than LZ4 produces from its stored bytes (255 bytes per byte; zstd has no such
    bound, so a ZBIC segment is held only to its own size), and the image, gaps included, no more than that of the whole file.
  · Compression: LZ4, and ZBIC (NSO flag bit 7, system 22.0.0 on: Nintendo's zstd variant, its own magic and entropy-table coding, which stock zstd cannot read) through
    libzbicdec (dm/third_party/zstd-zbic, Banana Native's decoder: zstd's, BSD, with an MIT reader of ZBIC's tables) by ctypes: zbic_decoder. A compressed NRO
    (system 23.0.0+) is an Error naming it, never a wrong image.
bss is only a size: the image never includes it (patch_nso.py lays it out; this script's output file gets it as a sparse tail).
Usage on its own: nso.py <module> <out.bin> (the whole memory image, bss included, for nso_fnmap.py and analysis)"""
import hashlib, os, struct, sys
from typing import NamedTuple

PAGE = 0x1000
LZ4_MAX_RATIO = 255   # an LZ4 block produces at most 255 bytes per stored byte (a match length extension byte)
SIZE_MAX = 0x7FFFFFFF   # Horizon reads segment sizes as s32
NSO_ZSTD, NRO_COMPRESSED = 1 << 7, 1 << 1
NAMES = ('text', 'ro', 'data')


class Error(ValueError):
    pass


ZBIC_LIB_ENV = 'DM_ZBIC_LIB'   # the path of libzbicdec (or a bare name the dynamic linker finds): a build of dm/third_party/zstd-zbic
ZBIC_SRC = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'third_party', 'zstd-zbic'))
ZSTD_DST_TOO_SMALL = 70   # ZSTD_error_dstSize_tooSmall (zstd_errors.h): the stream holds more than the header's size
_zbic_libs = {}   # path → decode, each library loaded once


def zbic_decoder():
    """The ZBIC decoder, decode(raw, size) → (bytearray, None) or (None, (zstd error code, its name)): libzbicdec through ctypes. Which library: $DM_ZBIC_LIB;
    else on Android libzbicdec.so by its bare name (the APK's lib/, android/ondevice/build.sh); else this machine's build of dm/third_party/zstd-zbic, made
    the first time with its C compiler (zbic_build). A library that is named but does not load is an Error, never a fallback to another decoder"""
    path = os.environ.get(ZBIC_LIB_ENV) or ('libzbicdec.so' if sys.platform == 'android' else None) or zbic_build()
    if path not in _zbic_libs: _zbic_libs[path] = _zbic_lib(path)
    return _zbic_libs[path]


def _zbic_lib(path):
    import ctypes
    try: lib = ctypes.CDLL(path)
    except OSError as e: raise Error(f'libzbicdec {path} does not load ({e}; {ZBIC_LIB_ENV} names the library)') from None
    lib.zbic_decompress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_char_p, ctypes.c_size_t]
    lib.zbic_decompress.restype = ctypes.c_long
    lib.zbic_error_name.argtypes, lib.zbic_error_name.restype = [ctypes.c_long], ctypes.c_char_p

    def decode(raw, size):
        out, src = bytearray(size), bytes(raw)   # out filled in place: no second copy of a segment (text can be 60 MB)
        dst = (ctypes.c_char * size).from_buffer(out) if size else None
        n = lib.zbic_decompress(dst, size, src, len(src))
        del dst   # the buffer export ends before out is resized
        if n < 0: return None, (-n, lib.zbic_error_name(n).decode(errors='replace'))
        del out[n:]
        return out, None
    return decode


def zbic_build():
    """The path of this machine's libzbicdec, dm/third_party/zstd-zbic/build/<sources' digest>-<CPU>/, built there the first time: build.sh with $CC, else
    cc, clang or gcc (on Windows the same compile line run directly). Built into a folder of its own and renamed into place, so processes and subinterpreters
    that build at once each get a whole library"""
    import platform, shlex, shutil, subprocess, tempfile
    h, files = hashlib.sha256(), []
    for d, dirs, names in os.walk(ZBIC_SRC):
        dirs[:] = sorted(x for x in dirs if not (d == ZBIC_SRC and x == 'build'))
        for n in sorted(names):
            p = os.path.join(d, n); files.append(p)
            with open(p, 'rb') as f: h.update(os.path.relpath(p, ZBIC_SRC).replace(os.sep, '/').encode() + b'\0' + f.read())
    if not files: raise Error(f'libzbicdec: {ZBIC_SRC} is missing (set {ZBIC_LIB_ENV} to a build of dm/third_party/zstd-zbic)')
    name = 'zbicdec.dll' if sys.platform == 'win32' else 'libzbicdec.dylib' if sys.platform == 'darwin' else 'libzbicdec.so'
    out = os.path.join(ZBIC_SRC, 'build', f'{h.hexdigest()[:16]}-{platform.machine().lower() or "cpu"}', name)
    if os.path.isfile(out): return out
    cc = shlex.split(os.environ.get('CC', '')) or next(([c] for c in ('cc', 'clang', 'gcc') if shutil.which(c)), None)
    if not cc:
        raise Error(f'libzbicdec (the ZBIC decoder) is not built here, and there is no C compiler to build it ($CC, cc, clang or gcc: the Mac\'s command line '
                    f'tools, Linux\'s gcc or clang, on Windows LLVM-MinGW or MSYS2\'s gcc); or set {ZBIC_LIB_ENV} to a build of dm/third_party/zstd-zbic')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    tmp = tempfile.mkdtemp(prefix='tmp-', dir=os.path.dirname(out))
    try:
        lib = os.path.join(tmp, name)
        if sys.platform == 'win32': cmd = cc + ['-shared', '-static-libgcc'] + ZBIC_FLAGS + ['-o', lib] + [os.path.join(ZBIC_SRC, *s.split('/')) for s in ZBIC_SOURCES]
        else: cmd = ['sh', os.path.join(ZBIC_SRC, 'build.sh'), lib] + cc
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace')
        if r.returncode or not os.path.isfile(lib):
            raise Error(f'libzbicdec does not build with {shlex.join(cc)} (exit code {r.returncode}): ' + ' | '.join(r.stdout.strip().splitlines()[-6:]))
        os.replace(lib, out)
    finally: shutil.rmtree(tmp, ignore_errors=True)
    return out


# build.sh's compile line without its shell (Windows: no sh, and no -Bsymbolic or soname for a DLL; zbic.c exports its two entry points with dllexport).
# dm/tools/tests/test_nso.py checks that these are build.sh's flags and files.
ZBIC_FLAGS = ['-O2', '-DNDEBUG', '-fvisibility=hidden', '-DZSTD_LEGACY_SUPPORT=0', '-DZSTD_DISABLE_ASM', '-DZSTD_TRACE=0', '-DDEBUGLEVEL=0', '-DZSTDLIB_VISIBLE=',
              '-DZSTDERRORLIB_VISIBLE=', '-DXXH_NAMESPACE=ZBIC_']
ZBIC_SOURCES = ['lib/common/debug.c', 'lib/common/error_private.c', 'lib/common/fse_decompress.c', 'lib/common/xxhash.c', 'lib/common/zstd_common.c',
                'lib/decompress/huf_decompress.c', 'lib/decompress/zstd_ddict.c', 'lib/decompress/zstd_decompress_block.c',
                'zbic/zbic_entropy.c', 'zbic/zbic_frame.c', 'zbic/zbic_ncount.c', 'zbic/zbic.c']


def zstd_segment(raw, size, what):
    """A ZBIC segment (NSO flag bit 7, system 22.0.0 on) decompressed to size by zbic_decoder(): a damaged stream is the decoder's own error, passed on; one
    that holds more than size stops at size and is this loader's Error, and one that holds less is caught by load() as a short LZ4 stream is"""
    try: decode = zbic_decoder()
    except Error as e: raise Error(f'{what}: {e}') from None
    out, err = decode(raw, size)
    if err and err[0] == ZSTD_DST_TOO_SMALL: raise Error(f'{what}: ZBIC decompresses to more than the {size:#x} bytes of the header: the file is damaged')
    if err: raise Error(f'{what}: ZBIC does not decompress ({err[1]})')
    return out


class Module(NamedTuple):
    image: bytearray    # text + ro + data at their memory offsets
    segments: tuple     # ((memory offset, size),) of text, ro and data
    bss: int
    build_id: bytes     # 0x20 bytes; the build ID tools key on is its first 0x10

    @property
    def text_end(self): return self.segments[0][1]


def load(d, name='module'):
    """Module of the file's bytes d (NSO0 or NRO0); name goes into the Error messages"""
    if d[:4] == b'NSO0':
        if len(d) < 0x100: raise Error(f'{name}: the NSO header is cut short ({len(d)} bytes)')
        flags = struct.unpack_from('<I', d, 0xc)[0]
        stored = [struct.unpack_from('<III', d, 0x10 + i * 0x10) for i in range(3)]   # (file offset, memory offset, size)
        csz = [c if flags >> i & 1 else sz for i, ((_, _, sz), c) in enumerate(zip(stored, struct.unpack_from('<III', d, 0x60)))]   # bytes in the file
        bss, build_id = struct.unpack_from('<I', d, 0x3c)[0], bytes(d[0x40:0x60])
        for i, ((fo, mo, sz), c) in enumerate(zip(stored, csz)):
            what = f'{name}: {NAMES[i]} segment'
            if mo % PAGE: raise Error(f'{what} is at {mo:#x}, not on a page')
            if sz > SIZE_MAX: raise Error(f'{what} claims {sz:#x} bytes, more than Horizon loads')
            if fo + c > len(d): raise Error(f'{what} is stored at {fo:#x}..{fo + c:#x}, past the end of the file ({len(d):#x} bytes): the file is cut short or damaged')
            if flags >> i & 1 and c > sz: raise Error(f'{what} is stored compressed in {c:#x} bytes, more than its {sz:#x}')
            if flags >> i & 1 and not flags & NSO_ZSTD and sz > c * LZ4_MAX_RATIO: raise Error(f'{what} claims {sz:#x} bytes from {c:#x} stored, more than LZ4 can produce')
        if stored[0][1]: raise Error(f'{name}: text segment is at {stored[0][1]:#x}, not at 0')
        for i in (1, 2):
            if stored[i][1] < stored[i - 1][1] + stored[i - 1][2]: raise Error(f'{name}: {NAMES[i]} segment at {stored[i][1]:#x} overlaps the {NAMES[i - 1]} segment before it')
    elif d[0x10:0x14] == b'NRO0':
        # The file is the image itself: each segment stored at its memory offset, uncompressed; +0x18 the module's size, +0x1c flags, +0x20 text, +0x28 ro, +0x30 data as {offset, size},
        # +0x38 bss size, +0x40 the build ID
        if len(d) < 0x80: raise Error(f'{name}: the NRO header is cut short ({len(d)} bytes)')
        size, nflags = struct.unpack_from('<II', d, 0x18)
        if nflags & NRO_COMPRESSED: raise Error(f'{name}: a compressed NRO (system 23.0.0+), which this loader does not decode yet')
        flags, stored = 0, [(o, o, sz) for o, sz in (struct.unpack_from('<II', d, 0x20 + 8 * i) for i in range(3))]
        csz = [sz for _, _, sz in stored]
        bss, build_id = struct.unpack_from('<I', d, 0x38)[0], bytes(d[0x40:0x60])
        at = 0
        for i, (o, _, sz) in enumerate(stored):
            if sz % PAGE or o != at: raise Error(f'{name}: {NAMES[i]} segment {o:#x}..{o + sz:#x} is not whole pages right after the one before it (at {at:#x})')
            at += sz
        if at != size: raise Error(f'{name}: the segments end at {at:#x}, not at the module\'s size {size:#x}')
        if size > len(d): raise Error(f'{name}: the module is {size:#x} bytes, the file {len(d):#x}: the file is cut short or damaged')
    else:
        raise Error(f'{name}: not an NSO or NRO (magic {bytes(d[:4])!r})')
    end = stored[2][1] + stored[2][2]
    if end > len(d) * LZ4_MAX_RATIO: raise Error(f'{name}: the segments span {end:#x} bytes, more than a file of {len(d):#x} bytes can fill (a gap of zeros between them)')
    img = bytearray(end)
    for i, ((fo, mo, sz), c) in enumerate(zip(stored, csz)):
        raw = d[fo:fo + c]
        if flags >> i & 1:
            if flags & NSO_ZSTD:
                raw = zstd_segment(raw, sz, f'{name}: {NAMES[i]} segment')
            else:
                import lz4.block
                try: raw = lz4.block.decompress(raw, uncompressed_size=sz)
                except lz4.block.LZ4BlockError as e: raise Error(f'{name}: {NAMES[i]} segment does not decompress ({e})') from None
        if len(raw) != sz: raise Error(f'{name}: {NAMES[i]} segment decompresses to {len(raw):#x} bytes, not the {sz:#x} of the header: the file is damaged')
        if flags >> (3 + i) & 1 and hashlib.sha256(raw).digest() != d[0xa0 + 0x20 * i:0xc0 + 0x20 * i]:
            raise Error(f'{name}: {NAMES[i]} segment does not match its SHA-256 in the header: the file is damaged')
        img[mo:mo + sz] = raw
    return Module(img, tuple((mo, sz) for _, mo, sz in stored), bss, build_id)


def read(path):
    """load() of a file by path; Error carries the path"""
    with open(path, 'rb') as f: return load(f.read(), str(path))


def cli_read(path):
    """read() for a script's own command line: a damaged module stops it with a [RED] line, not a traceback"""
    try: return read(path)
    except Error as e: sys.exit(f'[RED] {e}')


if __name__ == '__main__':
    m = cli_read(sys.argv[1])
    with open(sys.argv[2], 'wb') as f:
        f.write(m.image); f.truncate(len(m.image) + m.bss)   # bss as a sparse zero tail, never allocated (Metroid Dread's main has about 1.8 GB)
    print([hex(x) for s in m.segments for x in s], hex(m.bss), 'moduleid', m.build_id.hex())
