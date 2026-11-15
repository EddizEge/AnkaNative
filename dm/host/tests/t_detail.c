// Game settings › Plants and effects (dm/host/detail.h): the rows' parser, the in-place BYML editor, the SARC reader, the zstd frame of raw blocks and the whole
// overlay build over a RomFS folder, with hand-made files built here (a small BYML in both byte orders, a small SARC wrapped in a zstd frame). Expected values are
// written by hand from the formats, not computed by the code under test. zstd's own decoder (dm/third_party/zstd/zstddeclib.c, the host's) decodes every frame
// the builder writes. Any machine: make -C dm/host/tests GAME=<title ID> detail. Also every profile's detail rows (this GAME's) must read without a red line.
//
// As a tool (not in `make detail`): t_detail build <RomFS folder> <temp folder> <DM_DETAIL value> [both|loose|pack] [keep|none] [report file]
// builds the overlay as the host does at start, with the profile's rows (or the rows of the file DETAIL_ROWS names), and prints the log lines; the report lists
// each edited file and its old -> new values. dm/tools/tests/test_detail_overlay.py runs it over a pack compressed with a real dictionary; the PC check of
// session-2026-11-14 ran it over the player's files.
#include "../detail.h"
#include "game_profile.h"
#include <sys/wait.h>

typedef struct ZSTD_DCtx_s ZSTD_DCtx;
ZSTD_DCtx *ZSTD_createDCtx(void);
size_t ZSTD_freeDCtx(ZSTD_DCtx *dctx);
size_t ZSTD_decompress(void *dst, size_t cap, const void *src, size_t n);
size_t ZSTD_decompress_usingDict(ZSTD_DCtx *dctx, void *dst, size_t cap, const void *src, size_t n, const void *dict, size_t dn);
unsigned ZSTD_isError(size_t code);
const char *ZSTD_getErrorName(size_t code);
unsigned ZSTD_getDictID_fromFrame(const void *src, size_t n);

static int g_fail;
#define CHECK_OK(c, ...) ((c) ? 1 : (g_fail++, printf("FAIL %s:%d: ", __FILE__, __LINE__), printf(__VA_ARGS__), printf("\n"), 0))
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

// ---- The log, collected ----
static char g_log[64][8300]; static int g_nlog, g_echo;
static void logf_(void *c, const char *l) { (void)c; if (g_echo) puts(l); if (g_nlog < 64) snprintf(g_log[g_nlog++], sizeof g_log[0], "%s", l); }
static int log_has(const char *needle) { for (int i = 0; i < g_nlog; i++) if (strstr(g_log[i], needle)) return 1; return 0; }
static int log_red(void) { int n = 0; for (int i = 0; i < g_nlog; i++) if (!strncmp(g_log[i], "[RED] ", 6)) n++; return n; }

