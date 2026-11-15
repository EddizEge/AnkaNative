#!/usr/bin/env python3
# Writes the raw global memory merge's test fixtures (hand-written compute modules with a load helper in the translator's two forms, no game code) as .spvasm beside
# this file and assembles them into gmem_fixtures.h for t_android_gmem.c. Run after changing this file: python3 dm/host/tests/gmem/gen.py (needs spirv-as and
# spirv-val on the PATH).
#
# Each module reads base address words from the IN buffer (set 0 binding 1), calls the helper for base + constant offsets in the translator's form (low word + k,
# high word + carry, both through Function variables), keeps each result in a Function variable and writes them all to OUT (binding 2) at the end. The scenarios:
# a vec4 run (0, 4, 8, 12), a second base interleaved with it, a run across a 4 KiB bucket (4088 .. 4100), a run cut by a store to OUT, offsets near 2^32 (carries),
# a run that the _tw variant cuts with a store to the table itself, a run in another block, a call whose words are stored in another block (not known there), and a
# call whose low word is not base + constant. The helper's table is binding 0: pool entries of 8 words (start, end, delta, 2 unused), entry 0's words 6 and 7 the sink,
# and from word 32768 the page index (first page, page count, 2 words, then a record of 2 words per page and one past the window).
import os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

HEAD = '''               OpCapability Shader
               OpCapability Int64
               OpCapability PhysicalStorageBufferAddresses
               OpExtension "SPV_KHR_physical_storage_buffer"
      %glsl = OpExtInstImport "GLSL.std.450"
               OpMemoryModel PhysicalStorageBuffer64 GLSL450
               OpEntryPoint GLCompute %main "main"
               OpExecutionMode %main LocalSize 1 1 1
               OpDecorate %rta ArrayStride 4
               OpDecorate %blk BufferBlock
               OpMemberDecorate %blk 0 Offset 0
               OpDecorate %T DescriptorSet 0
               OpDecorate %T Binding 0
               OpDecorate %IN DescriptorSet 0
               OpDecorate %IN Binding 1
               OpDecorate %OUT DescriptorSet 0
               OpDecorate %OUT Binding 2
       %void = OpTypeVoid
       %bool = OpTypeBool
       %uint = OpTypeInt 32 0
        %int = OpTypeInt 32 1
      %float = OpTypeFloat 32
      %ulong = OpTypeInt 64 0
     %v2uint = OpTypeVector %uint 2
        %rta = OpTypeRuntimeArray %uint
        %blk = OpTypeStruct %rta
     %p_blk = OpTypePointer Uniform %blk
      %p_u  = OpTypePointer Uniform %uint
      %p_fi = OpTypePointer Function %int
     %p_psb = OpTypePointer PhysicalStorageBuffer %uint
        %fnv = OpTypeFunction %void
        %fnh = OpTypeFunction %int %p_fi %p_fi
          %T = OpVariable %p_blk Uniform
         %IN = OpVariable %p_blk Uniform
        %OUT = OpVariable %p_blk Uniform
      %int_0 = OpConstant %int 0
      %int_1 = OpConstant %int 1
      %int_6 = OpConstant %int 6
      %int_7 = OpConstant %int 7
      %int_8 = OpConstant %int 8
      %int_2 = OpConstant %int 2
      %int_3 = OpConstant %int 3
      %int_4 = OpConstant %int 4
      %int_5 = OpConstant %int 5
   %int_2048 = OpConstant %int 2048
  %int_16384 = OpConstant %int 16384
  %int_32768 = OpConstant %int 32768
     %uint_0 = OpConstant %uint 0
     %uint_1 = OpConstant %uint 1
    %uint_12 = OpConstant %uint 12
    %uint_20 = OpConstant %uint 20
  %uint_4095 = OpConstant %uint 4095
 %uint_32768 = OpConstant %uint 32768
 %uint_32769 = OpConstant %uint 32769
 %uint_32772 = OpConstant %uint 32772
  %uint_page = OpConstant %uint 4294963200
'''

