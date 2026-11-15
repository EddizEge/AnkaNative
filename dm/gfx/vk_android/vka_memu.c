// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors (the compute mesh emulation: pavelgajdo/Hokkaido #569 per-submission arena, #642 descriptor budget)
// SPDX-FileCopyrightText: Copyright 2026 Banana Native (this port)
// SPDX-License-Identifier: GPL-3.0-or-later
// The Eden and yuzu lines are those of Lichii's vk_rasterizer.cpp (the per-submission arena) and vk_pipeline_cache.cpp (the pipelines, the descriptor budget),
// as zortos293/Lichii #569 and #642 left them; #642's mesh_fallback_limits.h has only the licence identifier. Hokkaido is a fork of Lichii
//
// NVN2 task and mesh draws through compute (docs/reference/shaders.md#mesh-draws-through-compute-android), the Vulkan side that is not a draw: each task / mesh shader's
// compute form and vertex shader (made from the library's own modules by vka_memu_spv.c, as derived shaders with keys of their own, so the pipeline cache and the
// prewarm list keep them), the arena the converted modules write into (device-local, one per submission in flight, grown when a submission asked for more), and the
// helper passes (memu/memu_setup.comp, memu/memu_dist.comp). The batches of a pass's draws are vka_draw.c's (vka_memu_draw.inc); the look-ahead that finds them is
// vka_exec.c's memu_scan. GPL-3.0-or-later: see vka_memu_abi.h.
#if defined(__ANDROID__)
#include "vka.h"
#include "vka_memu.h"
#include "vka_memu_abi.h"
#include "vka_memu_rule.h"
#include "vka_memu_helpers_spv.h"
#include "vka_spvpatch.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

uint64_t vka_memu_n_batches, vka_memu_n_draws, vka_memu_n_splits, vka_memu_n_skip, vka_memu_n_wait, vka_memu_n_hold;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static const char *stage_name(int st) { return st == DMSH_VK_STAGE_TS ? "task" : "mesh"; }