// ---- A small BYML, version 7, either byte order ----
// keys (sorted): Arr Dist_LowTree Dist_Undergrowth0 IsEnableMoss LodSetting_hh Name Range_LowTree01 TurfDensity TurfMinBlades
// root: { Arr: [1.0f, 2.0f], IsEnableMoss: true, LodSetting_hh: { Dist_LowTree: 41.0f, Dist_Undergrowth0: 33.0f, Range_LowTree01: 7.0f }, Name: "scene_a",
//         TurfDensity: 123.0f, TurfMinBlades: 11 }   (made-up values, none of a game file)   (with_turf 0: no TurfDensity / TurfMinBlades)
static const char *KEYS[] = {"Arr", "Dist_LowTree", "Dist_Undergrowth0", "IsEnableMoss", "LodSetting_hh", "Name", "Range_LowTree01", "TurfDensity", "TurfMinBlades"};
typedef struct { uint8_t b[1024]; size_t n; int be; } buf;
static void put(buf *x, uint64_t v, int w) { for (int i = 0; i < w; i++) x->b[x->n + i] = (uint8_t)(v >> (8 * (x->be ? w - 1 - i : i))); x->n += (size_t)w; }
static void put_at(buf *x, size_t o, uint64_t v, int w) { size_t k = x->n; x->n = o; put(x, v, w); x->n = k; }
static void align4(buf *x) { while (x->n & 3) x->b[x->n++] = 0; }
static uint32_t f32(float f) { uint32_t u; memcpy(&u, &f, 4); return u; }
static void strtab(buf *x, const char **s, int n) {
    size_t o = x->n; put(x, 0xC2, 1); put(x, (uint64_t)n, 3); size_t offs = x->n; x->n += 4 * (size_t)(n + 1);
    for (int i = 0; i < n; i++) { put_at(x, offs + 4 * (size_t)i, x->n - o, 4); size_t l = strlen(s[i]) + 1; memcpy(x->b + x->n, s[i], l); x->n += l; }
    put_at(x, offs + 4 * (size_t)n, x->n - o, 4); align4(x);
}
static void ent(buf *x, int key, int type, uint32_t v) { put(x, (uint64_t)key, 3); put(x, (uint64_t)type, 1); put(x, v, 4); }
static size_t make_byml(uint8_t *out, int be, int with_turf, size_t *slot_density) {
    buf x = {{0}, 0, be};
    x.b[0] = be ? 'B' : 'Y'; x.b[1] = be ? 'Y' : 'B'; x.n = 2; put(&x, 7, 2); x.n = 16;
    size_t keys = x.n; strtab(&x, KEYS, 9);
    const char *strs[] = {"scene_a"}; size_t st = x.n; strtab(&x, strs, 1);
    size_t arr = x.n; put(&x, 0xC0, 1); put(&x, 2, 3); x.b[x.n++] = 0xD2; x.b[x.n++] = 0xD2; align4(&x); put(&x, f32(1.0f), 4); put(&x, f32(2.0f), 4);
    size_t lod = x.n; put(&x, 0xC1, 1); put(&x, 3, 3); ent(&x, 1, 0xD2, f32(41.0f)); ent(&x, 2, 0xD2, f32(33.0f)); ent(&x, 6, 0xD2, f32(7.0f));
    size_t root = x.n; put(&x, 0xC1, 1); put(&x, with_turf ? 6 : 4, 3);
    ent(&x, 0, 0xC0, (uint32_t)arr); ent(&x, 3, 0xD0, 1); ent(&x, 4, 0xC1, (uint32_t)lod); ent(&x, 5, 0xA0, 0);
    if (with_turf) { if (slot_density) *slot_density = x.n + 4; ent(&x, 7, 0xD2, f32(123.0f)); ent(&x, 8, 0xD1, 11); }
    put_at(&x, 4, keys, 4); put_at(&x, 8, st, 4); put_at(&x, 12, root, 4);
    memcpy(out, x.b, x.n); return x.n;
}
static float rdf(const uint8_t *b, size_t o, int be) { uint32_t u = 0; for (int i = 0; i < 4; i++) u |= (uint32_t)b[o + i] << (8 * (be ? 3 - i : i)); float f; memcpy(&f, &u, 4); return f; }
static int32_t rdi(const uint8_t *b, size_t o, int be) { uint32_t u = 0; for (int i = 0; i < 4; i++) u |= (uint32_t)b[o + i] << (8 * (be ? 3 - i : i)); return (int32_t)u; }
// The slots of the fixture, found by hand from make_byml's layout: root entries start after the root header
typedef struct { size_t arr0, arr1, moss, lod_low, lod_ug0, lod_range, density, blades; } slots;
static slots find_slots(const uint8_t *b, int be) {
    slots s; uint32_t root = (uint32_t)rdi(b, 12, be), arr = (uint32_t)rdi(b, root + 4 + 4, be), lod = (uint32_t)rdi(b, root + 4 + 16 + 4, be);
    s.arr0 = arr + 8; s.arr1 = arr + 12; s.moss = root + 4 + 8 + 4; s.lod_low = lod + 4 + 4; s.lod_ug0 = lod + 12 + 4; s.lod_range = lod + 20 + 4;
    s.density = root + 4 + 32 + 4; s.blades = root + 4 + 40 + 4; return s;
}
static detail_param row(const char *key, const char *edit) {
    detail_param q; memset(&q, 0, sizeof q); snprintf(q.key, sizeof q.key, "%s", key);
    if (!detail_edit_parse(edit, &q.kind, &q.num, &q.b)) { printf("bad edit %s\n", edit); exit(2); }
    return q;
}
// One row on its own (its own peer, a fresh set of edited values)
static int edit1(uint8_t *b, size_t n, const detail_param *q, unsigned *hits, char *why, size_t wn, char *chg, size_t cn) {
    const detail_param *peers[1] = {q}; detail_done done = {0}; unsigned applied = 0;
    int r = detail_byml_edit(b, n, q, peers, 1, &done, 0, hits, &applied, why, wn, chg, cn);
    if (r && applied != *hits) { printf("applied %u of %u\n", applied, *hits); r = 0; }
    free(done.v); return r;
}
static int ndiff(const uint8_t *a, const uint8_t *b, size_t n, size_t *first) { int d = 0; for (size_t i = 0; i < n; i++) if (a[i] != b[i]) { if (!d && first) *first = i; d++; } return d; }

static void test_byml(int be) {
    uint8_t orig[1024], b[1024]; size_t sd = 0, n = make_byml(orig, be, 1, &sd); slots s = find_slots(orig, be);
    CHECK(sd == s.density && rdf(orig, s.density, be) == 123.0f && rdi(orig, s.blades, be) == 11 && rdf(orig, s.lod_low, be) == 41.0f, "the fixture's slots (be %d)", be);
    char why[300], chg[1000]; unsigned hits; size_t first = 0;
    // a float scaled: only its 4 bytes change, the size is the file's
    memcpy(b, orig, n); chg[0] = 0; detail_param q = row("TurfDensity", "x0.5");
    CHECK(edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && hits == 1, "TurfDensity x0.5: %s", why);
    CHECK(rdf(b, s.density, be) == 61.5f && ndiff(orig, b, n, &first) >= 1 && ndiff(orig, b, n, NULL) <= 4 && first >= s.density && first < s.density + 4, "only TurfDensity's bytes, now 61.5 (be %d)", be);
    CHECK(strstr(chg, "TurfDensity 123 -> 61.5") != NULL, "the change noted: %s", chg);
    // an integer scaled and rounded
    memcpy(b, orig, n); chg[0] = 0; q = row("TurfMinBlades", "x0.5");
    CHECK(edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && hits == 1 && rdi(b, s.blades, be) == 6 && ndiff(orig, b, n, NULL) == 1, "TurfMinBlades 11 x0.5 = 5.5, rounded to 6");
    memcpy(b, orig, n); chg[0] = 0; q = row("TurfMinBlades", "x0.43");
    CHECK(edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && rdi(b, s.blades, be) == 5, "11 x0.43 = 4.73, rounded to 5");
    // a glob in a nested dictionary: the two distances, not the range
    memcpy(b, orig, n); chg[0] = 0; q = row("LodSetting_hh.Dist_*", "x0.5");
    CHECK(edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && hits == 2, "two Dist_ values: %u %s", hits, why);
    CHECK(rdf(b, s.lod_low, be) == 20.5f && rdf(b, s.lod_ug0, be) == 16.5f && rdf(b, s.lod_range, be) == 7.0f && ndiff(orig, b, n, NULL) <= 8, "41 -> 20.5, 33 -> 16.5, the range kept");
    // a boolean set false
    memcpy(b, orig, n); chg[0] = 0; q = row("IsEnableMoss", "=false");
    CHECK(edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && hits == 1 && rdi(b, s.moss, be) == 0 && ndiff(orig, b, n, NULL) == 1, "IsEnableMoss false");
    // an array element by index, and an absolute number
    memcpy(b, orig, n); chg[0] = 0; q = row("Arr.1", "x2");
    CHECK(edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && hits == 1 && rdf(b, s.arr1, be) == 4.0f && rdf(b, s.arr0, be) == 1.0f, "Arr.1 x2");
    memcpy(b, orig, n); chg[0] = 0; q = row("TurfMinBlades", "=3");
    CHECK(edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && rdi(b, s.blades, be) == 3, "=3");
    // a key the file does not have: no edit, no failure, the file unchanged
    memcpy(b, orig, n); chg[0] = 0; q = row("IsEnableStone", "=false");
    CHECK(edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && hits == 0 && !ndiff(orig, b, n, NULL), "absent key");
    q = row("LodSetting_hh.Nothing", "x0.5");
    CHECK(edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && hits == 0, "absent nested key");
    // edits that do not fit the value: refused with a reason
    const char *bad[][3] = {{"Name", "x0.5", "string"}, {"IsEnableMoss", "x0.5", "boolean"}, {"TurfDensity", "=false", "float"}, {"TurfMinBlades", "=1.5", "whole"},
                            {"LodSetting_hh", "x0.5", "dictionary"}, {"Arr", "=true", "array"}, {"TurfMinBlades", "x1e12", "range"}};
    for (size_t i = 0; i < sizeof bad / sizeof *bad; i++) {
        memcpy(b, orig, n); chg[0] = 0; why[0] = 0; q = row(bad[i][0], bad[i][1]);
        CHECK(!edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && strstr(why, bad[i][2]), "%s %s refused: %s", bad[i][0], bad[i][1], why);
    }
    // malformed files
    q = row("TurfDensity", "x0.5"); memcpy(b, orig, n);
    CHECK(!edit1(b, 10, &q, &hits, why, sizeof why, chg, sizeof chg), "cut to 10 bytes");
    CHECK(!edit1(b, n - 20, &q, &hits, why, sizeof why, chg, sizeof chg), "the root's entries cut off: %s", why);
    b[0] = 'X'; CHECK(!edit1(b, n, &q, &hits, why, sizeof why, chg, sizeof chg) && strstr(why, "not a BYML"), "not a BYML");
}

