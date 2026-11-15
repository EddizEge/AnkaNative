// Mesh draws through compute: the frame's rule and the arena's size (dm/gfx/vk_android/vka_memu_rule.h; vka_memu_draw.inc memu_frame_draws, vka_memu.c vka_memu_arena)
#include <stdio.h>
#include "../../gfx/vk_android/vka_memu_rule.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define MIB(x) ((uint32_t)((x) * 262144.0))

int main(void) {
    vka_memu_frame_rule r = {0};
    // A frame that began with nothing being made draws, even when its shading pass then asks for a new form (the prepass already drew: its objects are shaded)
    CHECK(vka_memu_frame_draws(&r, 100, 0) == 1, "frame 100 begins with nothing waited for: drawn");
    CHECK(vka_memu_frame_draws(&r, 100, 1) == 1, "frame 100 keeps drawing after a form is asked for in it");
    // The next frame begins with that form still being made: none of its mesh draws, also after the form comes in during it
    CHECK(vka_memu_frame_draws(&r, 101, 1) == 0, "frame 101 begins with a form being made: held");
    CHECK(vka_memu_frame_draws(&r, 101, 0) == 0, "frame 101 stays held after the form is made (its prepass drew nothing)");
    CHECK(vka_memu_frame_draws(&r, 102, 0) == 1, "frame 102: drawn again");
    CHECK(vka_memu_frame_draws(&r, 102, 3) == 1, "latched for the frame");
    vka_memu_frame_rule z = {0};
    CHECK(vka_memu_frame_draws(&z, 0, 2) == 0, "frame 0 is a frame too (the first call sets the latch)");
    CHECK(vka_memu_frame_draws(&z, 0, 0) == 0, "and latches it");
    // The arena: 64 MiB to start, doubled to the most a batch asked for, at most DM_VK_MESH_EMU_MB
    uint32_t max256 = 256u << 18;
    CHECK(vka_memu_arena_want(0, max256) == MIB(64), "first size 64 MiB");
    CHECK(vka_memu_arena_want(MIB(29.2), max256) == MIB(64), "a batch of 29.2 MiB fits the first size");
    CHECK(vka_memu_arena_want(MIB(64), max256) == MIB(64), "exactly 64 MiB fits");
    CHECK(vka_memu_arena_want(MIB(67.6), max256) == MIB(128), "67.6 MiB (the most a whole submission asked for before 2026-11-15) takes 128");
    CHECK(vka_memu_arena_want(MIB(300), max256) == max256, "capped at DM_VK_MESH_EMU_MB");
    CHECK(vka_memu_arena_want(0, 32u << 18) == (32u << 18), "a limit under the first size wins");
    CHECK(vka_memu_arena_want(MIB(100), 100u << 18) == (100u << 18), "a limit that is no power of two");
    printf("memurule: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
