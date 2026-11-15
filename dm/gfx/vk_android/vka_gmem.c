// Raw global memory loads: one page lookup or pool search for a group of loads from one base address. Plain C, no Vulkan (unit-tested by t_android_gmem.c).
//
// The translator reads raw global memory (an address the shader computed) through one helper function per module (GlobalToStorage.TryGenerateRawStorageOp), called
// once for each 4-byte load with the address's low and high words in two Function variables. The library's builder (vk_android_spv.py) gives the helper one of two
// forms: in a graphics, task or mesh stage the page index (gmem_pidx: the 4 KiB page's record read from the table's buffer, at an index computed from the address's
// high word and its low word >> 12, then the device address built from the record and the access made), in compute the pool search (gmem_loop: a loop of 12 halving
// steps over the sorted pool table, two table words read per step, then the entry's words and the access). Every call repeats its lookup, even for neighbours: Ocarina
// of Time's forest vertex shaders b1f06157308e3b22 / f8ead72a83b146b7 load 253 words, mostly the four words of a vec4 at base + 0, 4, 8, 12, each with its own
// dependent table read; the froxel compute pass 9e6b00b050e30801 runs 208 searches of 12 dependent steps each (session-2026-11-14 perf-measure-1.md, perf-gmem.md).
//
// A call's address words are the last values stored, in its block, to the two Function variables it is given, which is only sure when nothing else can write those
// variables: both must be the calling function's own variables, used in it by nothing but OpLoad, OpStore (as the pointer) and calls of the load helpers (own_vars;
// a copy or an access chain of one, even with no index, would be a second pointer a store could go through unseen: such calls are left as they are and counted).
// The call sites give the address as base + constant: low = base_lo + k, high = base_hi + (low < base_lo) (the translator's 64-bit add of a zero-extended k). For a
// group of calls of one helper with the same base words (the same SSA values), offsets within 4,095 bytes of each other, in one block with no memory write, barrier,
// atomic or other call between its first and last call, every address A_i = B + k_i lies in [A_min, A_max] = [B + k_min, B + k_max] (mod 2^64, a run of at most
// 4,096 bytes). The group's head (right before its first call) runs the lookup at A_min and at A_max; each call then takes its answer from them:
//  - page index form: A >> 12 (the page, q) of every A_i is q(A_min) or q(A_max), as a run of at most 4,096 bytes touches at most two 4 KiB pages, the pages of its
//    ends (also across 2^32 and 2^64). The helper's table reads whose index depends on the address depend on it only through q: its high word and its low word shifted
//    right by 12 or more (checked on the helper's code, below; with the table's header words, constant-index reads, and earlier such reads). So each such read of call
//    i reads the word the lookup at A_min read when q(A_i) = q(A_min), else the word the lookup at A_max read: the rewritten helper takes those values, selected by
//    q(A_i) == q(A_min). Everything else of the helper (the pool check, the device address from the record and the low 12 bits, the access, the sink, the value) runs as before.
//  - pool search form: each step k of the search compares the table's start of the candidate (idx + step_k, a word independent of the address) with the address as
//    one 64-bit unsigned "start <= A" (checked on the helper's code), so the step's decision is monotone in A, and the search's answer g(A) is the sum of the steps
//    decided true. For A_min <= A <= A_max (no wrap past 2^64: high(A_max) >= base_hi), A's decisions equal those of A_min and A_max on every step where those two
//    agree, up to their first disagreement (induction on the steps): g(A) has the leading bits g(A_min) and g(A_max) share, and its search continues from that step.
//    So each call starts its search at idx0 = g(A_min) with the bits from the highest differing bit down cleared and at step0 = that bit (0 when they are equal: one
//    pass of the loop that changes nothing, as step 0 adds nothing), and runs the helper's own loop from there: the same steps the original takes after that point.
//    With a wrap past 2^64 every call searches from the start (idx 0, the first step), as before.
// Table words read at the head and by the calls are the same words with nothing written between (the group's rule), so the answers are the original's for every
// address, every table and every memory: same-page runs, page crossings, unmapped pages (record 0: the sink and the value 0, as before), entries anywhere in the table.
// Calls of helpers this pass does not prove, calls whose address words it cannot follow, and groups of too few calls (page index: fewer than 3; search: fewer than 2)
// are left as they are and counted. Nothing is reordered: each call stays where it is and makes its own access.
#include "vka_gmem.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

enum { OpExtInst = 12, OpTypeVoid = 19, OpTypeBool = 20, OpTypeInt = 21, OpTypeFloat = 22, OpTypeVector = 23, OpTypeStruct = 30, OpTypePointer = 32, OpTypeFunction = 33,
       OpConstant = 43, OpFunction = 54, OpFunctionParameter = 55, OpFunctionEnd = 56, OpFunctionCall = 57, OpVariable = 59, OpLoad = 61, OpStore = 62,
       OpCopyMemory = 63, OpCopyMemorySized = 64, OpAccessChain = 65, OpInBoundsAccessChain = 66, OpVectorShuffle = 79, OpCompositeConstruct = 80,
       OpCompositeExtract = 81, OpCompositeInsert = 82, OpCopyObject = 83, OpConvertUToPtr = 120, OpBitcast = 124, OpIAdd = 128, OpLogicalOr = 166,
       OpLogicalAnd = 167, OpSelect = 169, OpIEqual = 170, OpUGreaterThanEqual = 174, OpULessThan = 176, OpULessThanEqual = 178, OpShiftRightLogical = 194,
       OpBitwiseOr = 197, OpBitwiseXor = 198, OpBitwiseAnd = 199, OpNot = 200, OpImageWrite = 99, OpPhi = 245, OpLoopMerge = 246, OpLabel = 248, OpBranch = 249,
       OpBranchConditional = 250, OpReturn = 253, OpReturnValue = 254 };
enum { SCUniform = 2, SCOutput = 3, SCPrivate = 6, SCFunction = 7, SCStorageBuffer = 12, SCPhysicalStorageBuffer = 5349 };
enum { FORM_PIDX = 1, FORM_LOOP = 2 };
#define MAXDYN 8
#define MAXH 8

typedef struct { uint32_t *w; size_t n, cap; } vec_t;
static int put(vec_t *v, uint32_t x) {
    if (v->n == v->cap) { size_t c = v->cap ? 2 * v->cap : 4096; uint32_t *p = realloc(v->w, c * 4); if (!p) return 0; v->w = p; v->cap = c; }
    v->w[v->n++] = x; return 1;
}
static int put_n(vec_t *v, const uint32_t *x, size_t n) { for (size_t i = 0; i < n; i++) if (!put(v, x[i])) return 0; return 1; }

typedef struct {
    const uint32_t *w; size_t nw; uint32_t bound, nb;   // nb: the next new id
    size_t *def;                                        // def[id]: word offset of the instruction that defines id (0: none)
    size_t first_fn;
    uint32_t entry;
    vec_t decl;                                         // new types and constants, emitted before the first function
    // new declarations by kind, so each is made once (a type twice is invalid SPIR-V): [k] = id
    uint32_t t_bool, t_u32;
} mod_t;
#define OPC(m, i) ((m)->w[i] & 0xffff)
#define LEN(m, i) ((m)->w[i] >> 16)
static int has_result(uint32_t op) {   // the instruction's word 2 is its result id (word 1 its type)
    switch (op) {
    case OpExtInst: case OpConstant: case 41: case 42: case 44: case 45: case 46: case 48: case 49: case 50: case 51: case 52: case OpFunction: case OpFunctionParameter: case OpFunctionCall:
    case OpVariable: case OpLoad: case OpAccessChain: case OpInBoundsAccessChain: case 67: case 70: case 60: case OpVectorShuffle: case OpCompositeConstruct:
    case OpCompositeExtract: case OpCompositeInsert: case OpCopyObject: case 84: case OpPhi: case 1: case 68: return 1;
    }
    if (op >= 333 && op <= 366) return 1;   // subgroup operations
    if (op >= 86 && op <= 205) return 1;       // images, conversions, arithmetic, relational, logical, bits
    if (op >= 227 && op <= 242 && op != 228) return 1;   // atomics (not OpAtomicStore)
    return 0;
}
static uint32_t type_of(const mod_t *m, uint32_t id) { size_t d = id < m->bound ? m->def[id] : 0; return d && has_result(OPC(m, d)) ? m->w[d + 1] : 0; }
static uint32_t op_of(const mod_t *m, uint32_t id) { size_t d = id < m->bound ? m->def[id] : 0; return d ? OPC(m, d) : 0; }
static int int32_type(const mod_t *m, uint32_t t) { size_t d = t < m->bound ? m->def[t] : 0; return d && OPC(m, d) == OpTypeInt && m->w[d + 2] == 32; }
static int scalar32(const mod_t *m, uint32_t t) { size_t d = t < m->bound ? m->def[t] : 0; return d && (OPC(m, d) == OpTypeInt || OPC(m, d) == OpTypeFloat) && m->w[d + 2] == 32; }
static int const32(const mod_t *m, uint32_t id, uint32_t *v) {
    size_t d = id < m->bound ? m->def[id] : 0;
    if (!d || OPC(m, d) != OpConstant || LEN(m, d) != 4 || !int32_type(m, m->w[d + 1])) return 0;
    *v = m->w[d + 3]; return 1;
}
// The storage class of a pointer-typed id (its type's), or ~0
static uint32_t sc_of(const mod_t *m, uint32_t id) {
    uint32_t t = type_of(m, id); size_t d = t < m->bound ? m->def[t] : 0;
    return d && OPC(m, d) == OpTypePointer ? m->w[d + 2] : ~0u;
}
static uint32_t pointee(const mod_t *m, uint32_t pt) { size_t d = pt < m->bound ? m->def[pt] : 0; return d && OPC(m, d) == OpTypePointer ? m->w[d + 3] : 0; }
// Through 32-bit scalar bit casts and copies (the bits are the same)
static uint32_t root(const mod_t *m, uint32_t v) {
    for (int k = 0; k < 64; k++) {
        size_t d = v < m->bound ? m->def[v] : 0;
        if (!d || (OPC(m, d) != OpBitcast && OPC(m, d) != OpCopyObject) || LEN(m, d) != 4) return v;
        uint32_t s = m->w[d + 3];
        if (!scalar32(m, m->w[d + 1]) || !scalar32(m, type_of(m, s))) return v;
        v = s;
    }
    return v;
}

