// Mesh draws through compute: the queue of compute forms made ahead (dm/gfx/vk_android/vka_memu_ahead.h; vka_pso.c mj_main, ah_make, vka_memu_ahead_note): its order
// (programs the game set up first, then the library's order, each key once), when it may start a form (the game's own jobs, the asynchronous graphics queue, the app in
// the background, MemAvailable), its stop, and the NDVE movie network never handed out
// the local memory guard (vka_ahead_private_bytes) on hand-made modules; with DM_VK_MEMU_LIB=<dmsh_vk.lib> on every task and mesh module of that library (the forms it
// leaves to their draw listed)
#include <stdio.h>
#include <stdlib.h>
#include "../../gfx/vk_android/vka_memu_ahead.h"
#include "../../gfx/vk_android/vka_shlib.h"
#include "../../gfx/nvn/dmsh_abi_vk.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
_Static_assert(VKA_AHEAD_TS == DMSH_VK_STAGE_TS && VKA_AHEAD_MS == DMSH_VK_STAGE_MS, "the header's stages are the ABI's");

// A small library: entries in its order (keys, stages), plus the NDVE network's shaders as the compute shaders they are
typedef struct { uint64_t key; int stage; } ent;
static const ent LIB[] = {
    {0x1000, DMSH_VK_STAGE_VS}, {0x1001, DMSH_VK_STAGE_MS}, {0x1002, DMSH_VK_STAGE_FS}, {0x1003, DMSH_VK_STAGE_TS}, {0x1004, DMSH_VK_STAGE_MS},
    {0xba079f68ae288dbaull, DMSH_VK_STAGE_CS}, {0xd15370a382630d4eull, DMSH_VK_STAGE_CS}, {0x4f41ccf24f163e0dull, DMSH_VK_STAGE_CS}, {0x49dff010b9832251ull, DMSH_VK_STAGE_CS},
    {0x1005, DMSH_VK_STAGE_MS}, {0x1006, DMSH_VK_STAGE_CS},
};
enum { NLIB = sizeof LIB / sizeof LIB[0] };
static int g_lie;   // 1: the lookup says the network's keys are mesh shaders (a damaged or hostile library): the queue must still refuse them
static int stage_of(void *c, uint64_t key) {
    (void)c;
    if (g_lie && vka_ndve_net_key(key)) return DMSH_VK_STAGE_MS;
    for (int i = 0; i < NLIB; i++) if (LIB[i].key == key) return LIB[i].stage;
    return -1;
}
static uint64_t lib_key(void *c, uint32_t i) { (void)c; return i < NLIB ? LIB[i].key : 0; }
static int drain(vka_ahead *a, uint64_t *out, int *src, int max) {
    int n = 0, s; uint64_t k;
    while (n < max && (k = vka_ahead_next(a, NULL, stage_of, NLIB, lib_key, &s))) { out[n] = k; src[n] = s; n++; }
    return n;
}

