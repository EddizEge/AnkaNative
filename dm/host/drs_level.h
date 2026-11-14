// A game's own dynamic-resolution level held or capped by the host (DM_RES_LEVEL; profile key drs_level; loader.c drs_level_apply). Header only, so that
// dm/host/tests/t_drs_level.c checks the parser and the code words it writes on any machine.
//
// What it does to the game. agl's dynamic-resolution controller (Ocarina of Time v131072: the per-frame update main+0x805aa0) keeps a palette of levels, index 0 the
// sharpest; once a frame it calls a selector (main+0x8065e0) that predicts each level's GPU time from its history and returns the index of the sharpest level that fits
// the frame budget. The controller then moves to that level, upward after a few frames of delay, downward at once only in some states (its down counter, ctl+0x180).
// The profile row names the instruction right after that call (the site): the host puts a branch there to a few instructions it writes into padding of the game's text
// (zero words between functions, checked), which change the selector's answer in w0 and come back after the site:
//   fixed:N   w0 = N                      the level stays N (the controller still lowers nothing and raises nothing)
//   cap:N     w0 = max(w0, N)             never sharper than level N; the game still lowers it under load
// then w0 = min(w0, palette size - 1) (the palette size is read from the controller, ctl+<count offset>), so a level past the palette is the palette's last, never the
// "no level" path that draws at full scale. The site's own instruction (a test whether a downward step is immediate) is dropped: with a level set, a step down is always
// immediate, as the game does itself in those states (the code after the site zeroes the down counter). DM_RES_LEVEL unset, empty or "dynamic" writes nothing.
// The scratch registers (w8, w9) are caller-saved, and the site must follow a BL to the selector the row names (checked), so nothing the caller keeps lives in them there.
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { DRS_DYNAMIC = 0, DRS_FIXED = 1, DRS_CAP = 2 };
#define DRS_LAST 0xFFFFu   // "lowest": a level past any palette, which the clamp turns into the palette's last

// DM_RES_LEVEL's value: NULL, "" or "dynamic" -> DRS_DYNAMIC; "fixed:<n>", "fixed:lowest", "cap:<n>" with n = 0..65535 (0 = the palette's sharpest level).
// Returns 0 and fills why on anything else
static inline int drs_level_parse(const char *s, int *mode, unsigned *n, char *why, size_t whyn) {
    *mode = DRS_DYNAMIC; *n = 0;
    if (!s || !*s || !strcmp(s, "dynamic")) return 1;
    const char *v; int m;
    if (!strncmp(s, "fixed:", 6)) m = DRS_FIXED, v = s + 6;
    else if (!strncmp(s, "cap:", 4)) m = DRS_CAP, v = s + 4;
    else { snprintf(why, whyn, "must be dynamic, fixed:<level>, fixed:lowest or cap:<level>"); return 0; }
    if (m == DRS_FIXED && !strcmp(v, "lowest")) { *mode = m; *n = DRS_LAST; return 1; }
    if (*v < '0' || *v > '9') { snprintf(why, whyn, "the level must be a number from 0 (the sharpest)%s", m == DRS_FIXED ? " or lowest" : ""); return 0; }
    char *end; unsigned long x = strtoul(v, &end, 10);
    if (*end || x > DRS_LAST) { snprintf(why, whyn, "the level must be a number from 0 to %u", DRS_LAST); return 0; }
    *mode = m; *n = (unsigned)x; return 1;
}

// The stub's instructions in order (before placement): returns their count (at most 8). ctl = the controller's register number, count_off = the palette size's offset in it
static inline int drs_level_body(int mode, unsigned n, unsigned ctl, unsigned count_off, uint32_t out[8]) {
    int k = 0;
    if (mode == DRS_FIXED) out[k++] = 0x52800000u | (n & 0xFFFF) << 5 | 0;                  // movz w0,#n
    else {
        out[k++] = 0x52800000u | (n & 0xFFFF) << 5 | 8;                                    // movz w8,#n
        out[k++] = 0x6B08001Fu;                                                            // cmp  w0,w8
        out[k++] = 0x1A88A000u;                                                            // csel w0,w0,w8,ge   (max)
    }
    out[k++] = 0xB9400000u | (count_off / 4) << 10 | (ctl & 31) << 5 | 9;                  // ldr  w9,[x<ctl>,#count_off]
    out[k++] = 0x51000529u;                                                                // sub  w9,w9,#1      (the last level)
    out[k++] = 0x6B09001Fu;                                                                // cmp  w0,w9
    out[k++] = 0x1A89D000u;                                                                // csel w0,w0,w9,le   (min)
    return k;
}