// ---- new declarations
static uint32_t find_type(mod_t *m, const uint32_t *x, uint32_t n) {   // an existing type of these words (x[0] the opcode word, x[1] skipped: the result), else new
    for (size_t i = 5; i < m->first_fn; i += LEN(m, i)) {
        if (LEN(m, i) == n && m->w[i] == x[0]) { uint32_t k = 2; for (; k < n; k++) if (m->w[i + k] != x[k]) break; if (k == n) return m->w[i + 1]; }
    }
    for (size_t i = 0; i < m->decl.n; i += m->decl.w[i] >> 16) {
        if ((m->decl.w[i] >> 16) == n && m->decl.w[i] == x[0]) { uint32_t k = 2; for (; k < n; k++) if (m->decl.w[i + k] != x[k]) break; if (k == n) return m->decl.w[i + 1]; }
    }
    uint32_t id = m->nb++;
    if (!put(&m->decl, x[0])) return 0;
    if (!put(&m->decl, id)) return 0;
    if (!put_n(&m->decl, x + 2, n - 2)) return 0;
    return id;
}
static uint32_t ty_bool(mod_t *m) { if (!m->t_bool) { uint32_t x[] = {2u << 16 | OpTypeBool, 0}; m->t_bool = find_type(m, x, 2); } return m->t_bool; }
static uint32_t ty_u32(mod_t *m) { if (!m->t_u32) { uint32_t x[] = {4u << 16 | OpTypeInt, 0, 32, 0}; m->t_u32 = find_type(m, x, 4); } return m->t_u32; }
static uint32_t ty_fn(mod_t *m, uint32_t ret, const uint32_t *par, uint32_t np) {
    uint32_t x[16]; if (np > 12) return 0;
    x[0] = (3 + np) << 16 | OpTypeFunction; x[1] = 0; x[2] = ret; for (uint32_t k = 0; k < np; k++) x[3 + k] = par[k];
    return find_type(m, x, 3 + np);
}
static uint32_t ty_vec2(mod_t *m, uint32_t comp) { uint32_t x[] = {4u << 16 | OpTypeVector, 0, comp, 2}; return find_type(m, x, 4); }
static uint32_t ty_struct(mod_t *m, const uint32_t *mem, uint32_t n) {   // always a new one (an existing struct of these members may be a decorated Block)
    uint32_t id = m->nb++;
    if (!put(&m->decl, (2 + n) << 16 | OpTypeStruct) || !put(&m->decl, id) || !put_n(&m->decl, mem, n)) return 0;
    return id;
}
static uint32_t cst(mod_t *m, uint32_t t, uint32_t v) {   // a 32-bit integer constant (constants may repeat; one per value keeps it small)
    for (size_t i = 0; i < m->decl.n; i += m->decl.w[i] >> 16)
        if (m->decl.w[i] == (4u << 16 | OpConstant) && m->decl.w[i + 1] == t && m->decl.w[i + 3] == v) return m->decl.w[i + 2];
    uint32_t id = m->nb++, x[] = {4u << 16 | OpConstant, t, id, v};
    return put_n(&m->decl, x, 4) ? id : 0;
}

// ---- instruction shapes
// Word positions (from the instruction's start) that hold ids, for the opcodes a helper may contain (anything else: the helper is not taken). -1 = not handled
static int id_words(const uint32_t *x, int *pos) {
    uint32_t op = x[0] & 0xffff, len = x[0] >> 16; int n = 0;
    switch (op) {
    case OpLoad:
        if (len > 4 && (x[4] & ~7u)) return -1;   // memory operands: Volatile, Aligned, Nontemporal only (no scope ids)
        pos[n++] = 1; pos[n++] = 2; pos[n++] = 3; return n;
    case OpCompositeExtract: pos[n++] = 1; pos[n++] = 2; pos[n++] = 3; return n;
    case OpCompositeInsert: case OpVectorShuffle: pos[n++] = 1; pos[n++] = 2; pos[n++] = 3; pos[n++] = 4; return n;
    case OpExtInst: pos[n++] = 1; pos[n++] = 2; pos[n++] = 3; for (uint32_t k = 5; k < len && n < 32; k++) pos[n++] = (int)k; return n;
    case OpLabel: case OpBranch: case OpReturnValue: pos[n++] = 1; return n;
    case OpReturn: case OpFunctionEnd: return 0;
    case OpBranchConditional: if (len != 4) return -1; pos[n++] = 1; pos[n++] = 2; pos[n++] = 3; return n;
    case OpLoopMerge: if (len != 4) return -1; pos[n++] = 1; pos[n++] = 2; return n;
    case OpFunction: pos[n++] = 1; pos[n++] = 2; pos[n++] = 4; return n;
    case OpFunctionParameter: pos[n++] = 1; pos[n++] = 2; return n;
    case OpPhi: case OpAccessChain: case OpInBoundsAccessChain: case OpCompositeConstruct: case OpCopyObject: case OpConvertUToPtr: case OpBitcast: case OpSelect:
        for (uint32_t k = 1; k < len && n < 32; k++) pos[n++] = (int)k; return len <= 32 ? n : -1;
    }
    if ((op >= 109 && op <= 117) || (op >= 126 && op <= 205)) { for (uint32_t k = 1; k < len && n < 32; k++) pos[n++] = (int)k; return len <= 32 ? n : -1; }
    return -1;
}
// Instructions that may write memory another load can see, or make other invocations' writes visible (a group ends there). Stores to Function, Private and Output
// variables do not (an output is not memory a load reads); every other store, copy, image write, atomic, barrier and call of a function that is not a load helper does
static int writes_memory(const mod_t *m, size_t i, const uint32_t *helper_ids, int nh) {
    uint32_t op = OPC(m, i);
    if (op == OpStore || op == OpCopyMemory || op == OpCopyMemorySized) {
        uint32_t sc = sc_of(m, m->w[i + 1]);
        return !(sc == SCFunction || sc == SCPrivate || sc == SCOutput);
    }
    if (op == OpFunctionCall) { for (int k = 0; k < nh; k++) if (m->w[i + 3] == helper_ids[k]) return 0; return 1; }
    if (op == OpImageWrite || op == 224 || op == 225 || (op >= 227 && op <= 242) || op == 5614 || op == 5615 || op == 6035) return 1;   // barriers, atomics
    if (op == 5380) return 1;   // OpDemoteToHelperInvocation (conservative)
    if (op == OpExtInst) { for (uint32_t k = 5; k < LEN(m, i); k++) if (sc_of(m, m->w[i + k]) != ~0u) return 1; }   // GLSL.std.450 Modf / Frexp write through a pointer
    return 0;
}

// ---- helpers
typedef struct {
    uint32_t fid, ret_t, fn_t, p0, p1, pt0, pt1;
    size_t at, end;                 // OpFunction and OpFunctionEnd offsets
    int form;
    // page index form: the table reads whose index depends on the address (in order) and their types
    int ndyn; size_t dyn[MAXDYN]; uint32_t dyn_t[MAXDYN];
    // pool search form: the blocks (label offsets), the phis, their initial constants, the loop's index type
    size_t b0, bh, bb, bc, bm; uint32_t phi_i, phi_s, i_init, s_init, idx_t, iN;
    // made: the new functions
    uint32_t f_new, f_aux, st_t;    // the rewritten helper; LK (page index) or DS (search); the struct (page index) or vec2 (search) the aux returns
} helper_t;