// ---------- Shaders ----------
static VkShaderModule make_module(const uint32_t *w, size_t n) {
    VkShaderModuleCreateInfo ci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = n * 4, .pCode = w};
    VkShaderModule m = VK_NULL_HANDLE; if (vk.vkCreateShaderModule(V.dev, &ci, NULL, &m)) m = VK_NULL_HANDLE;
    return m;
}
vka_memu_sh *vka_memu_of(vka_shader *s) {
    if (!s || !s->e || (s->e->stage != DMSH_VK_STAGE_TS && s->e->stage != DMSH_VK_STAGE_MS)) return NULL;
    pthread_mutex_lock(&g_mu);
    vka_memu_sh *d = s->memu_d;
    if (d) { pthread_mutex_unlock(&g_mu); return d->state > 0 ? d : NULL; }
    d = calloc(1, sizeof *d); char err[256] = ""; size_t nb = 0;
    uint32_t *w = vka_shader_memu_words(s, &nb, err, sizeof err);
    if (w && vka_memu_layout_of(w, nb / 4, &d->L, err, sizeof err)) {
        d->state = 1;
        // The storage buffers its stage binds and the emulation's two, within the device's limit for one compute stage (Hokkaido #642). And its workgroup
        for (unsigned i = 0; i < s->e->bind_count; i++) if (s->b[i].desc == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) d->nsb += s->b[i].count ? s->b[i].count : 1;
        d->nsb += 2;
        if (d->nsb > V.props.limits.maxPerStageDescriptorStorageBuffers) { snprintf(err, sizeof err, "%u storage buffers with the arena and the records, the device's compute stage takes %u", d->nsb, V.props.limits.maxPerStageDescriptorStorageBuffers); d->state = -1; }
        if (d->L.threads > V.props.limits.maxComputeWorkGroupInvocations) { snprintf(err, sizeof err, "a workgroup of %u invocations", d->L.threads); d->state = -1; }
        d->ce = *s->e; d->ce.stage = DMSH_VK_STAGE_CS; d->ce.key = s->key ^ VKA_MEMU_KEY_CS;
        memset(&d->ve, 0, sizeof d->ve); d->ve.key = s->key ^ VKA_MEMU_KEY_VS; d->ve.stage = DMSH_VK_STAGE_VS;
    } else d->state = -1;
    free(w);
    if (d->state < 0) { static _Atomic int n; if (atomic_fetch_add(&n, 1) < 16) VRED("%s shader %016llx is not drawn through compute: %s; its draws are skipped", stage_name(s->e->stage), (unsigned long long)s->key, err); }
    else { static _Atomic int n; if (atomic_fetch_add(&n, 1) < 4) VLOG("%s shader %016llx through compute: %u x %u x %u invocations%s", stage_name(s->e->stage), (unsigned long long)s->key, d->L.local[0], d->L.local[1], d->L.local[2],
                                                                     d->L.task ? "" : d->L.indexed ? ", indexed triangles" : d->L.points ? ", points" : ", triangles with per-primitive values"); }
    s->memu_d = d;
    pthread_mutex_unlock(&g_mu);
    return d->state > 0 ? d : NULL;
}
// A derived shader: s's fields, its own entry, key and module (vka_shader_remap's form)
static vka_shader *derive(vka_shader *s, const dmsh_vk_entry *e, VkShaderModule mod, uint8_t kind) {
    vka_shader *v = calloc(1, sizeof *v);
    *v = *s; memset(v->mods, 0, sizeof v->mods); v->mods[DMSH_VK_MOD_BASE] = mod;
    v->vin = v->fout = NULL; v->gs_fx = 0; v->gs_claim = VK_NULL_HANDLE;   // (Anka Native's vka_shader: no other per-module caches to clear)
    v->memu_d = NULL; v->memu = kind; v->e = e; v->key = e->key;
    if (kind == 2) { v->b = NULL; v->c1_len = 0; }
    return v;
}
vka_shader *vka_memu_cs_of(vka_shader *s, int gl) {
    vka_memu_sh *d = vka_memu_of(s); if (!d) return NULL;
    gl = gl && (s->e->flags & DMSH_VK_F_GL_DEPTH) ? 1 : 0;
    pthread_mutex_lock(&g_mu); vka_shader *have = d->cs[gl]; pthread_mutex_unlock(&g_mu);
    if (have) return have;
    char err[256] = ""; size_t nb = 0, fn = 0, on = 0;
    uint32_t *w = vka_shader_memu_words(s, &nb, err, sizeof err);
    // Its specialization constants folded, always: the compute form's pipeline sets none (cs_build) and its key and flags say the GL depth convention is in the module
    // (also Qualcomm's lanes read 0 in a compute module with a specialization constant, vka_spv_spec_freeze)
    uint32_t *fz = NULL; int fr = w ? vka_spv_spec_fold(w, nb / 4, DMSH_VK_SPEC_GL_DEPTH, (uint32_t)gl, &fz, &fn) : 0;
    if (fr < 0) snprintf(err, sizeof err, "a specialization constant composite or operation: its constants (GL depth %d) cannot be folded", gl);
    uint32_t *o = w && fr >= 0 ? vka_memu_cs(fz ? fz : w, fz ? fn : nb / 4, &on, err, sizeof err) : NULL;
    VkShaderModule mod = o ? make_module(o, on) : VK_NULL_HANDLE;
    free(w); free(fz); free(o);
    if (!mod) { static _Atomic int n; if (atomic_fetch_add(&n, 1) < 16) VRED("%s shader %016llx: its compute form not made (%s); its draws are skipped", stage_name(s->e->stage), (unsigned long long)s->key, err[0] ? err : "vkCreateShaderModule failed"); return NULL; }
    dmsh_vk_entry *e = malloc(sizeof *e); *e = d->ce; e->key = s->key ^ VKA_MEMU_KEY_CS ^ (gl ? VKA_MEMU_KEY_GL : 0);
    e->flags &= (uint16_t)~DMSH_VK_F_GL_DEPTH;   // (the depth convention is in the module)
    vka_shader *v = derive(s, e, mod, 1);
    pthread_mutex_lock(&g_mu);
    if (d->cs[gl]) { vka_shader *h = d->cs[gl]; pthread_mutex_unlock(&g_mu); vk.vkDestroyShaderModule(V.dev, mod, NULL); free(v); free(e); return h; }
    d->cs[gl] = v; pthread_mutex_unlock(&g_mu);
    return v;
}
vka_shader *vka_memu_vs_of(vka_shader *s) {
    vka_memu_sh *d = vka_memu_of(s); if (!d || d->L.task) return NULL;
    pthread_mutex_lock(&g_mu); vka_shader *have = d->vs; pthread_mutex_unlock(&g_mu);
    if (have) return have;
    char err[256] = ""; size_t on = 0;
    uint32_t *o = vka_memu_vs(&d->L, &on, err, sizeof err);
    VkShaderModule mod = o ? make_module(o, on) : VK_NULL_HANDLE; free(o);
    if (!mod) { VRED_ONCE("mesh shader %016llx: the vertex shader of its output not made (%s); its draws are skipped", (unsigned long long)s->key, err[0] ? err : "vkCreateShaderModule failed"); return NULL; }
    vka_shader *v = derive(s, &d->ve, mod, 2);
    pthread_mutex_lock(&g_mu);
    if (d->vs) { vka_shader *h = d->vs; pthread_mutex_unlock(&g_mu); vk.vkDestroyShaderModule(V.dev, mod, NULL); free(v); return h; }
    d->vs = v; pthread_mutex_unlock(&g_mu);
    return v;
}
vka_shader *vka_memu_by_key(uint64_t key) {
    static const uint64_t tag[3] = {VKA_MEMU_KEY_CS, VKA_MEMU_KEY_CS ^ VKA_MEMU_KEY_GL, VKA_MEMU_KEY_VS};
    for (int i = 0; i < 3; i++) {
        uint64_t k = key ^ tag[i];
        if (!vka_shader_lib_has(k)) continue;
        vka_shader *s = vka_shader_by_key(k); if (!s || !s->e || s->e->stage < DMSH_VK_STAGE_TS) continue;
        return i < 2 ? vka_memu_cs_of(s, i == 1) : vka_memu_vs_of(s);
    }
    return NULL;
}

