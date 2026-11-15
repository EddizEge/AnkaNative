// Raw global memory loads merged per base address (dm/gfx/vk_android/vka_gmem.c, DM_VK_GMEM_MERGE), on the PC: the hand-written fixtures in gmem/ (a compute module
// with a load helper in each of the library's two forms: the page index and the pool search, calls in the translator's base + constant form, runs across a 4 KiB
// bucket, near 2^32, cut by a store, in another block, not known in their block, not base + constant; the _tw variants with a store to the table between two calls)
// rewritten, checked by count, by spirv-val when it is on the PATH, and EXECUTED: a small SPIR-V interpreter runs the original and the rewritten module on the same
// random memory, inputs and tables (pages mapped and unmapped, windows across 2^32 and at the page index's clamp, sorted and unsorted pool tables, base words near page
// ends), and their outputs, every memory store, every helper call's value and every Output must be bit-identical. As a check of the check, the rewritten main
// fixture with the _tw store put back between its calls (a stale lookup) must differ. DM_VK_GMEM_LIB=<dmsh_vk.lib> does the same for every module of a real library
// (each variant), validated with spirv-val when present and executed DM_VK_GMEM_TRIALS times (default 24) each; DM_VK_GMEM_OUT=<folder> writes the rewritten modules.
// Values the rewrite does not touch (floating-point arithmetic, image reads, derivatives, subgroup operations) are deterministic hashes of their operands in both runs,
// so a difference can only come from the rewritten code; integer arithmetic, bit casts, composites, memory, calls, phis, branches and loops are exact.
#include "../../gfx/vk_android/vka_gmem.h"
#include "../../gfx/vk_android/vka_shlib.h"
#include "gmem/gmem_fixtures.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
static int g_val;
static int validate(const uint32_t *w, size_t n, const char *tag) {
    if (!g_val) return 1;
    char path[256]; snprintf(path, sizeof path, "/tmp/t_gmem_%d.spv", (int)getpid());
    FILE *f = fopen(path, "wb"); if (!f) return 0; fwrite(w, 4, n, f); fclose(f);
    int v14 = n > 1 && w[1] >= 0x10400;
    char cmd[400]; snprintf(cmd, sizeof cmd, "spirv-val --target-env %s %s 2>&1", v14 ? "vulkan1.3" : "vulkan1.1", path);
    int r = system(cmd);
    if (r) { char keep[300]; snprintf(keep, sizeof keep, "/tmp/t_gmem_fail_%s.spv", tag); rename(path, keep); printf("  spirv-val failed on %s (kept as %s)\n", tag, keep); }
    else remove(path);
    return r == 0;
}

