// Big depth targets: test switches for GitHub issue 5. On the Adreno 840 (Turnip, Mesa db6c407) the device is lost right after the first write to Ocarina of Time's
// 12288x12288 D16 target (299.3 MiB of image memory for 288 MiB of storage); the Adreno 830 with the same driver and the Qualcomm driver on the 840 run it. The cause is open
// (the player-facing summary and the hypotheses are in the issue's report). A "big depth image" is a depth / stencil image whose guest storage is DM_VK_BIGDEPTH_MB (live,
// default 256) or more: in that game only the 12288x12288 target (288 MiB; its 4096x4096 depth targets have 32 and 64 MiB). Every switch is off by default and logs a line when it acts:
//   DM_VK_BIGDEPTH_CLEAR=<n> (live): how a clear of a big depth image (clear_ds) is recorded. 0 (default) as for any target: in the render pass over it when one follows (loadOp
//     CLEAR or vkCmdClearAttachments), else outside it. 1 vkCmdClearDepthStencilImage outside any pass (DM_VK_PASS_CLEARS=0 for this image only). 2 vkCmdClearAttachments
//     in a pass of its own over the whole level. 3 the same in horizontal bands of DM_VK_BIGDEPTH_BAND rows (live, 1024), each band a pass of its own whose render area is the
//     band. 4 no clear command: the clear value copied in from a buffer (vkCmdCopyBufferToImage, DM_VK_BIGDEPTH_BAND rows a copy, at most 8 MiB; depth-only D16 / D32
//     formats, others take 1; a value outside 0-4 is logged once and taken as 0, before any pass is ended). Turnip clears the image's LRZ buffer (and its fast-clear flags) at
//     a depth clear of 0, 1 or a pass's loadOp CLEAR, and 2 and 3 disable LRZ for their pass. A copy (4) makes no LRZ fast clear: Turnip marks the image's LRZ disabled
//     (tu_disable_lrz, which still writes that mark into the LRZ buffer) until its next clear, so 4 leaves out the LRZ clear and LRZ use, not every LRZ write.
//   DM_VK_BIGDEPTH_NOCOMP=1 (restart): VK_EXT_image_compression_control enabled on the device and big depth images made with VK_IMAGE_COMPRESSION_DISABLED_EXT: no UBWC and
//     no flag buffer for those images only (TU_DEBUG=noubwc for one image). Logged once at the device and at each such image.
//   DM_VK_BIGDEPTH_SYNC=1 (live): after a big depth image's clear (outside a pass: with DM_VK_BIGDEPTH_CLEAR 1-4) and after the first DM_VK_BIGDEPTH_SYNC_PASSES (live, 4)
//     passes that have it as their depth attachment, the submission's part so far is handed to the queue and the queue waited for (vkQueueWaitIdle), with a line saying
//     whether it finished: the last such line before a loss names the step the GPU did not finish. Costs a GPU drain each time (a stutter), so a test only.
//   DM_VK_BIGDEPTH_LOG=1 (live): at most 64 "[vk] bigdepth" lines: each big depth image's create info and memory requirements, how its clears are recorded, each pass over it
//     (render area, load and store ops, clear value). Each line is followed by an fsync of the log, so the last ones before a lost device or a stopped process are on flash.
// They choose a method the Vulkan specification allows for the same result (a clear is a clear); none hides an error. Default behaviour, with none of them set, is unchanged
#if defined(__ANDROID__)
#include "vka.h"
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

uint64_t vka_bigdepth_pass_serial;
int vka_bigdepth_sync_on(void) { return VKA_ENV("DM_VK_BIGDEPTH_SYNC", 0) > 0; }

int vka_bigdepth_size(int kind, uint64_t storage) {
    if (kind < 3) return 0;
    double mb = VKA_ENVF("DM_VK_BIGDEPTH_MB", 256.0);
    return mb > 0 && (double)storage >= mb * 1048576.0;
}
int vka_bigdepth(const vka_img *im) { return im && vka_bigdepth_size(im->f.kind, im->L.size); }

int vka_bigdepth_log_on(void) { return VKA_ENV("DM_VK_BIGDEPTH_LOG", 0) > 0; }
void vka_bigdepth_log(const char *fmt, ...) {
    static _Atomic int n;
    if (!vka_bigdepth_log_on() || atomic_fetch_add(&n, 1) >= 64) return;
    char b[768]; va_list ap; va_start(ap, fmt); vsnprintf(b, sizeof b, fmt, ap); va_end(ap);
    VLOG("bigdepth: %s", b);
    fflush(stderr); fsync(STDERR_FILENO);   // (stderr is the run's log file: on flash before the device or the process is lost; EINVAL on a pipe, harmless)
}

