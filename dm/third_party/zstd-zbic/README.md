# zstd-zbic: libzbicdec, a ZBIC decoder

From [Banana Native](https://git.eden-emu.dev/moceure/BananaNative) f86575b0 (v0.2.2), as it was there, with the one change named under "Anka Native" below.

ZBIC is the Zstandard variant that system 22.0.0+ NSO modules use for their segments (NSO flag bit 7; [packing.md](../../../docs/reference/packing.md#zbic-compressed-nso-segments)).
A ZBIC frame is a Zstandard frame with two differences: the frame magic is `ZBIC` (`0x4349425A` little-endian) instead of `0xFD2FB528`, and every FSE
normalized-count table description (the sequence tables in FSE_Compressed mode and the FSE header of compressed Huffman weights) is coded differently. libzbicdec
is upstream zstd's decoder with those two things changed. `dm/tools/nso.py` loads it through ctypes (`DM_ZBIC_LIB`, `libzbicdec.so` on Android, else the machine's own build in
`build/`, made with its C compiler the first time).

## What is upstream

`lib/` is from [Zstandard](https://github.com/facebook/zstd) **v1.5.7**, tag `v1.5.7`, the GitHub source archive of the tag
(`https://codeload.github.com/facebook/zstd/tar.gz/refs/tags/v1.5.7`, SHA-256 `37d7284556b20954e56e1ca85b80226768902e2edabd3b649e9e72c0c9012ee3`), unmodified:
`lib/zstd.h`, `lib/zstd_errors.h`, `lib/common/` without `pool.*` and `threading.*`, and `lib/decompress/` without `huf_decompress_amd64.S` (the decoder only: no
compressor, dictionary builder, legacy formats or x86 assembly). Licence: BSD, `LICENSE` (zstd is dual-licensed BSD or GPLv2; the BSD terms are taken here).

## What changed

Nothing in `lib/` is edited. The changes are four small files in `zbic/` (MIT, as the project; [LICENSE](../../../LICENSE)), written by Banana Native:

- `zbic_ncount.c`: `FSE_readNCount` and `FSE_readNCount_bmi2` for ZBIC's table descriptions. It was written from a prose description of the format alone (a flag
  bit and a byte count, then a mixed-radix integer read backwards: the maximum symbol, the table log, the total, and the cumulative counts by binary interpolative
  coding), with no code from any other ZBIC implementation. Beyond the format's own checks it refuses a count below -1, which no encoder writes and which zstd's
  table builder would not handle.
- `zbic_entropy.c`: compiles `lib/common/entropy_common.c` with zstd's own reader renamed (`zstd_upstream_FSE_readNCount*`), so the decoder links to the one above.
- `zbic_frame.c`: compiles `lib/decompress/zstd_decompress.c` with `ZSTD_MAGICNUMBER` replaced by the ZBIC magic.
- `zbic.c` / `zbic.h`: the library's C entry points, `long zbic_decompress(void *dst, size_t dst_size, const void *src, size_t src_size)` (the bytes written, or
  `-(zstd error code)`) and `const char *zbic_error_name(long)`.

`build.sh <output> [compiler and flags]` builds it (default `cc`): only the two entry points are exported, and on ELF the library binds its own calls
(`-Bsymbolic`), so a stock libzstd in the same process cannot stand in for the ZBIC reader. `android/ondevice/build.sh` builds the phone's `libzbicdec.so` with
the NDK (16 KB pages); on a computer `nso.py` (`zbic_build`) runs it with `$CC`, `cc`, `clang` or `gcc` into `build/<digest of these files>-<CPU>/`
(ignored by git), on Windows the same compile line without the shell; `dm/tools/tests/test_nso.py` builds a copy in a temporary folder for its tests.

## Thanks

The format was discovered and documented by others: [Atmosphère](https://github.com/Atmosphere-NX/Atmosphere) and Yannik Marchand's
[zbic](https://github.com/kinnay/zbic) (GPL-2.0). No code from either is here.

## Checked (by Banana Native)

- Round trip against `zbic.compress` (PyPI zbic 1.0.1.post1, used as a black box): 1,415 of 1,416 streams (empty to 3 MB; text, random, repetitive, skewed, mixed,
  code-like; levels 1 to 19) decode to the input and to `zbic.decompress`'s output; the one other is a stream that `zbic.decompress` refuses too (same error).
- The three ZBIC segments of a retail system 22 `main` decode to their header sizes and SHA-256 (63,201,744 / 12,603,520 / 5,217,152 bytes), equal to
  `zbic.decompress`; their table descriptions include 1,971 with the low-probability flag.
- Damaged input (truncations, bit flips, random bytes, about 100,000 cases) returns an error or bytes, under ASan and UBSan with exact-size output buffers, never a
  crash; 3,000 damaged streams give the same result as `zbic.decompress`.

## Anka Native

- Changed: `zbic/zbic.c` exports its two entry points with `__declspec(dllexport)` on Windows (a DLL exports only what is marked), where `nso.py` builds the
  library without `build.sh`. Nothing else is changed; the other files are byte for byte Banana Native's.
- It replaced PyPI's zbic 1.0.1.post1 (GPL-2.0), which the on-phone packer carried up to Anka Native 0.2.2. Checked against it on this branch: the six ZBIC
  segments of Ocarina of Time's `main` (v65536 and v131072, 119,699,010 bytes stored, 323,633,332 bytes decoded across the copies compared) decode to the same
  bytes, and the rewrite of its modules and the full shader split give byte-identical outputs. Of 3,896 damaged streams (truncated, bits flipped, bytes
  replaced, random tails, two frames; three segments and one synthetic stream) both decoders failed on 1,757 and gave the same bytes on 2,131 (2,129 of them
  wrong: a ZBIC frame without a checksum cannot tell, and `nso.py`'s segment SHA-256 refuses them), except an empty stream, which libzbicdec decodes to nothing
  (zbic: an error; `nso.py` refuses the length), and a header size too small, which libzbicdec refuses where zbic decodes past it. 10,000 more damaged streams
  ran under ASan and UBSan with exact-size buffers without a report. `dm/tools/tests/test_nso.py` makes ZBIC streams from Zstandard frames (its `zstd_to_zbic`) and tests the
  decoder on them, damaged forms included; streams made that way decode alike with zbic and with libzbicdec.