static int is_ext_with_ptr(const mod_t *m, size_t i) {   // an OpExtInst with a pointer operand (Modf / Frexp)
    for (uint32_t k = 5; k < LEN(m, i); k++) if (sc_of(m, m->w[i + k]) != ~0u) return 1;
    return 0;
}
static int helper_params(const mod_t *m, helper_t *h) {   // OpFunction, two pointer-to-32-bit-int Function parameters, then the body
    size_t i = h->at;
    h->ret_t = m->w[i + 1]; h->fn_t = m->w[i + 4];
    size_t a = i + LEN(m, i), b;
    if (OPC(m, a) != OpFunctionParameter) return 0;
    b = a + LEN(m, a);
    if (OPC(m, b) != OpFunctionParameter) return 0;
    if (OPC(m, b + LEN(m, b)) != OpLabel) return 0;
    h->pt0 = m->w[a + 1]; h->p0 = m->w[a + 2]; h->pt1 = m->w[b + 1]; h->p1 = m->w[b + 2];
    { size_t d = h->ret_t < m->bound ? m->def[h->ret_t] : 0; if (!d || OPC(m, d) == OpTypePointer) return 0; }   // (a pointer result could carry a parameter out)
    for (int k = 0; k < 2; k++) {
        uint32_t pt = k ? h->pt1 : h->pt0; size_t d = pt < m->bound ? m->def[pt] : 0;
        if (!d || OPC(m, d) != OpTypePointer || m->w[d + 2] != SCFunction || !int32_type(m, m->w[d + 3])) return 0;
    }
    return 1;
}
// The kind of a load in a helper: 1 a parameter's word (low), 2 the high word, 3 a table read (Uniform / StorageBuffer variable through an access chain), 4 a physical
// pointer access, 0 anything else
static int load_kind(const mod_t *m, const helper_t *h, uint32_t ptr) {
    if (ptr == h->p0) return 1;
    if (ptr == h->p1) return 2;
    size_t d = ptr < m->bound ? m->def[ptr] : 0;
    if (!d) return 0;
    if ((OPC(m, d) == OpAccessChain || OPC(m, d) == OpInBoundsAccessChain)) {
        uint32_t base = m->w[d + 3]; size_t v = base < m->bound ? m->def[base] : 0;
        if (v && OPC(m, v) == OpVariable && v < m->first_fn && (m->w[v + 3] == SCUniform || m->w[v + 3] == SCStorageBuffer)) return 3;
        return 0;
    }
    if (OPC(m, d) == OpConvertUToPtr && sc_of(m, ptr) == SCPhysicalStorageBuffer) return 4;
    return 0;
}

// Taint of a value in a page index helper: 0 none (constants, header words), T_Q a function of the address's page q = A >> 12 (and of words that are), T_LOP the low
// word itself (through 32-bit bit casts), T_LO anything else that depends on the low word or on raw memory
enum { T_Q = 1, T_LOP = 2, T_LO = 3 };
static int take_pidx(const mod_t *m, helper_t *h, uint8_t *taint) {
    size_t i = h->at; int pos[40];
    i += LEN(m, i); i += LEN(m, i); i += LEN(m, i);   // OpFunction, 2 parameters
    if (OPC(m, i) != OpLabel) return 0;
    i += LEN(m, i);
    int u2p = 0, psb = 0;
    h->ndyn = 0;
    for (; i < h->end; i += LEN(m, i)) {
        uint32_t op = OPC(m, i);
        if (op == OpReturnValue) { if (i + LEN(m, i) != h->end) return 0; break; }
        int np = id_words(m->w + i, pos);
        if (np < 0 || op == OpLabel || op == OpBranch || op == OpBranchConditional || op == OpLoopMerge || op == OpPhi || op == OpFunction || op == OpFunctionParameter) return 0;
        if (op == OpExtInst && is_ext_with_ptr(m, i)) return 0;
        uint32_t r = m->w[i + 2]; if (r >= m->bound) return 0;
        int t = 0;
        if (op == OpLoad) {
            int k = load_kind(m, h, m->w[i + 3]);
            if (k == 1) t = T_LOP;
            else if (k == 2) t = T_Q;
            else if (k == 3) {
                size_t d = m->def[m->w[i + 3]];
                for (uint32_t q = 4; q < LEN(m, d); q++) { uint32_t x = m->w[d + q]; int tx = x < m->bound ? taint[x] : 0; if (tx > t) t = tx; }
                if (t == T_LOP || t == T_LO) return 0;   // a table read whose index depends on the low word below bit 12
                if (t == T_Q) { if (h->ndyn == MAXDYN) return 0; h->dyn[h->ndyn] = i; h->dyn_t[h->ndyn++] = m->w[i + 1]; }
            }
            else if (k == 4) { t = T_LO; psb++; }
            else return 0;
        } else {
            if (op == OpConvertUToPtr) u2p++;
            for (int q = 0; q < np; q++) {   // the operands (not the type and result; not an extended instruction's set)
                if (pos[q] < 3 || (op == OpExtInst && pos[q] == 3)) continue;
                uint32_t x = m->w[i + pos[q]]; int tx = x < m->bound ? taint[x] : 0; if (tx > t) t = tx; }
            if (t == T_LOP && !((op == OpBitcast || op == OpCopyObject) && scalar32(m, m->w[i + 1]))) {
                uint32_t c;   // the low word shifted right by 12 or more: a function of the page
                if (op == OpShiftRightLogical && LEN(m, i) == 5 && scalar32(m, m->w[i + 1]) && taint[m->w[i + 3]] == T_LOP && const32(m, m->w[i + 4], &c) && c >= 12 && c < 32) t = T_Q;
                else t = T_LO;
            }
        }
        taint[r] = (uint8_t)t;
    }
    if (i >= h->end || !u2p || !psb || !h->ndyn) return 0;
    for (int k = 0; k < h->ndyn; k++) if (!int32_type(m, h->dyn_t[k]) && !scalar32(m, h->dyn_t[k])) return 0;
    return 1;
}
// The pool search form (gmem_loop's shape): entry block (branch only), header (two phis, merge, branch), body, continue (step == 0 check), merge (straight to the return)
static size_t next_block(const mod_t *m, size_t i, size_t end) { for (i += LEN(m, i); i < end && OPC(m, i) != OpLabel; i += LEN(m, i)) {} return i; }
static int take_loop(const mod_t *m, helper_t *h) {
    size_t i = h->at; i += LEN(m, i); i += LEN(m, i); i += LEN(m, i);
    h->b0 = i; h->bh = next_block(m, h->b0, h->end); h->bb = next_block(m, h->bh, h->end); h->bc = next_block(m, h->bb, h->end); h->bm = next_block(m, h->bc, h->end);
    if (h->bm >= h->end || next_block(m, h->bm, h->end) < h->end) return 0;
    uint32_t L0 = m->w[h->b0 + 1], Lh = m->w[h->bh + 1], Lb = m->w[h->bb + 1], Lc = m->w[h->bc + 1], Lm = m->w[h->bm + 1];
    // entry: OpLabel, OpBranch Lh
    size_t x = h->b0 + 2; if (OPC(m, x) != OpBranch || m->w[x + 1] != Lh || x + 2 != h->bh) return 0;
    // header: two phis (from L0 and Lc), OpLoopMerge Lm Lc, OpBranch Lb
    x = h->bh + 2; uint32_t phis[2][3]; int np = 0;   // [result, init, back]
    while (OPC(m, x) == OpPhi) {
        if (LEN(m, x) != 7 || np == 2) return 0;
        uint32_t a = m->w[x + 3], pa = m->w[x + 4], b = m->w[x + 5], pb = m->w[x + 6];
        if (pa == L0 && pb == Lc) { phis[np][1] = a; phis[np][2] = b; } else if (pb == L0 && pa == Lc) { phis[np][1] = b; phis[np][2] = a; } else return 0;
        phis[np][0] = m->w[x + 2];
        if (!int32_type(m, m->w[x + 1])) return 0;
        h->idx_t = m->w[x + 1]; np++; x += LEN(m, x);
    }
    if (np != 2 || type_of(m, phis[0][0]) != type_of(m, phis[1][0])) return 0;
    if (OPC(m, x) != OpLoopMerge || LEN(m, x) != 4 || m->w[x + 1] != Lm || m->w[x + 2] != Lc) return 0;
    x += 4; if (OPC(m, x) != OpBranch || m->w[x + 1] != Lb || x + 2 != h->bb) return 0;
    // continue: e = IEqual(sN, 0), OpBranchConditional e Lm Lh
    x = h->bc + 2; uint32_t c;
    if (OPC(m, x) != OpIEqual || LEN(m, x) != 5) return 0;
    uint32_t e = m->w[x + 2], sN = m->w[x + 3];
    if (!(const32(m, m->w[x + 4], &c) && c == 0)) { sN = m->w[x + 4]; if (!(const32(m, m->w[x + 3], &c) && c == 0)) return 0; }
    x += 5; if (OPC(m, x) != OpBranchConditional || LEN(m, x) != 4 || m->w[x + 1] != e || m->w[x + 2] != Lm || m->w[x + 3] != Lh || x + 4 != h->bm) return 0;
    // which phi is the step: its back value is sN = ShiftRightLogical(phi, 1)
    int si = -1;
    for (int k = 0; k < 2; k++) if (phis[k][2] == sN) si = k;
    if (si < 0) return 0;
    h->phi_s = phis[si][0]; h->s_init = phis[si][1]; h->phi_i = phis[1 - si][0]; h->i_init = phis[1 - si][1]; h->iN = phis[1 - si][2];
    size_t d = m->def[sN]; uint32_t one;
    if (!d || d < h->bb || d >= h->bc || OPC(m, d) != OpShiftRightLogical || m->w[d + 3] != h->phi_s || !const32(m, m->w[d + 4], &one) || one != 1) return 0;
    uint32_t i0, s0;
    if (!const32(m, h->i_init, &i0) || i0 != 0 || !const32(m, h->s_init, &s0) || !s0 || (s0 & (s0 - 1)) || s0 > (1u << 30)) return 0;
    // iN = Select(cond, mid, phi_i), mid = IAdd(phi_i, phi_s)
    d = m->def[h->iN];
    if (!d || d < h->bb || d >= h->bc || OPC(m, d) != OpSelect || LEN(m, d) != 6 || m->w[d + 5] != h->phi_i) return 0;
    uint32_t cond = m->w[d + 3], mid = m->w[d + 4]; size_t dm = m->def[mid];
    if (!dm || dm < h->bb || dm >= h->bc || OPC(m, dm) != OpIAdd || !((m->w[dm + 3] == h->phi_i && m->w[dm + 4] == h->phi_s) || (m->w[dm + 4] == h->phi_i && m->w[dm + 3] == h->phi_s))) return 0;
    // cond = (SH < AH) || (SH == AH && SL <= AL): the candidate's start (SH:SL, table words whose index depends on mid and constants only) <= the address (AH:AL),
    // unsigned 64-bit. Operand orders of ||, && and == either way
    size_t dc = m->def[cond]; if (!dc || OPC(m, dc) != OpLogicalOr || LEN(m, dc) != 5) return 0;
    uint32_t lt = 0, an = 0;
    for (int k = 0; k < 2; k++) { uint32_t a = m->w[dc + 3 + k], b = m->w[dc + 4 - k]; if (op_of(m, a) == OpULessThan && op_of(m, b) == OpLogicalAnd) { lt = a; an = b; } }
    if (!lt) return 0;
    size_t dl = m->def[lt], da = m->def[an]; uint32_t eq = 0, le = 0;
    for (int k = 0; k < 2; k++) { uint32_t a = m->w[da + 3 + k], b = m->w[da + 4 - k]; if (op_of(m, a) == OpIEqual && op_of(m, b) == OpULessThanEqual) { eq = a; le = b; } }
    if (!eq) return 0;
    size_t de = m->def[eq], dle = m->def[le];
    uint32_t SH = root(m, m->w[dl + 3]), AH = root(m, m->w[dl + 4]), SL = root(m, m->w[dle + 3]), AL = root(m, m->w[dle + 4]);
    uint32_t e0 = root(m, m->w[de + 3]), e1 = root(m, m->w[de + 4]);
    // the address words: loads of the parameters (any load of the same parameter: nothing writes them)
    #define PLOAD(v, p) (op_of(m, v) == OpLoad && m->w[m->def[v] + 3] == (p))
    if (!PLOAD(AH, h->p1) || !PLOAD(AL, h->p0)) return 0;
    int eqok = (e0 == SH && PLOAD(e1, h->p1)) || (e1 == SH && PLOAD(e0, h->p1));
    if (!eqok) return 0;
    #undef PLOAD
    for (int k = 0; k < 2; k++) {   // the start words: table reads in the body whose index is a function of mid and constants
        uint32_t s = k ? SL : SH; size_t ds = m->def[s];
        if (!ds || ds < h->bb || ds >= h->bc || OPC(m, ds) != OpLoad || load_kind(m, h, m->w[ds + 3]) != 3) return 0;
        size_t dp = m->def[m->w[ds + 3]];
        for (uint32_t q = 4; q < LEN(m, dp); q++) {   // each index: a constant, mid, or an IAdd / IMul / shift of those (the table's entry layout)
            uint32_t v = m->w[dp + q], cc;
            if (const32(m, v, &cc) || v == mid) continue;
            size_t dv = m->def[v];
            if (!dv || dv < h->bb || dv >= h->bc) return 0;
            uint32_t ov = OPC(m, dv);
            if (ov != OpIAdd && ov != 132 && ov != 196) return 0;   // OpIAdd, OpIMul, OpShiftLeftLogical
            for (int z = 3; z < 5; z++) {
                uint32_t u = m->w[dv + z]; if (const32(m, u, &cc) || u == mid) continue;
                size_t du = m->def[u]; if (!du || du < h->bb || du >= h->bc || (OPC(m, du) != OpIAdd && OPC(m, du) != 132 && OPC(m, du) != 196)) return 0;
                for (int y = 3; y < 5; y++) { uint32_t g = m->w[du + y]; if (!(const32(m, g, &cc) || g == mid)) return 0; }
            }
        }
    }
    // every instruction of the body and the merge block is one this pass can copy, without side effects; the merge block reads nothing of the loop but iN
    int pos[40];
    for (size_t y = h->b0; y < h->end; y += LEN(m, y)) {
        uint32_t op = OPC(m, y);
        if (id_words(m->w + y, pos) < 0) return 0;
        if (op == OpExtInst && is_ext_with_ptr(m, y)) return 0;
        if (op == OpLoad) { int k = load_kind(m, h, m->w[y + 3]); if (!k) return 0; }
        if (y >= h->bm) {
            int np2 = id_words(m->w + y, pos);
            for (int q = 0; q < np2; q++) {
                if (pos[q] <= 2 && has_result(op) && op != OpLabel) continue;
                uint32_t v = m->w[y + pos[q]]; size_t dv = v < m->bound ? m->def[v] : 0;
                if (dv > h->b0 && dv < h->bm && v != h->iN) return 0;
            }
        }
        if (y >= h->bb && y < h->bc) {   // the body's values reach only the body, the continue block (sN) and the merge block (iN)
            if (op == OpBranch && y + 2 == h->bc) continue;
        }
    }
    for (size_t y = h->bh; y < h->bm; y += LEN(m, y)) if (OPC(m, y) == OpLoad && load_kind(m, h, m->w[y + 3]) == 4) return 0;   // no memory access in the loop
    return 1;
}