static const char *pnext_kinds(const void *p, char *b, size_t n) {   // the create info's pNext chain, by sType
    size_t o = 0; b[0] = 0;
    for (const VkBaseInStructure *s = p; s && o < n; s = s->pNext)
        o += (size_t)snprintf(b + o, n - o, "%s%s", o ? "," : "", s->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO ? "format list" :
                              s->sType == VK_STRUCTURE_TYPE_IMAGE_COMPRESSION_CONTROL_EXT ? "compression disabled" : "other");
    if (!o) snprintf(b, n, "none");
    return b;
}
void vka_bigdepth_made(const vka_img *im, const VkImageCreateInfo *ci, const VkMemoryRequirements *mr, int nocomp, const char *memory) {
    if (nocomp) { static _Atomic int n; if (atomic_fetch_add(&n, 1) < 8)
        VLOG("bigdepth: texture serial %llu %ux%u format %#x (VkFormat %d): made without compression (DM_VK_BIGDEPTH_NOCOMP; %.1f MiB of image memory)", (unsigned long long)im->serial,
             im->w, im->h, im->d.format, ci->format, mr->size / 1048576.0); }
    char pn[96];
    vka_bigdepth_log("made: texture serial %llu storage %.1f MiB at %p; VkImageCreateInfo flags %#x type %d format %d extent %ux%ux%u levels %u layers %u samples %d tiling %d usage %#x "
                     "pNext %s; memory requirements size %llu (%.2f MiB) alignment %llu type bits %#x; memory: %s", (unsigned long long)im->serial, im->L.size / 1048576.0,
                     (const void *)im->mem, ci->flags, ci->imageType, ci->format, ci->extent.width, ci->extent.height, ci->extent.depth, ci->mipLevels, ci->arrayLayers, ci->samples,
                     ci->tiling, ci->usage, pnext_kinds(ci->pNext, pn, sizeof pn), (unsigned long long)mr->size, mr->size / 1048576.0, (unsigned long long)mr->alignment,
                     mr->memoryTypeBits, memory);
}

