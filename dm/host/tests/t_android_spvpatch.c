// The Android replay's SPIR-V rewrite of vertex inputs fed integer formats (dm/gfx/vk_android/vka_spvpatch.c), on the Mac: the graphics selftest's vertex shader
// (a component read through an access chain at location 0, whole loads at location 1) retyped as uint / int, checked by structure and, when spirv-val is on the PATH,
// by validation. DM_VK_SPV_DIR=<folder of .spv vertex modules> also rewrites each of those (every vec4 input as uint) and validates it. DM_VK_GS_SPV=<a geometry module
// that writes memory> puts its writes under a per-primitive claim (vka_spv_gs_claim, after the one-invocation subgroup rewrite) and validates it
#include "../../gfx/vk_android/vka_spvpatch.h"
#include "../../gfx/vk_android/vka_selftest_spv.h"
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
static int have_val(void) { return system("spirv-val --version >/dev/null 2>&1") == 0; }
static int validate(const uint32_t *w, size_t n, const char *tag) {
    char path[256]; snprintf(path, sizeof path, "/tmp/t_spvpatch_%d.spv", (int)getpid());
    FILE *f = fopen(path, "wb"); if (!f) return 0; fwrite(w, 4, n, f); fclose(f);
    char cmd[400]; snprintf(cmd, sizeof cmd, "spirv-val --target-env vulkan1.3 %s", path);
    int r = system(cmd); remove(path);
    if (r) printf("  spirv-val failed on %s\n", tag);
    return r == 0;
}
// A pixel shader with the texture-use feedback bitmap at set 1 binding 3, a texture and a sampler at set 1 bindings 0 and 1, a storage buffer at set 0 binding 3:
// glslc --target-env=vulkan1.3 -O of
//   layout(set = 1, binding = 3) buffer FB { uint bits[]; } fb; layout(set = 1, binding = 0) uniform texture2D t0; layout(set = 1, binding = 1) uniform sampler s0;
//   layout(set = 0, binding = 3) buffer B0 { uint x; } b0; layout(location = 0) out vec4 o;
//   void main() { atomicOr(fb.bits[0], 1u); o = texture(sampler2D(t0, s0), vec2(0.5)) + vec4(float(b0.x)); }
static const uint32_t SPV_FB_FRAG[] = {
    0x07230203, 0x00010600, 0x000d000b, 0x0000002c, 0x00000000, 0x00020011, 0x00000001, 0x0006000b,
    0x00000001, 0x4c534c47, 0x6474732e, 0x3035342e, 0x00000000, 0x0003000e, 0x00000000, 0x00000001,
    0x000a000f, 0x00000004, 0x00000004, 0x6e69616d, 0x00000000, 0x0000000a, 0x00000015, 0x00000018,
    0x0000001c, 0x00000026, 0x00030010, 0x00000004, 0x00000007, 0x00040047, 0x00000007, 0x00000006,
    0x00000004, 0x00030047, 0x00000008, 0x00000002, 0x00050048, 0x00000008, 0x00000000, 0x00000023,
    0x00000000, 0x00040047, 0x0000000a, 0x00000021, 0x00000003, 0x00040047, 0x0000000a, 0x00000022,
    0x00000001, 0x00040047, 0x00000015, 0x0000001e, 0x00000000, 0x00040047, 0x00000018, 0x00000021,
    0x00000000, 0x00040047, 0x00000018, 0x00000022, 0x00000001, 0x00040047, 0x0000001c, 0x00000021,
    0x00000001, 0x00040047, 0x0000001c, 0x00000022, 0x00000001, 0x00030047, 0x00000024, 0x00000002,
    0x00050048, 0x00000024, 0x00000000, 0x00000023, 0x00000000, 0x00040047, 0x00000026, 0x00000021,
    0x00000003, 0x00040047, 0x00000026, 0x00000022, 0x00000000, 0x00020013, 0x00000002, 0x00030021,
    0x00000003, 0x00000002, 0x00040015, 0x00000006, 0x00000020, 0x00000000, 0x0003001d, 0x00000007,
    0x00000006, 0x0003001e, 0x00000008, 0x00000007, 0x00040020, 0x00000009, 0x0000000c, 0x00000008,
    0x0004003b, 0x00000009, 0x0000000a, 0x0000000c, 0x00040015, 0x0000000b, 0x00000020, 0x00000001,
    0x0004002b, 0x0000000b, 0x0000000c, 0x00000000, 0x00040020, 0x0000000d, 0x0000000c, 0x00000006,
    0x0004002b, 0x00000006, 0x0000000f, 0x00000001, 0x0004002b, 0x00000006, 0x00000010, 0x00000000,
    0x00030016, 0x00000012, 0x00000020, 0x00040017, 0x00000013, 0x00000012, 0x00000004, 0x00040020,
    0x00000014, 0x00000003, 0x00000013, 0x0004003b, 0x00000014, 0x00000015, 0x00000003, 0x00090019,
    0x00000016, 0x00000012, 0x00000001, 0x00000000, 0x00000000, 0x00000000, 0x00000001, 0x00000000,
    0x00040020, 0x00000017, 0x00000000, 0x00000016, 0x0004003b, 0x00000017, 0x00000018, 0x00000000,
    0x0002001a, 0x0000001a, 0x00040020, 0x0000001b, 0x00000000, 0x0000001a, 0x0004003b, 0x0000001b,
    0x0000001c, 0x00000000, 0x0003001b, 0x0000001e, 0x00000016, 0x00040017, 0x00000020, 0x00000012,
    0x00000002, 0x0004002b, 0x00000012, 0x00000021, 0x3f000000, 0x0005002c, 0x00000020, 0x00000022,
    0x00000021, 0x00000021, 0x0003001e, 0x00000024, 0x00000006, 0x00040020, 0x00000025, 0x0000000c,
    0x00000024, 0x0004003b, 0x00000025, 0x00000026, 0x0000000c, 0x00050036, 0x00000002, 0x00000004,
    0x00000000, 0x00000003, 0x000200f8, 0x00000005, 0x00060041, 0x0000000d, 0x0000000e, 0x0000000a,
    0x0000000c, 0x0000000c, 0x000700f1, 0x00000006, 0x00000011, 0x0000000e, 0x0000000f, 0x00000010,
    0x0000000f, 0x0004003d, 0x00000016, 0x00000019, 0x00000018, 0x0004003d, 0x0000001a, 0x0000001d,
    0x0000001c, 0x00050056, 0x0000001e, 0x0000001f, 0x00000019, 0x0000001d, 0x00050057, 0x00000013,
    0x00000023, 0x0000001f, 0x00000022, 0x00050041, 0x0000000d, 0x00000027, 0x00000026, 0x0000000c,
    0x0004003d, 0x00000006, 0x00000028, 0x00000027, 0x00040070, 0x00000012, 0x00000029, 0x00000028,
    0x00070050, 0x00000013, 0x0000002a, 0x00000029, 0x00000029, 0x00000029, 0x00000029, 0x00050081,
    0x00000013, 0x0000002b, 0x00000023, 0x0000002a, 0x0003003e, 0x00000015, 0x0000002b, 0x000100fd,
    0x00010038
};
static int decoration(const uint32_t *w, size_t n, uint32_t id, uint32_t dec) {   // the literal of OpDecorate id dec, -1 when absent
    for (size_t i = 5; i < n && (w[i] >> 16); i += w[i] >> 16) if ((w[i] & 0xffff) == 71 && (w[i] >> 16) >= 4 && w[i + 1] == id && w[i + 2] == dec) return (int)w[i + 3];
    return -1;
}
static int count_op(const uint32_t *w, size_t n, uint32_t op) { int k = 0; for (size_t i = 5; i < n; i += w[i] >> 16) { if (!(w[i] >> 16)) break; k += (w[i] & 0xffff) == op; } return k; }
int main(void) {
    int val = have_val();
    size_t n = sizeof SPV_TRI_VERT / 4, on = 0; char err[200] = "";
    uint32_t *o = vka_spv_vin_retype(SPV_TRI_VERT, n, 1, 2, &on, err, sizeof err);
    CHECK(o, "selftest vertex shader not rewritten: %s", err);
    if (o) {
        CHECK(o[3] > SPV_TRI_VERT[3], "bound grows (%u → %u)", SPV_TRI_VERT[3], o[3]);
        CHECK(count_op(o, on, 124) >= count_op(SPV_TRI_VERT, n, 124) + 2, "a bitcast after each load of the two inputs");
        CHECK(count_op(o, on, 21) >= 2, "an int type of each signedness");
        if (val) CHECK(validate(o, on, "selftest vertex"), "valid SPIR-V");
        free(o);
    }
    CHECK(!vka_spv_vin_retype(SPV_TRI_VERT, n, 4, 0, &on, err, sizeof err), "a location the module does not read is refused");
    CHECK(!vka_spv_vin_retype(SPV_TRI_VERT, n, 1, 1, &on, err, sizeof err), "a location both uint and int is refused");
    {   // A float colour output into an integer target (vka_spv_fout_retype): the selftest's fragment shader, its vec4 output at location 0 as uvec4 / ivec4
        size_t fn = sizeof SPV_TRI_FRAG / 4;
        for (int c = 0; c < 2; c++) {
            uint32_t *f = vka_spv_fout_retype(SPV_TRI_FRAG, fn, c ? 0 : 1, c ? 1 : 0, &on, err, sizeof err);
            CHECK(f, "selftest fragment shader output not retyped (%s): %s", c ? "int" : "uint", err);
            if (!f) continue;
            CHECK(count_op(f, on, 124) >= count_op(SPV_TRI_FRAG, fn, 124) + 1, "a bitcast before each store of the output");
            CHECK(count_op(f, on, 21) >= 1, "an integer type");
            if (val) CHECK(validate(f, on, c ? "selftest fragment (int)" : "selftest fragment (uint)"), "valid SPIR-V");
            free(f);
        }
        CHECK(!vka_spv_fout_retype(SPV_TRI_FRAG, fn, 2, 0, &on, err, sizeof err), "a location the module does not write is refused");
        CHECK(!vka_spv_fout_retype(SPV_TRI_FRAG, fn, 1, 1, &on, err, sizeof err), "a location both uint and int is refused");
    }
    const char *dir = getenv("DM_VK_SPV_DIR");
    if (dir) {
        DIR *d = opendir(dir); struct dirent *e; int done = 0, refused = 0;
        while (d && (e = readdir(d))) {
            size_t l = strlen(e->d_name); if (l < 4 || strcmp(e->d_name + l - 4, ".spv")) continue;
            char p[1024]; snprintf(p, sizeof p, "%s/%s", dir, e->d_name);
            FILE *f = fopen(p, "rb"); if (!f) continue; fseek(f, 0, SEEK_END); long sz = ftell(f); rewind(f);
            uint32_t *w = malloc((size_t)sz); if (fread(w, 1, (size_t)sz, f) != (size_t)sz) { fclose(f); free(w); continue; } fclose(f);
            // every Location-decorated input: find them by a first rewrite of all 32 locations, narrowing on refusal
            uint32_t mask = 0;
            for (size_t i = 5; i < (size_t)sz / 4; i += w[i] >> 16) { if (!(w[i] >> 16)) break; if ((w[i] & 0xffff) == 71 && (w[i] >> 16) >= 4 && w[i + 2] == 30 && w[i + 3] < 32) {
                uint32_t id = w[i + 1]; for (size_t j = 5; j < (size_t)sz / 4; j += w[j] >> 16) { if (!(w[j] >> 16)) break; if ((w[j] & 0xffff) == 59 && w[j + 2] == id && w[j + 3] == 1) mask |= 1u << w[i + 3]; } } }
            uint32_t *x = mask ? vka_spv_vin_retype(w, (size_t)sz / 4, mask, 0, &on, err, sizeof err) : NULL;
            if (!x) { refused++; if (refused <= 4) printf("  %s: %s\n", e->d_name, mask ? err : "no input"); }
            else { done++; if (val) CHECK(validate(x, on, e->d_name), "%s valid", e->d_name); free(x); }
            free(w);
        }
        if (d) closedir(d);
        printf("t_android_spvpatch: %d modules rewritten, %d refused in %s\n", done, refused, dir);
    }
    const char *gs = getenv("DM_VK_GS_SPV");
    if (gs) {
        FILE *f = fopen(gs, "rb"); CHECK(f, "%s not readable", gs);
        if (f) { fseek(f, 0, SEEK_END); long sz = ftell(f); rewind(f); uint32_t *w = malloc((size_t)sz); size_t nw = fread(w, 1, (size_t)sz, f) / 4; fclose(f);
            int ch = 0; size_t sn = 0; uint32_t *sg = vka_spv_subgroup_single(w, nw, &sn, &ch, err, sizeof err); if (sg) { free(w); w = sg; nw = sn; }
            int found = vka_spv_gs_claim(w, nw, 255, NULL, NULL, err, sizeof err);
            CHECK(found >= 0, "%s: memory writes counted (%s)", gs, err);
            uint32_t *x = NULL; size_t xn = 0; int r = found > 0 ? vka_spv_gs_claim(w, nw, 255, &x, &xn, err, sizeof err) : -1;
            if (found > 0) CHECK(r == found && x, "claim module made (%s)", err);
            if (x) {
                CHECK(count_op(x, xn, 218) == count_op(w, nw, 218), "emits as before");
                CHECK(count_op(x, xn, 247) == count_op(w, nw, 247) + found, "a selection around each write");
                CHECK(count_op(x, xn, 229) == count_op(w, nw, 229) + 1, "one exchange for the claim");
                if (val) CHECK(validate(x, xn, "gs claim"), "claim module valid");
                free(x);
            }
            printf("t_android_spvpatch: %s: %d memory writes claimed\n", gs, found);
            free(w); }
    }
    {   // the feedback bitmap moved to a set of its own (DM_VK_TEXFB_SET): only the variable at set 1 binding 3, not set 1's other bindings or set 0's binding 3
        size_t fn = sizeof SPV_FB_FRAG / 4; uint32_t *w = malloc(sizeof SPV_FB_FRAG); memcpy(w, SPV_FB_FRAG, sizeof SPV_FB_FRAG);
        CHECK(vka_spv_move_set(w, fn, 1, 3, 2) == 1, "one variable moved");
        CHECK(decoration(w, fn, 10, 34) == 2 && decoration(w, fn, 10, 33) == 3, "the bitmap at set 2 binding 3");
        CHECK(decoration(w, fn, 24, 34) == 1 && decoration(w, fn, 28, 34) == 1 && decoration(w, fn, 38, 34) == 0, "the other bindings keep their sets");
        CHECK(vka_spv_move_set(w, fn, 1, 3, 2) == 0, "nothing left at set 1 binding 3");
        if (val) CHECK(validate(w, fn, "feedback bitmap at set 2"), "valid SPIR-V");
        uint32_t bad[6] = {0x12345678, 0, 0, 1, 0, 0};
        CHECK(vka_spv_move_set(bad, 6, 1, 3, 2) == -1, "not SPIR-V");
        free(w);
    }
    printf("t_android_spvpatch: %s%s\n", g_fail ? "FAILED" : "ok", val ? "" : " (no spirv-val: structure only)");
    return g_fail != 0;
}
