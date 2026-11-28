// Unit tests for Android AThermal status/headroom mapping (vka_thermal.h) to iOS's 0..3 thermal scale.
// Pure C, no Android or Metal dependencies.
#include "../vk_android/vka_thermal.h"
#include <math.h>
#include <stdio.h>

static int checks, failed;
#define CHECK(name, ok) do { int pass = !!(ok); checks++; failed += !pass; printf("%s: %s\n", name, pass ? "PASS" : "FAIL"); } while (0)

int main(void) {
    // Default thresholds: at = 2 (moderate), hat = 0.85 (overlay yellow dot)
    CHECK("default: none + low headroom -> 0 (nominal)", vka_thermal_map(0, 0.50f, 2, 0.85f) == 0);
    CHECK("default: light + low headroom -> 1 (fair, keeps GPU-slowed on)", vka_thermal_map(1, 0.50f, 2, 0.85f) == 1);
    CHECK("default: moderate + low headroom -> 2 (serious, caps to 30)", vka_thermal_map(2, 0.50f, 2, 0.85f) == 2);
    CHECK("default: severe + low headroom -> 3 (critical)", vka_thermal_map(3, 0.50f, 2, 0.85f) == 3);
    CHECK("default: critical/emergency/shutdown -> clamped to 3",
          vka_thermal_map(4, 0.50f, 2, 0.85f) == 3 && vka_thermal_map(5, 0.50f, 2, 0.85f) == 3 && vka_thermal_map(6, 0.50f, 2, 0.85f) == 3);

    // Headroom threshold at 0.85
    CHECK("default: none + headroom 0.84 -> 0", vka_thermal_map(0, 0.84f, 2, 0.85f) == 0);
    CHECK("default: light + headroom 0.84 -> 1", vka_thermal_map(1, 0.84f, 2, 0.85f) == 1);
    CHECK("default: none + headroom 0.85 -> 2", vka_thermal_map(0, 0.85f, 2, 0.85f) == 2);
    CHECK("default: light + headroom 0.90 -> 2", vka_thermal_map(1, 0.90f, 2, 0.85f) == 2);
    CHECK("default: severe + headroom 0.85 stays 3", vka_thermal_map(3, 0.85f, 2, 0.85f) == 3);

    // NaN headroom handling
    CHECK("default: none + NaN headroom -> 0", vka_thermal_map(0, NAN, 2, 0.85f) == 0);
    CHECK("default: light + NaN headroom -> 1", vka_thermal_map(1, NAN, 2, 0.85f) == 1);
    CHECK("default: moderate + NaN headroom -> 2", vka_thermal_map(2, NAN, 2, 0.85f) == 2);
    CHECK("default: severe + NaN headroom -> 3", vka_thermal_map(3, NAN, 2, 0.85f) == 3);

    // Unavailable status (< 0)
    CHECK("unavailable status + NaN headroom -> -1", vka_thermal_map(-1, NAN, 2, 0.85f) == -1);
    CHECK("unavailable status + low headroom -> 0", vka_thermal_map(-1, 0.50f, 2, 0.85f) == 0);
    CHECK("unavailable status + high headroom -> 2", vka_thermal_map(-1, 0.85f, 2, 0.85f) == 2);
    CHECK("unavailable status + headroom disabled -> -1", vka_thermal_map(-1, 0.90f, 2, 0.0f) == -1);

    // Custom status threshold DM_THERMAL_ANDROID_AT
    CHECK("at=1: light -> 2 (caps early)", vka_thermal_map(1, 0.50f, 1, 0.85f) == 2);
    CHECK("at=1: none -> 0", vka_thermal_map(0, 0.50f, 1, 0.85f) == 0);
    CHECK("at=1: severe -> 3", vka_thermal_map(3, 0.50f, 1, 0.85f) == 3);
    CHECK("at=3: moderate stays 1 (below threshold)", vka_thermal_map(2, 0.50f, 3, 0.85f) == 1);
    CHECK("at=3: severe -> 3", vka_thermal_map(3, 0.50f, 3, 0.85f) == 3);

    // Disabled thresholds (0)
    CHECK("at=0: moderate/severe do not trigger cap", vka_thermal_map(2, 0.50f, 0, 0.85f) == 1 && vka_thermal_map(3, 0.50f, 0, 0.85f) == 1);
    CHECK("at=0: headroom still triggers cap", vka_thermal_map(0, 0.85f, 0, 0.85f) == 2);
    CHECK("hat=0: high headroom does not trigger cap", vka_thermal_map(0, 1.20f, 2, 0.0f) == 0 && vka_thermal_map(1, 1.20f, 2, 0.0f) == 1);
    CHECK("hat=0: moderate status still triggers cap", vka_thermal_map(2, 1.20f, 2, 0.0f) == 2);
    CHECK("at=0, hat=0: neither triggers cap", vka_thermal_map(4, 1.50f, 0, 0.0f) == 1 && vka_thermal_map(0, 1.50f, 0, 0.0f) == 0);

    printf("== %d checks, %d failed ==\n", checks, failed);
    return failed != 0;
}