// A BYML whose two dictionaries are one node, as the game's writer stores equal containers once: { LodSetting: L, LodSetting_hh: L }, L = { Dist_LowTree: 41.0f }
static size_t make_shared(uint8_t *out, size_t *slot) {
    static const char *k[] = {"Dist_LowTree", "LodSetting", "LodSetting_hh"}; buf x = {{0}, 0, 0};
    x.b[0] = 'Y'; x.b[1] = 'B'; x.n = 2; put(&x, 7, 2); x.n = 16;
    size_t keys = x.n; strtab(&x, k, 3);
    size_t lod = x.n; put(&x, 0xC1, 1); put(&x, 1, 3); *slot = x.n + 4; ent(&x, 0, 0xD2, f32(41.0f));
    size_t root = x.n; put(&x, 0xC1, 1); put(&x, 2, 3); ent(&x, 1, 0xC1, (uint32_t)lod); ent(&x, 2, 0xC1, (uint32_t)lod);
    put_at(&x, 4, keys, 4); put_at(&x, 12, root, 4); memcpy(out, x.b, x.n); return x.n;
}
static void test_shared(void) {
    uint8_t o[512], b[512]; size_t slot, n = make_shared(o, &slot); char why[300], chg[1000]; unsigned found, applied;
    detail_param hh = row("LodSetting_hh.Dist_*", "x0.5"), pl = row("LodSetting.Dist_*", "x0.5"), pq = row("LodSetting.Dist_*", "x0.25");
    snprintf(hh.text, sizeof hh.text, "x LodSetting_hh.Dist_* x0.5"); snprintf(pl.text, sizeof pl.text, "x LodSetting.Dist_* x0.5"); snprintf(pq.text, sizeof pq.text, "x LodSetting.Dist_* x0.25");
    // one row alone would change the other dictionary too: refused, the file as it was
    memcpy(b, o, n); chg[0] = 0; const detail_param *one[] = {&hh}; detail_done d = {0};
    CHECK(!detail_byml_edit(b, n, &hh, one, 1, &d, 0, &found, &applied, why, sizeof why, chg, sizeof chg) && strstr(why, "stored once with LodSetting.Dist_LowTree") && !ndiff(o, b, n, NULL), "shared, one row: %s", why);
    // both rows with the same edit: the value is edited once (25, not 12.5); the second row finds it, edits nothing
    const detail_param *two[] = {&hh, &pl};
    CHECK(detail_byml_edit(b, n, &hh, two, 2, &d, 0, &found, &applied, why, sizeof why, chg, sizeof chg) && found == 1 && applied == 1 && rdf(b, slot, 0) == 20.5f, "first row: %s", why);
    CHECK(detail_byml_edit(b, n, &pl, two, 2, &d, 0, &found, &applied, why, sizeof why, chg, sizeof chg) && found == 1 && applied == 0 && rdf(b, slot, 0) == 20.5f, "second row: once");
    free(d.v); d = (detail_done){0};
    // different edits of one stored value: refused
    memcpy(b, o, n); const detail_param *mix[] = {&hh, &pq};
    CHECK(!detail_byml_edit(b, n, &hh, mix, 2, &d, 0, &found, &applied, why, sizeof why, chg, sizeof chg) && !ndiff(o, b, n, NULL), "different edits: %s", why);
    free(d.v);
}