// ---- call sites
// The function's own variables (from fn, its OpFunction): Function variables it declares whose id appears nowhere in it but as the pointer of an OpLoad, the pointer of
// an OpStore and an argument of a call of a taken load helper (which neither writes nor keeps it: no store, no call, a result that is not a pointer). Only then is the
// last OpStore to one in a block its value at a later call in that block: no other pointer to it exists (an OpCopyObject, an access chain, even with no index, a phi, a
// select, or an argument of another function, could write it unseen). own[id]: OWN_YES such a variable, OWN_NO a Function variable used any other way, 0 not one.
// Any word of any other instruction that equals the variable's id counts as a use (a literal that happens to equal it only leaves a call as it is). 0: a bad instruction
enum { OWN_YES = 1, OWN_NO = 2 };
static int own_vars(const mod_t *m, size_t fn, const uint32_t *hid, int nh, uint8_t *own) {
    memset(own, 0, m->bound);
    size_t i = fn;
    for (; i < m->nw && OPC(m, i) != OpFunctionEnd; i += LEN(m, i)) {
        if (!LEN(m, i) || i + LEN(m, i) > m->nw) return 0;
        if (OPC(m, i) == OpVariable && LEN(m, i) >= 4 && m->w[i + 3] == SCFunction && m->w[i + 2] < m->bound) own[m->w[i + 2]] = OWN_YES;
    }
    if (i >= m->nw) return 0;
    for (i = fn; OPC(m, i) != OpFunctionEnd; i += LEN(m, i)) {
        uint32_t op = OPC(m, i), len = LEN(m, i), skip[3] = {0, 0, 0};   // word positions where a variable's id is a use this pass follows
        if (op == OpVariable) skip[0] = 2;                                // (its own definition)
        else if (op == OpLoad && len >= 4) skip[0] = 3;
        else if (op == OpStore && len >= 3) skip[0] = 1;
        else if (op == OpFunctionCall && len == 6) { for (int k = 0; k < nh; k++) if (m->w[i + 3] == hid[k]) { skip[0] = 4; skip[1] = 5; } }
        else if (op == 8 || op == 317) continue;                           // OpLine, OpNoLine: a file name and line numbers
        for (uint32_t k = 1; k < len; k++) {
            if (k == skip[0] || k == skip[1]) continue;
            uint32_t v = m->w[i + k];
            if (v < m->bound && own[v] == OWN_YES) own[v] = OWN_NO;
        }
    }
    return 1;
}
typedef struct { size_t call; int h; uint32_t bl, bh, k, lo, hi; int cl; } site_t;
typedef struct { int h; int n; int first; uint32_t bl, bh, kmin, kmax; uint32_t fn; } cluster_t;
typedef struct {
    site_t *s; int ns, cs;
    cluster_t *c; int nc, cc;
} plan_t;