// ---------------------------------------------------------------- the interpreter
static uint64_t mix(uint64_t x) { x += 0x9e3779b97f4a7c15ull; x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9ull; x = (x ^ (x >> 27)) * 0x94d049bb133111ebull; return x ^ (x >> 31); }
enum { K_NONE, K_VOID, K_BOOL, K_INT, K_FLOAT, K_VEC, K_MAT, K_ARR, K_RTA, K_STRUCT, K_PTR, K_FN, K_OPAQUE };
typedef struct { uint8_t k, w, sg; uint32_t a, n, leaf, stride; uint32_t *ml, *mo; const uint32_t *mem; } ty_t;   // a: component / element / pointee; n: count / storage class
typedef struct {
    const uint32_t *w; size_t nw; uint32_t bound, obound;   // obound: the original module's bound (ids below it are the original's in both runs)
    size_t *def; ty_t *ty; uint32_t *vb; size_t nval;       // vb[id]: offset of the id's value leaves
    uint32_t *lb, *ls; size_t nloc;                          // lb[var]: offset of a local variable's leaves, ls[var]: their count
    uint8_t *vk;                                             // variable kind: 1 local, 2 buffer, 3 opaque
    uint8_t *table;                                          // a variable the raw global memory helpers read as their table
    uint32_t entry; int bad; uint32_t bad_op;
    uint64_t *cval; size_t ncval;                            // constants' leaves (filled once)
} im_t;
typedef struct { uint64_t *k; uint32_t *v; size_t n, cap; } map_t;
typedef struct {
    im_t *M; uint64_t *val, *loc; map_t buf, psb;
    uint64_t *pool; size_t np, cp;                           // physical pointers: addresses
    uint64_t seed; long steps; int stop, cap_hit;
    uint64_t *tr; size_t ntr, ctr;                           // the trace
    uint32_t P0, N; uint64_t sink; int sorted; uint32_t ES;  // the trial's tables
    long cov[8];                                             // pidx c true / false; search equal / from a bit / full; sink loads / mapped loads
} run_t;
static uint32_t *map_slot(map_t *m, uint64_t key, int add) {
    if (add && (m->n + 1) * 2 > m->cap) {
        size_t c = m->cap ? 2 * m->cap : 1024; uint64_t *k = calloc(c, 8); uint32_t *v = calloc(c, 4);
        for (size_t i = 0; i < m->cap; i++) if (m->k[i]) { size_t j = mix(m->k[i]) & (c - 1); while (k[j]) j = (j + 1) & (c - 1); k[j] = m->k[i]; v[j] = m->v[i]; }
        free(m->k); free(m->v); m->k = k; m->v = v; m->cap = c;
    }
    if (!m->cap) return NULL;
    key = key * 2 + 1;   // (never 0)
    size_t j = mix(key) & (m->cap - 1);
    while (m->k[j] && m->k[j] != key) j = (j + 1) & (m->cap - 1);
    if (m->k[j]) return &m->v[j];
    if (!add) return NULL;
    m->k[j] = key; m->n++; return &m->v[j];
}
static void map_clear(map_t *m) { if (m->cap) { memset(m->k, 0, m->cap * 8); } m->n = 0; }
static void tr_add(run_t *r, uint64_t a, uint64_t b, uint64_t c, uint64_t d) {
    if (r->ntr + 4 > r->ctr) { r->ctr = r->ctr ? 2 * r->ctr : 4096; r->tr = realloc(r->tr, r->ctr * 8); }
    r->tr[r->ntr++] = a; r->tr[r->ntr++] = b; r->tr[r->ntr++] = c; r->tr[r->ntr++] = d;
}
#define OPC(i) (M->w[i] & 0xffff)
#define LEN(i) (M->w[i] >> 16)
static uint32_t leafc(im_t *M, uint32_t t);
static void ty_fill(im_t *M, uint32_t t) {
    ty_t *y = &M->ty[t]; size_t d = M->def[t]; if (!d || y->k) return;
    const uint32_t *x = M->w + d; uint32_t op = x[0] & 0xffff, len = x[0] >> 16;
    switch (op) {
    case 19: y->k = K_VOID; break; case 20: y->k = K_BOOL; y->w = 1; break;
    case 21: y->k = K_INT; y->w = (uint8_t)x[2]; y->sg = (uint8_t)x[3]; break; case 22: y->k = K_FLOAT; y->w = (uint8_t)x[2]; break;
    case 23: y->k = K_VEC; y->a = x[2]; y->n = x[3]; break; case 24: y->k = K_MAT; y->a = x[2]; y->n = x[3]; break;
    case 28: { y->k = K_ARR; y->a = x[2]; size_t c = M->def[x[3]]; y->n = c && (M->w[c] & 0xffff) == 43 ? M->w[c + 3] : 1; break; }
    case 29: y->k = K_RTA; y->a = x[2]; break;
    case 30: y->k = K_STRUCT; y->n = len - 2; y->mem = x + 2; break;
    case 32: y->k = K_PTR; y->n = x[2]; y->a = x[3]; break;
    case 39: break;
    case 33: y->k = K_FN; break;
    default: y->k = K_OPAQUE; break;
    }
}
static uint32_t leafc(im_t *M, uint32_t t) {
    if (t >= M->bound) return 1;
    ty_t *y = &M->ty[t]; ty_fill(M, t);
    if (y->leaf) return y->leaf;
    uint32_t n = 1;
    switch (y->k) {
    case K_VEC: case K_MAT: case K_ARR: n = y->n * leafc(M, y->a); break;
    case K_RTA: case K_VOID: n = 0; break;
    case K_STRUCT: n = 0; y->ml = calloc(y->n + 1, 4); for (uint32_t m = 0; m < y->n; m++) { y->ml[m] = n; n += leafc(M, y->mem[m]); } break;
    }
    if (n > (1u << 20)) n = 1u << 20;
    y->leaf = n ? n : (y->k == K_RTA || y->k == K_VOID ? 0 : 1);
    if (y->k == K_STRUCT && !y->leaf) y->leaf = 0;
    return y->leaf;
}
static uint32_t has_res(uint32_t op) {
    switch (op) { case 12: case 41: case 42: case 43: case 44: case 45: case 46: case 48: case 49: case 50: case 51: case 52: case 54: case 55: case 57: case 59: case 60: case 61: case 65: case 66:
                  case 67: case 68: case 70: case 77: case 79: case 80: case 81: case 82: case 83: case 84: case 245: case 1: return 1; }
    if (op >= 86 && op <= 205) return 1;
    if (op >= 207 && op <= 215) return 1;
    if (op >= 227 && op <= 242 && op != 228) return 1;
    if (op >= 333 && op <= 366) return 1;
    if (op >= 4421 && op <= 4430) return 1;
    if (op == 5614 || op == 5615 || op == 6035 || op == 5381 || op == 400) return 1;
    return 0;
}
static void im_free(im_t *M) {
    if (M->ty) for (uint32_t t = 0; t < M->bound; t++) { free(M->ty[t].ml); free(M->ty[t].mo); }
    free(M->def); free(M->ty); free(M->vb); free(M->lb); free(M->ls); free(M->vk); free(M->table); free(M->cval);
}
static int im_load(im_t *M, const uint32_t *w, size_t nw, uint32_t obound) {
    memset(M, 0, sizeof *M);
    M->w = w; M->nw = nw; M->bound = w[3]; M->obound = obound;
    M->def = calloc(M->bound, sizeof *M->def); M->ty = calloc(M->bound, sizeof *M->ty); M->vb = calloc(M->bound, 4); M->lb = calloc(M->bound, 4); M->ls = calloc(M->bound, 4);
    M->vk = calloc(M->bound, 1); M->table = calloc(M->bound, 1);
    for (size_t i = 5; i < nw;) {
        uint32_t op = w[i] & 0xffff, len = w[i] >> 16; if (!len || i + len > nw) return 0;
        uint32_t id = 0;
        if ((op >= 19 && op <= 39) || op == 248 || op == 11) id = w[i + 1];
        else if (has_res(op) && len >= 3) id = w[i + 2];
        if (id && id < M->bound) M->def[id] = i;
        if (op == 15 && !M->entry) M->entry = w[i + 2];
        i += len;
    }
    for (size_t i = 5; i < nw; i += w[i] >> 16) {   // decorations: array strides, member offsets
        uint32_t op = w[i] & 0xffff;
        if (op == 71 && w[i + 2] == 6 && w[i + 1] < M->bound) M->ty[w[i + 1]].stride = w[i + 3];
        if (op == 72 && w[i + 3] == 35 && w[i + 1] < M->bound) {
            ty_t *y = &M->ty[w[i + 1]]; ty_fill(M, w[i + 1]); if (y->k != K_STRUCT) continue;
            if (!y->mo) y->mo = calloc(y->n + 1, 4);
            if (w[i + 2] < y->n) y->mo[w[i + 2]] = w[i + 4];
        }
    }
    // value slots for every result, local storage for every local variable
    size_t nv = 0, nl = 0;
    for (size_t i = 5; i < nw; i += w[i] >> 16) {
        uint32_t op = w[i] & 0xffff, len = w[i] >> 16;
        if (!has_res(op) || len < 3 || w[i + 2] >= M->bound) continue;
        uint32_t t = w[i + 1], id = w[i + 2]; uint32_t lc = op == 54 ? 0 : leafc(M, t);
        if (lc > 65536) { M->bad = 1; M->bad_op = op; return 0; }
        M->vb[id] = (uint32_t)nv; nv += lc ? lc : 1;
        if (op == 59) {
            uint32_t sc = w[i + 3]; ty_fill(M, t); uint32_t pt = M->ty[t].a;
            if (sc == 7 || sc == 6 || sc == 1 || sc == 3 || sc == 4 || sc == 5402) { M->vk[id] = 1; M->lb[id] = (uint32_t)nl; uint32_t c = leafc(M, pt); if (c > 65536) { M->bad = 1; return 0; } M->ls[id] = c ? c : 1; nl += c ? c : 1; }
            else if (sc == 2 || sc == 12 || sc == 9) M->vk[id] = 2;
            else M->vk[id] = 3;
        }
    }
    M->nval = nv + 1; M->nloc = nl + 1;
    return 1;
}
// The helpers' tables: Uniform / StorageBuffer variables read through access chains inside functions that make a physical pointer
static void find_tables(im_t *M) {
    size_t f0 = 0;
    for (size_t i = 5; i < M->nw; i += LEN(i)) {
        if (OPC(i) == 54) f0 = i;
        if (OPC(i) == 120 && f0 && M->w[f0 + 2] != M->entry) {
            for (size_t j = f0; j < M->nw && OPC(j) != 56; j += LEN(j))
                if ((OPC(j) == 65 || OPC(j) == 66) && M->w[j + 3] < M->bound && M->vk[M->w[j + 3]] == 2) M->table[M->w[j + 3]] = 1;
        }
    }
}