// ---- A small SARC (little-endian), members in name order ----
static size_t make_sarc(uint8_t *out, const char **names, const uint8_t **data, const size_t *len, int n) {
    size_t names_size = 0; for (int i = 0; i < n; i++) names_size += (strlen(names[i]) + 4) & ~(size_t)3;
    size_t o = 0x20 + 16 * (size_t)n, sfnt = o; o += 8; size_t pool = o; o += names_size;
    size_t data_off = (o + 0x7F) & ~(size_t)0x7F; size_t p = data_off;
    memset(out, 0, data_off);
    memcpy(out, "SARC", 4); out[4] = 0x14; out[6] = 0xFF; out[7] = 0xFE; out[0x10] = 0; out[0x11] = 1;
    memcpy(out + 0x14, "SFAT", 4); out[0x18] = 0x0C; out[0x1A] = (uint8_t)n; out[0x1C] = 0x65;
    size_t nm = pool;
    for (int i = 0; i < n; i++) {
        size_t e = 0x20 + 16 * (size_t)i; uint32_t attr = 0x01000000u | (uint32_t)((nm - pool) / 4);
        memcpy(out + nm, names[i], strlen(names[i]) + 1); nm += (strlen(names[i]) + 4) & ~(size_t)3;
        p = (p + 7) & ~(size_t)7; memcpy(out + p, data[i], len[i]);
        uint32_t s = (uint32_t)(p - data_off), en = (uint32_t)(p - data_off + len[i]);
        for (int k = 0; k < 4; k++) { out[e + 4 + k] = (uint8_t)(attr >> 8 * k); out[e + 8 + k] = (uint8_t)(s >> 8 * k); out[e + 12 + k] = (uint8_t)(en >> 8 * k); }
        p += len[i];
    }
    memcpy(out + sfnt, "SFNT", 4); out[sfnt + 4] = 8;
    for (int k = 0; k < 4; k++) { out[8 + k] = (uint8_t)(p >> 8 * k); out[12 + k] = (uint8_t)(data_off >> 8 * k); }
    return p;
}
// A zstd frame header by hand: single segment, 4-byte content size, with (dictid != 0: a 1-byte ID) or without a dictionary ID
static size_t frame_head(uint8_t *h, size_t content, unsigned dictid) {
    size_t k = 0; h[k++] = 0x28; h[k++] = 0xB5; h[k++] = 0x2F; h[k++] = 0xFD; h[k++] = (uint8_t)(0xA0 | (dictid ? 1 : 0));
    if (dictid) h[k++] = (uint8_t)dictid;
    for (int i = 0; i < 4; i++) h[k++] = (uint8_t)(content >> 8 * i);
    return k;
}

static void test_zstd(void) {
    enum { N = 300000 }; uint8_t *data = malloc(N), *back = malloc(N), h[32]; char why[200];
    for (size_t i = 0; i < N; i++) data[i] = (uint8_t)(i * 7 + (i >> 9));
    for (int dict = 0; dict < 2; dict++) for (int mode = 0; mode < 2; mode++) {
        detail_zhead zh; frame_head(h, N, dict ? 4 : 0);
        CHECK(detail_zstd_head(h, sizeof h, &zh, why, sizeof why) && zh.fcs == N && zh.dictid == (dict ? 4u : 0u) && zh.single, "header parse");
        size_t wn = 0; uint8_t *w = detail_zstd_raw(&zh, data, N, mode, &wn);
        CHECK(w && wn == zh.hsize - (dict && mode ? 1 : 0) + 3 * 3 + N, "three raw blocks of 128 KiB at most: %zu", wn);
        unsigned id = ZSTD_getDictID_fromFrame(w, wn);
        CHECK(id == (dict && !mode ? 4u : 0u), "dictionary ID %u (source %d, mode %s)", id, dict ? 4 : 0, mode ? "none" : "keep");
        size_t r = ZSTD_decompress(back, N, w, wn);
        if (id) CHECK(ZSTD_isError(r), "a frame naming dictionary 4 is refused without it (as libzstd checks the ID)");
        else CHECK(!ZSTD_isError(r) && r == N && !memcmp(back, data, N), "zstd's decoder gives the content back: %s", ZSTD_isError(r) ? ZSTD_getErrorName(r) : "");
        free(w);
    }
    // a source frame with a window descriptor (not single segment): its layout is kept
    uint8_t hw[16] = {0x28, 0xB5, 0x2F, 0xFD, 0x80, 0x40 /* window 2^(10+8) = 256 KiB */, 0, 0, 0, 0}; for (int i = 0; i < 4; i++) hw[6 + i] = (uint8_t)(N >> 8 * i);
    detail_zhead zh; CHECK(detail_zstd_head(hw, sizeof hw, &zh, why, sizeof why) && !zh.single && zh.window == 256u << 10 && zh.fcs == N, "window descriptor");
    size_t wn = 0; uint8_t *w = detail_zstd_raw(&zh, data, N, 0, &wn);
    CHECK(w && w[4] == 0x80 && w[5] == 0x40, "the header's layout kept");
    size_t r = ZSTD_decompress(back, N, w, wn); CHECK(!ZSTD_isError(r) && r == N && !memcmp(back, data, N), "decoded");
    free(w);
    // content of another size than the header says: no frame (the size must not change)
    frame_head(h, N, 0); detail_zstd_head(h, sizeof h, &zh, why, sizeof why);
    CHECK(!detail_zstd_raw(&zh, data, N - 1, 0, &wn), "a size the header does not say is refused");
    // an empty content: one empty last block
    frame_head(h, 0, 0); detail_zstd_head(h, sizeof h, &zh, why, sizeof why); w = detail_zstd_raw(&zh, data, 0, 0, &wn);
    CHECK(w && ZSTD_decompress(back, 1, w, wn) == 0, "empty");
    free(w); free(data); free(back);
}