// The address words of a call as base + constant (the translator's 64-bit add of a zero-extended constant), or the words themselves as a base with offset 0
static void base_of(const mod_t *m, uint32_t lo, uint32_t hi, uint32_t *bl, uint32_t *bh, uint32_t *k) {
    uint32_t l = root(m, lo), h = root(m, hi);
    *bl = l; *bh = h; *k = 0;
    size_t dl = l < m->bound ? m->def[l] : 0, dh = h < m->bound ? m->def[h] : 0;
    if (!dl || OPC(m, dl) != OpIAdd || !int32_type(m, m->w[dl + 1]) || !dh || OPC(m, dh) != OpIAdd || !int32_type(m, m->w[dh + 1])) return;
    uint32_t x, kk;
    if (const32(m, m->w[dl + 4], &kk)) x = m->w[dl + 3]; else if (const32(m, m->w[dl + 3], &kk)) x = m->w[dl + 4]; else return;
    uint32_t bx = root(m, x);
    for (int o = 0; o < 2; o++) {   // hi = IAdd(y, Select(ULessThan(lo, base_lo), 1, 0)), the IAdd's operands either way
        uint32_t y = m->w[dh + 3 + o], s = root(m, m->w[dh + 4 - o]); size_t ds = s < m->bound ? m->def[s] : 0;
        if (!ds || OPC(m, ds) != OpSelect || LEN(m, ds) != 6 || !int32_type(m, m->w[ds + 1])) continue;
        uint32_t one, zero; if (!const32(m, m->w[ds + 4], &one) || one != 1 || !const32(m, m->w[ds + 5], &zero) || zero != 0) continue;
        size_t dc = m->def[m->w[ds + 3]];
        if (!dc || OPC(m, dc) != OpULessThan || root(m, m->w[dc + 3]) != l || root(m, m->w[dc + 4]) != bx) continue;
        if (!scalar32(m, type_of(m, m->w[dc + 3])) || !scalar32(m, type_of(m, m->w[dc + 4]))) continue;
        *bl = bx; *bh = root(m, y); *k = kk; return;
    }
}
static int cmp_k(const void *a, const void *b) { const site_t *x = a, *y = b; return x->k < y->k ? -1 : x->k > y->k ? 1 : x->call < y->call ? -1 : 1; }
static int cmp_call(const void *a, const void *b) { const site_t *x = a, *y = b; return x->call < y->call ? -1 : 1; }
// The open sites of a block, made into clusters: per helper and base, by offset, runs within 4,095 bytes of their lowest, at least `min` calls each
static int flush(plan_t *p, int from, const helper_t *hs, uint32_t fn, vka_gmem_stats *st) {
    int n = p->ns - from; if (n <= 0) return 1;
    site_t *s = p->s + from;
    // group by (helper, base): a simple quadratic pass (a block holds tens of calls)
    char *done = calloc((size_t)n, 1); site_t *tmp = malloc(sizeof *tmp * (size_t)n); if (!done || !tmp) { free(done); free(tmp); return 0; }
    for (int a = 0; a < n; a++) {
        if (done[a]) continue;
        int t = 0;
        for (int b = a; b < n; b++) if (!done[b] && s[b].h == s[a].h && s[b].bl == s[a].bl && s[b].bh == s[a].bh) { tmp[t++] = s[b]; done[b] = 1; }
        qsort(tmp, (size_t)t, sizeof *tmp, cmp_k);
        int min = hs[s[a].h].form == FORM_PIDX ? 3 : 2;
        for (int u = 0; u < t;) {   // per 4 KiB bucket of offsets (k >> 12): the lookups at its ends, the same for every group of this base and bucket
            int v = u; while (v < t && (tmp[v].k >> 12) == (tmp[u].k >> 12)) v++;
            if (v - u >= min) {
                if (p->nc == p->cc) { int c2 = p->cc ? 2 * p->cc : 64; cluster_t *q = realloc(p->c, sizeof *q * (size_t)c2); if (!q) { free(done); free(tmp); return 0; } p->c = q; p->cc = c2; }
                cluster_t *c = &p->c[p->nc]; *c = (cluster_t){s[a].h, v - u, -1, s[a].bl, s[a].bh, tmp[u].k & ~0xfffu, (tmp[u].k & ~0xfffu) + 0xfffu, fn};
                for (int z = u; z < v; z++) for (int y = 0; y < n; y++) if (s[y].call == tmp[z].call) s[y].cl = p->nc;
                p->nc++; st->merged += v - u; st->groups++;
            } else st->left_single += v - u;
            u = v;
        }
    }
    free(done); free(tmp);
    // drop the sites not in a cluster; keep the rest in call order
    int w = from;
    for (int a = from; a < p->ns; a++) if (p->s[a].cl >= 0) p->s[w++] = p->s[a];
    p->ns = w;
    qsort(p->s + from, (size_t)(p->ns - from), sizeof *p->s, cmp_call);
    for (int a = from; a < p->ns; a++) if (p->c[p->s[a].cl].first < 0) p->c[p->s[a].cl].first = a;
    return 1;
}

// ---- output
typedef struct { uint32_t *map; uint32_t bound; } rmap_t;
static uint32_t rm(const rmap_t *r, uint32_t id) { return id < r->bound && r->map[id] ? r->map[id] : id; }
static int clone_ins(vec_t *o, const uint32_t *x, const rmap_t *r) {
    int pos[40]; int np = id_words(x, pos); uint32_t len = x[0] >> 16;
    if (np < 0) return 0;
    size_t at = o->n;
    if (!put_n(o, x, len)) return 0;
    for (int q = 0; q < np; q++) o->w[at + pos[q]] = rm(r, x[pos[q]]);
    return 1;
}
// New ids for every result and label of the function's body (from its first label)
static int fresh_ids(mod_t *m, size_t from, size_t to, rmap_t *r) {
    for (size_t i = from; i < to; i += LEN(m, i)) {
        uint32_t op = OPC(m, i);
        uint32_t id = op == OpLabel ? m->w[i + 1] : has_result(op) ? m->w[i + 2] : 0;
        if (id && id < r->bound) r->map[id] = m->nb++;
    }
    return 1;
}
static uint32_t as_type(mod_t *m, vec_t *o, uint32_t v, uint32_t t) {   // v as type t (32-bit scalars: a bit cast when the types differ)
    if (type_of(m, v) == t) return v;
    uint32_t r = m->nb++, x[] = {4u << 16 | OpBitcast, t, r, v};
    return put_n(o, x, 4) ? r : 0;
}
static uint32_t emit3(mod_t *m, vec_t *o, uint32_t op, uint32_t t, uint32_t a, uint32_t b) { uint32_t r = m->nb++, x[] = {5u << 16 | op, t, r, a, b}; return put_n(o, x, 5) ? r : 0; }
static uint32_t emit2(mod_t *m, vec_t *o, uint32_t op, uint32_t t, uint32_t a) { uint32_t r = m->nb++, x[] = {4u << 16 | op, t, r, a}; return put_n(o, x, 4) ? r : 0; }
static uint32_t emit_sel(mod_t *m, vec_t *o, uint32_t t, uint32_t c, uint32_t a, uint32_t b) { uint32_t r = m->nb++, x[] = {6u << 16 | OpSelect, t, r, c, a, b}; return put_n(o, x, 6) ? r : 0; }

