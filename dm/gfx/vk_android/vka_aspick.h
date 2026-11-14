// Which job of the asynchronous pipeline queue (DM_VK_PSO_ASYNC, vka_pso.c as_run) is made next, as a pure function so t_android_aspick.c can run it.
// Today's order (DM_VK_PSO_ASYNC_NEED, default on): the job a draw was last skipped for (the newest need frame), the oldest of those. With DM_VK_PSO_CHEAP=1 the cheapest of
// those jobs (least predicted cost: the SPIR-V bytes of its stages) goes first, and the oldest of them takes its turn once it has waited DM_VK_PSO_FAIR_MS. Banana Native
// found that on Turnip a library compile grew with the shader's size (about 25-170 ms under 70 KB, 1.6-3.8 s for 88-130 KB), so in Ocarina of Time's market a few large
// shaders held hundreds of cheap ones and their draws back (their v0.3.11, 49fa341d: 334 pipelines in 18 s instead of 170 in 140 s). Plain C, no Vulkan
#pragma once
#include <stdint.h>

typedef struct { uint32_t need; uint64_t t0, cost; } vka_as_cand;   // a queued job: its need frame, when it was queued (ns), its predicted cost
// c: the candidates in queue (arrival) order, n >= 1. Returns the index to take
static inline int vka_as_pick(const vka_as_cand *c, int n, int need_on, int cheap_on, uint64_t now, uint64_t fair_ns) {
    int best = 0;
    if (need_on) for (int i = 1; i < n; i++) if ((int32_t)(c[i].need - c[best].need) > 0) best = i;   // (wrapping frame numbers)
    if (!cheap_on || now - c[best].t0 >= fair_ns) return best;   // the oldest of the most needed, in its turn
    int pick = best;
    for (int i = best + 1; i < n; i++) if ((!need_on || c[i].need == c[best].need) && c[i].cost < c[pick].cost) pick = i;   // (the first of least cost among equals)
    return pick;
}
