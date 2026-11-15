// What a run keeps in the pipeline cache file and the prewarm list (dm/gfx/vk_android/vka_pcguard.h; vka_pso.c pc_nokeep, pc_drop_ndve, pw_load): the NDVE network's
// keys, and the switches that keep a run's pipelines out of the files
#include <stdio.h>
#include <string.h>
#include "../../gfx/vk_android/vka_pcguard.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

int main(void) {
    // The ten keys a DM_VK_DISPATCH_SKIP=0 run saved into the phone's prewarm list (session-2026-11-14 s3-cache, records 2007-2016), and none of the others
    static const uint64_t s3[] = {0xba079f68ae288dbaull, 0xa8b4fb48fee0ccf8ull, 0xe2eebd7ba18fe35full, 0xe6be82d835fbe4d3ull, 0x9442573ee4ace011ull,
                                  0x0a56002cdc7e1fceull, 0xd15370a382630d4eull, 0x7f7ff0f36b54d90full, 0x5a7d69b067a7f011ull, 0x4f41ccf24f163e0dull};
    for (size_t i = 0; i < sizeof s3 / sizeof *s3; i++) CHECK(vka_ndve_net_key(s3[i]), "%016llx is the network or its last pass", (unsigned long long)s3[i]);
    CHECK(!vka_ndve_net_key(VKA_NDVE_CONVERT), "the colour conversion runs in every movie frame: not the network");
    CHECK(!vka_ndve_net_key(0xd99fbeeb53d38847ull) && !vka_ndve_net_key(0xfa3033d05c0a41d6ull), "agl's NDSR upscaler networks are made by normal runs: not the NDVE network");
    CHECK(!vka_ndve_net_key(0), "no key");
    // The switches
    CHECK(vka_pc_keep_blocker(NULL, 1) == NULL, "a normal run keeps its pipelines");
    CHECK(vka_pc_keep_blocker("", 1) == NULL, "an empty DM_VK_DISPATCH_SKIP is unset");
    const char *b = vka_pc_keep_blocker("0", 1);
    CHECK(b && !strcmp(b, "DM_VK_DISPATCH_SKIP"), "DM_VK_DISPATCH_SKIP=0 (the run that saved the network) keeps nothing");
    b = vka_pc_keep_blocker("12,40", 1);
    CHECK(b && !strcmp(b, "DM_VK_DISPATCH_SKIP"), "a range too");
    b = vka_pc_keep_blocker(NULL, 0);
    CHECK(b && !strcmp(b, "DM_VK_NDVE_DIRECT=0"), "DM_VK_NDVE_DIRECT=0 runs the network: keeps nothing");
    printf("pcguard: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