// LK(q0, q1) -> struct of the address-dependent table reads (the helper's instructions they need, in order)
static int emit_lk(mod_t *m, vec_t *o, const helper_t *h, uint32_t *map) {
    rmap_t r = {map, m->bound}; memset(map, 0, sizeof *map * m->bound);
    uint8_t *need = calloc(m->bound, 1); if (!need) return 0;
    for (int k = 0; k < h->ndyn; k++) need[m->w[h->dyn[k] + 2]] = 1;
    // backward over the single block: an instruction is needed when its result is
    size_t body = h->at; body += LEN(m, body); body += LEN(m, body); body += LEN(m, body);
    int nl = 0; for (size_t i = body + 2; i < h->end; i += LEN(m, i)) nl++;
    size_t *list = malloc(sizeof *list * (size_t)(nl + 1)); if (!list) { free(need); return 0; }
    nl = 0; for (size_t i = body + 2; i < h->end; i += LEN(m, i)) list[nl++] = i;
    int pos[40];
    for (int a = nl - 1; a >= 0; a--) {
        size_t i = list[a]; uint32_t op = OPC(m, i);
        if (!has_result(op) || !need[m->w[i + 2]]) continue;
        int np = id_words(m->w + i, pos);
        for (int q = 0; q < np; q++) { uint32_t v = m->w[i + pos[q]]; if (pos[q] >= 3 && v < m->bound && m->def[v] > h->at && m->def[v] < h->end) need[v] = 1; }
    }
    uint32_t q0 = m->nb++, q1 = m->nb++, lab = m->nb++;
    map[h->p0] = q0; map[h->p1] = q1;
    for (int a = 0; a < nl; a++) { size_t i = list[a]; if (has_result(OPC(m, i)) && need[m->w[i + 2]]) map[m->w[i + 2]] = m->nb++; }
    uint32_t x[] = {5u << 16 | OpFunction, h->st_t, h->f_aux, 0, 0, 3u << 16 | OpFunctionParameter, h->pt0, q0, 3u << 16 | OpFunctionParameter, h->pt1, q1, 2u << 16 | OpLabel, lab};
    uint32_t par[2] = {h->pt0, h->pt1}; x[4] = ty_fn(m, h->st_t, par, 2);
    if (!x[4] || !put_n(o, x, sizeof x / 4)) { free(need); free(list); return 0; }
    for (int a = 0; a < nl; a++) { size_t i = list[a]; if (has_result(OPC(m, i)) && need[m->w[i + 2]] && !clone_ins(o, m->w + i, &r)) { free(need); free(list); return 0; } }
    free(need); free(list);
    uint32_t s = m->nb++;
    if (!put(o, (uint32_t)(3 + h->ndyn) << 16 | OpCompositeConstruct) || !put(o, h->st_t) || !put(o, s)) return 0;
    for (int k = 0; k < h->ndyn; k++) if (!put(o, rm(&r, m->w[h->dyn[k] + 2]))) return 0;
    uint32_t y[] = {2u << 16 | OpReturnValue, s, 1u << 16 | OpFunctionEnd};
    return put_n(o, y, 3);
}
// H'(q0, q1, sa, sb, c): the helper with each address-dependent table read replaced by select(c, its word of sa, its word of sb)
static int emit_pidx_new(mod_t *m, vec_t *o, const helper_t *h, uint32_t *map) {
    rmap_t r = {map, m->bound}; memset(map, 0, sizeof *map * m->bound);
    size_t body = h->at; body += LEN(m, body); body += LEN(m, body); body += LEN(m, body);
    fresh_ids(m, body, h->end, &r);
    uint32_t q0 = m->nb++, q1 = m->nb++, sa = m->nb++, sb = m->nb++, c = m->nb++;
    map[h->p0] = q0; map[h->p1] = q1;
    uint32_t par[5] = {h->pt0, h->pt1, h->st_t, h->st_t, ty_bool(m)}, ft = ty_fn(m, h->ret_t, par, 5);
    uint32_t x[] = {5u << 16 | OpFunction, h->ret_t, h->f_new, 0, ft, 3u << 16 | OpFunctionParameter, h->pt0, q0, 3u << 16 | OpFunctionParameter, h->pt1, q1,
                    3u << 16 | OpFunctionParameter, h->st_t, sa, 3u << 16 | OpFunctionParameter, h->st_t, sb, 3u << 16 | OpFunctionParameter, par[4], c};
    if (!ft || !put_n(o, x, sizeof x / 4)) return 0;
    for (size_t i = body; i < h->end; i += LEN(m, i)) {
        int j = -1; for (int k = 0; k < h->ndyn; k++) if (h->dyn[k] == i) j = k;
        if (j >= 0) {
            uint32_t t = m->w[i + 1], ea = m->nb++, eb = m->nb++;
            uint32_t y[] = {5u << 16 | OpCompositeExtract, t, ea, sa, (uint32_t)j, 5u << 16 | OpCompositeExtract, t, eb, sb, (uint32_t)j, 6u << 16 | OpSelect, t, rm(&r, m->w[i + 2]), c, ea, eb};
            if (!put_n(o, y, sizeof y / 4)) return 0;
            continue;
        }
        if (!clone_ins(o, m->w + i, &r)) return 0;
    }
    return put(o, 1u << 16 | OpFunctionEnd);
}
// H'(q0, q1, i0, s0): the search helper with its loop starting at index i0 and step s0
static int emit_loop_new(mod_t *m, vec_t *o, const helper_t *h, uint32_t *map) {
    rmap_t r = {map, m->bound}; memset(map, 0, sizeof *map * m->bound);
    fresh_ids(m, h->b0, h->end, &r);
    uint32_t q0 = m->nb++, q1 = m->nb++, i0 = m->nb++, s0 = m->nb++;
    map[h->p0] = q0; map[h->p1] = q1;
    uint32_t par[4] = {h->pt0, h->pt1, h->idx_t, h->idx_t}, ft = ty_fn(m, h->ret_t, par, 4);
    uint32_t x[] = {5u << 16 | OpFunction, h->ret_t, h->f_new, 0, ft, 3u << 16 | OpFunctionParameter, h->pt0, q0, 3u << 16 | OpFunctionParameter, h->pt1, q1,
                    3u << 16 | OpFunctionParameter, h->idx_t, i0, 3u << 16 | OpFunctionParameter, h->idx_t, s0};
    if (!ft || !put_n(o, x, sizeof x / 4)) return 0;
    for (size_t i = h->b0; i < h->end; i += LEN(m, i)) {
        size_t at = o->n;
        if (!clone_ins(o, m->w + i, &r)) return 0;
        if (OPC(m, i) == OpPhi && (m->w[i + 2] == h->phi_i || m->w[i + 2] == h->phi_s)) {   // the initial value (from the entry block) becomes the parameter
            uint32_t L0 = rm(&r, m->w[h->b0 + 1]);
            for (uint32_t k = 3; k + 1 < LEN(m, i); k += 2) if (o->w[at + k + 1] == L0) o->w[at + k] = m->w[i + 2] == h->phi_i ? i0 : s0;
        }
    }
    return put(o, 1u << 16 | OpFunctionEnd);
}
// DS(a0, a1, b0, b1) -> vec2(g(A), g(B)): the helper's search, twice in one loop (the second copy's own step and exit test are the first's, unused)
static int emit_ds(mod_t *m, vec_t *o, const helper_t *h, uint32_t *map, uint32_t *map2) {
    rmap_t r1 = {map, m->bound}, r2 = {map2, m->bound};
    memset(map, 0, sizeof *map * m->bound); memset(map2, 0, sizeof *map2 * m->bound);
    fresh_ids(m, h->bh, h->bm, &r1); fresh_ids(m, h->bh, h->bm, &r2);
    uint32_t a0 = m->nb++, a1 = m->nb++, b0 = m->nb++, b1 = m->nb++, L0 = m->nb++, Lm = m->nb++;
    map[h->p0] = a0; map[h->p1] = a1; map2[h->p0] = b0; map2[h->p1] = b1;
    map[m->w[h->b0 + 1]] = L0; map2[m->w[h->b0 + 1]] = L0; map[m->w[h->bm + 1]] = Lm; map2[m->w[h->bm + 1]] = Lm;
    // the blocks (labels) of the loop are copy 1's
    uint32_t labs[3] = {m->w[h->bh + 1], m->w[h->bb + 1], m->w[h->bc + 1]};
    for (int k = 0; k < 3; k++) map2[labs[k]] = map[labs[k]];
    uint32_t par[4] = {h->pt0, h->pt1, h->pt0, h->pt1}, ft = ty_fn(m, h->st_t, par, 4);
    uint32_t x[] = {5u << 16 | OpFunction, h->st_t, h->f_aux, 0, ft, 3u << 16 | OpFunctionParameter, h->pt0, a0, 3u << 16 | OpFunctionParameter, h->pt1, a1,
                    3u << 16 | OpFunctionParameter, h->pt0, b0, 3u << 16 | OpFunctionParameter, h->pt1, b1, 2u << 16 | OpLabel, L0, 2u << 16 | OpBranch, map[labs[0]]};
    if (!ft || !put_n(o, x, sizeof x / 4)) return 0;
    // header: copy 1's label, both copies' phis, copy 1's merge and branch
    if (!put(o, 2u << 16 | OpLabel) || !put(o, map[labs[0]])) return 0;
    for (int cp = 0; cp < 2; cp++) for (size_t i = h->bh + 2; OPC(m, i) == OpPhi; i += LEN(m, i)) if (!clone_ins(o, m->w + i, cp ? &r2 : &r1)) return 0;
    for (size_t i = h->bh + 2; i < h->bb; i += LEN(m, i)) if (OPC(m, i) != OpPhi && !clone_ins(o, m->w + i, &r1)) return 0;
    // body: label, copy 1, copy 2 (without its branch), copy 1's branch
    if (!put(o, 2u << 16 | OpLabel) || !put(o, map[labs[1]])) return 0;
    size_t br = h->bb; for (size_t i = h->bb; i < h->bc; i += LEN(m, i)) br = i;   // the body's terminator
    for (int cp = 0; cp < 2; cp++) for (size_t i = h->bb + 2; i < br; i += LEN(m, i)) if (!clone_ins(o, m->w + i, cp ? &r2 : &r1)) return 0;
    if (!clone_ins(o, m->w + br, &r1)) return 0;
    // continue: label, copy 2's test (unused), copy 1's test and branch
    if (!put(o, 2u << 16 | OpLabel) || !put(o, map[labs[2]])) return 0;
    if (!clone_ins(o, m->w + h->bc + 2, &r2)) return 0;
    for (size_t i = h->bc + 2; i < h->bm; i += LEN(m, i)) if (!clone_ins(o, m->w + i, &r1)) return 0;
    uint32_t v = m->nb++;
    uint32_t y[] = {2u << 16 | OpLabel, Lm, 5u << 16 | OpCompositeConstruct, h->st_t, v, rm(&r1, h->iN), rm(&r2, h->iN), 2u << 16 | OpReturnValue, v, 1u << 16 | OpFunctionEnd};
    return put_n(o, y, sizeof y / 4);
}

