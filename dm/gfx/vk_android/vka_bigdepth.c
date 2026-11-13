// Big depth targets: test switches for GitHub issue 5. On the Adreno 840 (Turnip, Mesa db6c407) the device is lost right after the first write to Ocarina of Time's
// 12288x12288 D16 target (299.3 MiB of image memory for 288 MiB of storage); the Adreno 830 with the same driver and the Qualcomm driver on the 840 run it. The cause is open
// (the player-facing summary and the hypotheses are in the issue's report). A "big depth image" is a depth / stencil image whose guest storage is DM_VK_BIGDEPTH_MB (live,
// default 256) or more: in that game only the 12288x12288 target (288 MiB; its 4096x4096 depth targets have 32 and 64 MiB). Every switch is off by default and logs a line when it acts:
//   DM_VK_BIGDEPTH_CLEAR=<n> (live): how a clear of a big depth image (clear_ds) is recorded. 0 (default) as for any target: in the render pass over it when one follows (loadOp
//     CLEAR or vkCmdClearAttachments), else outside it. 1 vkCmdClearDepthStencilImage outside any pass (DM_VK_PASS_CLEARS=0 for this image only). 2 vkCmdClearAttachments
//     in a pass of its own over the whole level. 3 the same in horizontal bands of DM_VK_BIGDEPTH_BAND rows (live, 1024), each band a pass of its own whose render area is the
//     band. 4 no clear command: the clear value copied in from a buffer (vkCmdCopyBufferToImage, DM_VK_BIGDEPTH_BAND rows a copy, at most 8 MiB; depth-only D16 / D32
//     formats, others take 1). Turnip
//     clears the image's LRZ buffer (and its fast-clear flags) at a depth clear of 0, 1 or a pass's loadOp CLEAR, and 2 and 3 disable LRZ for their pass; after a copy (4) it
//     disables LRZ for the image until its next clear (tu_disable_lrz), so 4 tells whether the LRZ clear is where the GPU stops.
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

