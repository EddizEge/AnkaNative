// What a run may keep in the pipeline cache file and the prewarm list (vka_pso.c), as pure functions so t_android_pcguard.c can run them. Plain C, no Vulkan.
//
// Turnip reads every object of a pipeline cache file when the cache is made (vkCreatePipelineCache: its pipeline_cache_import_ops deserialize each shader and upload it,
// tu_shader_deserialize -> tu_upload_shader -> tu_setup_pvtmem), so a cached program's private memory sizes the device's one per-fiber private memory buffer at the start
// of every later run, before any pipeline is made and whether or not the prewarm list runs. The movie library's NDVE network (vka_draw.c) is the program that does it:
// one of its shaders needs 64 KiB a fiber, and the buffer grows to 64 KiB x 4,096 fibers x 6 SPs = 1,536 MiB on the Adreno 830, against 192 MiB without it
// (session-2026-11-14 memu-line-2.md). The replay makes the network only when DM_VK_NDVE_DIRECT=0 or when no colour conversion was matched before a network dispatch
// (before 2026-11-15, a DM_VK_DISPATCH_SKIP run that skipped the conversion did that), and since 2026-11-15 a network pipeline is made outside the pipeline cache and
// kept out of the prewarm list (vka_pso.c cs_build); older files may still hold it (pc_drop_ndve)
#pragma once
#include <stdint.h>
#include <stdlib.h>

// The NDVE network's shader keys (NintendoSDK movie, Movie/Ndve/Ndve-Nvn2-vid_seq5.dat): the colour conversion before it, its 9 dispatches and its last pass
#define VKA_NDVE_CONVERT 0x49dff010b9832251ull
#define VKA_NDVE_FINAL 0x4f41ccf24f163e0dull
static const uint64_t VKA_NDVE_NET[] = {0xba079f68ae288dbaull, 0xa8b4fb48fee0ccf8ull, 0xe2eebd7ba18fe35full, 0xe6be82d835fbe4d3ull, 0x9442573ee4ace011ull,
                                        0x0a56002cdc7e1fceull, 0xd15370a382630d4eull, 0x7f7ff0f36b54d90full, 0x5a7d69b067a7f011ull};
// A key of the network or its last pass (not the conversion, which every movie frame runs)
static inline int vka_ndve_net_key(uint64_t key) {
    if (key == VKA_NDVE_FINAL) return 1;
    for (size_t i = 0; i < sizeof VKA_NDVE_NET / sizeof *VKA_NDVE_NET; i++) if (key == VKA_NDVE_NET[i]) return 1;
    return 0;
}
// The switch that keeps this run's pipelines out of the pipeline cache file and the prewarm list, NULL when the run may write them. dispatch_skip: DM_VK_DISPATCH_SKIP as
// set (NULL or empty: unset). Such a run is a diagnosis: what it makes is not what the user's runs make, and a program only it makes stays in the file for every later
// start. (DM_VK_NDVE_DIRECT=0 is a user's switch, not a diagnosis: its run keeps everything but the network's pipelines, which cs_build keeps out)
static inline const char *vka_pc_keep_blocker(const char *dispatch_skip) {
    if (dispatch_skip && *dispatch_skip) return "DM_VK_DISPATCH_SKIP";
    return NULL;
}
