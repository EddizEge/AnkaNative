// Mesh draws through compute (vka_memu.c, vka_pso.c): the compute forms of the library's task and mesh shaders made ahead of their first draw, on a thread of their own
// (vka_pso.c ah_main) that starts one only while the thread making the forms draws wait for (mj_main) is idle, as pure functions so t_android_memuahead.c can run them. Plain C, no Vulkan.
//
// A start with an empty pipeline cache (the first after an install or a driver change) met Ocarina of Time's Kokiri Forest with none of its 52 forms made: 31.8 s of
// compiling on the phone, every mesh-drawn object (foliage, canopies, Link's door) held by the frame rule (vka_memu_rule.h) for 30-45 s. A form's pipeline depends only on
// the library's module, the device's depth mode (a mesh module with the GL depth constant), the device (subgroup size, texture-use feedback) and the layout made from the
// module's own bindings, so every form can be made from the library alone, the same pipeline (the same entry of vka_pso.c's compute map, the same pipeline cache
// entry) a draw would make. The order, after what the game itself waits for (its draws' forms, then the prewarm list's: the forms earlier runs drew):
//   1. the task / mesh shaders of programs the game has set up (nvnProgramSetShaders, vka_shader.c mtl_prog_prefetch), in the order it set them up;
//   2. every other task / mesh shader of the library, in the library's order.
// The NDVE movie network is never made here (vka_pcguard.h: a cached network program sized the device's private memory to 1.5 GiB at every start): its shaders are
// compute shaders, not task or mesh ones, and vka_ahead_candidate refuses their keys whatever stage a lookup reports
#pragma once
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "vka_pcguard.h"

enum { VKA_AHEAD_TS = 6, VKA_AHEAD_MS = 7 };   // DMSH_VK_STAGE_TS / _MS (vka_pso.c checks they are the same)
enum { VKA_AHEAD_NONE, VKA_AHEAD_PROGRAM, VKA_AHEAD_LIBRARY, VKA_AHEAD_MORE };   // where a key came from (MORE: none yet, vka_ahead_next's budget spent)
enum { VKA_AHEAD_STAGE_UNKNOWN = -2 };   // a key's stage when the library is not loaded yet (-1: not in the library)
enum { VKA_AHEAD_FIFO_MAX = 1 << 16 };   // program keys waiting (deduplicated: the library has fewer shaders than this); past it they are counted, and the library walk takes them

typedef struct {
    uint64_t *fifo; uint32_t cap, head, n, dropped;   // keys of programs the game set up, not yet taken (a ring)
    uint64_t *seen; uint32_t seen_cap, seen_n;        // every key noted or handed out once (a key is made ahead at most once)
    uint32_t lib_next;                                 // the library walk's next entry
    int stop;                                          // the queue ended (switch off, no library, the run's end): nothing more is handed out
} vka_ahead;

// A key the queue may make ahead: a task or mesh shader's, never the NDVE network's
static inline int vka_ahead_candidate(int stage, uint64_t key) { return (stage == VKA_AHEAD_TS || stage == VKA_AHEAD_MS) && key && !vka_ndve_net_key(key); }

