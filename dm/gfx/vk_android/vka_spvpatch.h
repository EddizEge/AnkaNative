// SPIR-V rewrites made by the replay at pipeline time (vka_spvpatch.c). Plain C, no Vulkan
#pragma once
#include <stddef.h>
#include <stdint.h>
// The vertex module w (nw words) with the vec4 float Input variables at the locations of umask retyped as uvec4 and those of smask as ivec4, every load of them bit-cast back
// to float: the shader then sees the integer register bits Maxwell's fetch gives an integer format. A malloc'd copy (*out_nw words), or NULL with the reason in err
uint32_t *vka_spv_vin_retype(const uint32_t *w, size_t nw, uint32_t umask, uint32_t smask, size_t *out_nw, char *err, size_t errn);
// Subgroup operations and built-ins rewritten as subgroups of one invocation, for a stage the device runs none in (vka_spvpatch.c). A malloc'd copy (*out_nw words,
// *changed = 1), NULL with err empty when the module has none, NULL with the reason in err when it cannot be rewritten
uint32_t *vka_spv_subgroup_single(const uint32_t *w, size_t nw, size_t *out_nw, int *changed, char *err, size_t errn);
// A geometry shader's memory writes, counted (the return; -1 with err on failure) and, with out and a count above 0, the module with each of them made only by the run of
// the shader that first claims its primitive in the storage buffer at set 0 binding (zeroed for each draw, a word per primitive), malloc'd (*out_nw words): vka_spvpatch.c
int vka_spv_gs_claim(const uint32_t *w, size_t nw, uint32_t binding, uint32_t **out, size_t *out_nw, char *err, size_t errn);
// A pixel shader's float colour outputs at the locations of umask / smask as unsigned / signed integers of their bits (each store bit-cast), for integer attachments: malloc'd
// (*out_nw words), NULL with the reason in err (vka_spvpatch.c)
uint32_t *vka_spv_fout_retype(const uint32_t *w, size_t nw, uint32_t umask, uint32_t smask, size_t *out_nw, char *err, size_t errn);
// A module's float-sampled UniformConstant image / sampled-image variables at set 0 bindings of umask / smask retyped as uint / int sampled images, each read bit-cast back
// to float under the original result id (for integer textures bound to float-typed bindings): malloc'd (*out_nw words), NULL with the reason in err (vka_spvpatch.c)
uint32_t *vka_spv_tex_retype(const uint32_t *w, size_t nw, uint64_t umask, uint64_t smask, size_t *out_nw, char *err, size_t errn);
// The variables at DescriptorSet set, Binding binding moved to DescriptorSet to, in place (vka_spvpatch.c): how many (0 none, -1 not a SPIR-V module)
int vka_spv_move_set(uint32_t *w, size_t nw, uint32_t set, uint32_t binding, uint32_t to);
// A pixel module whose vec4 float outputs at the locations of `modes` (2 bits each: 1 = source factor SRC_ALPHA, 2 = ONE_MINUS_SRC_ALPHA) return a NaN or Inf rgb component as
// 0 where their alpha makes that blend weight zero, as NVIDIA's blender does (vka_spvpatch.c, DM_VK_BLEND_ZERO): malloc'd (*out_nw words), NULL with the reason in err (empty: none to guard)
uint32_t *vka_spv_blend_zero(const uint32_t *w, size_t nw, uint32_t modes, size_t *out_nw, char *err, size_t errn);
// A compute-like module's specialization constants made plain constants, SpecId spec_id taking value, when it reads a subgroup lane built-in (Qualcomm's driver reads the
// lane as 0 in such a module that has any specialization constant): malloc'd (*out_nw words), NULL when there is nothing to do or it cannot (vka_spvpatch.c; Banana Native)
uint32_t *vka_spv_spec_freeze(const uint32_t *w, size_t nw, uint32_t spec_id, uint32_t value, size_t *out_nw);
// The same fold for any module with a SpecId, lane built-ins or not: a module made into another stage's form whose specialization constant the pipeline will not set
// (a mesh shader's compute form, vka_memu.c: its GL depth constant). → 1 folded (*out malloc'd, *out_nw words), 0 no SpecId (nothing to do), -1 not foldable here
// (a specialization constant composite or operation)
int vka_spv_spec_fold(const uint32_t *w, size_t nw, uint32_t spec_id, uint32_t value, uint32_t **out, size_t *out_nw);