static void test_sarc(void) {
    uint8_t a[1024], b[1024], s[4096]; size_t na = make_byml(a, 0, 1, NULL), nb = make_byml(b, 0, 0, NULL);
    const char *names[] = {"Foliage/ResFoliageScene/A.bgyml", "Other/B.bgyml"}; const uint8_t *d[] = {a, b}; size_t l[] = {na, nb};
    size_t n = make_sarc(s, names, d, l, 2); detail_member *m = NULL; char why[200];
    int c = detail_sarc(s, n, &m, why, sizeof why);
    CHECK(c == 2 && !strcmp(m[0].name, names[0]) && m[0].end - m[0].start == na && !memcmp(s + m[0].start, a, na) && !strcmp(m[1].name, names[1]) && m[1].end - m[1].start == nb, "two members: %s", why);
    free(m);
    CHECK(detail_sarc(s, n - 1, &m, why, sizeof why) < 0, "a size the header does not say");
    s[0] = 'X'; CHECK(detail_sarc(s, n, &m, why, sizeof why) < 0, "not a SARC");
}

static const char *ROWS[] = {
    "detail_level\tfull\tshown\tFull (as the game draws it)", "detail_level\treduced\tshown\tReduced", "detail_level\tlowest\tshown\tLowest", "detail_level\ta1\thidden\tCandidate",
    "detail_zsdic\tPack/ZsDic.pack.zs",
    "detail_param\treduced,lowest\tFoliage/ResFoliageScene/*.bgyml\tTurfDensity\tx0.5",
    "detail_param\treduced,lowest\tpack:Pack/Foliage.pack.zs:Foliage/ResFoliageScene/*.bgyml\tTurfDensity\tx0.5",
    "detail_param\tlowest\tFoliage/ResFoliageScene/*.bgyml\tIsEnableMoss\t=false",
    "detail_env\ta1\tDM_OPMODE=0",
    // malformed or naming what does not exist: each a red line, left out
    "detail_level\treduced\tshown\tTwice", "detail_level\tBad-Id\tshown\tx", "detail_param\tnope\tA/b.bgyml\tK\tx0.5", "detail_param\tfull\tA/b.bgyml\tK\tx0.5",
    "detail_param\treduced\tA/*/b.bgyml\tK\tx0.5", "detail_param\treduced\tA/b.bgyml\tK\tx0", "detail_param\treduced\tA/b.bgyml\tK\ttimes2", "detail_param\treduced\t/abs\tK\tx0.5",
    "detail_param\treduced\tpack:Pack/*.zs:m\tK\tx0.5", "detail_param\treduced\tA/b.bgyml\tK..L\tx0.5", "detail_param\treduced\tA/b.bgyml\tK",
    "detail_env\treduced\tDM_ROMFS=/x", "detail_env\treduced\tDM_DETAIL=lowest", "detail_env\treduced\tOPMODE=0", "detail_zsdic\tPack/Other.zs",
    "res_choice\tfixed:0\tnot a detail row"};

static void test_parse(void) {
    detail_profile *p = calloc(1, sizeof *p); g_nlog = 0;
    int red = detail_parse(p, ROWS, (int)(sizeof ROWS / sizeof *ROWS), logf_, NULL);
    CHECK(red == 15 && log_red() == 15, "15 malformed rows, 15 red lines: %d %d", red, log_red());
    CHECK(p->nlv == 4 && p->npar == 3 && p->nenv == 1 && !strcmp(p->zsdic, "Pack/ZsDic.pack.zs"), "4 levels, 3 params, 1 env kept: %d %d %d", p->nlv, p->npar, p->nenv);
    CHECK(p->par[0].levels == 6 && !p->par[0].pack && p->par[1].pack && !strcmp(p->par[1].path, "Pack/Foliage.pack.zs") && !strcmp(p->par[1].member, "Foliage/ResFoliageScene/*.bgyml"), "rows read");
    CHECK(log_has("unknown level nope") && log_has("named twice") && log_has("the first (as the game draws it)") && log_has("DM_ROMFS=/x"), "the reasons are named");
    char why[300];
    CHECK(detail_choose(p, "full", why, sizeof why) == 0, "full alone: nothing");
    CHECK(detail_choose(p, "reduced", why, sizeof why) == 2 && detail_choose(p, "reduced+a1", why, sizeof why) == (2 | 8), "levels joined by +");
    CHECK(detail_choose(p, "medium", why, sizeof why) == -1 && strstr(why, "no level medium") && strstr(why, "full reduced lowest a1"), "unknown level: %s", why);
    // a profile with no rows: every value is unknown
    detail_profile *e = calloc(1, sizeof *e); detail_parse(e, NULL, 0, logf_, NULL);
    CHECK(detail_choose(e, "reduced", why, sizeof why) == -1, "no rows");
    free(e);
    // this game's own rows read without a red line
    static const char *const prof[] = DM_GAME_DETAIL; g_nlog = 0;
    CHECK(detail_parse(p, prof, DM_GAME_NDETAIL, logf_, NULL) == 0, "the profile's detail rows (%s): %d red lines: %s", DM_GAME_ID, g_nlog, g_nlog ? g_log[0] : "");
    free(p);
}