// Calls of raw global memory helpers this pass does not take (stores, other shapes)
static void count_untaken(const mod_t *m, const uint32_t *hid, int nh, vka_gmem_stats *st) {
    for (size_t i = m->first_fn; i < m->nw; i += LEN(m, i)) if (OPC(m, i) == OpFunctionCall && LEN(m, i) >= 4) {
        uint32_t f = m->w[i + 3]; int taken = 0; for (int k = 0; k < nh; k++) if (hid[k] == f) taken = 1;
        if (taken) continue;
        size_t d = f < m->bound ? m->def[f] : 0;
        for (size_t j = d; d && j < m->nw && OPC(m, j) != OpFunctionEnd; j += LEN(m, j)) if (OPC(m, j) == OpConvertUToPtr) { st->left_helper_calls++; break; }
    }
}
static void add_stats(vka_gmem_stats *to, const vka_gmem_stats *st) {
    if (!to) return;
    to->helpers_pidx += st->helpers_pidx; to->helpers_loop += st->helpers_loop; to->helpers_left += st->helpers_left; to->calls += st->calls; to->merged += st->merged;
    to->groups += st->groups; to->left_single += st->left_single; to->left_unknown += st->left_unknown; to->left_helper_calls += st->left_helper_calls;
}
int vka_spv_gmem_merge(const uint32_t *w, size_t nw, uint32_t **out, size_t *out_nw, vka_gmem_stats *st_out, char *err, size_t errn) {
#define FAIL(...) do { snprintf(err, errn, __VA_ARGS__); goto fail; } while (0)
    vka_gmem_stats st0 = {0}, *st = &st0;
    mod_t M = {0}, *m = &M; plan_t P = {0}; vec_t o = {0}; uint32_t *map = NULL, *map2 = NULL, *last = NULL, *fvars = NULL; uint8_t *taint = NULL, *own = NULL;
    helper_t hs[MAXH]; int nh = 0; uint32_t hid[MAXH]; size_t *fn_at = NULL; int nfn = 0, cfn = 0;
    *out = NULL; *out_nw = 0; err[0] = 0;
    if (nw < 5 || w[0] != 0x07230203) FAIL("not SPIR-V");
    m->w = w; m->nw = nw; m->bound = w[3]; m->nb = w[3]; m->first_fn = nw;
    if (!(m->def = calloc(m->bound, sizeof *m->def))) FAIL("out of memory");
    // pass 1: definitions, the first function, the entry point
    for (size_t i = 5; i < nw;) {
        uint32_t op = w[i] & 0xffff, len = w[i] >> 16;
        if (!len || i + len > nw) FAIL("bad instruction at word %zu", i);
        if (op == 15 && len >= 3) { if (m->entry) goto none; m->entry = w[i + 2]; }   // one entry point
        if (op == OpFunction) { if (m->first_fn == nw) m->first_fn = i;
            if (nfn == cfn) { cfn = cfn ? 2 * cfn : 16; size_t *q = realloc(fn_at, sizeof *q * (size_t)cfn); if (!q) FAIL("out of memory"); fn_at = q; } fn_at[nfn++] = i; }
        uint32_t id = 0;
        if ((op >= OpTypeVoid && op <= 39) || op == OpLabel || op == 11) id = len >= 2 ? w[i + 1] : 0;   // types (and OpExtInstImport 11), labels
        else if (has_result(op) && len >= 3) id = w[i + 2];
        if (id && id < m->bound) m->def[id] = i;
        i += len;
    }
    if (!nfn || !m->entry) goto none;
    // pass 2: the helpers
    if (!(taint = calloc(m->bound, 1))) FAIL("out of memory");
    for (int f = 0; f < nfn; f++) {
        helper_t h = {0}; h.at = fn_at[f]; h.fid = w[h.at + 2];
        size_t e = h.at; while (e < nw && OPC(m, e) != OpFunctionEnd) e += LEN(m, e);
        h.end = e;
        if (h.fid == m->entry) continue;
        int raw = 0; for (size_t i = h.at; i < h.end; i += LEN(m, i)) if (OPC(m, i) == OpConvertUToPtr) raw = 1;
        if (!raw) continue;
        if (!helper_params(m, &h)) { st->helpers_left++; continue; }
        int stores = 0; for (size_t i = h.at; i < h.end; i += LEN(m, i)) if (OPC(m, i) == OpStore || OPC(m, i) == OpFunctionCall || (OPC(m, i) >= 227 && OPC(m, i) <= 242)) stores = 1;
        if (stores) { st->helpers_left++; continue; }
        int lp = 0; for (size_t i = h.at; i < h.end; i += LEN(m, i)) if (OPC(m, i) == OpLoopMerge) lp = 1;
        if (!lp && take_pidx(m, &h, taint)) h.form = FORM_PIDX;
        else if (lp && take_loop(m, &h)) h.form = FORM_LOOP;
        if (!h.form || nh == MAXH) { st->helpers_left++; continue; }
        hid[nh] = h.fid; hs[nh++] = h;
        if (h.form == FORM_PIDX) st->helpers_pidx++; else st->helpers_loop++;
    }
    if (!nh) goto count_left;
    // pass 3: call sites, per function and block
    if (!(last = calloc(m->bound, sizeof *last)) || !(own = calloc(m->bound, 1))) FAIL("out of memory");
    uint32_t *touched = NULL; size_t nt = 0, ct = 0;
    for (int f = 0; f < nfn; f++) {
        uint32_t fid = w[fn_at[f] + 2]; int is_h = 0;
        for (int k = 0; k < nh; k++) if (hid[k] == fid) is_h = 1;
        if (is_h) continue;
        if (!own_vars(m, fn_at[f], hid, nh, own)) { free(touched); FAIL("bad instruction in function %u", fid); }
        int from = P.ns;
        for (size_t i = fn_at[f]; i < nw && OPC(m, i) != OpFunctionEnd; i += LEN(m, i)) {
            uint32_t op = OPC(m, i);
            if (op == OpLabel) {
                if (!flush(&P, from, hs, fid, st)) { free(touched); FAIL("out of memory"); }
                from = P.ns; for (size_t t = 0; t < nt; t++) last[touched[t]] = 0; nt = 0;
                continue;
            }
            if (op == OpStore && w[i + 1] < m->bound && own[w[i + 1]] == OWN_YES) {   // an own variable's value in this block (nothing else can write it: own_vars)
                if (nt == ct) { size_t c2 = ct ? 2 * ct : 256; uint32_t *q = realloc(touched, c2 * 4); if (!q) { free(touched); FAIL("out of memory"); } touched = q; ct = c2; }
                last[w[i + 1]] = w[i + 2]; touched[nt++] = w[i + 1];
                continue;
            }
            int hk = -1;
            if (op == OpFunctionCall && LEN(m, i) == 6) for (int k = 0; k < nh; k++) if (w[i + 3] == hid[k]) hk = k;
            if (hk >= 0) {
                st->calls++;
                // the address words: only through the function's own variables (any other pointer to them, a copy, an access chain, a phi, a select, a parameter or
                // another call's argument, could write them without this pass seeing it: such calls are left as they are)
                uint32_t a0 = w[i + 4], a1 = w[i + 5];
                uint32_t lo = a0 < m->bound && own[a0] == OWN_YES ? last[a0] : 0, hi = a1 < m->bound && own[a1] == OWN_YES ? last[a1] : 0;
                if (!lo || !hi) { st->left_unknown++; continue; }
                site_t s = {i, hk, 0, 0, 0, lo, hi, -1};
                base_of(m, lo, hi, &s.bl, &s.bh, &s.k);
                if (P.ns == P.cs) { int c2 = P.cs ? 2 * P.cs : 256; site_t *q = realloc(P.s, sizeof *q * (size_t)c2); if (!q) { free(touched); FAIL("out of memory"); } P.s = q; P.cs = c2; }
                P.s[P.ns++] = s;
                continue;
            }
            if (writes_memory(m, i, hid, nh)) {
                if (!flush(&P, from, hs, fid, st)) { free(touched); FAIL("out of memory"); }
                from = P.ns;
            }
        }
        if (!flush(&P, from, hs, fid, st)) { free(touched); FAIL("out of memory"); }
        for (size_t t = 0; t < nt; t++) last[touched[t]] = 0; nt = 0;
    }
    free(touched);
    if (!P.nc) goto count_left;
    // pass 4: write the module
    if (!(map = calloc(m->bound, 4)) || !(map2 = calloc(m->bound, 4))) FAIL("out of memory");
    for (int k = 0; k < nh; k++) {   // the new functions' ids and types
        helper_t *h = &hs[k]; int used = 0;
        for (int c = 0; c < P.nc; c++) used |= P.c[c].h == k;
        if (!used) continue;
        h->f_new = m->nb++; h->f_aux = m->nb++;
        if (h->form == FORM_PIDX) h->st_t = ty_struct(m, h->dyn_t, (uint32_t)h->ndyn);
        else h->st_t = ty_vec2(m, h->idx_t);
        if (!h->st_t) FAIL("out of memory");
    }
    uint32_t tb = ty_bool(m), tu = ty_u32(m), c0 = cst(m, tu, 0), c1 = cst(m, tu, 1), c12 = cst(m, tu, 12);
    if (!tb || !tu || !c0 || !c1 || !c12) FAIL("out of memory");
    for (int k = 0; k < nh; k++) if (hs[k].f_new && hs[k].form == FORM_LOOP) {
        uint32_t s[5]; for (int z = 0; z < 5; z++) s[z] = cst(m, hs[k].idx_t, 1u << z);
        if (!s[4]) FAIL("out of memory");
    }
    // per function: its new variables (two per page index helper used there, four per search helper), as [function][helper][4]
    if (!(fvars = calloc((size_t)nfn * MAXH * 4, 4))) FAIL("out of memory");
    for (int c = 0; c < P.nc; c++) {
        int f = 0; while (f < nfn && w[fn_at[f] + 2] != P.c[c].fn) f++;
        uint32_t *v = fvars + ((size_t)f * MAXH + (size_t)P.c[c].h) * 4;
        if (!v[0]) for (int z = 0; z < (hs[P.c[c].h].form == FORM_PIDX ? 2 : 4); z++) v[z] = m->nb++;
    }
    // the body, with the new declarations before the first function
    vec_t body = {0};
    if (!put_n(&body, w, 5)) FAIL("out of memory");
    if (!put_n(&body, w + 5, m->first_fn - 5)) { free(body.w); FAIL("out of memory"); }
    size_t decl_at = body.n;   // (the declarations go in here once all are known)
    int si = 0, fcur = -1; int in_entry_vars = 0;
    // per cluster: the head's values
    uint32_t (*cv)[4] = calloc((size_t)P.nc, sizeof *cv);   // page index: lo_min >> 12, hi_min, S_min, S_max; search: i0, s0
    if (!cv) { free(body.w); FAIL("out of memory"); }
    for (size_t i = m->first_fn; i < nw;) {
        uint32_t op = OPC(m, i);
        if (op == OpFunction) { fcur++; in_entry_vars = 0; }
        if (op == OpLabel && in_entry_vars == 0) {   // the function's first block: its variables follow the label; ours go right after them
            in_entry_vars = 1;
            if (!put_n(&body, w + i, LEN(m, i))) goto ofail;
            i += LEN(m, i);
            while (i < nw && OPC(m, i) == OpVariable) { if (!put_n(&body, w + i, LEN(m, i))) goto ofail; i += LEN(m, i); }
            for (int k = 0; k < nh && fcur >= 0; k++) {
                uint32_t *v = fvars + ((size_t)fcur * MAXH + (size_t)k) * 4;
                for (int z = 0; z < 4; z++) if (v[z]) { uint32_t x[] = {4u << 16 | OpVariable, z & 1 ? hs[k].pt1 : hs[k].pt0, v[z], SCFunction}; if (!put_n(&body, x, 4)) goto ofail; }
            }
            continue;
        }
        if (si < P.ns && P.s[si].call == i) {
            site_t *s = &P.s[si]; cluster_t *c = &P.c[s->cl]; helper_t *h = &hs[c->h];
            uint32_t *v = fvars + ((size_t)fcur * MAXH + (size_t)c->h) * 4;
            if (c->first == si) {   // the head: lookups (or searches) at the lowest and highest address
                uint32_t blu = as_type(m, &body, c->bl, tu), bhu = as_type(m, &body, c->bh, tu); if (!blu || !bhu) goto ofail;
                uint32_t lo[2], hi[2];
                for (int z = 0; z < 2; z++) {
                    uint32_t k = z ? c->kmax : c->kmin;
                    lo[z] = emit3(m, &body, OpIAdd, tu, blu, cst(m, tu, k));
                    uint32_t cr = emit3(m, &body, OpULessThan, tb, lo[z], blu), ci = emit_sel(m, &body, tu, cr, c1, c0);
                    hi[z] = emit3(m, &body, OpIAdd, tu, bhu, ci);
                    if (!lo[z] || !cr || !ci || !hi[z]) goto ofail;
                }
                uint32_t p0t = pointee(m, h->pt0), p1t = pointee(m, h->pt1);
                if (h->form == FORM_PIDX) {
                    for (int z = 0; z < 2; z++) {
                        uint32_t a = as_type(m, &body, lo[z], p0t), b = as_type(m, &body, hi[z], p1t), r = m->nb++;
                        uint32_t x[] = {3u << 16 | OpStore, v[0], a, 3u << 16 | OpStore, v[1], b, 6u << 16 | OpFunctionCall, h->st_t, r, h->f_aux, v[0], v[1]};
                        if (!a || !b || !put_n(&body, x, sizeof x / 4)) goto ofail;
                        cv[s->cl][2 + z] = r;
                    }
                    cv[s->cl][0] = emit3(m, &body, OpShiftRightLogical, tu, lo[0], c12); cv[s->cl][1] = hi[0];
                    if (!cv[s->cl][0]) goto ofail;
                } else {
                    uint32_t a0 = as_type(m, &body, lo[0], p0t), a1 = as_type(m, &body, hi[0], p1t), b0 = as_type(m, &body, lo[1], p0t), b1 = as_type(m, &body, hi[1], p1t), g = m->nb++;
                    uint32_t x[] = {3u << 16 | OpStore, v[0], a0, 3u << 16 | OpStore, v[1], a1, 3u << 16 | OpStore, v[2], b0, 3u << 16 | OpStore, v[3], b1,
                                    8u << 16 | OpFunctionCall, h->st_t, g, h->f_aux, v[0], v[1], v[2], v[3]};
                    if (!a0 || !a1 || !b0 || !b1 || !put_n(&body, x, sizeof x / 4)) goto ofail;
                    uint32_t it = h->idx_t, gmin = m->nb++, gmax = m->nb++;
                    uint32_t y[] = {5u << 16 | OpCompositeExtract, it, gmin, g, 0, 5u << 16 | OpCompositeExtract, it, gmax, g, 1};
                    if (!put_n(&body, y, sizeof y / 4)) goto ofail;
                    // mask: every bit from the highest bit where they differ down; step0 = that bit (0 when equal); i0 = their common leading bits
                    uint32_t d = emit3(m, &body, OpBitwiseXor, it, gmin, gmax);
                    for (int z = 0; z < 5; z++) { uint32_t sh = emit3(m, &body, OpShiftRightLogical, it, d, cst(m, it, 1u << z)); d = emit3(m, &body, OpBitwiseOr, it, d, sh); if (!sh || !d) goto ofail; }
                    uint32_t sh1 = emit3(m, &body, OpShiftRightLogical, it, d, cst(m, it, 1)), sd = emit3(m, &body, OpBitwiseXor, it, d, sh1);
                    uint32_t nm = emit2(m, &body, OpNot, it, d), ip = emit3(m, &body, OpBitwiseAnd, it, gmin, nm);
                    uint32_t nowrap = emit3(m, &body, OpUGreaterThanEqual, tb, hi[1], bhu);
                    cv[s->cl][0] = emit_sel(m, &body, it, nowrap, ip, h->i_init); cv[s->cl][1] = emit_sel(m, &body, it, nowrap, sd, h->s_init);
                    if (!sh1 || !sd || !nm || !ip || !nowrap || !cv[s->cl][0] || !cv[s->cl][1]) goto ofail;
                }
            }
            // the call itself
            if (h->form == FORM_PIDX) {
                uint32_t lu = as_type(m, &body, s->lo, tu), hu = as_type(m, &body, s->hi, tu); if (!lu || !hu) goto ofail;
                uint32_t q = emit3(m, &body, OpShiftRightLogical, tu, lu, c12), e1 = emit3(m, &body, OpIEqual, tb, hu, cv[s->cl][1]), e2 = emit3(m, &body, OpIEqual, tb, q, cv[s->cl][0]);
                uint32_t cc = emit3(m, &body, OpLogicalAnd, tb, e1, e2);
                uint32_t x[] = {9u << 16 | OpFunctionCall, w[i + 1], w[i + 2], h->f_new, w[i + 4], w[i + 5], cv[s->cl][2], cv[s->cl][3], cc};
                if (!q || !e1 || !e2 || !cc || !put_n(&body, x, 9)) goto ofail;
            } else {
                uint32_t x[] = {8u << 16 | OpFunctionCall, w[i + 1], w[i + 2], h->f_new, w[i + 4], w[i + 5], cv[s->cl][0], cv[s->cl][1]};
                if (!put_n(&body, x, 8)) goto ofail;
            }
            si++; i += LEN(m, i);
            continue;
        }
        if (!put_n(&body, w + i, LEN(m, i))) goto ofail;
        i += LEN(m, i);
    }
    // the new functions
    for (int k = 0; k < nh; k++) {
        helper_t *h = &hs[k]; if (!h->f_new) continue;
        int ok = h->form == FORM_PIDX ? emit_lk(m, &body, h, map) && emit_pidx_new(m, &body, h, map) : emit_ds(m, &body, h, map, map2) && emit_loop_new(m, &body, h, map);
        if (!ok) goto ofail;
    }
    // assemble: header, globals, the new declarations, the functions
    if (!put_n(&o, body.w, decl_at) || !put_n(&o, m->decl.w, m->decl.n) || !put_n(&o, body.w + decl_at, body.n - decl_at)) goto ofail;
    o.w[3] = m->nb;
    free(body.w); free(cv);
    // a last check of the words: every instruction whole, every id below the bound
    for (size_t i = 5; i < o.n;) { uint32_t len = o.w[i] >> 16; if (!len || i + len > o.n) FAIL("internal: bad instruction at word %zu of the output", i); i += len; }
    count_untaken(m, hid, nh, st); add_stats(st_out, st);
    int merged = st->merged;
    free(m->def); free(m->decl.w); free(P.s); free(P.c); free(fn_at); free(map); free(map2); free(last); free(fvars); free(taint); free(own);
    *out = o.w; *out_nw = o.n;
    return merged;
ofail:
    free(body.w); free(cv);
    FAIL("out of memory");
count_left:
none:
    if (m->def && nfn) count_untaken(m, hid, nh, st);
    add_stats(st_out, st);
    free(m->def); free(m->decl.w); free(P.s); free(P.c); free(fn_at); free(map); free(map2); free(last); free(fvars); free(taint); free(own);
    return 0;
fail:
    free(m->def); free(m->decl.w); free(P.s); free(P.c); free(fn_at); free(o.w); free(map); free(map2); free(last); free(fvars); free(taint); free(own);
    return -1;
#undef FAIL
}
