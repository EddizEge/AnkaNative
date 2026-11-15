// The frame rate the launcher's overlay shows, and the 3D scene's size (nxhost_fps, vk_android.h): the present thread counts each image it hands to the swapchain (vka_fps_note), the game's own
// frames apart from frames the frame generation made (vka_framegen.c), and publishes the counts once a second. Nothing is drawn here: the launcher reads the numbers through JNI
// and draws them in an Android view over the SurfaceView, so the counter costs no GPU work. The log gets the same numbers every 15 s ("[vk] fps: ...")
#if defined(__ANDROID__)
#include "vka.h"
#include "vk_android.h"

static uint64_t g_win_t0, g_base_n, g_out_n, g_base_ns_sum, g_last_base, g_log_t;   // present thread only
static _Atomic uint32_t g_pub_base, g_pub_out, g_pub_ft;   // published: fps x 100, fps x 100, frame time in µs
static _Atomic uint64_t g_pub_t;                          // when they were published (now_ns)
static _Atomic int g_fg_state, g_fg_mult;                 // frame generation: 0 off, 1 generating, 2 on but not generating (warming up, paced out), -1 unavailable; frames per game frame

void vka_fps_fg(int state, int mult) { atomic_store(&g_fg_state, state); atomic_store(&g_fg_mult, mult); }

void vka_fps_note(int shown, int generated) {
    if (!shown) return;
    uint64_t t = now_ns();
    if (!g_win_t0) g_win_t0 = t;
    g_out_n++;
    if (!generated) {
        if (g_last_base && t - g_last_base < 1000000000ull) g_base_ns_sum += t - g_last_base;
        g_last_base = t; g_base_n++;
    }
    uint64_t dt = t - g_win_t0;
    if (dt < 1000000000ull) return;
    double s = dt / 1e9;
    atomic_store(&g_pub_base, (uint32_t)(g_base_n / s * 100 + 0.5));
    atomic_store(&g_pub_out, (uint32_t)(g_out_n / s * 100 + 0.5));
    atomic_store(&g_pub_ft, g_base_n ? (uint32_t)(g_base_ns_sum / g_base_n / 1000) : 0);
    atomic_store(&g_pub_t, t);
    if (t - g_log_t >= 15000000000ull) {
        g_log_t = t;
        int st = atomic_load(&g_fg_state);
        VLOG("fps: %.1f game frames a second (%.1f ms), %.1f presented%s", g_base_n / s, g_base_n ? g_base_ns_sum / g_base_n / 1e6 : 0.0, g_out_n / s,
             st == 1 ? " (frame generation on)" : st == 2 ? " (frame generation on, not generating)" : st < 0 ? " (frame generation unavailable)" : "");
    }
    g_win_t0 = t; g_base_n = g_out_n = g_base_ns_sum = 0;
}

// The GPU's busy time a frame, averaged over up to 32 recent frames whose time is in (vka_exec.c reads two timestamps per submission; a frame's time is in 8 frames
// later): only when the overlay asks (twice a second), a few atomic loads a frame. -1 without GPU timestamps
uint64_t vka_frame(void); double mtl_gpu_ms(uint64_t f);
static float gpu_ms(void) {
    uint64_t f = vka_frame(); double sum = 0; int n = 0;
    for (uint64_t i = f > 41 ? f - 41 : 0; i + 9 <= f; i++) { double g = mtl_gpu_ms(i); if (g >= 0) { sum += g; n++; } }
    return n ? (float)(sum / n) : -1;
}

