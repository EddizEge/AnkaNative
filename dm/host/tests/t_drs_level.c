// The dynamic-resolution level switch (DM_RES_LEVEL, dm/host/drs_level.h, loader.c drs_level_apply): the parser, and the code words laid out for Ocarina of Time
// v131072's drs_level row (dm/games/0400486024DEC000/game.tsv), checked word for word against encodings from llvm-mc (aarch64) and branch targets worked out by hand.
// Also runs the laid-out code's arithmetic on a model of the three cases (fixed, cap, a palette shorter than the level). Any machine: make -C dm/host/tests drs_level
#include "../drs_level.h"
#include <stdio.h>

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

// Ocarina of Time v131072's row: site main+0x805f8c (after bl main+0x8065e0), controller x19, palette size at +0x1a0, padding 0x8065c4 (7 words) and 0x806874 (3 words)
static const uint64_t AREA[2] = {0x8065c4, 0x806874};
static const unsigned LEN[2] = {7, 3};
#define SITE 0x805f8c

static uint32_t at(const drs_write *w, int n, uint64_t off) { for (int i = 0; i < n; i++) if (w[i].off == off) return w[i].word; return 0xDEADBEEF; }

// What the written code computes: the selector's answer w0, the palette size cnt -> the level the controller takes
static int model(int mode, unsigned n, int w0, int cnt) {
    if (mode == DRS_FIXED) w0 = (int)n;
    else if (w0 < (int)n) w0 = (int)n;
    int last = cnt - 1;
    return w0 <= last ? w0 : last;
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

    // The arithmetic: 10 levels (v131072), the selector answering 0 (sharpest), 1, 9
    CHECK(model(DRS_FIXED, 4, 0, 10) == 4 && model(DRS_FIXED, 4, 9, 10) == 4, "fixed:4 holds 4");
    CHECK(model(DRS_FIXED, DRS_LAST, 0, 10) == 9, "lowest is 9 of 10");
    CHECK(model(DRS_CAP, 1, 0, 10) == 1 && model(DRS_CAP, 1, 5, 10) == 5, "cap:1 lifts 0 to 1, keeps 5");
    CHECK(model(DRS_FIXED, 6, 0, 4) == 3 && model(DRS_CAP, 6, 0, 4) == 3, "a palette of 4: level 6 is its last, 3");
    CHECK(model(DRS_FIXED, 1, -1, 0) == -1 && model(DRS_CAP, 1, -1, 0) == -1, "no palette: -1, as the selector answers then");

    printf(g_fail ? "%d FAILED\n" : "drs_level: all passed\n", g_fail);
    return g_fail != 0;
}