# The page index form (vk_android_spv.py gmem_pidx's shape): one record read at an index from the high word and the low word >> 12, the device address from it
PIDX = '''          %H = OpFunction %int None %fnh
         %p0 = OpFunctionParameter %p_fi
         %p1 = OpFunctionParameter %p_fi
         %hl = OpLabel
         %a0 = OpLoad %int %p0
         %lo = OpBitcast %uint %a0
         %a1 = OpLoad %int %p1
         %hi = OpBitcast %uint %a1
        %ix0 = OpBitcast %int %uint_32768
        %pc0 = OpAccessChain %p_u %T %int_0 %ix0
      %first = OpLoad %uint %pc0
        %ix1 = OpBitcast %int %uint_32769
        %pc1 = OpAccessChain %p_u %T %int_0 %ix1
      %count = OpLoad %uint %pc1
         %hc = OpExtInst %uint %glsl UMin %hi %uint_4095
         %hs = OpShiftLeftLogical %uint %hc %uint_20
         %ls = OpShiftRightLogical %uint %lo %uint_12
        %key = OpBitwiseOr %uint %hs %ls
        %rel = OpISub %uint %key %first
        %idx = OpExtInst %uint %glsl UMin %rel %count
         %i2 = OpShiftLeftLogical %uint %idx %uint_1
         %w0 = OpIAdd %uint %i2 %uint_32772
        %w0i = OpBitcast %int %w0
        %pr0 = OpAccessChain %p_u %T %int_0 %w0i
         %r0 = OpLoad %uint %pr0
         %w1 = OpIAdd %uint %w0 %uint_1
        %w1i = OpBitcast %int %w1
        %pr1 = OpAccessChain %p_u %T %int_0 %w1i
         %r1 = OpLoad %uint %pr1
         %vb = OpBitwiseAnd %uint %r0 %uint_1
      %valid = OpINotEqual %bool %vb %uint_0
         %pg = OpBitwiseAnd %uint %r0 %uint_page
        %off = OpBitwiseAnd %uint %lo %uint_4095
        %dlo = OpBitwiseOr %uint %pg %off
         %dv = OpCompositeConstruct %v2uint %dlo %r1
        %d64 = OpBitcast %ulong %dv
        %ps0 = OpAccessChain %p_u %T %int_0 %int_6
         %s0 = OpLoad %uint %ps0
        %ps1 = OpAccessChain %p_u %T %int_0 %int_7
         %s1 = OpLoad %uint %ps1
         %sv = OpCompositeConstruct %v2uint %s0 %s1
        %s64 = OpBitcast %ulong %sv
        %a64 = OpSelect %ulong %valid %d64 %s64
         %pp = OpConvertUToPtr %p_psb %a64
         %ld = OpLoad %uint %pp Aligned 4
         %lf = OpBitcast %float %ld
         %li = OpBitcast %int %lf
          %r = OpSelect %int %valid %li %int_0
         %nv = OpLogicalNot %bool %valid
        %res = OpSelect %int %nv %int_0 %r
               OpReturnValue %res
               OpFunctionEnd
'''