// ---- the trial's memory
static uint32_t biased(run_t *r, uint64_t h) {
    uint32_t cat = (uint32_t)(h % 10), pg = r->P0 + (uint32_t)((h >> 8) % (r->N + 3)) - 1;
    static const uint32_t edge[] = {0, 1, 2, 3, 4, 8, 12, 0xfe0, 0xff0, 0xff4, 0xff8, 0xffc, 0xffd, 0xfff};
    switch (cat) {
    case 0: case 1: case 2: return (pg << 12) + ((h >> 32) & 1 ? edge[(h >> 40) % 14] : (uint32_t)(h >> 44) & 0xfff);
    case 3: case 4: case 5: return pg >> 20;
    case 6: return (uint32_t)(h >> 20) & 63;
    case 7: return 0xffffffffu - ((uint32_t)(h >> 24) & 15);
    default: return (uint32_t)(h >> 16);
    }
}
static uint32_t table_word(run_t *r, uint64_t wi) {
    uint64_t h = mix(r->seed ^ (wi * 0x100000001b3ull));
    if (wi >= 32768) {
        uint64_t j = wi - 32768;
        if (j == 0) return r->P0;
        if (j == 1) return r->N;
        if (j < 4) return 0;
        uint64_t rec = (j - 4) / 2; int half = (int)((j - 4) & 1);
        uint64_t hr = mix(r->seed ^ (rec * 0x9e3779b97f4a7c15ull) ^ 0x5151);
        if (rec >= r->N || (hr & 3) == 0) return 0;   // unmapped pages, and the record past the window
        return half ? (uint32_t)(hr >> 40) & 0xff : ((uint32_t)(hr >> 8) & 0xfffff) << 12 | 1;
    }
    uint64_t e = wi / 8, f = wi % 8;
    if (e == 0 && f >= 6) return f == 6 ? (uint32_t)r->sink : (uint32_t)(r->sink >> 32);
    uint64_t start, len, delta = (mix(r->seed ^ e ^ 0x77) & 0xffffff) << 12;
    if (r->sorted) { start = ((uint64_t)r->P0 << 12) - 64 * 4096ull + e * r->ES; len = (mix(r->seed ^ e ^ 0x99) & 1) ? r->ES : r->ES / 2; }
    else { uint64_t a = mix(r->seed ^ e ^ 0x31); start = (uint64_t)biased(r, a) | (uint64_t)biased(r, a >> 7) << 32; len = (a >> 50) << 12; }
    uint64_t end = start + len;
    switch (f) { case 0: return (uint32_t)start; case 1: return (uint32_t)(start >> 32); case 2: return (uint32_t)end; case 3: return (uint32_t)(end >> 32);
                 case 4: return (uint32_t)delta; case 5: return (uint32_t)(delta >> 32); default: return (uint32_t)h; }
}
static uint32_t buf_word(run_t *r, uint32_t var, uint64_t b) {
    uint32_t *p = map_slot(&r->buf, (uint64_t)var << 40 | (b & 0xffffffffffull), 0);
    if (p) return *p;
    if (r->M->table[var]) return table_word(r, b / 4);
    return biased(r, mix(r->seed ^ ((uint64_t)var << 40) ^ b));
}
static uint32_t psb_word(run_t *r, uint64_t a) {
    uint32_t *p = map_slot(&r->psb, a, 0);
    return p ? *p : (uint32_t)mix(r->seed * 31 ^ a);
}