// B's reach: a word offset in 26 bits, so -128 MiB .. +128 MiB - 4 from the branch, both ends word-aligned
static inline int drs_b_reach(uint64_t from, uint64_t to) {
    int64_t d = (int64_t)to - (int64_t)from;
    return !(from % 4) && !(to % 4) && d >= -(1ll << 27) && d < (1ll << 27);
}
static inline uint32_t drs_b(uint64_t from, uint64_t to) { return 0x14000000u | (uint32_t)(((int64_t)to - (int64_t)from) / 4 & 0x3FFFFFF); }

// The target of the BL word at `at` (bl imm26: the same reach as B), or ~0 when the word is not a BL. The host checks that the call before the site goes to the
// level selector the profile row names
static inline uint64_t drs_bl_target(uint64_t at, uint32_t word) {
    if ((word & 0xFC000000u) != 0x94000000u) return ~0ull;
    int64_t imm = (int64_t)(word & 0x3FFFFFFu);
    if (imm & 0x2000000) imm -= 0x4000000;
    return (uint64_t)((int64_t)at + imm * 4);
}

typedef struct { uint64_t off; uint32_t word; } drs_write;

// Lays the stub out over the padding areas (offsets and word counts, in order): the body, a B to the next area where an area runs out, a B back to site + 4 at the end;
// then the B from the site to the first area. Returns the number of writes into w (at most 16), 0 when the areas are too small, when an area is not word-aligned, overlaps
// another or covers the site or the BL before it, or when a branch would not reach (B's +-128 MiB), so a row is never written half right. count_off must be a multiple
// of 4 below 16 KB
static inline int drs_level_layout(int mode, unsigned n, unsigned ctl, unsigned count_off, uint64_t site, const uint64_t *area, const unsigned *len, int nareas, drs_write w[16]) {
    if (mode == DRS_DYNAMIC || count_off % 4 || count_off >= 0x4000 || ctl > 30 || nareas < 1 || nareas > 4 || site < 4 || site % 4) return 0;
    for (int a = 0; a < nareas; a++) {
        if (area[a] % 4 || !len[a]) return 0;
        uint64_t lo = area[a], hi = area[a] + 4ull * len[a];
        if (lo < site + 4 && site - 4 < hi) return 0;                                         // covers the BL or the site
        for (int b = 0; b < a; b++) if (lo < area[b] + 4ull * len[b] && area[b] < hi) return 0;   // overlaps an earlier area
    }
    uint32_t body[8]; int nb = drs_level_body(mode, n, ctl, count_off, body), nw = 0, i = 0, done = 0;
    for (int a = 0; a < nareas && !done; a++) {
        uint64_t at = area[a];
        for (unsigned left = len[a]; left; left--, at += 4) {
            if (i == nb) {                                                                     // all placed: back after the site
                if (!drs_b_reach(at, site + 4)) return 0;
                w[nw++] = (drs_write){at, drs_b(at, site + 4)}; done = 1; break;
            }
            if (left == 1) {                                                                   // one slot left: it takes the branch on
                if (a == nareas - 1 || !drs_b_reach(at, area[a + 1])) return 0;
                w[nw++] = (drs_write){at, drs_b(at, area[a + 1])}; break;
            }
            w[nw++] = (drs_write){at, body[i++]};
        }
    }
    if (!done || !drs_b_reach(site, area[0])) return 0;
    w[nw++] = (drs_write){site, drs_b(site, area[0])};
    return nw;
}