# The pool search form (gmem_loop's shape): 12 halving steps over the entries' starts (64-bit start <= address), then the entry's end and delta
LOOP = '''          %H = OpFunction %int None %fnh
         %p0 = OpFunctionParameter %p_fi
         %p1 = OpFunctionParameter %p_fi
         %l0 = OpLabel
               OpBranch %lh
         %lh = OpLabel
          %i = OpPhi %int %int_0 %l0 %iN %lc
          %s = OpPhi %int %int_2048 %l0 %sN %lc
               OpLoopMerge %lm %lc DontUnroll
               OpBranch %lb
         %lb = OpLabel
        %mid = OpIAdd %int %i %s
         %e8 = OpIMul %int %mid %int_8
         %x0 = OpIAdd %int %e8 %int_0
         %q0 = OpAccessChain %p_u %T %int_0 %x0
         %sl = OpLoad %uint %q0
         %x1 = OpIAdd %int %e8 %int_1
         %q1 = OpAccessChain %p_u %T %int_0 %x1
         %sh = OpLoad %uint %q1
         %ah = OpLoad %int %p1
        %ahu = OpBitcast %uint %ah
         %lt = OpULessThan %bool %sh %ahu
        %shi = OpBitcast %int %sh
        %ah2 = OpLoad %int %p1
         %eq = OpIEqual %bool %shi %ah2
         %al = OpLoad %int %p0
        %alu = OpBitcast %uint %al
         %le = OpULessThanEqual %bool %sl %alu
        %and = OpLogicalAnd %bool %eq %le
         %or = OpLogicalOr %bool %lt %and
         %iN = OpSelect %int %or %mid %i
         %sN = OpShiftRightLogical %int %s %int_1
               OpBranch %lc
         %lc = OpLabel
       %done = OpIEqual %bool %sN %int_0
               OpBranchConditional %done %lm %lh
         %lm = OpLabel
         %g8 = OpIMul %int %iN %int_8
         %y2 = OpIAdd %int %g8 %int_2
         %u2 = OpAccessChain %p_u %T %int_0 %y2
         %el = OpLoad %uint %u2
         %y3 = OpIAdd %int %g8 %int_3
         %u3 = OpAccessChain %p_u %T %int_0 %y3
         %eh = OpLoad %uint %u3
         %bh = OpLoad %int %p1
        %bhu = OpBitcast %uint %bh
        %plt = OpULessThan %bool %bhu %eh
        %ehi = OpBitcast %int %eh
        %bh2 = OpLoad %int %p1
        %peq = OpIEqual %bool %bh2 %ehi
         %bl = OpLoad %int %p0
        %blu = OpBitcast %uint %bl
        %pl2 = OpULessThan %bool %blu %el
       %pand = OpLogicalAnd %bool %peq %pl2
     %inpool = OpLogicalOr %bool %plt %pand
         %y4 = OpIAdd %int %g8 %int_4
         %u4 = OpAccessChain %p_u %T %int_0 %y4
         %dl = OpLoad %uint %u4
         %y5 = OpIAdd %int %g8 %int_5
         %u5 = OpAccessChain %p_u %T %int_0 %y5
         %dh = OpLoad %uint %u5
        %nlo = OpIAdd %uint %blu %dl
         %cy = OpULessThan %bool %nlo %blu
        %cyi = OpSelect %uint %cy %uint_1 %uint_0
        %nh0 = OpIAdd %uint %bhu %dh
        %nhi = OpIAdd %uint %nh0 %cyi
         %dv = OpCompositeConstruct %v2uint %nlo %nhi
        %d64 = OpBitcast %ulong %dv
        %ps0 = OpAccessChain %p_u %T %int_0 %int_6
         %s0 = OpLoad %uint %ps0
        %ps1 = OpAccessChain %p_u %T %int_0 %int_7
         %s1 = OpLoad %uint %ps1
         %sv = OpCompositeConstruct %v2uint %s0 %s1
        %s64 = OpBitcast %ulong %sv
        %a64 = OpSelect %ulong %inpool %d64 %s64
         %pp = OpConvertUToPtr %p_psb %a64
         %ld = OpLoad %uint %pp Aligned 4
         %lf = OpBitcast %float %ld
         %li = OpBitcast %int %lf
          %r = OpSelect %int %inpool %li %int_0
         %nv = OpLogicalNot %bool %inpool
        %res = OpSelect %int %nv %int_0 %r
               OpReturnValue %res
               OpFunctionEnd
'''


class Entry:
    def __init__(self):
        self.consts, self.vars, self.code, self.n, self.res = {}, [], [], 0, []

    def new(self, p='t'):
        self.n += 1
        return f'%{p}{self.n}'

    def k(self, v):   # an int constant (the translator's offsets are int constants)
        if v not in self.consts: self.consts[v] = f'%k{len(self.consts)}'
        return self.consts[v]

    def var(self):
        v = self.new('v'); self.vars.append(f'{v} = OpVariable %p_fi Function'); return v

    def load_in(self, word):
        p, x = self.new(), self.new()
        self.code += [f'{p} = OpAccessChain %p_u %IN %int_0 {self.k(word)}', f'{x} = OpLoad %uint {p}']
        b = self.new(); self.code.append(f'{b} = OpBitcast %int {x}')
        return b

    def call(self, blo, bhi, k, lo=None):   # one helper call at base + k (the translator's words), its result kept in a Function variable
        c = self.code
        if lo is None:
            lo = self.new(); c.append(f'{lo} = OpIAdd %int {blo} {self.k(k)}')
            lu, bu, cr, cs, hi = self.new(), self.new(), self.new(), self.new(), self.new()
            c += [f'{lu} = OpBitcast %uint {lo}', f'{bu} = OpBitcast %uint {blo}', f'{cr} = OpULessThan %bool {lu} {bu}', f'{cs} = OpSelect %int {cr} %int_1 %int_0',
                  f'{hi} = OpIAdd %int {bhi} {cs}']
        else: hi = bhi
        vl, vh, vr, r = self.var(), self.var(), self.var(), self.new()
        c += [f'OpStore {vl} {lo}', f'OpStore {vh} {hi}', f'{r} = OpFunctionCall %int %H {vl} {vh}', f'OpStore {vr} {r}']
        self.res.append(vr)
        return vl, vh, vr

    def store_out(self, word, val):
        p = self.new(); self.code += [f'{p} = OpAccessChain %p_u %OUT %int_0 {self.k(word)}']
        u = self.new(); self.code += [f'{u} = OpBitcast %uint {val}', f'OpStore {p} {u}']