// ---- execution
#define V(id) (r->val + M->vb[id])
static uint64_t enc_ptr(int kind, uint32_t var, uint64_t off) { return (uint64_t)kind << 62 | (uint64_t)var << 40 | (off & 0xffffffffffull); }
static uint64_t mask_w(int w) { return w >= 64 ? ~0ull : (1ull << w) - 1; }
static int64_t sx(uint64_t v, int w) { return w >= 64 ? (int64_t)v : (int64_t)(v << (64 - w)) >> (64 - w); }
static int comp_w(im_t *M, uint32_t t) { ty_fill(M, t); ty_t *y = &M->ty[t]; if (y->k == K_VEC) { ty_fill(M, y->a); return M->ty[y->a].w; } return y->w; }
static uint32_t comp_t(im_t *M, uint32_t t) { ty_fill(M, t); return M->ty[t].k == K_VEC ? M->ty[t].a : t; }
static void mread(run_t *r, int kind, uint32_t var, uint64_t b, uint32_t t, uint64_t *out) {
    im_t *M = r->M; ty_fill(M, t); ty_t *y = &M->ty[t];
    #define WORD(o) (kind == 3 ? psb_word(r, o) : buf_word(r, var, o))
    switch (y->k) {
    case K_BOOL: out[0] = WORD(b) != 0; break;
    case K_INT: case K_FLOAT: out[0] = y->w == 64 ? (uint64_t)WORD(b) | (uint64_t)WORD(b + 4) << 32 : WORD(b) & mask_w(y->w); if (y->w < 32 && !M->bad) { M->bad = 1; M->bad_op = 21; } break;
    case K_VEC: { uint32_t cw = (uint32_t)comp_w(M, t) / 8; for (uint32_t c = 0; c < y->n; c++) mread(r, kind, var, b + c * cw, y->a, out + c); break; }
    case K_ARR: { uint32_t lc = leafc(M, y->a); for (uint32_t e = 0; e < y->n; e++) mread(r, kind, var, b + (uint64_t)e * (y->stride ? y->stride : 4), y->a, out + e * lc); break; }
    case K_STRUCT: leafc(M, t); for (uint32_t m = 0; m < y->n; m++) mread(r, kind, var, b + (y->mo ? y->mo[m] : 0), y->mem[m], out + y->ml[m]); break;
    case K_PTR: { uint64_t a = (uint64_t)WORD(b) | (uint64_t)WORD(b + 4) << 32; if (r->np == r->cp) { r->cp = r->cp ? 2 * r->cp : 256; r->pool = realloc(r->pool, r->cp * 8); } r->pool[r->np] = a; out[0] = enc_ptr(3, 0, r->np++); break; }
    default: out[0] = mix((uint64_t)var << 40 ^ b ^ 0xabc); break;   // an image or sampler: a token
    }
    #undef WORD
}
static void mwrite(run_t *r, int kind, uint32_t var, uint64_t b, uint32_t t, const uint64_t *in) {
    im_t *M = r->M; ty_fill(M, t); ty_t *y = &M->ty[t];
    #define PUT(o, v) do { uint32_t *p_ = kind == 3 ? map_slot(&r->psb, o, 1) : map_slot(&r->buf, (uint64_t)var << 40 | ((o) & 0xffffffffffull), 1); *p_ = (uint32_t)(v); \
                           tr_add(r, kind, var, o, (uint32_t)(v)); } while (0)
    switch (y->k) {
    case K_BOOL: case K_INT: case K_FLOAT: PUT(b, in[0]); if (y->w == 64) PUT(b + 4, in[0] >> 32); break;
    case K_VEC: { uint32_t cw = (uint32_t)comp_w(M, t) / 8; for (uint32_t c = 0; c < y->n; c++) mwrite(r, kind, var, b + c * cw, y->a, in + c); break; }
    case K_ARR: { uint32_t lc = leafc(M, y->a); for (uint32_t e = 0; e < y->n; e++) mwrite(r, kind, var, b + (uint64_t)e * (y->stride ? y->stride : 4), y->a, in + e * lc); break; }
    case K_STRUCT: leafc(M, t); for (uint32_t m = 0; m < y->n; m++) mwrite(r, kind, var, b + (y->mo ? y->mo[m] : 0), y->mem[m], in + y->ml[m]); break;
    default: { uint64_t a = in[0] >> 62 == 3 ? r->pool[in[0] & 0xffffffffffull] : in[0]; PUT(b, a); PUT(b + 4, a >> 32); break; }
    }
    #undef PUT
}
// A pointer: kind 1 local (off: leaf), 2 buffer (off: byte), 3 physical (off: pool index of the address), the pointee type
static void ptr_dec(run_t *r, uint64_t p, int *kind, uint32_t *var, uint64_t *off) {
    *kind = (int)(p >> 62); *var = (uint32_t)(p >> 40) & 0x3fffff; *off = p & 0xffffffffffull;
    if (*kind == 3) *off = r->pool[*off];
}
static uint64_t ptr_new(run_t *r, int kind, uint32_t var, uint64_t off) {
    if (kind != 3) return enc_ptr(kind, var, off);
    if (r->np == r->cp) { r->cp = r->cp ? 2 * r->cp : 256; r->pool = realloc(r->pool, r->cp * 8); }
    r->pool[r->np] = off; return enc_ptr(3, 0, r->np++);
}
static uint64_t hash_ins(run_t *r, const uint32_t *x, uint32_t leaf) {   // a value the rewrite does not touch: a hash of the opcode, its type and its operands' values
    im_t *M = r->M; uint32_t len = x[0] >> 16; uint64_t h = mix(x[0] ^ (uint64_t)x[1] << 20 ^ (uint64_t)leaf << 44);
    for (uint32_t k = 3; k < len; k++) {
        uint32_t id = x[k];
        if (id < M->obound && M->def[id] && has_res(M->w[M->def[id]] & 0xffff) && (M->w[M->def[id]] & 0xffff) != 54) {
            uint32_t lc = leafc(M, M->w[M->def[id] + 1]); if (!lc) lc = 1;
            for (uint32_t q = 0; q < lc && q < 64; q++) h = mix(h ^ V(id)[q]);
        } else h = mix(h ^ id);
    }
    return h;
}
static void exact_ext(run_t *r, const uint32_t *x, uint64_t *out, int *done) {   // GLSL.std.450 integer instructions, exactly
    im_t *M = r->M; uint32_t t = x[1], n = leafc(M, t), e = x[4]; int w = comp_w(M, t); *done = 0;
    if (!(e == 5 || e == 7 || (e >= 73 && e <= 75) || e == 38 || e == 39 || e == 41 || e == 42 || e == 44 || e == 45)) return;
    for (uint32_t c = 0; c < n; c++) {
        uint64_t a = V(x[5])[c], b = (x[0] >> 16) > 6 ? V(x[6])[c] : 0, d = (x[0] >> 16) > 7 ? V(x[7])[c] : 0, v = 0;
        switch (e) {
        case 5: v = sx(a, w) < 0 ? -(uint64_t)a : a; break; case 7: v = sx(a, w) < 0 ? ~0ull : a ? 1 : 0; break;
        case 73: v = a ? (uint64_t)__builtin_ctzll(a) : ~0ull; break;
        case 74: { int64_t s = sx(a, w); uint64_t u = s < 0 ? ~(uint64_t)s : (uint64_t)s; u &= mask_w(w); v = u ? (uint64_t)(63 - __builtin_clzll(u)) : ~0ull; break; }
        case 75: v = a ? (uint64_t)(63 - __builtin_clzll(a)) : ~0ull; break;
        case 38: v = a < b ? a : b; break; case 41: v = a > b ? a : b; break;
        case 39: v = sx(a, w) < sx(b, w) ? a : b; break; case 42: v = sx(a, w) > sx(b, w) ? a : b; break;
        case 44: v = a < b ? b : a > d ? d : a; break; case 45: v = sx(a, w) < sx(b, w) ? b : sx(a, w) > sx(d, w) ? d : a; break;
        }
        out[c] = v & mask_w(w);
    }
    *done = 1;
}
static int exec_fn(run_t *r, uint32_t fid, const uint32_t *args, uint32_t nargs, uint64_t *ret, int depth);
static void bitcast(run_t *r, uint32_t dt, uint32_t src, uint64_t *out) {
    im_t *M = r->M; uint32_t st = M->w[M->def[src] + 1];
    uint32_t sn = leafc(M, st), dn = leafc(M, dt); int sw = comp_w(M, st), dw = comp_w(M, dt);
    ty_fill(M, st); ty_fill(M, dt);
    if (M->ty[st].k == K_PTR || M->ty[dt].k == K_PTR) { out[0] = V(src)[0]; return; }
    uint64_t bits[8] = {0}; int pos = 0;
    for (uint32_t c = 0; c < sn && c < 8; c++) { uint64_t v = V(src)[c] & mask_w(sw); bits[(pos) / 64] |= v << (pos % 64); if (pos % 64 + sw > 64) bits[pos / 64 + 1] |= v >> (64 - pos % 64); pos += sw; }
    pos = 0;
    for (uint32_t c = 0; c < dn && c < 8; c++) { uint64_t v = bits[pos / 64] >> (pos % 64); if (pos % 64 + dw > 64) v |= bits[pos / 64 + 1] << (64 - pos % 64); out[c] = v & mask_w(dw); pos += dw; }
}
static uint64_t ibin(uint32_t op, uint64_t a, uint64_t b, int w, int *ok) {
    uint64_t m = mask_w(w); int64_t sa = sx(a, w), sb = sx(b, w); *ok = 1;
    switch (op) {
    case 128: return (a + b) & m; case 130: return (a - b) & m; case 132: return (a * b) & m;
    case 134: return b ? (a / b) & m : 0; case 135: return sb && !(sa == INT64_MIN && sb == -1) ? (uint64_t)(sa / sb) & m : 0;
    case 137: return b ? a % b : 0; case 138: return sb && sb != -1 ? (uint64_t)(sa % sb) & m : 0;
    case 139: { if (!sb || sb == -1) return 0; int64_t q = sa % sb; if (q && ((q < 0) != (sb < 0))) q += sb; return (uint64_t)q & m; }
    case 194: return (b & 63) >= (uint64_t)w ? 0 : (a & m) >> (b & 63); case 195: return (b & 63) >= (uint64_t)w ? (sa < 0 ? m : 0) : (uint64_t)(sa >> (b & 63)) & m;
    case 196: return (b & 63) >= (uint64_t)w ? 0 : (a << (b & 63)) & m;
    case 197: return a | b; case 198: return a ^ b; case 199: return a & b;
    case 170: return (a & m) == (b & m); case 171: return (a & m) != (b & m);
    case 172: return a > b; case 173: return sa > sb; case 174: return a >= b; case 175: return sa >= sb;
    case 176: return a < b; case 177: return sa < sb; case 178: return a <= b; case 179: return sa <= sb;
    case 164: return a == b; case 165: return a != b; case 166: return a || b; case 167: return a && b;
    }
    *ok = 0; return 0;
}
static int exec_fn(run_t *r, uint32_t fid, const uint32_t *args, uint32_t nargs, uint64_t *ret, int depth) {
    im_t *M = r->M; size_t i = M->def[fid]; uint32_t np = 0;
    if (!i || depth > 16) { M->bad = 1; return 0; }
    uint32_t rt = M->w[i + 1];
    for (i += LEN(i); OPC(i) == 55; i += LEN(i)) {
        uint32_t p = M->w[i + 2], lc = leafc(M, M->w[i + 1]); if (!lc) lc = 1;
        if (np < nargs) memcpy(V(p), r->val + M->vb[args[np]], lc * 8);   // (arguments read before any parameter is written: no recursion, so no aliasing)
        np++;
    }
    uint32_t prev = 0, cur = 0;
    for (;;) {
        if (r->stop) return 0;
        if (++r->steps > 4000000) { r->cap_hit = 1; r->stop = 1; return 0; }
        const uint32_t *x = M->w + i; uint32_t op = x[0] & 0xffff, len = x[0] >> 16;
        switch (op) {
        case 248: {   // a block: its phis read the values on entry, then all are set
            prev = cur; cur = x[1]; i += len;
            uint64_t tmp[64]; size_t tp = 0, j = i;
            while (OPC(j) == 245) {
                uint32_t lc = leafc(M, M->w[j + 1]); int found = 0;
                for (uint32_t k = 3; k + 1 < LEN(j); k += 2) if (M->w[j + k + 1] == prev) { for (uint32_t q = 0; q < lc && tp < 64; q++) tmp[tp++] = V(M->w[j + k])[q]; found = 1; break; }
                if (!found || lc > 8) { M->bad = 1; M->bad_op = 245; return 0; }
                j += LEN(j);
            }
            tp = 0;
            while (OPC(i) == 245) { uint32_t lc = leafc(M, M->w[i + 1]); for (uint32_t q = 0; q < lc; q++) V(M->w[i + 2])[q] = tmp[tp++]; i += LEN(i); }
            continue; }
        case 249: i = M->def[x[1]]; continue;
        case 250: i = M->def[V(x[1])[0] ? x[2] : x[3]]; continue;
        case 251: { uint64_t s = V(x[1])[0]; uint32_t tgt = x[2]; int lw = comp_w(M, M->w[M->def[x[1]] + 1]) > 32 ? 2 : 1;
                    for (uint32_t k = 3; k + lw < len; k += 1 + lw) { uint64_t l = x[k] | (lw == 2 ? (uint64_t)x[k + 1] << 32 : 0); if (l == (s & mask_w(lw * 32))) { tgt = x[k + lw]; break; } }
                    i = M->def[tgt]; continue; }
        case 253: return 1;
        case 254: { uint32_t lc = leafc(M, rt); memcpy(ret, V(x[1]), (lc ? lc : 1) * 8); return 1; }
        case 252: case 4416: case 255: r->stop = 1; return 0;   // OpKill, OpTerminateInvocation, OpUnreachable: the invocation ends
        case 246: case 247: case 8: case 317: case 224: case 225: case 5380: case 218: case 219: case 5295: i += len; continue;
        case 5294: r->stop = 1; tr_add(r, 9, 0, 0, hash_ins(r, x, 0)); return 0;   // OpEmitMeshTasksEXT ends the invocation
        case 59: { uint32_t pt = x[1]; ty_fill(M, pt); uint32_t lc = leafc(M, M->ty[pt].a); if (!lc) lc = 1;
                   V(x[2])[0] = enc_ptr(1, x[2], 0);
                   if (M->vk[x[2]] == 1) { if (len > 4) memcpy(r->loc + M->lb[x[2]], V(x[4]), lc * 8); else memset(r->loc + M->lb[x[2]], 0, lc * 8); }
                   i += len; continue; }
        case 62: case 63: {   // OpStore, OpCopyMemory
            int kind; uint32_t var; uint64_t off; ptr_dec(r, V(x[1])[0], &kind, &var, &off);
            uint32_t pt = M->w[M->def[x[1]] + 1]; ty_fill(M, pt); uint32_t t = M->ty[pt].a, lc = leafc(M, t);
            uint64_t tmp[4096]; const uint64_t *src;
            if (op == 63) { int k2; uint32_t v2; uint64_t o2; ptr_dec(r, V(x[2])[0], &k2, &v2, &o2); if (lc > 4096) { M->bad = 1; return 0; }
                            if (k2 == 1 && (v2 >= M->bound || o2 + lc > M->ls[v2])) memset(tmp, 0, lc * 8); else if (k2 == 1) memcpy(tmp, r->loc + M->lb[v2] + o2, lc * 8); else mread(r, k2, v2, o2, t, tmp); src = tmp; }
            else src = V(x[2]);
            if (kind == 1 && (var >= M->bound || off + lc > M->ls[var])) {}   // outside the variable: nothing written (both runs alike)
            else if (kind == 1) { memcpy(r->loc + M->lb[var] + off, src, lc * 8); uint32_t sc = M->ty[pt].n; if (sc != 7 && sc != 6) for (uint32_t q = 0; q < lc; q++) tr_add(r, 1, var, off + q, src[q]); }
            else if (kind == 2 || kind == 3) mwrite(r, kind, var, off, t, src);
            i += len; continue; }
        case 57: {   // OpFunctionCall
            uint64_t tmp[64] = {0}; uint32_t lc = leafc(M, x[1]);
            if (lc > 64) { M->bad = 1; return 0; }
            if (!exec_fn(r, x[3], x + 4, len - 4, tmp, depth + 1) && r->stop) return 0;
            memcpy(V(x[2]), tmp, (lc ? lc : 1) * 8);
            ty_fill(M, x[1]);
            if (M->ty[x[1]].k == K_INT && M->ty[x[1]].w == 32) {   // a value a helper returned: in the trace; the rewrite's parameters counted
                tr_add(r, 5, 0, 0, tmp[0]);
                if (x[3] >= M->obound && len == 9) r->cov[V(x[8])[0] ? 0 : 1]++;
                if (x[3] >= M->obound && len == 8) { uint64_t i0 = V(x[6])[0], s0 = V(x[7])[0]; r->cov[s0 == 0 ? 2 : (i0 == 0 && s0 >= 1024) ? 4 : 3]++; }
            }
            i += len; continue; }
        }
        if (!has_res(op)) { if (op == 99) tr_add(r, 8, 0, 0, hash_ins(r, x, 0)); else if (op >= 227 && op <= 242) { M->bad = 1; M->bad_op = op; return 0; } i += len; continue; }
        uint32_t t = x[1], res = x[2], n = leafc(M, t); uint64_t *o = V(res); int w = comp_w(M, t);
        switch (op) {
        case 61: {   // OpLoad
            int kind; uint32_t var; uint64_t off; ptr_dec(r, V(x[3])[0], &kind, &var, &off);
            if (kind == 1 && (var >= M->bound || off + (n ? n : 1) > M->ls[var])) memset(o, 0, (n ? n : 1) * 8);   // outside the variable: 0 (both runs alike)
            else if (kind == 1) memcpy(o, r->loc + M->lb[var] + off, (n ? n : 1) * 8);
            else mread(r, kind, var, off, t, o);
            if (kind == 3) r->cov[off == r->sink ? 5 : 6]++;
            break; }
        case 65: case 66: {   // access chains
            int kind; uint32_t var; uint64_t off; ptr_dec(r, V(x[3])[0], &kind, &var, &off);
            uint32_t bt = M->w[M->def[x[3]] + 1]; ty_fill(M, bt); uint32_t T = M->ty[bt].a;
            for (uint32_t k = 4; k < len; k++) {
                ty_fill(M, T); ty_t *y = &M->ty[T]; uint32_t it = M->w[M->def[x[k]] + 1]; int64_t idx = sx(V(x[k])[0], comp_w(M, it));
                if (y->k == K_STRUCT) { leafc(M, T); off += kind == 1 ? y->ml[idx] : (y->mo ? y->mo[idx] : 0); T = y->mem[idx]; }
                else if (y->k == K_ARR || y->k == K_RTA) { off += kind == 1 ? (uint64_t)idx * leafc(M, y->a) : (uint64_t)idx * (y->stride ? y->stride : 4); T = y->a; }
                else if (y->k == K_VEC) { off += kind == 1 ? (uint64_t)idx : (uint64_t)idx * (comp_w(M, T) / 8); T = y->a; }
                else if (y->k == K_MAT && kind == 1) { off += (uint64_t)idx * leafc(M, y->a); T = y->a; }
                else { M->bad = 1; M->bad_op = op; return 0; }
            }
            o[0] = ptr_new(r, kind, var, off); break; }
        case 120: o[0] = ptr_new(r, 3, 0, V(x[3])[0]); break;   // OpConvertUToPtr
        case 117: { int kind; uint32_t var; uint64_t off; ptr_dec(r, V(x[3])[0], &kind, &var, &off); o[0] = kind == 3 ? off : hash_ins(r, x, 0); break; }
        case 124: bitcast(r, t, x[3], o); break;
        case 83: memcpy(o, V(x[3]), (n ? n : 1) * 8); break;
        case 80: { uint32_t q = 0; for (uint32_t k = 3; k < len; k++) { uint32_t lc = leafc(M, M->w[M->def[x[k]] + 1]); memcpy(o + q, V(x[k]), lc * 8); q += lc; } break; }
        case 81: case 82: {   // extract / insert
            uint32_t src = op == 81 ? x[3] : x[4], T = M->w[M->def[src] + 1], q = 0, first = op == 81 ? 4 : 5;
            for (uint32_t k = first; k < len; k++) { ty_fill(M, T); ty_t *y = &M->ty[T]; uint32_t ix = x[k];
                if (y->k == K_STRUCT) { leafc(M, T); q += y->ml[ix]; T = y->mem[ix]; } else { q += ix * leafc(M, y->a); T = y->a; } }
            uint32_t lc = leafc(M, T);
            if (op == 81) memcpy(o, V(src) + q, lc * 8); else { memcpy(o, V(src), n * 8); memcpy(o + q, V(x[3]), lc * 8); }
            break; }
        case 79: { uint32_t na = leafc(M, M->w[M->def[x[3]] + 1]); for (uint32_t c = 0; c + 5 < len; c++) { uint32_t s = x[5 + c]; o[c] = s == 0xffffffffu ? 0 : s < na ? V(x[3])[s] : V(x[4])[s - na]; } break; }
        case 169: { uint32_t cn = leafc(M, M->w[M->def[x[3]] + 1]); for (uint32_t c = 0; c < n; c++) o[c] = V(x[3])[cn > 1 ? c : 0] ? V(x[4])[c] : V(x[5])[c]; break; }
        case 168: o[0] = !V(x[3])[0]; for (uint32_t c = 1; c < n; c++) o[c] = !V(x[3])[c]; break;
        case 200: for (uint32_t c = 0; c < n; c++) o[c] = ~V(x[3])[c] & mask_w(w); break;
        case 126: for (uint32_t c = 0; c < n; c++) o[c] = (0 - V(x[3])[c]) & mask_w(w); break;
        case 113: case 114: { int sw2 = comp_w(M, M->w[M->def[x[3]] + 1]); for (uint32_t c = 0; c < n; c++) o[c] = (op == 114 ? (uint64_t)sx(V(x[3])[c], sw2) : V(x[3])[c]) & mask_w(w); break; }
        case 154: case 155: { uint32_t cn = leafc(M, M->w[M->def[x[3]] + 1]); uint64_t v = op == 155; for (uint32_t c = 0; c < cn; c++) v = op == 154 ? (v || V(x[3])[c]) : (v && V(x[3])[c]); o[0] = v; break; }
        case 12: { int done = 0; exact_ext(r, x, o, &done); if (!done) for (uint32_t c = 0; c < (n ? n : 1); c++) o[c] = hash_ins(r, x, c) & (w ? mask_w(w) : ~0ull); break; }
        default: {
            int ok = 0;
            if (len == 5 && ((op >= 128 && op <= 139 && op != 129 && op != 131 && op != 133 && op != 136) || (op >= 164 && op <= 179) || (op >= 194 && op <= 199))) {
                uint32_t at = M->w[M->def[x[3]] + 1]; int aw = comp_w(M, at); ty_fill(M, comp_t(M, at));
                if (M->ty[comp_t(M, at)].k == K_INT || M->ty[comp_t(M, at)].k == K_BOOL) {
                    for (uint32_t c = 0; c < (n ? n : 1); c++) { o[c] = ibin(op, V(x[3])[c] & mask_w(aw), V(x[4])[c] & mask_w(aw), aw, &ok); if (!ok) break; }
                    if (ok && w && w < 64 && (op < 164 || op > 179)) for (uint32_t c = 0; c < n; c++) o[c] &= mask_w(w);
                }
            }
            if (!ok) for (uint32_t c = 0; c < (n ? n : 1); c++) { ty_fill(M, comp_t(M, t)); uint64_t h = hash_ins(r, x, c);
                o[c] = M->ty[comp_t(M, t)].k == K_BOOL ? h & 1 : w && w < 64 ? h & mask_w(w) : h; }
        } }
        i += len;
    }
}
// One run of the module's entry point with the trial's memory; 0: the module has something the interpreter does not take
static void trial_setup(run_t *r, uint64_t seed) {
    r->seed = mix(seed); uint64_t h = mix(seed ^ 0x1234);
    r->N = 16 + (uint32_t)(h % 48);
    switch (seed % 4) {
    case 0: r->P0 = (uint32_t)(h >> 8) & 0x0fffff00; break;                                   // a window in the low 1 TiB
    case 1: r->P0 = ((uint32_t)(h >> 16) % 16) << 20 | (0x100000 - r->N / 2); break;          // across a 4 GiB line (a carry into the high word)
    case 2: r->P0 = 0xfff00000u - r->N / 2; break;                                            // at the page index's clamp (high word 4095)
    default: r->P0 = (uint32_t)(h >> 20) & 0xfffff; break;                                    // high word 0
    }
    memset(r->cov, 0, sizeof r->cov);
    r->sink = mix(seed ^ 0x51) & ~0xfffull; r->sorted = (seed / 4) % 2 == 0; r->ES = 4096u << (seed % 3);
}
static int run_module(run_t *r, uint64_t seed) {
    im_t *M = r->M;
    trial_setup(r, seed);
    memset(r->val, 0, M->nval * 8);
    map_clear(&r->buf); map_clear(&r->psb); r->np = 0; r->ntr = 0; r->steps = 0; r->stop = 0; r->cap_hit = 0;
    // constants and variables
    for (size_t i = 5; i < M->nw && (M->w[i] & 0xffff) != 54; i += LEN(i)) {
        uint32_t op = OPC(i), len = LEN(i); const uint32_t *x = M->w + i;
        if (op == 43 || op == 50) { uint32_t w = comp_w(M, x[1]); V(x[2])[0] = (w > 32 && len > 4 ? (uint64_t)x[3] | (uint64_t)x[4] << 32 : x[3]) & mask_w(w ? w : 32); }
        else if (op == 41 || op == 48) V(x[2])[0] = 1;
        else if (op == 42 || op == 49) V(x[2])[0] = 0;
        else if (op == 44 || op == 51) { uint32_t q = 0; for (uint32_t k = 3; k < len; k++) { uint32_t lc = leafc(M, M->w[M->def[x[k]] + 1]); memcpy(V(x[2]) + q, V(x[k]), lc * 8); q += lc; } }
        else if (op == 46) memset(V(x[2]), 0, (leafc(M, x[1]) ? leafc(M, x[1]) : 1) * 8);
        else if (op == 1) for (uint32_t c = 0; c < (leafc(M, x[1]) ? leafc(M, x[1]) : 1); c++) V(x[2])[c] = mix(x[2] ^ c);
        else if (op == 59) {
            uint32_t var = x[2], sc = x[3]; ty_fill(M, x[1]); uint32_t lc = leafc(M, M->ty[x[1]].a); if (!lc) lc = 1;
            if (M->vk[var] == 1) { V(var)[0] = enc_ptr(1, var, 0);
                for (uint32_t q = 0; q < lc; q++) r->loc[M->lb[var] + q] = sc == 1 ? biased(r, mix(r->seed ^ (uint64_t)var << 32 ^ q)) : len > 4 ? V(x[4])[q] : 0; }
            else V(var)[0] = enc_ptr(2, var, 0);
        }
        else if (op == 52) { M->bad = 1; M->bad_op = 52; return 0; }   // OpSpecConstantOp
    }
    uint64_t ret[64];
    exec_fn(r, M->entry, NULL, 0, ret, 0);
    if (M->bad) return 0;
    // the outputs at the end
    for (size_t i = 5; i < M->nw && (M->w[i] & 0xffff) != 54; i += LEN(i))
        if (OPC(i) == 59 && M->w[i + 3] == 3) { uint32_t var = M->w[i + 2]; ty_fill(M, M->w[i + 1]); uint32_t lc = leafc(M, M->ty[M->w[i + 1]].a); for (uint32_t q = 0; q < lc; q++) tr_add(r, 7, var, q, r->loc[M->lb[var] + q]); }
    return 1;
}
typedef struct { long trials, same, differ, skipped, unsupported; long cov[8]; } diff_t;
// The original and the rewritten module, run on the same trials: 1 equal, 0 a difference (printed), -1 not interpreted
static int differential(const uint32_t *a, size_t an, const uint32_t *b, size_t bn, int trials, uint64_t salt, diff_t *d, const char *tag, int quiet) {
    im_t A, B; int res = 1;
    if (!im_load(&A, a, an, a[3]) || !im_load(&B, b, bn, a[3])) { d->unsupported++; im_free(&A); im_free(&B); return -1; }
    find_tables(&A); find_tables(&B);
    run_t ra = {0}, rb = {0}; ra.M = &A; rb.M = &B;
    ra.val = calloc(A.nval, 8); ra.loc = calloc(A.nloc, 8); rb.val = calloc(B.nval, 8); rb.loc = calloc(B.nloc, 8);
    for (int t = 0; t < trials; t++) {
        uint64_t seed = salt * 1000003ull + (uint64_t)t;
        int oa = run_module(&ra, seed), ob = run_module(&rb, seed);
        if (!oa || !ob) { d->unsupported++; res = -1; if (!quiet) printf("  %s: not interpreted (opcode %u / %u)\n", tag, A.bad_op, B.bad_op); break; }
        if (ra.cap_hit || rb.cap_hit) { d->skipped++; continue; }
        d->trials++;
        for (int k = 0; k < 5; k++) d->cov[k] += rb.cov[k];
        d->cov[5] += ra.cov[5]; d->cov[6] += ra.cov[6];
        if (ra.ntr != rb.ntr || (ra.ntr && memcmp(ra.tr, rb.tr, ra.ntr * 8))) {
            d->differ++; res = 0;
            if (!quiet) { size_t j = 0; while (j < ra.ntr && j < rb.ntr && ra.tr[j] == rb.tr[j]) j++;
                printf("  %s: trial %d differs at trace word %zu of %zu / %zu (event %llu %llu %llx: %llx against %llx)\n", tag, t, j, ra.ntr, rb.ntr,
                       j / 4 * 4 < ra.ntr ? (unsigned long long)ra.tr[j / 4 * 4] : 0ull, j / 4 * 4 + 1 < ra.ntr ? (unsigned long long)ra.tr[j / 4 * 4 + 1] : 0ull,
                       j / 4 * 4 + 2 < ra.ntr ? (unsigned long long)ra.tr[j / 4 * 4 + 2] : 0ull, j < ra.ntr ? (unsigned long long)ra.tr[j] : 0ull, j < rb.ntr ? (unsigned long long)rb.tr[j] : 0ull); }
            if (d->differ > 3) break;
        } else d->same++;
    }
    free(ra.val); free(ra.loc); free(rb.val); free(rb.loc); free(ra.tr); free(rb.tr); free(ra.pool); free(rb.pool);
    free(ra.buf.k); free(ra.buf.v); free(ra.psb.k); free(ra.psb.v); free(rb.buf.k); free(rb.buf.v); free(rb.psb.k); free(rb.psb.v);
    im_free(&A); im_free(&B);
    return res;
}
static void cov_print(const char *what, const diff_t *d) {
    printf("t_android_gmem: %s: %ld trials equal, %ld differ, %ld stopped at the step limit, %ld not interpreted; rewritten calls: page index %ld in the lowest page / %ld in the "
           "highest, search %ld from the shared answer / %ld from a differing bit / %ld from the start; original loads %ld of the sink / %ld mapped\n", what, d->same, d->differ, d->skipped,
           d->unsupported, d->cov[0], d->cov[1], d->cov[2], d->cov[3], d->cov[4], d->cov[5], d->cov[6]);
}

