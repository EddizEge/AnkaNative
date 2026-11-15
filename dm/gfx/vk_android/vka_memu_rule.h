// Mesh draws through compute (vka_memu_draw.inc, vka_memu.c): the two rules that keep a mesh-drawn object drawn in all its passes of a frame or in none, as pure
// functions so t_android_memurule.c can run them. Plain C, no Vulkan.
//
// A frame of Ocarina of Time draws a depth prepass (colour mask 0, LESS, depth write), the sky over the whole target with depth test off, then the shading with depth
// EQUAL, and the prepass and the shading draw an object with two different mesh programs (docs/reference/shaders.md "Sky pixels along the edges of mesh-drawn objects").
// Wherever the prepass drew an object and the shading did not, the sky shows through it. Two things dropped mesh draws in one pass and not the other: a compute form
// still being made (its draws are skipped until it is there, vka_pso.c vka_memu_pipeline), and an arena too small for the submission (the draws past its room place no
// output). Dropped in the prepass and drawn in the shading the object is only absent (EQUAL fails, what is behind it shows), so each rule errs that way.
#pragma once
#include <stdint.h>

// 1. No mesh draw in a frame that began with a form a draw asked for still being made. Latched at the frame's first mesh draw whose forms are there: the forms of a
// frame's later passes are often first asked for after its earlier passes were recorded, so a frame that began drawing keeps drawing every mesh draw it can (a form
// asked for in that frame is held from the next one on), and a frame that began holding holds all of them, also those whose forms come in meanwhile.
// waited: forms a draw asked for that are not made yet (vka_memu_forms_waited). Returns 1 when the frame's mesh draws are drawn
typedef struct { uint64_t frame; int on, set; } vka_memu_frame_rule;
static inline int vka_memu_frame_draws(vka_memu_frame_rule *r, uint64_t frame, int waited) {
    if (!r->set || r->frame != frame) { r->set = 1; r->frame = frame; r->on = waited <= 0; }
    return r->on;
}

// 2. The arena's room is a batch's, not the submission's: each batch (the mesh draws of one render pass, vka_exec.c memu_scan) hands out the arena from its start again
// (its counter cleared before its setup pass; the full barrier there orders the earlier batch's draws, which read the arena, before this batch's compute writes it), so
// a submission's need is its largest batch's instead of the sum of all its batches. The first size is VKA_MEMU_ARENA_FIRST_MB, doubled until it holds the most a batch
// has asked for, at most max_words (DM_VK_MESH_EMU_MB). Sizes in 32-bit words
enum { VKA_MEMU_ARENA_FIRST_MB = 64 };
static inline uint32_t vka_memu_arena_want(uint32_t peak_words, uint32_t max_words) {
    uint64_t w = (uint64_t)VKA_MEMU_ARENA_FIRST_MB << 18;
    while (w < peak_words && w < max_words) w *= 2;
    return w > max_words ? max_words : (uint32_t)w;
}