def module(form, tw):
    e = Entry()
    b = [(e.load_in(2 * i), e.load_in(2 * i + 1)) for i in range(7)]
    # the table word the _tw variant writes, and its value (made in every variant, so the rewritten main module can take the store as it is)
    twp, twv = '%twp', '%twv'
    e.code += [f'{twp} = OpAccessChain %p_u %T %int_0 {"%int_32768" if form == "pidx" else "%int_16384"}', f'{twv} = OpBitcast %uint {b[6][0]}']
    for k in (0, 4, 8, 12): e.call(*b[0], k)                     # a vec4 at base 0 ...
    for k in (0, 4): e.call(*b[1], k)                            # ... and a second base interleaved with its tail
    e.call(*b[0], 16); e.call(*b[1], 8)
    for k in (4088, 4092, 4096, 4100): e.call(*b[2], k)          # across the 4 KiB bucket
    e.call(*b[3], 16); e.call(*b[3], 20)
    e.store_out(100, b[3][0])                                    # a store between the calls of one base
    e.call(*b[3], 24)
    for k in (0xfffffff0, 0xfffffff4, 0xfffffff8): e.call(*b[4], k)   # offsets near 2^32: carries
    e.call(*b[5], 48); e.call(*b[5], 52)
    if tw: e.code.append(f'OpStore {twp} {twv}')                 # the table written between the calls
    e.call(*b[5], 56); e.call(*b[5], 60)
    vl, vh, _ = e.var(), e.var(), None
    lo = e.new(); e.code += [f'{lo} = OpIAdd %int {b[6][0]} {e.k(64)}', f'OpStore {vl} {lo}', f'OpStore {vh} {b[6][1]}']   # words stored in this block, called in the next
    x = e.new(); e.code.append(f'{x} = OpBitwiseXor %int {b[6][0]} {e.k(16)}'); e.call(None, b[6][1], 0, lo=x)   # not base + constant
    cin = e.load_in(14); cb = e.new(); cnd = e.new()
    e.code += [f'{cb} = OpBitwiseAnd %int {cin} %int_1', f'{cnd} = OpINotEqual %bool {cb} %int_0', 'OpSelectionMerge %mrg None', 'OpBranchConditional ' + cnd + ' %then %mrg', '%then = OpLabel']
    for k in (32, 36, 40, 44): e.call(*b[0], k)                  # in another block
    e.code += ['OpBranch %mrg', '%mrg = OpLabel']
    ru, r = e.new(), e.new(); vr = e.var(); e.res.append(vr)
    e.code += [f'{r} = OpFunctionCall %int %H {vl} {vh}', f'OpStore {vr} {r}']
    for j, v in enumerate(e.res):
        x = e.new(); e.code.append(f'{x} = OpLoad %int {v}'); e.store_out(j, x)
    out = [HEAD] + [f'{n} = OpConstant %int {v if v < 2**31 else v - 2**32}' for v, n in e.consts.items()]
    out += ['%main = OpFunction %void None %fnv', '%entry = OpLabel'] + e.vars + e.code + ['OpReturn', 'OpFunctionEnd', PIDX if form == 'pidx' else LOOP]
    return '\n'.join(out) + '\n'


FIXTURES = [('pidx', False, 'GMEM_PIDX'), ('pidx', True, 'GMEM_PIDX_TW'), ('loop', False, 'GMEM_LOOP'), ('loop', True, 'GMEM_LOOP_TW')]


def main():
    out = ['// Generated by dm/host/tests/gmem/gen.py (spirv-as --target-env vulkan1.1). Do not edit', '#pragma once', '#include <stdint.h>']
    for form, tw, sym in FIXTURES:
        name = form + ('_tw' if tw else '')
        src = os.path.join(HERE, name + '.spvasm')
        open(src, 'w').write(module(form, tw))
        with tempfile.TemporaryDirectory() as td:
            spv = os.path.join(td, name + '.spv')
            subprocess.run(['spirv-as', '--target-env', 'vulkan1.1', '-o', spv, src], check=True)
            subprocess.run(['spirv-val', '--target-env', 'vulkan1.1', spv], check=True)
            data = open(spv, 'rb').read()
        words = [int.from_bytes(data[i:i + 4], 'little') for i in range(0, len(data), 4)]
        out.append('static const uint32_t %s[] = {' % sym)
        for i in range(0, len(words), 12):
            out.append('    ' + ', '.join('0x%08x' % w for w in words[i:i + 12]) + ',')
        out.append('};')
    open(os.path.join(HERE, 'gmem_fixtures.h'), 'w').write('\n'.join(out) + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