// The rewritten module with the 3-word store `st` put back right before its k-th call of a rewritten helper (the negative control)
static uint32_t *insert_before_call(const uint32_t *w, size_t n, uint32_t obound, uint32_t rt, int k, const uint32_t *st, size_t *on) {
    uint32_t *o = malloc((n + 3) * 4); size_t j = 0; int seen = 0, done = 0;
    for (size_t i = 0; i < 5; i++) o[j++] = w[i];
    for (size_t i = 5; i < n; i += w[i] >> 16) {
        if (!done && (w[i] & 0xffff) == 57 && w[i + 3] >= obound && w[i + 1] == rt && seen++ == k) { memcpy(o + j, st, 12); j += 3; done = 1; }
        memcpy(o + j, w + i, (w[i] >> 16) * 4); j += w[i] >> 16;
    }
    *on = j; return o;
}
static const uint32_t *find_store(const uint32_t *w, size_t n, const uint32_t *notin, size_t nn) {   // the store _tw has and the main fixture does not
    for (size_t i = 5; i < n; i += w[i] >> 16) if ((w[i] & 0xffff) == 62 && (w[i] >> 16) == 3) {
        int found = 0; for (size_t j = 5; j < nn; j += notin[j] >> 16) if ((notin[j] & 0xffff) == 62 && notin[j + 1] == w[i + 1] && notin[j + 2] == w[i + 2]) found = 1;
        if (!found) return w + i;
    }
    return NULL;
}

