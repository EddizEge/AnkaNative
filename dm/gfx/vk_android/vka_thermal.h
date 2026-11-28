// Mapping from Android AThermal readings to iOS's 0..3 thermal scale for hle_nvn.c's thermal cap and slowed gates:
//   0 nominal, 1 fair, 2 serious, 3 critical (-1 = no reading).
// Kept in a platform-independent header so host unit tests (dm/gfx/tests/t_thermal_android.c) can exercise it without Android.
#pragma once

// status: AThermal_getCurrentThermalStatus (0 none, 1 light, 2 moderate, 3 severe, 4+ critical; <0 unavailable)
// headroom: last non-NaN AThermal_getThermalHeadroom(10) forecast (1.0 = severe; NaN unavailable)
// at: DM_THERMAL_ANDROID_AT (default 2 = moderate; <= 0 disables status triggering >= 2)
// hat: DM_THERMAL_ANDROID_HEADROOM (default 0.85; <= 0 disables headroom triggering >= 2)
static inline int vka_thermal_map(int status, float headroom, int at, float hat) {
    int r = -1;
    if (status >= 0) {
        if (at > 0 && status >= at) r = status >= 3 ? 3 : 2;
        else r = status > 0 ? 1 : 0;
    }
    if (hat > 0.0f && headroom == headroom) {
        if (headroom >= hat) { if (r < 2) r = 2; }
        else if (r < 0) r = 0;
    }
    return r;
}
