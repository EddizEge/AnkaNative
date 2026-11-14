// NVN counter timestamps are 614.4 MHz ticks (384 ticks per 625 ns), not the CPU's 19.2 MHz system counter.
// Keep the guest clock's epoch in both directions. Wide products avoid overflow on long host uptimes.
// Test switch DM_NVN_TS_HZ=<Hz> (restart; unset = 614.4 MHz, as before) writes the reports at another rate, and nvnDeviceGetTimestampInNanoseconds converts at the
// same one. nxnative PR #1714 (open, MIT) finds that The Legend of Zelda: Ocarina of Time (Switch 2) turns the difference of two raw reports into its wind and particle
// time step at 31.25 MHz, the Switch 2's system counter rate, so at 614.4 MHz its foliage, falling leaves and hanging plants move 614.4 / 31.25 = 19.66 times too fast.
// Whether the game's GPU-time-driven dynamic resolution reads the same reports at that rate is unknown here: DM_NVN_TS_HZ=31250000 is an A/B for a phone run, not a fix yet
#pragma once
#include <stdint.h>
#include <stdlib.h>
#include <stdatomic.h>

#define NVN_TS_HZ_DEFAULT 614400000ull
// The conversions at a given rate; 614.4 MHz takes the exact old arithmetic
static inline uint64_t nvn_ts_from_ns_hz(uint64_t ns, uint64_t hz) {
    return hz == NVN_TS_HZ_DEFAULT ? (uint64_t)((__uint128_t)ns * 384 / 625) : (uint64_t)((__uint128_t)ns * hz / 1000000000u);
}
static inline uint64_t nvn_ts_to_ns_hz(uint64_t ticks, uint64_t hz) {
    return hz == NVN_TS_HZ_DEFAULT ? (uint64_t)((__uint128_t)ticks * 625 / 384) : (uint64_t)((__uint128_t)ticks * 1000000000u / hz);
}
// The rate in use: DM_NVN_TS_HZ when it is a positive number, else 614.4 MHz; read once per process
static inline uint64_t nvn_ts_hz(void) {
    static _Atomic uint64_t hz;
    uint64_t value = atomic_load_explicit(&hz, memory_order_relaxed);
    if (!value) {
        const char *e = getenv("DM_NVN_TS_HZ");
        char *end = NULL;
        unsigned long long v = e && *e ? strtoull(e, &end, 10) : 0;
        value = v && end && !*end ? (uint64_t)v : NVN_TS_HZ_DEFAULT;
        uint64_t unset = 0;
        if (!atomic_compare_exchange_strong_explicit(&hz, &unset, value, memory_order_relaxed, memory_order_relaxed)) value = unset;
    }
    return value;
}
static inline uint64_t nvn_timestamp_from_ns(uint64_t ns) { return nvn_ts_from_ns_hz(ns, nvn_ts_hz()); }
static inline uint64_t nvn_timestamp_to_ns(uint64_t ticks) { return nvn_ts_to_ns_hz(ticks, nvn_ts_hz()); }