int main(void) {
    char err[256];
    g_val = system("spirv-val --version > /dev/null 2>&1") == 0;
    // The fixtures: counts, validation, and execution
    struct { const uint32_t *w; size_t n; const char *tag; int merged, groups, single, unknown; } fx[] = {
        {GMEM_PIDX, sizeof GMEM_PIDX / 4, "pidx", 19, 5, 8, 1}, {GMEM_PIDX_TW, sizeof GMEM_PIDX_TW / 4, "pidx_tw", 15, 4, 12, 1},
        {GMEM_LOOP, sizeof GMEM_LOOP / 4, "loop", 25, 8, 2, 1}, {GMEM_LOOP_TW, sizeof GMEM_LOOP_TW / 4, "loop_tw", 25, 9, 2, 1}};
    uint32_t *rew[4] = {0}; size_t rn[4] = {0};
    for (int f = 0; f < 4; f++) {
        vka_gmem_stats st = {0};
        int r = vka_spv_gmem_merge(fx[f].w, fx[f].n, &rew[f], &rn[f], &st, err, sizeof err);
        CHECK(r == fx[f].merged && st.groups == fx[f].groups && st.left_single == fx[f].single && st.left_unknown == fx[f].unknown && st.calls == 28,
              "%s: %d merged in %d groups, %d single, %d unknown of %d calls (%s); want %d, %d, %d, %d of 28", fx[f].tag, r, st.groups, st.left_single, st.left_unknown, st.calls, err,
              fx[f].merged, fx[f].groups, fx[f].single, fx[f].unknown);
        if (r <= 0) continue;
        CHECK(validate(rew[f], rn[f], fx[f].tag), "%s: the rewritten module validates", fx[f].tag);
        diff_t d = {0};
        int e = differential(fx[f].w, fx[f].n, rew[f], rn[f], 2000, (uint64_t)f + 1, &d, fx[f].tag, 0);
        CHECK(e == 1 && d.trials >= 1900, "%s: original and rewritten run the same (%ld of %ld trials equal)", fx[f].tag, d.same, d.trials);
        char what[64]; snprintf(what, sizeof what, "fixture %s", fx[f].tag); cov_print(what, &d);
        if (f == 0) CHECK(d.cov[0] > 100 && d.cov[1] > 100 && d.cov[5] > 100 && d.cov[6] > 100, "%s: same-page, page-crossing, unmapped and mapped loads all met", fx[f].tag);
        if (f == 2) CHECK(d.cov[2] > 100 && d.cov[3] > 10 && d.cov[4] > 10 && d.cov[5] > 100 && d.cov[6] > 100, "%s: shared answers, differing answers, wraps, unmapped and mapped loads all met", fx[f].tag);
    }
    // The check of the check: the main fixture's rewrite with the table store of _tw put back between the third base's calls (after its rewrite merged across that
    // point) must differ from _tw itself: a stale lookup is seen
    for (int f = 0; f < 4; f += 2) if (rew[f]) {
        const uint32_t *st = find_store(fx[f + 1].w, fx[f + 1].n, fx[f].w, fx[f].n);
        CHECK(st != NULL, "%s: the _tw store found", fx[f].tag);
        if (!st) continue;
        // the store sits before the third call of base 5: calls of rewritten helpers in program order: base 0 (5), 1 (3), 2 (pidx: 0, loop: 4), 3 (pidx: 0, loop: 2),
        // 4 (3), then base 5's (48, 52, 56, 60): its third
        int k = f == 0 ? 5 + 3 + 3 + 2 : 5 + 3 + 4 + 2 + 3 + 2;
        uint32_t rt = 0; for (size_t i = 5; i < fx[f].n && !rt; i += fx[f].w[i] >> 16) if ((fx[f].w[i] & 0xffff) == 57) rt = fx[f].w[i + 1];
        size_t mn; uint32_t *mut = insert_before_call(rew[f], rn[f], fx[f].w[3], rt, k, st, &mn);
        diff_t d = {0};
        int e = differential(fx[f + 1].w, fx[f + 1].n, mut, mn, 400, 77, &d, "stale", 1);
        CHECK(e == 0 && d.differ > 0, "%s: a lookup kept across a table write is caught (%ld trials differ)", fx[f].tag, d.differ);
        printf("t_android_gmem: check of the check (%s): a lookup kept across a write to the table differs in %ld of %ld trials\n", fx[f].tag, d.differ, d.trials);
        free(mut);
    }
    for (int f = 0; f < 4; f++) free(rew[f]);
    // Not SPIR-V; a module without helpers
    { uint32_t *o = NULL; size_t on = 0; uint32_t junk[5] = {1, 2, 3, 4, 5};
      CHECK(vka_spv_gmem_merge(junk, 5, &o, &on, NULL, err, sizeof err) == -1 && !o, "not SPIR-V: refused"); }

    // A real library (DM_VK_GMEM_LIB)
    const char *lp = getenv("DM_VK_GMEM_LIB"), *outd = getenv("DM_VK_GMEM_OUT"), *tr = getenv("DM_VK_GMEM_TRIALS");
    int trials = tr ? atoi(tr) : 24;
    if (lp) {
        vka_shlib S; CHECK(vka_shlib_open(&S, lp, NULL, NULL, NULL, NULL, err, sizeof err), "%s: %s", lp, err);
        static const char *sn[8] = {"vertex", "tess control", "tess evaluation", "geometry", "pixel", "compute", "task", "mesh"};
        vka_gmem_stats st[8] = {{0}}; int mods[8] = {0}, rw[8] = {0}, bad = 0, inval = 0; diff_t dd = {0}; long eq_mods = 0, diff_mods = 0, unint = 0;
        for (uint32_t i = 0; S.h && i < S.h->entry_count; i++) {
            const dmsh_vk_entry *e = &S.ent[i]; int s = e->stage & 7;
            for (int v = 0; v < 8; v++) {
                const dmsh_vk_module *m = vka_shlib_module(&S, e, v); if (!m) continue;
                size_t nb; uint32_t *w = vka_shlib_spirv(&S, m, &nb, err, sizeof err); if (!w) { bad++; continue; }
                uint32_t *o = NULL; size_t on = 0;
                int r = vka_spv_gmem_merge(w, nb / 4, &o, &on, &st[s], err, sizeof err);
                mods[s]++;
                if (r < 0) { bad++; printf("  %016llx variant %d: %s\n", (unsigned long long)e->key, v, err); }
                if (r > 0) {
                    rw[s]++;
                    char tag[64]; snprintf(tag, sizeof tag, "%016llx_%d", (unsigned long long)e->key, v);
                    if (!validate(o, on, tag)) inval++;
                    if (outd) { char p[600]; snprintf(p, sizeof p, "%s/%s.spv", outd, tag); FILE *f = fopen(p, "wb"); if (f) { fwrite(o, 4, on, f); fclose(f); } }
                    if (trials > 0) {
                        int x = differential(w, nb / 4, o, on, trials, e->key ^ (uint64_t)v, &dd, tag, 0);
                        if (x == 1) eq_mods++; else if (x == 0) diff_mods++; else unint++;
                    }
                }
                free(o); free(w);
            }
        }
        for (int s = 0; s < 8; s++) if (mods[s])
            printf("t_android_gmem: %s: %d modules, %d rewritten: %d of %d loads merged in %d groups (helpers: %d page index, %d pool search, %d not taken); left: %d without "
                   "enough neighbours, %d not base + constant, %d calls of helpers not taken\n", sn[s], mods[s], rw[s], st[s].merged, st[s].calls, st[s].groups, st[s].helpers_pidx,
                   st[s].helpers_loop, st[s].helpers_left, st[s].left_single, st[s].left_unknown, st[s].left_helper_calls);
        char what[200]; snprintf(what, sizeof what, "%s: %ld rewritten modules executed the same, %ld differ, %ld not interpreted", lp, eq_mods, diff_mods, unint);
        cov_print(what, &dd);
        CHECK(!bad, "every module of the library is rewritten or left (%d failed)", bad);
        CHECK(!inval, "every rewritten module validates (%d did not)", inval);
        CHECK(!diff_mods && !dd.differ, "every rewritten module runs as the original (%ld differ)", diff_mods);
        vka_shlib_close(&S);
    }
    printf("t_android_gmem: %s%s\n", g_fail ? "FAILED" : "ok", g_val ? "" : " (no spirv-val: structure only)");
    return g_fail ? 1 : 0;
}