int vka_memu_vs_feeds(vka_shader *vs, vka_shader *fs) {
    if (!vs || vs->memu != 2 || !fs || !fs->e || fs->e->stage != DMSH_VK_STAGE_FS) return 1;
    vka_shader *ms = vka_shader_by_key(vs->key ^ VKA_MEMU_KEY_VS); vka_memu_sh *d = ms ? vka_memu_of(ms) : NULL;
    if (!d) return 1;
    char err[128]; size_t nb = 0; uint64_t in = 0;
    uint32_t *w = vka_shader_memu_words(fs, &nb, err, sizeof err);   // (the pixel shader's base words; their inputs are every module's)
    int known = w && vka_memu_fs_input_locs(w, nb / 4, &in);
    free(w);
    return !known || !(in & ~vka_memu_vs_locs(&d->L));
}

// ---------- The arena ----------
// One per submission in flight: taken at the submission's first emulated batch (its counter cleared in the command buffer), given the submission's fence at its commit
// (vka_memu_retire), free again once that fence is done. Each batch of the submission (the mesh draws of one render pass) hands it out from its start again
// (vka_memu_draw.inc MEMU_SETUP clears the counter; vka_memu_rule.h rule 2), so its control words, read once the submission is done, hold the most one batch asked for.
// A batch whose arena had less room draws none of its mesh draws past it (memu_common.glsl alloc: their output is not placed): one pass draws an object and another
// does not. Until 2026-11-15 the whole submission shared the room, so the draws past it were the submission's later passes', the shading after a depth prepass among
// them, and the sky showed through objects the prepass had drawn (16, 32, 64 and 128 MiB steps in Ocarina of Time's first seconds, a submission asking up to 67.6 MiB).
// The size is the whole game's, not each arena's own: every arena taken is made at least as large as the most any batch has asked for (g_peak, doubled from
// VKA_MEMU_ARENA_FIRST_MB up to DM_VK_MESH_EMU_MB, default 256 MiB). Each arena learning only from its own last submission left the second and later ones (taken only
// while the first is still on the GPU, so rarely) at 16 or 32 MiB long after the first had grown. What is left: the first batch that asks for more than any before it
// and more than the first size still loses its last mesh draws, once
enum { NARENA = 6 };
typedef struct { VkBuffer buf, ctl; VkDeviceMemory mem, cmem; volatile uint32_t *cp; uint32_t words; uint64_t fence, last, seen; int used; } arena;
static arena g_ar[NARENA]; static int g_cur = -1; static uint64_t g_acq;   // (last: g_acq when the arena was last taken; seen: the fence whose control words were read)
static uint32_t g_peak;   // the most arena words a batch has asked for
static uint64_t g_short;   // submissions with a batch whose arena had too little room (mesh draws not drawn)
static void arena_free(arena *a) {
    if (a->buf) { vk.vkDestroyBuffer(V.dev, a->buf, NULL); vk.vkFreeMemory(V.dev, a->mem, NULL); }
    a->buf = VK_NULL_HANDLE; a->mem = VK_NULL_HANDLE; a->words = 0;
}
static int arena_make(arena *a, uint32_t words) {
    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = (VkDeviceSize)words * 4, .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT};
    if (vk.vkCreateBuffer(V.dev, &bi, NULL, &a->buf)) { a->buf = VK_NULL_HANDLE; return 0; }
    VkMemoryRequirements mr; vk.vkGetBufferMemoryRequirements(V.dev, a->buf, &mr);
    uint32_t t = vka_mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0); if (t == UINT32_MAX) t = vka_mem_type(mr.memoryTypeBits, 0, 0);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size, .memoryTypeIndex = t};
    if (t == UINT32_MAX || vk.vkAllocateMemory(V.dev, &ai, NULL, &a->mem) || vk.vkBindBufferMemory(V.dev, a->buf, a->mem, 0)) {
        if (a->mem) vk.vkFreeMemory(V.dev, a->mem, NULL); vk.vkDestroyBuffer(V.dev, a->buf, NULL); a->buf = VK_NULL_HANDLE; a->mem = VK_NULL_HANDLE; return 0; }
    a->words = words; vka_mem_grew(mr.size);
    if (!a->ctl) {
        VkBufferCreateInfo ci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 64, .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT};
        if (vk.vkCreateBuffer(V.dev, &ci, NULL, &a->ctl)) { a->ctl = VK_NULL_HANDLE; arena_free(a); return 0; }
        vk.vkGetBufferMemoryRequirements(V.dev, a->ctl, &mr);
        t = vka_mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
        VkMemoryAllocateInfo ci2 = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size, .memoryTypeIndex = t}; void *p = NULL;
        if (t == UINT32_MAX || vk.vkAllocateMemory(V.dev, &ci2, NULL, &a->cmem) || vk.vkBindBufferMemory(V.dev, a->ctl, a->cmem, 0) || vk.vkMapMemory(V.dev, a->cmem, 0, VK_WHOLE_SIZE, 0, &p)) {
            if (a->cmem) vk.vkFreeMemory(V.dev, a->cmem, NULL); vk.vkDestroyBuffer(V.dev, a->ctl, NULL); a->ctl = VK_NULL_HANDLE; a->cmem = VK_NULL_HANDLE; arena_free(a); return 0; }
        a->cp = p; a->cp[VKA_MEMU_CTL_COUNTER] = a->cp[VKA_MEMU_CTL_ASKED] = 0;
    }
    return 1;
}
int vka_memu_arena(VkCommandBuffer cb, VkBuffer *buf, VkBuffer *ctl, uint32_t *cap) {
    if (g_cur < 0) {
        uint64_t done = vka_done(); int k = -1;
        int mb = VKA_ENV("DM_VK_MESH_EMU_MB", 256);   // (words below: 2^18 a MiB, so 16383 MiB at most in 32 bits)
        if (mb < 1 || mb > 16383) { VRED_ONCE("mesh draws through compute: DM_VK_MESH_EMU_MB=%d is not 1-16383: 256 taken", mb); mb = 256; }
        uint32_t maxw = (uint32_t)mb << 18;
        uint32_t rng = V.props.limits.maxStorageBufferRange / 4; if (rng && maxw > rng) maxw = rng;   // (bound whole as a storage buffer)
        // What the batches of the submissions done since the last look asked for (each read once: its arena's control words stay until the arena is taken again)
        for (int i = 0; i < NARENA; i++) {
            arena *o = &g_ar[i];
            if (!o->cp || o->used || !o->fence || o->fence > done || o->seen == o->fence) continue;
            o->seen = o->fence;
            uint32_t asked = o->cp[VKA_MEMU_CTL_ASKED];
            if (asked > o->words) {
                g_short++;
                // Red: draws were lost, the frame was drawn wrong (the first 16; every one is counted in the statistics line)
                static _Atomic int ns; if (atomic_fetch_add(&ns, 1) < 16) VRED("mesh draws through compute: a batch of a submission asked for %.1f MiB of arena, it had %.1f: its mesh draws past that were not drawn (the most a batch asked for so far %.1f MiB)",
                                                                              asked / 262144.0, o->words / 262144.0, (asked > g_peak ? asked : g_peak) / 262144.0);
                if (asked > maxw) VRED_ONCE("mesh draws through compute: a batch asked for %.1f MiB of arena, more than DM_VK_MESH_EMU_MB=%u: some mesh workgroups are not drawn", asked / 262144.0, maxw >> 18);
            }
            if (asked > g_peak) g_peak = asked;
        }
        uint32_t want = vka_memu_arena_want(g_peak, maxw);   // (VKA_MEMU_ARENA_FIRST_MB to start, doubled until it holds the most a batch asked for)
        for (int i = 0; i < NARENA && k < 0; i++) if (g_ar[i].buf && !g_ar[i].used && g_ar[i].fence <= done) k = i;
        for (int i = 0; i < NARENA && k < 0; i++) if (!g_ar[i].buf) k = i;
        if (k < 0) { VRED_ONCE("mesh draws through compute: more than %d submissions in flight with them; the draws of this one are skipped", NARENA); return 0; }
        arena *a = &g_ar[k];
        // Arenas never shrink while used, but one left idle (not taken for ARENA_IDLE submissions: it was for a burst of submissions in flight) gives its memory back;
        // made again, it is made at the size above
        enum { ARENA_IDLE = 1024 };
        g_acq++; a->last = g_acq;
        for (int i = 0; i < NARENA; i++) {
            arena *o = &g_ar[i];
            if (i == k || !o->buf || o->used || o->fence > done || g_acq - o->last < ARENA_IDLE) continue;
            static _Atomic int nl; if (atomic_fetch_add(&nl, 1) < 4) VLOG("mesh draws through compute: arena %d not used for %d submissions: its %.0f MiB freed", i, ARENA_IDLE, o->words / 262144.0);
            arena_free(o);
        }
        if (a->buf && a->words < want) {
            static _Atomic int ng; if (atomic_fetch_add(&ng, 1) < 16) VLOG("mesh draws through compute: arena %d made again larger, %.1f MiB to %.1f MiB (a batch asked for %.1f; DM_VK_MESH_EMU_MB=%u at most)",
                                                                          k, a->words / 262144.0, want / 262144.0, g_peak / 262144.0, maxw >> 18);
            arena_free(a);
        }
        if (!a->buf && !arena_make(a, want)) { VRED_ONCE("mesh draws through compute: no arena of %.0f MiB; the mesh draws are skipped", want / 262144.0); return 0; }
        a->used = 1; g_cur = k;
        vk.vkCmdFillBuffer(cb, a->ctl, 0, 64, 0);   // (outside a pass; the batch's barrier after it)
    }
    arena *a = &g_ar[g_cur]; *buf = a->buf; *ctl = a->ctl; *cap = a->words;
    return 1;
}
void vka_memu_retire(uint64_t fence) { if (g_cur >= 0) { g_ar[g_cur].fence = fence; g_ar[g_cur].used = 0; g_cur = -1; } }