int main(void) {
    uint64_t out[64]; int src[64], n;
    // Order: programs the game set up first (in its order, the task / mesh keys among their stages; unknown stages checked when taken), then the library's order, each once
    {
        vka_ahead a = {0};
        CHECK(vka_ahead_note(&a, 0x1004, VKA_AHEAD_STAGE_UNKNOWN) == 1, "a key noted before the library is loaded");
        CHECK(vka_ahead_note(&a, 0x1000, VKA_AHEAD_STAGE_UNKNOWN) == 1, "a vertex key noted before the library is loaded (dropped when taken)");
        CHECK(vka_ahead_note(&a, 0x1002, DMSH_VK_STAGE_FS) == 0, "a pixel shader's key with its stage known is not noted");
        CHECK(vka_ahead_note(&a, 0x1003, DMSH_VK_STAGE_TS) == 1, "a task shader of a program");
        CHECK(vka_ahead_note(&a, 0x1004, DMSH_VK_STAGE_MS) == 0, "a key noted twice is queued once");
        CHECK(vka_ahead_note(&a, 0x9999, -1) == 0, "a key not in the library is not noted");
        n = drain(&a, out, src, 64);
        CHECK(n == 4, "four forms: 0x1004, 0x1003 from programs, then 0x1001, 0x1005 from the library (got %d)", n);
        if (n == 4) {
            CHECK(out[0] == 0x1004 && src[0] == VKA_AHEAD_PROGRAM, "the first program's mesh shader first");
            CHECK(out[1] == 0x1003 && src[1] == VKA_AHEAD_PROGRAM, "then the task shader, in the order set up");
            CHECK(out[2] == 0x1001 && src[2] == VKA_AHEAD_LIBRARY, "then the library's order");
            CHECK(out[3] == 0x1005 && src[3] == VKA_AHEAD_LIBRARY, "the library's last mesh shader; 0x1003 and 0x1004 not again");
        }
        CHECK(vka_ahead_next(&a, NULL, stage_of, NLIB, lib_key, &src[0]) == 0 && src[0] == VKA_AHEAD_NONE, "nothing left");
        CHECK(vka_ahead_note(&a, 0x1001, DMSH_VK_STAGE_MS) == 0, "a key handed out by the library walk is not noted again");
        CHECK(vka_ahead_next(&a, NULL, stage_of, NLIB, lib_key, &src[0]) == 0, "still nothing");
        vka_ahead_free(&a);
    }
    // The guard: the NDVE network is never handed out, whether noted by a program, met in the library or reported as a mesh shader by the lookup
    {
        vka_ahead a = {0};
        for (size_t i = 0; i < sizeof VKA_NDVE_NET / sizeof *VKA_NDVE_NET; i++) CHECK(vka_ahead_note(&a, VKA_NDVE_NET[i], VKA_AHEAD_STAGE_UNKNOWN) == 0, "network key %zu not noted", i);
        CHECK(vka_ahead_note(&a, VKA_NDVE_FINAL, DMSH_VK_STAGE_MS) == 0, "the network's last pass not noted, whatever the stage given");
        CHECK(!vka_ahead_candidate(DMSH_VK_STAGE_MS, VKA_NDVE_NET[0]) && !vka_ahead_candidate(DMSH_VK_STAGE_TS, VKA_NDVE_FINAL), "never a candidate");
        CHECK(!vka_ahead_candidate(DMSH_VK_STAGE_CS, 0x1006) && !vka_ahead_candidate(DMSH_VK_STAGE_VS, 0x1000) && !vka_ahead_candidate(DMSH_VK_STAGE_MS, 0), "only task / mesh keys");
        g_lie = 1;
        n = drain(&a, out, src, 64);
        int net = 0; for (int i = 0; i < n; i++) if (vka_ndve_net_key(out[i]) || out[i] == VKA_NDVE_CONVERT) net++;
        CHECK(net == 0, "no network key handed out (%d)", net);
        CHECK(n == 4, "the library's four task / mesh shaders (got %d)", n);
        g_lie = 0;
        vka_ahead_free(&a);
    }
    // Stop: nothing noted or handed out after it
    {
        vka_ahead a = {0};
        vka_ahead_note(&a, 0x1001, DMSH_VK_STAGE_MS);
        a.stop = 1;
        CHECK(vka_ahead_next(&a, NULL, stage_of, NLIB, lib_key, &src[0]) == 0, "stopped: none handed out");
        CHECK(vka_ahead_note(&a, 0x1004, DMSH_VK_STAGE_MS) == 0, "stopped: none noted");
        CHECK(vka_ahead_turn(&a, 0, 0, 0, 8000, 2048) == VKA_AHEAD_STOPPED, "stopped: no turn");
        vka_ahead_free(&a);
        CHECK(a.fifo == NULL && a.seen == NULL && a.n == 0, "freed");
    }
    // Priority: the game's own requests first (a form a draw or the prewarm list waits for, a graphics pipeline a draw waits for), then the app in front, then memory
    {
        vka_ahead a = {0};
        CHECK(vka_ahead_turn(&a, 1, 0, 0, 8000, 2048) == VKA_AHEAD_JOBS, "a job waiting: it goes first");
        CHECK(vka_ahead_turn(&a, 1, 5, 1, 10, 2048) == VKA_AHEAD_JOBS, "jobs before anything else");
        CHECK(vka_ahead_turn(&a, 0, 3, 0, 8000, 2048) == VKA_AHEAD_ASYNC, "a graphics pipeline a draw waits for: none ahead");
        CHECK(vka_ahead_turn(&a, 0, 0, 1, 8000, 2048) == VKA_AHEAD_PAUSED, "the app in the background: none");
        CHECK(vka_ahead_turn(&a, 0, 0, 0, 2047, 2048) == VKA_AHEAD_MEMORY, "under the floor: none");
        CHECK(vka_ahead_turn(&a, 0, 0, 0, -1, 2048) == VKA_AHEAD_MEMORY, "MemAvailable not read: none");
        CHECK(vka_ahead_turn(&a, 0, 0, 0, 2048, 2048) == VKA_AHEAD_GO, "at the floor: one may start");
        CHECK(vka_ahead_turn(&a, 0, 0, 0, 900, 1) == VKA_AHEAD_GO, "a floor of 1 MiB lets it run at 900");
    }
    // A long list of program keys: deduplicated, the ring grows, past VKA_AHEAD_FIFO_MAX they are counted and left to the library walk
    {
        vka_ahead a = {0}; uint32_t q = 0;
        for (uint64_t k = 1; k <= VKA_AHEAD_FIFO_MAX + 10; k++) q += (uint32_t)vka_ahead_note(&a, 0x100000 + k, VKA_AHEAD_STAGE_UNKNOWN);
        for (uint64_t k = 1; k <= 100; k++) q += (uint32_t)vka_ahead_note(&a, 0x100000 + k, VKA_AHEAD_STAGE_UNKNOWN);
        CHECK(q == VKA_AHEAD_FIFO_MAX, "the ring holds %d keys (queued %u)", VKA_AHEAD_FIFO_MAX, q);
        CHECK(a.dropped == 10, "10 past it counted (%u)", a.dropped);
        n = drain(&a, out, src, 64);   // (none of them is in the library: all dropped when taken; then the library)
        CHECK(n == 4 && src[0] == VKA_AHEAD_LIBRARY, "keys not in the library dropped when taken, the library's four after (got %d)", n);
        vka_ahead_free(&a);
    }
    // The local memory guard: the largest Private / Function variable, arrays whole (a constant length, a specialization constant's default), structs summed
    {
        // ids: 1 uint, 2 float, 3 vec4, 4 const 1920, 5 uint[1920], 6 ptr Private uint[1920], 7 var, 8 ptr Function vec4, 9 var, 10 spec const 300, 11 float[300],
        // 12 struct {vec4, float[300]}, 13 ptr Function struct, 14 var, 15 ptr Uniform uint[1920], 16 var (not counted)
        uint32_t m[] = {0x07230203, 0x10000, 0, 17, 0,
            (4u << 16) | 21, 1, 32, 0, (3u << 16) | 22, 2, 32, (4u << 16) | 23, 3, 2, 4, (4u << 16) | 43, 1, 4, 1920, (4u << 16) | 28, 5, 1, 4,
            (4u << 16) | 32, 6, 6, 5, (4u << 16) | 59, 6, 7, 6, (4u << 16) | 32, 8, 7, 3, (4u << 16) | 59, 8, 9, 7,
            (4u << 16) | 50, 1, 10, 300, (4u << 16) | 28, 11, 2, 10, (4u << 16) | 30, 12, 3, 11, (4u << 16) | 32, 13, 7, 12, (4u << 16) | 59, 13, 14, 7,
            (4u << 16) | 32, 15, 2, 5, (4u << 16) | 59, 15, 16, 2};
        CHECK(vka_ahead_private_bytes(m, sizeof m / 4) == 7680, "the Private uint[1920] is the largest: 7,680 bytes (got %llu)", (unsigned long long)vka_ahead_private_bytes(m, sizeof m / 4));
        m[19] = 16;   // uint[16]: 64 bytes; then the Function struct {vec4, float[300]} is the largest, 1,216 bytes
        CHECK(vka_ahead_private_bytes(m, sizeof m / 4) == 1216, "the struct of a vec4 and float[300] (a specialization constant's default length): 1,216 bytes");
        CHECK(vka_ahead_private_bytes(m, 3) == 0 && vka_ahead_private_bytes(NULL, 0) == 0, "no module: 0");
        m[0] = 0; CHECK(vka_ahead_private_bytes(m, sizeof m / 4) == 0, "not SPIR-V: 0");
        uint32_t bad[] = {0x07230203, 0x10000, 0, 8, 0, (9u << 16) | 21, 1, 32};   // an instruction past the end
        CHECK(vka_ahead_private_bytes(bad, sizeof bad / 4) == 0, "a truncated module: 0, no read past it");
    }
    const char *lib = getenv("DM_VK_MEMU_LIB");
    if (lib && *lib) {
        vka_shlib S; char err[256];
        if (!vka_shlib_open(&S, lib, NULL, NULL, NULL, NULL, err, sizeof err)) { CHECK(0, "DM_VK_MEMU_LIB %s: %s", lib, err); }
        else {
            unsigned nt = 0, nm = 0, big = 0; uint64_t most = 0, most_small = 0;
            for (uint32_t i = 0; i < S.h->entry_count; i++) {
                const dmsh_vk_entry *e = &S.ent[i];
                if (!vka_ahead_candidate(e->stage, e->key)) continue;
                if (e->stage == DMSH_VK_STAGE_TS) nt++; else nm++;
                const dmsh_vk_module *md = vka_shlib_module(&S, e, DMSH_VK_MOD_BASE); size_t nb = 0;
                uint32_t *w = md ? vka_shlib_spirv(&S, md, &nb, err, sizeof err) : NULL;
                CHECK(w != NULL, "%016llx: no base words", (unsigned long long)e->key);
                uint64_t pv = vka_ahead_private_bytes(w, nb / 4); free(w);
                if (pv > most) most = pv;
                if (pv > VKA_AHEAD_PRIVATE_MAX) { big++; printf("  %016llx (%s): a %llu-byte local memory array: left to its draw\n", (unsigned long long)e->key, e->stage == DMSH_VK_STAGE_TS ? "task" : "mesh", (unsigned long long)pv); }
                else if (pv > most_small) most_small = pv;
            }
            printf("memuahead: %s: %u task and %u mesh shaders, %u left to their draw (the largest array %llu bytes; of the rest %llu)\n", lib, nt, nm, big, (unsigned long long)most, (unsigned long long)most_small);
            CHECK(nt + nm > 0, "the library has task / mesh shaders");
            vka_shlib_close(&S);
        }
    }
    printf("memuahead: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