// DM_VK_BIGDEPTH_CLEAR's methods. The caller (clear_ds) has ended any open pass; the image is in GENERAL, as every replay image. Each returns what it recorded (the log line)
static const char *clear_passes(VkCommandBuffer cb, const vka_pclear *c, uint32_t band) {   // 2 (band 0: one pass over the level) and 3: vka_clear_rect, a pass each
    vka_img *im = c->im;
    if (!(im->usage & VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)) {   // (img_create_ may drop the usage the format lacks)
        vka_clear_outside(c);
        return c->whole ? "vkCmdClearDepthStencilImage outside any pass (the image cannot be a depth attachment)"
                        : "nothing: the image cannot be a depth attachment, and a part of a level is cleared only in a pass";
    }
    uint32_t y0 = (uint32_t)c->rc.offset.y, y1 = y0 + c->rc.extent.height, step = band ? band : c->rc.extent.height;
    for (uint32_t y = y0; y < y1; y += step) {
        uint32_t bh = y1 - y < step ? y1 - y : step;
        VkRect2D r = {{c->rc.offset.x, (int32_t)y}, {c->rc.extent.width, bh}};
        if (vka_clear_rect(cb, im, im->f.vk, c->asp, c->lv, c->l0, c->nl, r, &c->out, band ? &r : NULL) > 0) continue;
        if (y != y0) { VRED_ONCE("bigdepth: serial %llu: no view for a band of its clear: the rest not cleared", (unsigned long long)im->serial); vka_barrier(cb); return "vkCmdClearAttachments in bands, a pass each (stopped: no view)"; }
        if (!c->whole) VRED_ONCE("bigdepth: serial %llu: no view for its clear in a pass: not cleared", (unsigned long long)im->serial);
        vka_clear_outside(c);   // (no view of it: none was recorded)
        return c->whole ? "vkCmdClearDepthStencilImage outside any pass (no view of the image for a pass)" : "nothing: no view of the image for a pass";
    }
    vka_barrier(cb);
    return band ? "vkCmdClearAttachments in bands, a pass each" : "vkCmdClearAttachments in a pass of its own";
}
static const char *clear_copy(VkCommandBuffer cb, const vka_pclear *c, uint32_t band) {   // 4: NULL = recorded; else why not (the caller takes 1)
    vka_img *im = c->im; uint32_t bpt;
    if (c->asp != VK_IMAGE_ASPECT_DEPTH_BIT) return "a copy takes depth-only D16 / D32 images";
    if (im->f.vk == VK_FORMAT_D16_UNORM) bpt = 2; else if (im->f.vk == VK_FORMAT_D32_SFLOAT) bpt = 4; else return "a copy takes depth-only D16 / D32 images";
    if (im->samples > 1) return "a copy takes single-sample images";
    uint32_t rows = band ? band : 1024, cap = (uint32_t)((8u << 20) / ((uint64_t)c->rc.extent.width * bpt));   // (at most 8 MiB of scratch: the same rows copied band after band)
    if (rows > cap) rows = cap ? cap : 1;
    if (rows > c->rc.extent.height) rows = c->rc.extent.height;
    VkDeviceSize n = (VkDeviceSize)c->rc.extent.width * rows * bpt;
    vka_span sp; if (!vka_scratch(n, 16, &sp)) return "no scratch memory for the copy";
    // [0, 1] as a clear writes it: a copy into D32_SFLOAT is outside the spec for other values without VK_EXT_depth_range_unrestricted (D16 takes only those)
    float d = c->out.depthStencil.depth, cl = d >= 0 ? d <= 1 ? d : 1 : 0;   // (NaN: 0)
    if (bpt == 2) { uint16_t v = (uint16_t)lrintf(cl * 65535.0f); uint16_t *p = (uint16_t *)sp.ptr; for (VkDeviceSize i = 0; i < n / 2; i++) p[i] = v; }
    else { uint32_t v; memcpy(&v, &cl, 4); uint32_t *p = (uint32_t *)sp.ptr; for (VkDeviceSize i = 0; i < n / 4; i++) p[i] = v; }
    for (uint32_t l = c->l0; l < c->l0 + c->nl; l++)
        for (uint32_t y = (uint32_t)c->rc.offset.y, y1 = y + c->rc.extent.height; y < y1; y += rows) {
            uint32_t bh = y1 - y < rows ? y1 - y : rows;
            VkBufferImageCopy r = {.bufferOffset = sp.off, .imageSubresource = {VK_IMAGE_ASPECT_DEPTH_BIT, c->lv, l, 1}, .imageOffset = {c->rc.offset.x, (int32_t)y, 0},
                                   .imageExtent = {c->rc.extent.width, bh, 1}};
            vk.vkCmdCopyBufferToImage(cb, sp.buf, im->img, VK_IMAGE_LAYOUT_GENERAL, 1, &r);
        }
    vka_barrier(cb);
    return NULL;
}
static const char *clear_outside_how(const vka_pclear *c) {   // what vka_clear_outside records for a depth / stencil clear
    return c->whole ? "vkCmdClearDepthStencilImage outside any pass" : "vkCmdClearAttachments in a pass of its own (a scissored clear: vkCmdClearDepthStencilImage takes whole levels)";
}
int vka_bigdepth_clear(VkCommandBuffer cb, const vka_pclear *c, int mode) {
    if (mode < 1 || mode > 4 || !cb || !vka_bigdepth(c->im)) return 0;
    uint32_t band = (uint32_t)(VKA_ENV("DM_VK_BIGDEPTH_BAND", 1024) > 0 ? VKA_ENV("DM_VK_BIGDEPTH_BAND", 1024) : 1024);
    const char *how = NULL; char b[160];
    if (mode == 4) {
        const char *why = clear_copy(cb, c, band);
        if (!why) how = "a buffer copy of the clear value";
        else { vka_clear_outside(c); snprintf(b, sizeof b, "%s (not a copy: %s)", clear_outside_how(c), why); how = b; }
    } else if (mode == 1) { vka_clear_outside(c); how = clear_outside_how(c); }
    else how = clear_passes(cb, c, mode == 3 ? band : 0);
    static _Atomic int n; if (atomic_fetch_add(&n, 1) < 8)
        VLOG("bigdepth: serial %llu %ux%u VkFormat %d: clear (aspect %#x, depth %g, stencil %u, rectangle %d,%d %ux%u, level %u layers %u+%u) recorded as %s (DM_VK_BIGDEPTH_CLEAR=%d%s)",
             (unsigned long long)c->im->serial, c->im->w, c->im->h, c->im->f.vk, c->asp, c->out.depthStencil.depth, c->out.depthStencil.stencil, c->rc.offset.x, c->rc.offset.y,
             c->rc.extent.width, c->rc.extent.height, c->lv, c->l0, c->nl, how, mode, mode >= 3 ? ", DM_VK_BIGDEPTH_BAND rows a step" : "");
    fflush(stderr);
    return 1;
}
#endif