// ---- The build over a RomFS folder ----
typedef struct { const char *dir; } fs_ctx;
static uint8_t *fs_read(void *c, const char *path, size_t *n, char *why, size_t wn) {
    char p[1200]; snprintf(p, sizeof p, "%s/%s", ((fs_ctx *)c)->dir, path);
    FILE *f = fopen(p, "rb"); if (!f) { snprintf(why, wn, "cannot open %s", p); return NULL; }
    fseek(f, 0, SEEK_END); long l = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = malloc(l ? (size_t)l : 1); if (fread(b, 1, (size_t)l, f) != (size_t)l) { fclose(f); free(b); snprintf(why, wn, "short read"); return NULL; }
    fclose(f); *n = (size_t)l; return b;
}
static int fs_list(void *c, const char *dir, void (*cb)(void *, const char *), void *u) {
    char p[1200]; snprintf(p, sizeof p, "%s/%s", ((fs_ctx *)c)->dir, dir);
    DIR *d = opendir(p); if (!d) return 0; struct dirent *e;
    while ((e = readdir(d))) { char f[1500]; struct stat st; snprintf(f, sizeof f, "%s/%s", p, e->d_name); if (!stat(f, &st) && S_ISREG(st.st_mode)) cb(u, e->d_name); }
    closedir(d); return 1;
}
static uint8_t *fs_zdecode(void *c, const uint8_t *src, size_t n, const uint8_t *dict, size_t dn, size_t cap, char *why, size_t wn) {
    (void)c; uint8_t *o = malloc(cap ? cap : 1); ZSTD_DCtx *z = ZSTD_createDCtx();
    size_t r = ZSTD_decompress_usingDict(z, o, cap, src, n, dict, dict ? dn : 0); ZSTD_freeDCtx(z);
    if (ZSTD_isError(r) || r != cap) { snprintf(why, wn, "zstd: %s", ZSTD_isError(r) ? ZSTD_getErrorName(r) : "short"); free(o); return NULL; }
    return o;
}
static void wfile(const char *root, const char *rel, const uint8_t *b, size_t n) { CHECK(!detail_write(root, rel, b, n), "fixture %s", rel); }
static uint8_t *rfile(const char *path, size_t *n) { fs_ctx c = {""}; char why[100]; return fs_read(&c, path, n, why, sizeof why); }
// The manifest of an overlay: beside its romfs/ folder (the folder the host hands to DM_ROMFS_OVERLAY), never in it
static void manifest_of(char *m, size_t mn, const char *out) { snprintf(m, mn, "%s", out); char *sl = strrchr(m, '/'); if (sl && !strcmp(sl, "/romfs")) *sl = 0; snprintf(m + strlen(m), mn - strlen(m), "/detail.txt"); }
static int count_dirs(const char *dir, const char *prefix) { DIR *d = opendir(dir); int k = 0; struct dirent *e; while (d && (e = readdir(d))) if (!strncmp(e->d_name, prefix, strlen(prefix))) k++; if (d) closedir(d); return k; }