static inline int vka_ahead_seen_has(const vka_ahead *a, uint64_t key) {
    if (!a->seen_cap) return 0;
    for (uint32_t j = (uint32_t)(key * 0x9E3779B97F4A7C15ull >> 40) & (a->seen_cap - 1); a->seen[j]; j = (j + 1) & (a->seen_cap - 1)) if (a->seen[j] == key) return 1;
    return 0;
}
// 1 = key was not in the set (now it is); 0 = it was, or no memory (then it is treated as seen: never made ahead twice)
static inline int vka_ahead_seen_add(vka_ahead *a, uint64_t key) {
    if (!key) return 0;
    if (2 * (a->seen_n + 1) > a->seen_cap) {
        uint32_t nc = a->seen_cap ? 2 * a->seen_cap : 1024; uint64_t *n = (uint64_t *)calloc(nc, sizeof *n);
        if (!n) return 0;
        for (uint32_t i = 0; i < a->seen_cap; i++) if (a->seen[i]) { uint32_t j = (uint32_t)(a->seen[i] * 0x9E3779B97F4A7C15ull >> 40) & (nc - 1); while (n[j]) j = (j + 1) & (nc - 1); n[j] = a->seen[i]; }
        free(a->seen); a->seen = n; a->seen_cap = nc;
    }
    uint32_t j = (uint32_t)(key * 0x9E3779B97F4A7C15ull >> 40) & (a->seen_cap - 1);
    for (; a->seen[j]; j = (j + 1) & (a->seen_cap - 1)) if (a->seen[j] == key) return 0;
    a->seen[j] = key; a->seen_n++; return 1;
}
// A shader key of a program the game set up: its library stage, or VKA_AHEAD_STAGE_UNKNOWN before the library is loaded (then checked when it is taken). 1 = queued
static inline int vka_ahead_note(vka_ahead *a, uint64_t key, int stage) {
    if (a->stop || !key || vka_ndve_net_key(key) || (stage != VKA_AHEAD_STAGE_UNKNOWN && !vka_ahead_candidate(stage, key))) return 0;
    if (vka_ahead_seen_has(a, key)) return 0;
    if (a->n == a->cap) {
        if (a->cap >= VKA_AHEAD_FIFO_MAX) { a->dropped++; return 0; }
        uint32_t nc = a->cap ? 2 * a->cap : 256; uint64_t *f = (uint64_t *)malloc((size_t)nc * sizeof *f);
        if (!f) { a->dropped++; return 0; }
        for (uint32_t i = 0; i < a->n; i++) f[i] = a->fifo[(a->head + i) % a->cap];
        free(a->fifo); a->fifo = f; a->cap = nc; a->head = 0;
    }
    if (!vka_ahead_seen_add(a, key)) return 0;
    a->fifo[(a->head + a->n) % a->cap] = key; a->n++;
    return 1;
}
// The next key to make ahead, 0 = none left (or stopped). stage_of: a key's library stage, -1 not in the library; lib_n / lib_key: the library's entries in its order.
// *src: VKA_AHEAD_PROGRAM or VKA_AHEAD_LIBRARY; VKA_AHEAD_MORE (with 0) when budget keys were looked at and none was taken: call again (the caller holds a lock the
// game's thread takes at every nvnProgramSetShaders, and up to VKA_AHEAD_FIFO_MAX keys noted before the library was loaded may wait here)
typedef int (*vka_ahead_stage_fn)(void *ctx, uint64_t key);
typedef uint64_t (*vka_ahead_lib_fn)(void *ctx, uint32_t i);
static inline uint64_t vka_ahead_next(vka_ahead *a, void *ctx, vka_ahead_stage_fn stage_of, uint32_t lib_n, vka_ahead_lib_fn lib_key, uint32_t budget, int *src) {
    *src = VKA_AHEAD_NONE;
    if (a->stop) return 0;
    for (; a->n; budget--) {
        if (!budget) { *src = VKA_AHEAD_MORE; return 0; }
        uint64_t k = a->fifo[a->head]; a->head = (a->head + 1) % a->cap; a->n--;
        if (vka_ahead_candidate(stage_of(ctx, k), k)) { *src = VKA_AHEAD_PROGRAM; return k; }
    }
    for (; a->lib_next < lib_n; budget--) {
        if (!budget) { *src = VKA_AHEAD_MORE; return 0; }
        uint64_t k = lib_key(ctx, a->lib_next++);
        if (vka_ahead_candidate(stage_of(ctx, k), k) && vka_ahead_seen_add(a, k)) { *src = VKA_AHEAD_LIBRARY; return k; }
    }
    return 0;
}
// Whether the thread may start a form ahead now; VKA_AHEAD_GO when it may, else why not. The queue ends for good when the device is lost (lost: vka_hung; nothing made
// after it is of use, and every try would fail). The game's own requests first: a form a draw waits for or one the prewarm list queued (jobs: either queue not empty, or
// the mesh pipeline thread making one: the forms made ahead have their own thread, so a draw never waits behind one, and none is started beside the game's), a graphics
// pipeline whose draws are skipped until it is made (async_depth: DM_VK_PSO_ASYNC's queue). Then the app in the background (paused) and memory: a form's compile took
// up to 842 MB on the PC (Turnip on drm-shim, Ocarina of Time's heaviest with the raw global memory merge), so none starts while MemAvailable (avail_mib; -1 = not read)
// is under floor_mib
enum { VKA_AHEAD_GO, VKA_AHEAD_STOPPED, VKA_AHEAD_JOBS, VKA_AHEAD_ASYNC, VKA_AHEAD_PAUSED, VKA_AHEAD_MEMORY, VKA_AHEAD_LOST };
static inline int vka_ahead_turn(const vka_ahead *a, int lost, int jobs, int async_depth, int paused, long avail_mib, int floor_mib) {
    if (a->stop) return VKA_AHEAD_STOPPED;
    if (lost) return VKA_AHEAD_LOST;
    if (jobs) return VKA_AHEAD_JOBS;
    if (async_depth > 0) return VKA_AHEAD_ASYNC;
    if (paused) return VKA_AHEAD_PAUSED;
    if (avail_mib < 0 || avail_mib < floor_mib) return VKA_AHEAD_MEMORY;
    return VKA_AHEAD_GO;
}
// The largest Private or Function variable of a module, in bytes (an array whole; its length a constant or a specialization constant's default), 0 = none or not
// readable. The translator keeps a shader's local memory in such an array, and Turnip puts an array indexed at run time in private memory (scratch): one buffer for the
// whole device, per fiber the largest program's need rounded up to a power of two, times 4,096 fibers and 6 SPs on the Adreno 830, never shrunk, and made again at every
// start for each program the pipeline cache file holds (vka_pcguard.h). Of Ocarina of Time's 515 forms the 10 with such an array (7,472-9,264 bytes) take a 384 MiB
// buffer; the other 505 (their largest variable 12 bytes) take at most 96 MiB (their spills), the 52 of Kokiri Forest 96 MiB (Anka Turnip on drm-shim, 2026-11-15).
// This reads arrays only, not register spills: with the raw global memory merge (vka_gmem.c, on by default) one of the 505, 626fbf48041cb82b, spills 8 KiB a fiber and
// takes a 192 MiB buffer. That form is one Ocarina of Time draws (perf-gmem: among the 81 forms a long play uses), so a cache of a long play holds it anyway; made
// ahead it is there from the first start on (192 MiB of private memory at every start, accepted: docs/reference/shaders.md)
static inline uint64_t vka_ahead_private_bytes(const uint32_t *w, size_t n) {
    if (!w || n < 5 || w[0] != 0x07230203u || w[3] > (1u << 22)) return 0;
    uint32_t bound = w[3]; uint64_t *sz = (uint64_t *)calloc(bound, sizeof *sz); uint32_t *ptr = (uint32_t *)calloc(bound, sizeof *ptr), *cst = (uint32_t *)calloc(bound, sizeof *cst);
    uint64_t best = 0;
    if (!sz || !ptr || !cst) { free(sz); free(ptr); free(cst); return 0; }
    for (size_t i = 5; i < n;) {
        uint32_t wc = w[i] >> 16, op = w[i] & 0xffff;
        if (!wc || i + wc > n) break;
        const uint32_t *x = w + i; uint32_t id = wc > 1 ? x[1] : 0;
        if (op == 20 && wc >= 2 && id < bound) sz[id] = 4;                                                    // OpTypeBool
        else if ((op == 21 || op == 22) && wc >= 3 && id < bound) sz[id] = x[2] / 8;                           // OpTypeInt, OpTypeFloat
        else if ((op == 23 || op == 24) && wc >= 4 && id < bound && x[2] < bound) sz[id] = sz[x[2]] * x[3];   // OpTypeVector, OpTypeMatrix
        else if (op == 28 && wc >= 4 && id < bound && x[2] < bound && x[3] < bound) sz[id] = sz[x[2]] * cst[x[3]];   // OpTypeArray
        else if (op == 30 && wc >= 2 && id < bound) { uint64_t t = 0; for (uint32_t m = 2; m < wc; m++) if (x[m] < bound) t += sz[x[m]]; sz[id] = t; }   // OpTypeStruct
        else if (op == 32 && wc >= 4 && id < bound) ptr[id] = x[3] < bound ? x[3] : 0;                         // OpTypePointer
        else if ((op == 43 || op == 50) && wc >= 4 && x[2] < bound) cst[x[2]] = x[3];                         // OpConstant, OpSpecConstant (its default)
        else if (op == 59 && wc >= 4 && (x[3] == 6 || x[3] == 7) && x[1] < bound && ptr[x[1]] && sz[ptr[x[1]]] > best) best = sz[ptr[x[1]]];   // OpVariable Private / Function
        i += wc;
    }
    free(sz); free(ptr); free(cst);
    return best;
}
// A form whose largest such variable is over this is not made ahead (left to its draw, as before): making it would grow the device's private memory for the process
// and, once in the pipeline cache file, at every start, whether or not the game ever draws it
enum { VKA_AHEAD_PRIVATE_MAX = 1024 };
static inline void vka_ahead_free(vka_ahead *a) { free(a->fifo); free(a->seen); memset(a, 0, sizeof *a); }
