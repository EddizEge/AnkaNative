// The dynamic-resolution level switch (DM_RES_LEVEL, dm/host/drs_level.h, loader.c drs_level_apply): the parser, and the code words laid out for Ocarina of Time
// v131072's drs_level row (dm/games/0400486024DEC000/game.tsv), checked word for word against encodings from llvm-mc (aarch64) and branch targets worked out by hand.
// Then runs the laid-out words themselves, from the site's branch to the branch back, in a small AArch64 interpreter (only the instructions drs_level_body and the
// layout emit: movz, cmp, csel, ldr w, sub imm, b; anything else fails the test), with a fake controller in x19, and checks the level in w0 against what each mode
// must give (fixed, cap, a palette shorter than the level, no palette). Any machine: make -C dm/host/tests drs_level
#include "../drs_level.h"
#include <stdio.h>

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

// Ocarina of Time v131072's row: site main+0x805f8c (after bl main+0x8065e0), controller x19, palette size at +0x1a0, padding 0x8065c4 (7 words) and 0x806874 (3 words)
static const uint64_t AREA[2] = {0x8065c4, 0x806874};
static const unsigned LEN[2] = {7, 3};
#define SITE 0x805f8c

static uint32_t at(const drs_write *w, int n, uint64_t off) { for (int i = 0; i < n; i++) if (w[i].off == off) return w[i].word; return 0xDEADBEEF; }

// What the written code must give: the selector's answer w0, the palette size cnt -> the level the controller takes (the specification the interpreter is checked against)
static int want(int mode, unsigned n, int w0, int cnt) {
    if (mode == DRS_FIXED) w0 = (int)n;
    else if (w0 < (int)n) w0 = (int)n;
    int last = cnt - 1;
    return w0 <= last ? w0 : last;
}

// A small AArch64 interpreter for the emitted words: x19 = CTL, the 32-bit word at CTL + count_off = the palette size, w0 = the selector's answer at the site.
// Returns 1 and the final w0 when the code reaches site + 4, 0 on an instruction it does not know, a fetch outside the written words, a load elsewhere or a loop
#define CTL 0x10000000ull
static int cond_holds(unsigned c, int N, int Z, int C, int V) {
    int r;
    switch (c >> 1) {
    case 0: r = Z; break;                  // EQ / NE
    case 1: r = C; break;                  // CS / CC
    case 2: r = N; break;                  // MI / PL
    case 3: r = V; break;                  // VS / VC
    case 4: r = C && !Z; break;            // HI / LS
    case 5: r = N == V; break;             // GE / LT
    case 6: r = N == V && !Z; break;       // GT / LE
    default: return 1;                     // AL
    }
    return (c & 1) && c != 15 ? !r : r;
}
static int run(const drs_write *w, int nw, uint64_t site, unsigned count_off, uint32_t count, int32_t sel, int32_t *out) {
    uint64_t x[32] = {0}; int N = 0, Z = 0, C = 0, V = 0;
    x[0] = (uint32_t)sel; x[19] = CTL;
    uint64_t pc = site;
    for (int steps = 0; steps < 64; steps++) {
        if (pc == site + 4) { *out = (int32_t)(uint32_t)x[0]; return 1; }
        uint32_t i = at(w, nw, pc);
        if (i == 0xDEADBEEF) { printf("  fetch outside the written words: %#llx\n", (unsigned long long)pc); return 0; }
        unsigned rd = i & 31, rn = i >> 5 & 31, rm = i >> 16 & 31;
        #define WREG(r) ((r) == 31 ? 0u : (uint32_t)x[r])
        if ((i & 0xFF800000u) == 0x52800000u) {                                   // movz wd,#imm16,lsl #(16*hw)
            if (rd != 31) x[rd] = (uint32_t)((i >> 5 & 0xFFFF) << (16 * (i >> 21 & 3)));
        } else if ((i & 0xFF200000u) == 0x6B000000u) {                            // subs wd,wn,wm (cmp when wd = wzr), no shift
            if ((i >> 10 & 63) || (i >> 22 & 3)) { printf("  shifted subs %08x\n", i); return 0; }
            uint32_t a = WREG(rn), b = WREG(rm), r = a - b;
            N = r >> 31; Z = r == 0; C = a >= b; V = ((a ^ b) & (a ^ r)) >> 31;
            if (rd != 31) x[rd] = r;
        } else if ((i & 0xFFE00C00u) == 0x1A800000u) {                            // csel wd,wn,wm,cond
            uint32_t r = cond_holds(i >> 12 & 15, N, Z, C, V) ? WREG(rn) : WREG(rm);
            if (rd != 31) x[rd] = r;
        } else if ((i & 0xFFC00000u) == 0xB9400000u) {                            // ldr wt,[xn,#imm12*4]
            if (rn == 31) { printf("  ldr from sp\n"); return 0; }
            uint64_t addr = x[rn] + 4ull * (i >> 10 & 0xFFF);
            if (addr != CTL + count_off) { printf("  load from %#llx, not the palette size\n", (unsigned long long)addr); return 0; }
            if (rd != 31) x[rd] = count;
        } else if ((i & 0xFF800000u) == 0x51000000u) {                            // sub wd,wn,#imm12{,lsl #12}
            if (rn == 31 || rd == 31) { printf("  sub with wsp\n"); return 0; }
            x[rd] = (uint32_t)((uint32_t)x[rn] - ((i >> 10 & 0xFFF) << (i >> 22 & 1 ? 12 : 0)));
        } else if ((i & 0xFC000000u) == 0x14000000u) {                            // b imm26
            int64_t imm = i & 0x3FFFFFF; if (imm & 0x2000000) imm -= 0x4000000;
            pc = (uint64_t)((int64_t)pc + imm * 4); continue;
        } else { printf("  unknown instruction %08x at %#llx\n", i, (unsigned long long)pc); return 0; }
        #undef WREG
        pc += 4;
    }
    printf("  no way back to the site after 64 steps\n");
    return 0;
}

