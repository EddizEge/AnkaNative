// The asynchronous pipeline queue's pick (dm/gfx/vk_android/vka_aspick.h): today's order by need, and the cheapest-first order of DM_VK_PSO_CHEAP with its fair turn
#include <stdio.h>
#include "../../gfx/vk_android/vka_aspick.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define MS 1000000ull

int main(void) {
    // queue order: a (old, frame 10, large), b (frame 12, large), c (frame 12, small), d (frame 12, smallest but queued last), e (frame 11)
    vka_as_cand q[] = {{10, 0, 130000}, {12, 100 * MS, 120000}, {12, 200 * MS, 40000}, {12, 300 * MS, 20000}, {11, 400 * MS, 10}};
    uint64_t now = 500 * MS, fair = 2000 * MS;
    CHECK(vka_as_pick(q, 5, 0, 0, now, fair) == 0, "need and cheap off: queue order");
    CHECK(vka_as_pick(q, 5, 1, 0, now, fair) == 1, "need on (today's default): the oldest of the newest need frame");
    CHECK(vka_as_pick(q, 5, 1, 1, now, fair) == 3, "cheap: the least cost among the newest need frame, not the cheaper job of an older frame");
    CHECK(vka_as_pick(q, 5, 0, 1, now, fair) == 4, "cheap without need: the least cost of all");
    CHECK(vka_as_pick(q, 5, 1, 1, 100 * MS + fair, fair) == 1, "the oldest of the most needed takes its turn after the fair wait");
    vka_as_cand eq[] = {{5, 0, 300}, {5, 1, 200}, {5, 2, 200}};
    CHECK(vka_as_pick(eq, 3, 1, 1, 10, fair) == 1, "the first of least cost among equals");
    vka_as_cand wrap[] = {{0xfffffffe, 0, 5}, {1, 1, 9}, {1, 2, 7}};
    CHECK(vka_as_pick(wrap, 3, 1, 0, 10, fair) == 1 && vka_as_pick(wrap, 3, 1, 1, 10, fair) == 2, "a need frame past the wrap is newer");
    vka_as_cand one[] = {{3, 0, 1}};
    CHECK(vka_as_pick(one, 1, 1, 1, 5, fair) == 0, "a single job");
    printf("aspick: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