// ---------- The helper passes ----------
int vka_memu_helpers(VkPipeline *setup, VkPipeline *dist, VkPipelineLayout *layout) {
    static int state; static VkPipeline ps, pd; static VkPipelineLayout pl;
    if (!state) {
        state = -1;
        VkDescriptorSetLayoutBinding b[3]; for (uint32_t i = 0; i < 3; i++) b[i] = (VkDescriptorSetLayoutBinding){i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL};
        VkDescriptorSetLayoutCreateInfo li = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, .flags = VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR, .bindingCount = 3, .pBindings = b};
        VkDescriptorSetLayout sl; VkPushConstantRange pr = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 16};
        if (!V.push_desc || vk.vkCreateDescriptorSetLayout(V.dev, &li, NULL, &sl)) { VRED("mesh draws through compute: no push descriptors for the helper passes; the mesh draws are skipped"); return 0; }
        VkPipelineLayoutCreateInfo pi = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1, .pSetLayouts = &sl, .pushConstantRangeCount = 1, .pPushConstantRanges = &pr};
        if (vk.vkCreatePipelineLayout(V.dev, &pi, NULL, &pl)) return 0;
        const uint32_t *code[2] = {SPV_MEMU_SETUP, SPV_MEMU_DIST}; size_t len[2] = {sizeof SPV_MEMU_SETUP, sizeof SPV_MEMU_DIST}; VkPipeline *out[2] = {&ps, &pd};
        for (int i = 0; i < 2; i++) {
            VkShaderModule m = make_module(code[i], len[i] / 4); if (!m) return 0;
            VkComputePipelineCreateInfo ci = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO, .layout = pl, .stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_COMPUTE_BIT, .module = m, .pName = "main"}};
            VkResult r = vk.vkCreateComputePipelines(V.dev, VK_NULL_HANDLE, 1, &ci, NULL, out[i]); vk.vkDestroyShaderModule(V.dev, m, NULL);
            if (r) { VRED("mesh draws through compute: helper pipeline %d: %d", i, r); return 0; }
        }
        state = 1;
    }
    if (state < 0) return 0;
    *setup = ps; *dist = pd; *layout = pl; return 1;
}
void vka_memu_stats(char *b, size_t n) {
    static uint64_t b0, d0, s0, k0, w0, q0, h0;
    uint64_t nb = vka_memu_n_batches - b0, nd = vka_memu_n_draws - d0, ns = vka_memu_n_splits - s0, nk = vka_memu_n_skip - k0, nw = vka_memu_n_wait - w0, nq = g_short - q0, nh = vka_memu_n_hold - h0;
    b0 = vka_memu_n_batches; d0 = vka_memu_n_draws; s0 = vka_memu_n_splits; k0 = vka_memu_n_skip; w0 = vka_memu_n_wait; q0 = g_short; h0 = vka_memu_n_hold;
    if (!nb && !nd) { if (n) b[0] = 0; return; }
    char pl[400]; vka_memu_pso_line(pl, sizeof pl);
    uint64_t aw = 0; int na = 0; for (int i = 0; i < NARENA; i++) if (g_ar[i].buf) { aw += g_ar[i].words; na++; }   // (read without the replay's lock: a statistic)
    snprintf(b, n, "mesh draws through compute: %llu draws in %llu batches (%.1f a batch), %llu render passes split for one, %llu not prepared, %llu skipped waiting for their compute pipelines, "
             "%llu held in frames that began with one being made; arenas %d, %.0f MiB of device memory (the most a batch asked for %.1f MiB), %llu submissions short of room (their last mesh draws not drawn)%s",
             (unsigned long long)nd, (unsigned long long)nb, nb ? (double)nd / (double)nb : 0.0, (unsigned long long)ns, (unsigned long long)nk, (unsigned long long)nw, (unsigned long long)nh,
             na, aw / 262144.0, g_peak / 262144.0, (unsigned long long)nq, pl);
}
#endif