// The size the game renders its 3D scene at now, and the size of the picture it presents, for the overlay (nxhost_fps out[6..9]). No game reports its render scale
// through NVN, and a target's size says nothing on its own (a game with dynamic resolution keeps a full-size target and draws into part of it, or one target per
// level), so the replay reads it where it holds for every game: the viewport of the 3D scene's draws. Those are draws that test depth and write colour (a shadow map or a
// depth prepass writes no colour, a sky, a full-screen post-process pass, the upscaler, the composite and the HUD test no depth); per frame the viewport size that has
// the most of them is the scene (ties: the larger), clipped to the pass's render area. A game without dynamic resolution shows its one 3D size, which is the
// presented size when it does not upscale; a frame with no such draw (a 2D game, a loading screen) publishes nothing, and after 2.5 s the overlay shows no size.
// The draw thread keeps the frame's tally (vka_fps_scene_draw, from vka_draw); the replay's present (vka_present_fill) ends the frame, and the first draw after it publishes
// the tally of the frame before. Only counted once the overlay has asked for the size (g_scene_want): a relaxed atomic load a draw otherwise
static _Atomic int g_scene_want;
static _Atomic uint32_t g_scene_epoch, g_pub_scene, g_pub_out;   // frames ended; published sizes as w << 16 | h
static _Atomic uint64_t g_pub_scene_t;
static struct { uint32_t w, h, n; } g_tally[8]; static int g_ntally; static uint32_t g_tally_epoch;   // the draw thread's
int vka_fps_scene_want(void) { return atomic_load_explicit(&g_scene_want, memory_order_relaxed); }
void vka_fps_frame_end(void) { atomic_fetch_add_explicit(&g_scene_epoch, 1, memory_order_relaxed); }
void vka_fps_output(uint32_t w, uint32_t h) { if (w && h && w < 65536 && h < 65536) atomic_store_explicit(&g_pub_out, w << 16 | h, memory_order_relaxed); }
void vka_fps_scene_draw(uint32_t w, uint32_t h) {
    uint32_t e = atomic_load_explicit(&g_scene_epoch, memory_order_relaxed);
    if (e != g_tally_epoch) {   // a frame ended since the last count: publish its scene
        int best = -1;
        for (int i = 0; i < g_ntally; i++)
            if (best < 0 || g_tally[i].n > g_tally[best].n || (g_tally[i].n == g_tally[best].n && (uint64_t)g_tally[i].w * g_tally[i].h > (uint64_t)g_tally[best].w * g_tally[best].h)) best = i;
        if (best >= 0) { atomic_store(&g_pub_scene, g_tally[best].w << 16 | g_tally[best].h); atomic_store(&g_pub_scene_t, now_ns()); }
        g_ntally = 0; g_tally_epoch = e;
    }
    if (!w || !h || w >= 65536 || h >= 65536) return;
    for (int i = 0; i < g_ntally; i++) if (g_tally[i].w == w && g_tally[i].h == h) { g_tally[i].n++; return; }
    if (g_ntally < 8) g_tally[g_ntally++] = (typeof(g_tally[0])){w, h, 1};   // (a ninth size in one frame is not counted: the scene's is among the first)
}

int nxhost_fps(float *out, int n) {
    float v[10];
    uint64_t t = atomic_load(&g_pub_t);
    int fresh = t && now_ns() - t < 2500000000ull;   // no present for 2.5 s (a load, the app in the background): nothing to show
    v[0] = fresh ? atomic_load(&g_pub_base) / 100.0f : 0;
    v[1] = fresh ? atomic_load(&g_pub_out) / 100.0f : 0;
    v[2] = fresh ? atomic_load(&g_pub_ft) / 1000.0f : 0;
    v[3] = (float)atomic_load(&g_fg_state);
    v[4] = (float)atomic_load(&g_fg_mult);
    v[5] = fresh && n > 5 ? gpu_ms() : -1;
    if (n > 6) atomic_store_explicit(&g_scene_want, 1, memory_order_relaxed);
    uint64_t st = atomic_load(&g_pub_scene_t); uint32_t sc = st && now_ns() - st < 2500000000ull ? atomic_load(&g_pub_scene) : 0, o = atomic_load(&g_pub_out);
    v[6] = (float)(sc >> 16); v[7] = (float)(sc & 0xffff); v[8] = (float)(o >> 16); v[9] = (float)(o & 0xffff);
    for (int i = 0; i < n && i < 10; i++) out[i] = v[i];
    return fresh;
}
#endif