static void test_build(void) {
    const char *td = getenv("TMPDIR"); char root[600]; snprintf(root, sizeof root, "%s/t_detail-XXXXXX", td && *td ? td : "/tmp");
    if (!mkdtemp(root)) { CHECK(0, "mkdtemp %s", root); return; }
    char rom[700], tmp[700]; snprintf(rom, sizeof rom, "%s/romfs", root); snprintf(tmp, sizeof tmp, "%s/temp", root); mkdir(tmp, 0755);
    uint8_t a[1024], b[1024], s[4096], f[8192]; size_t na = make_byml(a, 0, 1, NULL), nb = make_byml(b, 1, 0, NULL);
    wfile(rom, "Foliage/ResFoliageScene/A.bgyml", a, na);   // TurfDensity 123, IsEnableMoss
    wfile(rom, "Foliage/ResFoliageScene/B.bgyml", b, nb);   // big-endian, no TurfDensity
    const char *names[] = {"Foliage/ResFoliageScene/A.bgyml", "Foliage/Other/C.bgyml"}; const uint8_t *d[] = {a, a}; size_t l[] = {na, na};
    size_t ns = make_sarc(s, names, d, l, 2), hn = frame_head(f, ns, 0);
    detail_zhead zh; char why[200]; detail_zstd_head(f, hn, &zh, why, sizeof why); size_t fn = 0; uint8_t *fr = detail_zstd_raw(&zh, s, ns, 0, &fn);
    wfile(rom, "Pack/Foliage.pack.zs", fr, fn); free(fr);
    fs_ctx c = {rom}; detail_io io = {&c, fs_read, fs_list, fs_zdecode, logf_};
    detail_profile *p = calloc(1, sizeof *p); g_nlog = 0; detail_parse(p, ROWS, 9, logf_, NULL);
    char out[1300]; char path[1600]; size_t n; uint8_t *x;
    // Full: no row is selected, nothing is built, no folder appears
    g_nlog = 0;
    CHECK(detail_build(p, 0, "full", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 1 && !*out && !g_nlog && !count_dirs(tmp, "detail-"), "Full: nothing");
    CHECK(detail_build(p, 8, "a1", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 1 && !*out && !g_nlog && !count_dirs(tmp, "detail-"), "a level with switches only: no files");
    // Reduced: the loose A edited, B has no TurfDensity (a key it does not have), the pack's A member edited and its C member as it was
    g_nlog = 0;
    CHECK(detail_build(p, 2, "reduced", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 1 && strstr(out, "/detail-reduced-"), "reduced built: %s", out);
    CHECK(!log_red() && log_has("built: 2 files (1 loose, 1 packs)") && log_has("[*.bgyml TurfDensity x0.5: 1 values in 1 of 2]") && log_has("[Pack/Foliage.pack.zs:*.bgyml TurfDensity x0.5: 1 values in 1 of 1]"), "the [detail] line: %s", g_nlog ? g_log[0] : "");
    snprintf(path, sizeof path, "%s/Foliage/ResFoliageScene/A.bgyml", out); x = rfile(path, &n); slots sa = find_slots(a, 0);
    CHECK(x && n == na && rdf(x, sa.density, 0) == 61.5f && ndiff(a, x, n, NULL) <= 4, "loose A: 61.5, same size");
    free(x);
    snprintf(path, sizeof path, "%s/Foliage/ResFoliageScene/B.bgyml", out); CHECK(access(path, F_OK), "B, without the key, is not in the overlay");
    snprintf(path, sizeof path, "%s/Pack/Foliage.pack.zs", out); x = rfile(path, &n);
    uint8_t *dec = x ? malloc(ns) : NULL; size_t r = x ? ZSTD_decompress(dec, ns, x, n) : 0;
    CHECK(x && !ZSTD_isError(r) && r == ns, "the pack decodes with zstd's decoder to the SARC's size");
    if (dec && r == ns) {
        detail_member *m = NULL; int cm = detail_sarc(dec, ns, &m, why, sizeof why);
        CHECK(cm == 2 && rdf(dec + m[0].start, sa.density, 0) == 61.5f && !memcmp(dec + m[1].start, a, na) && ndiff(s, dec, ns, NULL) <= 4, "member A edited, C as it was");
        free(m);
    }
    free(x); free(dec);
    CHECK(strlen(out) > 6 && !strcmp(out + strlen(out) - 6, "/romfs"), "the overlay is the romfs/ folder of the build: %s", out);
    manifest_of(path, sizeof path, out); CHECK(!access(path, F_OK), "manifest beside romfs/");
    snprintf(path, sizeof path, "%s/detail.txt", out); CHECK(access(path, F_OK), "no manifest among the game's files (rom:/detail.txt)");
    snprintf(path, sizeof path, "%s/.lock", tmp); CHECK(!access(path, F_OK) && count_dirs(tmp, "detail-") == 1, "the lock file and one folder");
    // the same rows and files again: the overlay of the earlier start
    char first[1300]; snprintf(first, sizeof first, "%s", out); g_nlog = 0;
    CHECK(detail_build(p, 2, "reduced", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 1 && !strcmp(out, first) && log_has("made at an earlier start"), "reused");
    // a partial folder (no manifest: a build cut off) is built again
    manifest_of(path, sizeof path, out); unlink(path); g_nlog = 0;
    CHECK(detail_build(p, 2, "reduced", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 1 && !strcmp(out, first) && log_has("built:") && !access(path, F_OK), "rebuilt");
    // an older folder of another level or game version goes also when an earlier overlay is used as it is
    snprintf(path, sizeof path, "%s/detail-old-0000000000000000/romfs", tmp); detail_mkdirs(path); g_nlog = 0;
    CHECK(detail_build(p, 2, "reduced", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 1 && log_has("made at an earlier start") && count_dirs(tmp, "detail-") == 1, "older folder removed on reuse");
    // lowest (more rows): a new folder, the older one removed
    g_nlog = 0;
    CHECK(detail_build(p, 4, "lowest", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 1 && strstr(out, "/detail-lowest-") && count_dirs(tmp, "detail-") == 1, "lowest replaces reduced");
    snprintf(path, sizeof path, "%s/Foliage/ResFoliageScene/B.bgyml", out); x = rfile(path, &n); slots sb = find_slots(b, 1);
    CHECK(x && n == nb && rdi(x, sb.moss, 1) == 0 && ndiff(b, x, n, NULL) == 1, "big-endian B: IsEnableMoss false");
    free(x);
    // DM_DETAIL_COPY: loose only / pack only
    g_nlog = 0; detail_build(p, 2, "reduced", DETAIL_COPY_LOOSE, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL);
    snprintf(path, sizeof path, "%s/Pack/Foliage.pack.zs", out); CHECK(access(path, F_OK) && log_has("1 files (1 loose, 0 packs)"), "loose only");
    g_nlog = 0; detail_build(p, 2, "reduced", DETAIL_COPY_PACK, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL);
    snprintf(path, sizeof path, "%s/Foliage/ResFoliageScene/A.bgyml", out); CHECK(access(path, F_OK) && log_has("1 files (0 loose, 1 packs)"), "pack only");
    // failures, each a red line; what fails stays the game's own
    const char *frows[] = {"detail_level\tfull\tshown\tF", "detail_level\tr\tshown\tR",
                           "detail_param\tr\tNothing/*.bgyml\tK\tx0.5",                         // matches no file
                           "detail_param\tr\tFoliage/ResFoliageScene/*.bgyml\tNoSuchKey\tx0.5",  // in none of its files
                           "detail_param\tr\tFoliage/ResFoliageScene/A.bgyml\tName\tx0.5",      // a string: A stays the game's own
                           "detail_param\tr\tFoliage/ResFoliageScene/B.bgyml\tIsEnableMoss\t=false",
                           "detail_param\tr\tpack:Pack/Bad.pack.zs:*\tK\tx0.5",                 // cannot be decoded
                           "detail_param\tr\tpack:Pack/Foliage.pack.zs:Foliage/*/*.bgyml\tIsEnableMoss\t=false",   // both members
                           "detail_param\tr\tpack:Pack/Foliage.pack.zs:Foliage/*/A.bgyml\tName\tx0.5"};  // fails on member A: A back as it was, C kept
    uint8_t junk[64] = {0x28, 0xB5, 0x2F, 0xFD, 0xA0, 0x40, 0, 0, 0, 0xFF, 0xFF, 0xFF};   // says 64 bytes, then a reserved block type
    wfile(rom, "Pack/Bad.pack.zs", junk, sizeof junk);
    detail_profile *q = calloc(1, sizeof *q); detail_parse(q, frows, 9, logf_, NULL); g_nlog = 0;
    CHECK(detail_build(q, 2, "r", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 1, "the rows that work still build");
    CHECK(log_red() == 5 && log_has("member Foliage/ResFoliageScene/A.bgyml: Name is a string") && log_has("no file matches Nothing/*.bgyml") && log_has("has the key NoSuchKey") && log_has("A.bgyml: Name is a string") && log_has("Pack/Bad.pack.zs:"),
          "five red lines: %d: %s | %s | %s | %s | %s", log_red(), g_log[0], g_log[1], g_log[2], g_log[3], g_log[4]);
    snprintf(path, sizeof path, "%s/Foliage/ResFoliageScene/A.bgyml", out); CHECK(access(path, F_OK), "A, whose edit failed, stays the game's own");
    snprintf(path, sizeof path, "%s/Pack/Bad.pack.zs", out); CHECK(access(path, F_OK), "the bad pack stays the game's own");
    snprintf(path, sizeof path, "%s/Foliage/ResFoliageScene/B.bgyml", out); CHECK(!access(path, F_OK), "B is edited");
    {   // the next start uses that overlay and says every red line again: those of the files (step 1, said each time) and those of the build (from its manifest)
        char again[1300]; snprintf(again, sizeof again, "%s", out); g_nlog = 0;
        CHECK(detail_build(q, 2, "r", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 1 && !strcmp(out, again) && log_has("made at an earlier start"), "reused with red lines");
        CHECK(log_red() == 5 && log_has("member Foliage/ResFoliageScene/A.bgyml: Name is a string") && log_has("no file matches Nothing/*.bgyml") && log_has("has the key NoSuchKey") && log_has("A.bgyml: Name is a string") && log_has("Pack/Bad.pack.zs:"),
              "the same five red lines at the later start: %d", log_red());
    }
    snprintf(path, sizeof path, "%s/Pack/Foliage.pack.zs", out); x = rfile(path, &n); dec = x ? malloc(ns) : NULL; r = x ? ZSTD_decompress(dec, ns, x, n) : 0;
    if (CHECK_OK(x && r == ns, "the pack is written (member C edited)")) {
        detail_member *m = NULL; detail_sarc(dec, ns, &m, why, sizeof why); slots sc = find_slots(a, 0);
        CHECK(!memcmp(dec + m[0].start, a, na) && rdi(dec + m[1].start, sc.moss, 0) == 0 && ndiff(a, dec + m[1].start, na, NULL) == 1, "member A as the game has it (its moss edit undone), C's moss false");
        free(m);
    }
    free(x); free(dec);
    // nothing editable at all: no overlay, the game as it draws it
    const char *nrows[] = {"detail_level\tfull\tshown\tF", "detail_level\tr\tshown\tR", "detail_param\tr\tFoliage/ResFoliageScene/*.bgyml\tNoSuchKey\tx0.5"};
    detail_parse(q, nrows, 3, logf_, NULL); g_nlog = 0;
    CHECK(detail_build(q, 2, "r", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, tmp, &io, out, sizeof out, NULL) == 0 && !*out && log_has("no file was edited") && log_has("(Full)"), "nothing edited");
    { DIR *dd = opendir(tmp); struct dirent *e; int part = 0; while (dd && (e = readdir(dd))) if (strstr(e->d_name, ".tmp")) part++; if (dd) closedir(dd); CHECK(!part, "no partial folder left"); }
    // a temp folder that cannot be written: no overlay, no partial folder
    char ro[700]; snprintf(ro, sizeof ro, "%s/readonly", root); mkdir(ro, 0555); g_nlog = 0;
    if (geteuid()) {
        CHECK(detail_build(p, 2, "reduced", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, ro, &io, out, sizeof out, NULL) == 0 && !*out && log_has("[RED] [detail] cannot write") && log_has("(Full)"), "read-only temp: %s", g_nlog ? g_log[0] : "");
        CHECK(count_dirs(ro, "detail-") == 0, "no partial folder");
    }
    CHECK(detail_build(p, 2, "reduced", DETAIL_COPY_BOTH, DETAIL_DICTID_KEEP, NULL, &io, out, sizeof out, NULL) == 0 && log_has("DM_TEMP is not set"), "no DM_TEMP");
    chmod(ro, 0755); free(p); free(q);
    detail_rm_tree(root);
}

// ---- The tool: the overlay of a RomFS folder, with the profile's rows or a file's ----
static int tool(int argc, char **argv) {
    if (argc < 5) { fprintf(stderr, "usage: t_detail build <RomFS folder> <temp folder> <DM_DETAIL value> [both|loose|pack] [keep|none] [report]\n"); return 2; }
    static const char *const prof[] = DM_GAME_DETAIL; const char *const *rows = prof; int nrows = DM_GAME_NDETAIL;
    char *own[512]; const char *rf = getenv("DETAIL_ROWS");
    if (rf) {
        FILE *f = fopen(rf, "r"); char line[1200]; nrows = 0; if (!f) { perror(rf); return 2; }
        while (nrows < 512 && fgets(line, sizeof line, f)) { line[strcspn(line, "\n")] = 0; if (*line && *line != '#') own[nrows++] = strdup(line); }
        fclose(f); rows = (const char *const *)own;
    }
    g_echo = 1; setvbuf(stdout, NULL, _IOLBF, 0);
    detail_profile *p = calloc(1, sizeof *p); detail_parse(p, rows, nrows, logf_, NULL);
    char why[300]; int64_t m = detail_choose(p, argv[4], why, sizeof why);
    if (m < 0) { printf("[RED] [detail] DM_DETAIL=%s: %s\n", argv[4], why); free(p); return 1; }
    int copy = argc > 5 && !strcmp(argv[5], "loose") ? DETAIL_COPY_LOOSE : argc > 5 && !strcmp(argv[5], "pack") ? DETAIL_COPY_PACK : DETAIL_COPY_BOTH;
    int dict = argc > 6 && !strcmp(argv[6], "none") ? DETAIL_DICTID_NONE : DETAIL_DICTID_KEEP;
    FILE *rep = argc > 7 ? fopen(argv[7], "w") : NULL;
    char name[300] = ""; for (int i = 1; i < p->nlv; i++) if (m >> i & 1) { size_t l = strlen(name); snprintf(name + l, sizeof name - l, "%s%s", l ? "+" : "", p->lv[i].id); }
    fs_ctx c = {argv[2]}; detail_io io = {&c, fs_read, fs_list, fs_zdecode, logf_}; char out[1300] = "";
    int ok = m ? detail_build(p, (uint32_t)m, name, copy, dict, argv[3], &io, out, sizeof out, rep) : 1;
    if (rep) fclose(rep);
    printf("overlay: %s\n", ok ? out : "(none)");
    for (int i = 0; i < p->nenv; i++) if (p->env[i].levels & (uint32_t)m) printf("env: %s\n", p->env[i].kv);
    free(p); if (rf) for (int i = 0; i < nrows; i++) free(own[i]);
    return ok ? 0 : 1;
}

int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "build")) return tool(argc, argv);
    test_parse();
    test_byml(0); test_byml(1);
    test_shared();
    test_sarc();
    test_zstd();
    test_build();
    if (g_fail) { printf("t_detail: %d FAILED\n", g_fail); return 1; }
    printf("t_detail: all passed\n");
    return 0;
}
