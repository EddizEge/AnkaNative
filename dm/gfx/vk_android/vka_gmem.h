// Raw global memory loads: one page lookup or pool search for a group of loads from one base address (vka_gmem.c). Plain C, no Vulkan: unit-tested on the PC by
// dm/host/tests/t_android_gmem.c, which also executes the original and the rewritten code on the same memory and tables.
#pragma once
#include <stddef.h>
#include <stdint.h>

// The rewrite's version: a change to what vka_spv_gmem_merge writes for a module takes the next number, so the pipeline cache file of the earlier one (its name carries
// this, vka_pcguard.h vka_pc_default_name) is no longer read (1: 2026-11-15, the first)
#define VKA_GMEM_PASS 1

typedef struct {
    int helpers_pidx, helpers_loop;   // load helpers taken: the page index form (graphics, mesh, task) and the pool search loop form (compute)
    int helpers_left;                 // raw global memory helpers not taken: stores, or a shape this pass does not prove (their calls stay as they are)
    int calls;                        // calls of the helpers taken
    int merged;                       // of those, calls given their group's lookups
    int groups;                       // groups made (each: two lookups or searches, at its lowest and highest address)
    int left_single;                  // calls left as they are: too few calls of their base within 4 KiB in a block with no write between
    int left_unknown;                 // calls left as they are: the address words are not base + constant in the translator's form within the block, or are in
                                      // variables another pointer could write (a copy, an access chain, a parameter, another call's argument)
    int left_helper_calls;            // calls of the helpers not taken
} vka_gmem_stats;

// The module w (nw words) with each group of raw global memory loads from one base address (base + constant offsets within 4 KiB, one block, no memory write, barrier
// or other call between) given two lookups at the group's lowest and highest address, which every load of the group then takes its page's answer from. → number of
// calls merged: > 0 with *out malloc'd (*out_nw words); 0 nothing to merge (*out NULL; st says why); -1 with the reason in err. st (may be NULL) is added to
int vka_spv_gmem_merge(const uint32_t *w, size_t nw, uint32_t **out, size_t *out_nw, vka_gmem_stats *st, char *err, size_t errn);