// DM_VK_BIGDEPTH_CLEAR's methods. The caller (clear_ds) has ended any open pass; the image is in GENERAL, as every replay image
static void clear_passes(VkCommandBuffer cb, const vka_pclear *c, uint32_t band) {   // 2 (band 0: one pass over the level) and 3
    vka_img *im = c->im;
    VkImageView view = vka_view(im, im->f.vk, c->nl > 1 ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D, im->aspect, c->lv, 1, c->l0, c->nl, NULL);
    if (!view) { VRED_ONCE("bigdepth: no view of serial %llu for its clear: cleared with vkCmdClearDepthStencilImage instead", (unsigned long long)im->serial); vka_clear_outside(c); return; }
    uint32_t w = im->w >> c->lv ? im->w >> c->lv : 1, h = im->h >> c->lv ? im->h >> c->lv : 1;
    VkRenderingAttachmentInfo a = {VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO, .imageView = view, .imageLayout = VK_IMAGE_LAYOUT_GENERAL, .loadOp = VK_ATTACHMENT_LOAD_OP_LOAD, .storeOp = VK_ATTACHMENT_STORE_OP_STORE};
    uint32_t y0 = (uint32_t)c->rc.offset.y, y1 = y0 + c->rc.extent.height, step = band ? band : c->rc.extent.height;
    for (uint32_t y = y0; y < y1; y += step) {
        uint32_t bh = y1 - y < step ? y1 - y : step;
        VkRect2D area = band ? (VkRect2D){{c->rc.offset.x, (int32_t)y}, {c->rc.extent.width, bh}} : (VkRect2D){{0, 0}, {w, h}};
        VkRenderingInfo ri = {VK_STRUCTURE_TYPE_RENDERING_INFO, .renderArea = area, .layerCount = c->nl,
                              .pDepthAttachment = im->aspect & VK_IMAGE_ASPECT_DEPTH_BIT ? &a : NULL, .pStencilAttachment = im->aspect & VK_IMAGE_ASPECT_STENCIL_BIT ? &a : NULL};
        vk.vkCmdBeginRendering(cb, &ri);
        VkClearAttachment ca = {c->asp, 0, c->out}; VkClearRect cr = {{{c->rc.offset.x, (int32_t)y}, {c->rc.extent.width, bh}}, 0, c->nl};
        vk.vkCmdClearAttachments(cb, 1, &ca, 1, &cr);
        vk.vkCmdEndRendering(cb);
    }
    vka_barrier(cb);
}
static int clear_copy(VkCommandBuffer cb, const vka_pclear *c, uint32_t band) {   // 4: 0 = not for this format (the caller takes 1)
    vka_img *im = c->im; uint32_t bpt;
    if (c->asp != VK_IMAGE_ASPECT_DEPTH_BIT) return 0;
    if (im->f.vk == VK_FORMAT_D16_UNORM) bpt = 2; else if (im->f.vk == VK_FORMAT_D32_SFLOAT) bpt = 4; else return 0;
    if (im->samples > 1) return 0;
    uint32_t rows = band ? band : 1024, cap = (uint32_t)((8u << 20) / ((uint64_t)c->rc.extent.width * bpt));   // (at most 8 MiB of scratch: the same rows copied band after band)
    if (rows > cap) rows = cap ? cap : 1;
    if (rows > c->rc.extent.height) rows = c->rc.extent.height;
    VkDeviceSize n = (VkDeviceSize)c->rc.extent.width * rows * bpt;
    vka_span sp; if (!vka_scratch(n, 16, &sp)) return 0;
    float d = c->out.depthStencil.depth;
    if (bpt == 2) { float cl = d < 0 ? 0 : d > 1 ? 1 : d; uint16_t v = (uint16_t)lrintf(cl * 65535.0f); uint16_t *p = (uint16_t *)sp.ptr; for (VkDeviceSize i = 0; i < n / 2; i++) p[i] = v; }
    else { uint32_t v; memcpy(&v, &d, 4); uint32_t *p = (uint32_t *)sp.ptr; for (VkDeviceSize i = 0; i < n / 4; i++) p[i] = v; }
    for (uint32_t l = c->l0; l < c->l0 + c->nl; l++)
        for (uint32_t y = (uint32_t)c->rc.offset.y, y1 = y + c->rc.extent.height; y < y1; y += rows) {
            uint32_t bh = y1 - y < rows ? y1 - y : rows;
            VkBufferImageCopy r = {.bufferOffset = sp.off, .imageSubresource = {VK_IMAGE_ASPECT_DEPTH_BIT, c->lv, l, 1}, .imageOffset = {c->rc.offset.x, (int32_t)y, 0},
                                   .imageExtent = {c->rc.extent.width, bh, 1}};
            vk.vkCmdCopyBufferToImage(cb, sp.buf, im->img, VK_IMAGE_LAYOUT_GENERAL, 1, &r);
        }
    vka_barrier(cb);
    return 1;
}
int vka_bigdepth_clear(VkCommandBuffer cb, const vka_pclear *c) {
    int mode = VKA_ENV("DM_VK_BIGDEPTH_CLEAR", 0);
    if (mode < 1 || mode > 4 || !cb || !vka_bigdepth(c->im)) return 0;
    uint32_t band = (uint32_t)(VKA_ENV("DM_VK_BIGDEPTH_BAND", 1024) > 0 ? VKA_ENV("DM_VK_BIGDEPTH_BAND", 1024) : 1024);
    const char *how = mode == 1 ? "vkCmdClearDepthStencilImage outside any pass" : mode == 2 ? "vkCmdClearAttachments in a pass of its own" : mode == 3 ? "vkCmdClearAttachments in bands, a pass each" : "a buffer copy of the clear value";
    if (mode == 4 && !clear_copy(cb, c, band)) { mode = 1; how = "vkCmdClearDepthStencilImage outside any pass (a copy takes depth-only D16 / D32 images)"; }
    if (mode == 1) vka_clear_outside(c);
    else if (mode == 2) clear_passes(cb, c, 0);
    else if (mode == 3) clear_passes(cb, c, band);
    static _Atomic int n; if (atomic_fetch_add(&n, 1) < 8)
        VLOG("bigdepth: serial %llu %ux%u VkFormat %d: clear (aspect %#x, depth %g, stencil %u, rectangle %d,%d %ux%u, level %u layers %u+%u) recorded as %s (DM_VK_BIGDEPTH_CLEAR=%d%s)",
             (unsigned long long)c->im->serial, c->im->w, c->im->h, c->im->f.vk, c->asp, c->out.depthStencil.depth, c->out.depthStencil.stencil, c->rc.offset.x, c->rc.offset.y,
             c->rc.extent.width, c->rc.extent.height, c->lv, c->l0, c->nl, how, VKA_ENV("DM_VK_BIGDEPTH_CLEAR", 0), mode >= 3 ? ", DM_VK_BIGDEPTH_BAND rows a step" : "");
    fflush(stderr);
    return 1;
}
#endif