int main(void) {
    int mode; unsigned n; char why[128];
    CHECK(drs_level_parse(NULL, &mode, &n, why, sizeof why) && mode == DRS_DYNAMIC, "unset: dynamic");
    CHECK(drs_level_parse("", &mode, &n, why, sizeof why) && mode == DRS_DYNAMIC, "empty: dynamic");
    CHECK(drs_level_parse("dynamic", &mode, &n, why, sizeof why) && mode == DRS_DYNAMIC, "dynamic");
    CHECK(drs_level_parse("fixed:1", &mode, &n, why, sizeof why) && mode == DRS_FIXED && n == 1, "fixed:1");
    CHECK(drs_level_parse("fixed:lowest", &mode, &n, why, sizeof why) && mode == DRS_FIXED && n == DRS_LAST, "fixed:lowest");
    CHECK(drs_level_parse("cap:4", &mode, &n, why, sizeof why) && mode == DRS_CAP && n == 4, "cap:4");
    CHECK(drs_level_parse("cap:65535", &mode, &n, why, sizeof why) && n == 65535, "cap:65535");
    const char *bad[] = {"fixed", "fixed:", "fixed:-1", "fixed:1x", "cap:lowest", "cap:65536", "max", "fixed: 2", "Fixed:1"};
    for (unsigned i = 0; i < sizeof bad / sizeof *bad; i++) {
        why[0] = 0;
        CHECK(!drs_level_parse(bad[i], &mode, &n, why, sizeof why) && mode == DRS_DYNAMIC && why[0], "refused: %s", bad[i]);
    }

    drs_write w[16];
    CHECK(!drs_level_layout(DRS_DYNAMIC, 0, 19, 0x1a0, SITE, AREA, LEN, 2, w), "dynamic writes nothing");

    // fixed:4 fits the first area: movz w0,#4; ldr w9,[x19,#0x1a0]; sub w9,w9,#1; cmp w0,w9; csel w0,w0,w9,le; b 0x805f90; and b 0x8065c4 at the site
    int nw = drs_level_layout(DRS_FIXED, 4, 19, 0x1a0, SITE, AREA, LEN, 2, w);
    CHECK(nw == 7, "fixed: 7 writes, got %d", nw);
    const uint32_t fx[6] = {0x52800080, 0xB941A269, 0x51000529, 0x6B09001F, 0x1A89D000, 0x17FFFE6E};
    for (int i = 0; i < 6; i++) CHECK(at(w, nw, 0x8065c4 + 4 * i) == fx[i], "fixed word %d: %08x, want %08x", i, at(w, nw, 0x8065c4 + 4 * i), fx[i]);
    CHECK(at(w, nw, SITE) == 0x1400018E, "fixed: site b 0x8065c4: %08x", at(w, nw, SITE));
    CHECK(at(w, nw, 0x8065dc) == 0xDEADBEEF && at(w, nw, 0x806874) == 0xDEADBEEF, "fixed: the rest of the padding untouched");

    // cap:1 spills into the second area: movz w8,#1; cmp w0,w8; csel w0,w0,w8,ge; ldr; sub; cmp w0,w9; b 0x806874 | csel w0,w0,w9,le; b 0x805f90
    nw = drs_level_layout(DRS_CAP, 1, 19, 0x1a0, SITE, AREA, LEN, 2, w);
    CHECK(nw == 10, "cap: 10 writes, got %d", nw);
    const uint32_t c1[7] = {0x52800028, 0x6B08001F, 0x1A88A000, 0xB941A269, 0x51000529, 0x6B09001F, 0x140000A6};
    for (int i = 0; i < 7; i++) CHECK(at(w, nw, 0x8065c4 + 4 * i) == c1[i], "cap word %d: %08x, want %08x", i, at(w, nw, 0x8065c4 + 4 * i), c1[i]);
    CHECK(at(w, nw, 0x806874) == 0x1A89D000 && at(w, nw, 0x806878) == 0x17FFFDC6 && at(w, nw, 0x80687c) == 0xDEADBEEF, "cap: second area %08x %08x",
          at(w, nw, 0x806874), at(w, nw, 0x806878));
    CHECK(at(w, nw, SITE) == 0x1400018E, "cap: site");

    // lowest: movz w0,#0xffff, then the clamp makes it the palette's last level
    nw = drs_level_layout(DRS_FIXED, DRS_LAST, 19, 0x1a0, SITE, AREA, LEN, 2, w);
    CHECK(nw == 7 && at(w, nw, 0x8065c4) == 0x529FFFE0, "lowest: movz w0,#0xffff: %08x", at(w, nw, 0x8065c4));

    // Too little padding: the cap's 8 words do not fit 7 words alone, nor 2 + 2 + 2
    const unsigned small[1] = {7};
    CHECK(!drs_level_layout(DRS_CAP, 1, 19, 0x1a0, SITE, AREA, small, 1, w), "cap in 7 words: refused");
    const uint64_t a3[3] = {0x1000, 0x2000, 0x3000}; const unsigned l3[3] = {2, 2, 2};
    CHECK(!drs_level_layout(DRS_CAP, 1, 19, 0x1a0, SITE, a3, l3, 3, w), "cap in 2+2+2 words: refused");
    CHECK(!drs_level_layout(DRS_FIXED, 1, 19, 0x1a2, SITE, AREA, LEN, 2, w), "unaligned palette size offset: refused");

    // The specification, by hand: 10 levels (v131072), the selector answering 0 (sharpest), 1, 9
    CHECK(want(DRS_FIXED, 4, 0, 10) == 4 && want(DRS_FIXED, 4, 9, 10) == 4, "fixed:4 holds 4");
    CHECK(want(DRS_FIXED, DRS_LAST, 0, 10) == 9, "lowest is 9 of 10");
    CHECK(want(DRS_CAP, 1, 0, 10) == 1 && want(DRS_CAP, 1, 5, 10) == 5, "cap:1 lifts 0 to 1, keeps 5");
    CHECK(want(DRS_FIXED, 6, 0, 4) == 3 && want(DRS_CAP, 6, 0, 4) == 3, "a palette of 4: level 6 is its last, 3");
    CHECK(want(DRS_FIXED, 1, -1, 0) == -1 && want(DRS_CAP, 1, -1, 0) == -1, "no palette: -1, as the selector answers then");

    // The emitted words, run: every mode the launcher or a user can set, every selector answer from -1 to 11, palettes of 0, 1, 4 and 10 levels
    const struct { int mode; unsigned n; } modes[] = {{DRS_FIXED, 0}, {DRS_FIXED, 1}, {DRS_FIXED, 4}, {DRS_FIXED, 6}, {DRS_FIXED, 12}, {DRS_FIXED, DRS_LAST},
                                                      {DRS_CAP, 0}, {DRS_CAP, 1}, {DRS_CAP, 4}, {DRS_CAP, 9}, {DRS_CAP, 12}, {DRS_CAP, 0xFFFF}};
    const int counts[] = {0, 1, 4, 10};
    int runs = 0;
    for (unsigned m = 0; m < sizeof modes / sizeof *modes; m++) {
        nw = drs_level_layout(modes[m].mode, modes[m].n, 19, 0x1a0, SITE, AREA, LEN, 2, w);
        CHECK(nw > 0, "layout of mode %d level %u", modes[m].mode, modes[m].n);
        if (nw <= 0) continue;
        for (unsigned c = 0; c < sizeof counts / sizeof *counts; c++)
            for (int sel = -1; sel <= 11; sel++) {
                int32_t got = 0x7EADBEEF;
                int ok = run(w, nw, SITE, 0x1a0, (uint32_t)counts[c], sel, &got);
                int exp = want(modes[m].mode, modes[m].n, sel, counts[c]);
                CHECK(ok && got == exp, "%s:%u, palette %d, selector %d: w0 = %d, want %d", modes[m].mode == DRS_FIXED ? "fixed" : "cap", modes[m].n, counts[c], sel,
                      got, exp);
                runs++;
            }
    }
    CHECK(runs == 12 * 4 * 13, "all runs made: %d", runs);

    // Another controller register and palette-size offset: the load must follow them
    nw = drs_level_layout(DRS_CAP, 2, 19, 0x40, SITE, AREA, LEN, 2, w);
    int32_t got = 0;
    CHECK(nw > 0 && run(w, nw, SITE, 0x40, 3, 0, &got) && got == 2, "cap:2 with the size at +0x40: %d", got);

    // The BL before the site: its target (Ocarina's bl main+0x8065e0 at main+0x805f88, a backward one, and a word that is not a BL)
    CHECK(drs_bl_target(0x805f88, 0x94000196) == 0x8065e0, "bl forward: %#llx", (unsigned long long)drs_bl_target(0x805f88, 0x94000196));
    CHECK(drs_bl_target(0x1000, 0x97FFFFFF) == 0xFFC, "bl backward");
    CHECK(drs_bl_target(0x1000, 0x14000001) == ~0ull && drs_bl_target(0x1000, 0x76000036) == ~0ull, "not a bl");

    // Rows the layout refuses rather than encode wrongly: a branch out of B's reach (+-128 MiB), areas that overlap, an area over the site or the BL before it
    const uint64_t far[2] = {SITE + 0x8000000, 0x806874}; const unsigned flen[2] = {7, 3};
    CHECK(!drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, far, flen, 2, w), "site to an area 128 MiB on: refused");
    const uint64_t near_far[2] = {0x8065c4, 0x8065c4 + 0x8000000}; const unsigned nflen[2] = {7, 3};
    CHECK(!drs_level_layout(DRS_CAP, 1, 19, 0x1a0, SITE, near_far, nflen, 2, w), "area to area 128 MiB on: refused");
    CHECK(drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, near_far, nflen, 2, w) == 7, "the same row with the code inside the first area: written");
    const uint64_t back[1] = {SITE - 0x8000000 - 4}; const unsigned blen[1] = {7};
    CHECK(!drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, back, blen, 1, w), "an area 128 MiB + 4 back: refused");
    const uint64_t edge[1] = {SITE - 0x8000000}; const unsigned elen[1] = {7};
    nw = drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, edge, elen, 1, w);
    CHECK(nw == 7 && at(w, nw, SITE) == 0x16000000, "an area exactly 128 MiB back: b -128 MiB: %08x", at(w, nw, SITE));
    const uint64_t ov[2] = {0x8065c4, 0x8065d0}; const unsigned olen[2] = {7, 3};
    CHECK(!drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, ov, olen, 2, w), "overlapping areas: refused");
    const uint64_t ov2[2] = {0x806874, 0x80686c}; const unsigned olen2[2] = {3, 3};
    CHECK(!drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, ov2, olen2, 2, w), "overlapping areas, the later one first: refused");
    const uint64_t on_site[1] = {SITE - 8}; const unsigned slen[1] = {8};
    CHECK(!drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, on_site, slen, 1, w), "an area over the BL and the site: refused");
    const uint64_t on_bl[1] = {SITE - 12}; const unsigned bllen[1] = {3};
    CHECK(!drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, on_bl, bllen, 1, w), "an area ending on the BL: refused");
    const uint64_t after[1] = {SITE + 4}; const unsigned alen[1] = {7};
    CHECK(drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, after, alen, 1, w) == 7, "an area right after the site: written");
    const uint64_t odd[1] = {0x8065c6};
    CHECK(!drs_level_layout(DRS_FIXED, 1, 19, 0x1a0, SITE, odd, LEN, 1, w), "an unaligned area: refused");

    printf(g_fail ? "%d FAILED\n" : "drs_level: all passed\n", g_fail);
    return g_fail != 0;
}
