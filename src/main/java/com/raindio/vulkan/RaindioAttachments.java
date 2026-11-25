package com.raindio.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.raindio.RaindioClientMod;
import com.raindio.mixin.VulkanCommandEncoderAccessor;
import com.raindio.pipeline.RaindioPipelineSpec;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VK12;

/**
 * Owns all Raindio Vulkan G-Buffer, Shadow, Atmosphere LUT, half-res SSR/GTAO, quarter-res Bloom,
 * and persistent TAA temporal history attachments created through RenderPearl's GpuDevice API.
 */
public final class RaindioAttachments implements AutoCloseable {
    private static final int COLOR_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT
                    | GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_COPY_SRC
                    | GpuTexture.USAGE_COPY_DST;

    private static final int DEPTH_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT
                    | GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_COPY_SRC
                    | GpuTexture.USAGE_COPY_DST;

    private static final Vector4f CLEAR_BLACK = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
    private static final Vector4f CLEAR_NORMAL_DEFAULT = new Vector4f(0.5f, 0.5f, 0.0f, 1.0f);

    private int width = -1;
    private int height = -1;
    private int displayWidth = -1;
    private int displayHeight = -1;
    private int shadowResolution = -1;
    private boolean halfResColortex9 = true;
    private int bloomDivisor = 4;
    private boolean initialized = false;
    private boolean historyIndex = false;
    private boolean needsHistoryClear = true;

    // Full internal resolution color attachments (renderW x renderH):
    //   colortex0 / colortex0Scratch (RGBA16F ping-pong, zero fullscreen copy!)
    //   colortex1 (RGBA16F high-precision octahedral normals + skyLight)
    //   colortex2 (RGBA8_UNORM pure linear albedo / translucent color, 50% bandwidth)
    //   colortex3 (RGBA16F full-res PTGI water normals & roughness)
    //   pointLightTex (RGBA16F clustered PBR point lights + unified 64^3 LPV GI)
    private GpuTexture colortex0, colortex0Scratch, colortex1, colortex2, colortex3, pointLightTex;
    private GpuTextureView colortex0View, colortex0ScratchView, colortex1View, colortex2View, colortex3View, pointLightView;

    // Half/full internal-resolution SSR + GTAO attachment (colortex9, RGBA16F)
    private GpuTexture colortex9;
    private GpuTextureView colortex9View;

    // Quarter/half-resolution Dual Bloom attachments (RGBA16F)
    private GpuTexture bloomTight, bloomWide;
    private GpuTextureView bloomTightView, bloomWideView;

    // Persistent native display-resolution TAA/TAAU history ping-pong (displayW x displayH, RGBA16F)
    private GpuTexture taaHistoryA, taaHistoryB;
    private GpuTextureView taaHistoryAView, taaHistoryBView;

    // Native display-resolution tonemapped / FSR EASU upscaled LDR intermediate (displayW x displayH, RGBA16F)
    private GpuTexture displayLdr;
    private GpuTextureView displayLdrView;

    // Full internal resolution depth attachments (D32F)
    private GpuTexture depthtex0, depthtex1;
    private GpuTextureView depthtex0View, depthtex1View;

    // Fixed-size attachments: colortex6 (128x64 LUT), autoExposureTex (1x1), shadowtex0 & shadowcolor0
    private GpuTexture colortex6, autoExposureTex, shadowtex0, shadowcolor0;
    private GpuTextureView colortex6View, autoExposureView, shadowtex0View, shadowcolor0View;
    private boolean needsAutoExpClear = true;

    // Shared samplers
    private GpuSampler linearClampSampler;
    private GpuSampler nearestClampSampler;
    private GpuSampler linearRepeatSampler;

    public void ensureSize(int targetWidth, int targetHeight) {
        ensureSize(null, targetWidth, targetHeight, targetWidth, targetHeight,
                shadowResolution > 0 ? shadowResolution : RaindioPipelineSpec.SHADOW_MAP_RESOLUTION,
                this.halfResColortex9, this.bloomDivisor);
    }

    public void ensureSize(int targetWidth, int targetHeight, int targetShadowRes) {
        ensureSize(null, targetWidth, targetHeight, targetWidth, targetHeight, targetShadowRes,
                this.halfResColortex9, this.bloomDivisor);
    }

    public void ensureSize(
            CommandEncoder encoder, int targetWidth, int targetHeight, int targetShadowRes,
            boolean targetHalfResC9, int targetBloomDivisor
    ) {
        ensureSize(encoder, targetWidth, targetHeight, targetWidth, targetHeight,
                targetShadowRes, targetHalfResC9, targetBloomDivisor);
    }

    public void ensureSize(
            CommandEncoder encoder, int targetWidth, int targetHeight,
            int targetDisplayW, int targetDisplayH, int targetShadowRes,
            boolean targetHalfResC9, int targetBloomDivisor
    ) {
        int safeW = Math.max(2, targetWidth);
        int safeH = Math.max(2, targetHeight);
        int safeDispW = Math.max(2, targetDisplayW);
        int safeDispH = Math.max(2, targetDisplayH);
        int safeShadow = Math.max(512, targetShadowRes);
        int safeBloomDiv = (targetBloomDivisor == 2) ? 2 : 4;
        boolean viewportChanged = !initialized
                || this.width != safeW
                || this.height != safeH
                || this.displayWidth != safeDispW
                || this.displayHeight != safeDispH
                || this.halfResColortex9 != targetHalfResC9
                || this.bloomDivisor != safeBloomDiv;
        boolean shadowChanged = !initialized || this.shadowResolution != safeShadow;
        if (!viewportChanged && !shadowChanged) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }

        // Synchronize Vulkan device before destroying/rebuilding attachments on resize or preset change
        if (initialized && (viewportChanged || shadowChanged)) {
            waitVulkanDeviceIdle(encoder);
        }

        if (!initialized) {
            createFixedResources(device);
        }
        if (shadowChanged) {
            recreateShadowTextures(device, safeShadow);
            this.shadowResolution = safeShadow;
        }
        if (viewportChanged) {
            destroyViewportTextures();
            this.width = safeW;
            this.height = safeH;
            this.displayWidth = safeDispW;
            this.displayHeight = safeDispH;
            this.halfResColortex9 = targetHalfResC9;
            this.bloomDivisor = safeBloomDiv;
            createViewportTextures(device, safeW, safeH, safeDispW, safeDispH, targetHalfResC9, safeBloomDiv);
            this.needsHistoryClear = true;
        }
        this.initialized = true;

        int c9W = targetHalfResC9 ? Math.max(2, safeW / 2) : safeW;
        int c9H = targetHalfResC9 ? Math.max(2, safeH / 2) : safeH;
        int bW = Math.max(2, safeW / safeBloomDiv);
        int bH = Math.max(2, safeH / safeBloomDiv);
        RaindioClientMod.LOGGER.info(
                "[Raindio] Allocated Vulkan attachments (internal={}x{}, display={}x{}, colortex9={}x{}, bloom={}x{}, shadow={}x{})",
                safeW, safeH, safeDispW, safeDispH, c9W, c9H, bW, bH, safeShadow, safeShadow
        );
    }

    private void recreateShadowTextures(GpuDevice device, int shadowRes) {
        closeQuietly(shadowtex0View); shadowtex0View = null;
        closeQuietly(shadowcolor0View); shadowcolor0View = null;
        closeQuietly(shadowtex0); shadowtex0 = null;
        closeQuietly(shadowcolor0); shadowcolor0 = null;

        this.shadowtex0 = device.createTexture(
                "raindio_shadowtex0", DEPTH_USAGE, GpuFormat.D32_FLOAT, shadowRes, shadowRes, 1, 1
        );
        this.shadowtex0View = device.createTextureView(this.shadowtex0);

        this.shadowcolor0 = device.createTexture(
                "raindio_shadowcolor0", COLOR_USAGE, GpuFormat.RGBA8_UNORM, shadowRes, shadowRes, 1, 1
        );
        this.shadowcolor0View = device.createTextureView(this.shadowcolor0);
    }

    private void createFixedResources(GpuDevice device) {
        this.colortex6 = device.createTexture(
                "raindio_colortex6_atm_lut", COLOR_USAGE, GpuFormat.RGBA16_FLOAT,
                RaindioPipelineSpec.ATMOSPHERE_LUT_WIDTH, RaindioPipelineSpec.ATMOSPHERE_LUT_HEIGHT, 1, 1
        );
        this.colortex6View = device.createTextureView(this.colortex6);

        this.autoExposureTex = device.createTexture(
                "raindio_auto_exposure_1x1", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, 1, 1, 1, 1
        );
        this.autoExposureView = device.createTextureView(this.autoExposureTex);
        this.needsAutoExpClear = true;

        this.linearClampSampler = device.createSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty()
        );
        this.nearestClampSampler = device.createSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty()
        );
        this.linearRepeatSampler = device.createSampler(
                AddressMode.REPEAT, AddressMode.REPEAT,
                FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty()
        );
    }

    private void createViewportTextures(GpuDevice device, int w, int h, int dispW, int dispH, boolean halfResC9, int bloomDiv) {
        int c9W = halfResC9 ? Math.max(2, w / 2) : w;
        int c9H = halfResC9 ? Math.max(2, h / 2) : h;
        int bloomW = Math.max(2, w / bloomDiv);
        int bloomH = Math.max(2, h / bloomDiv);

        this.colortex0 = device.createTexture("raindio_colortex0", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        this.colortex0Scratch = device.createTexture("raindio_colortex0_scratch", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        this.colortex1 = device.createTexture("raindio_colortex1", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        this.colortex2 = device.createTexture("raindio_colortex2", COLOR_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        this.colortex3 = device.createTexture("raindio_colortex3", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        this.pointLightTex = device.createTexture("raindio_point_lights", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);

        this.colortex9 = device.createTexture("raindio_colortex9", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, c9W, c9H, 1, 1);
        this.bloomTight = device.createTexture("raindio_bloom_tight", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, bloomW, bloomH, 1, 1);
        this.bloomWide = device.createTexture("raindio_bloom_wide", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, bloomW, bloomH, 1, 1);

        // Native display-resolution TAA/TAAU history & intermediate LDR buffer for RCAS
        this.taaHistoryA = device.createTexture("raindio_taa_history_a", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, dispW, dispH, 1, 1);
        this.taaHistoryB = device.createTexture("raindio_taa_history_b", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, dispW, dispH, 1, 1);
        this.displayLdr = device.createTexture("raindio_display_ldr", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, dispW, dispH, 1, 1);

        this.depthtex0 = device.createTexture("raindio_depthtex0", DEPTH_USAGE, GpuFormat.D32_FLOAT, w, h, 1, 1);
        this.depthtex1 = device.createTexture("raindio_depthtex1", DEPTH_USAGE, GpuFormat.D32_FLOAT, w, h, 1, 1);

        this.colortex0View = device.createTextureView(this.colortex0);
        this.colortex0ScratchView = device.createTextureView(this.colortex0Scratch);
        this.colortex1View = device.createTextureView(this.colortex1);
        this.colortex2View = device.createTextureView(this.colortex2);
        this.colortex3View = device.createTextureView(this.colortex3);
        this.pointLightView = device.createTextureView(this.pointLightTex);
        this.colortex9View = device.createTextureView(this.colortex9);
        this.bloomTightView = device.createTextureView(this.bloomTight);
        this.bloomWideView = device.createTextureView(this.bloomWide);
        this.taaHistoryAView = device.createTextureView(this.taaHistoryA);
        this.taaHistoryBView = device.createTextureView(this.taaHistoryB);
        this.displayLdrView = device.createTextureView(this.displayLdr);
        this.depthtex0View = device.createTextureView(this.depthtex0);
        this.depthtex1View = device.createTextureView(this.depthtex1);
    }

    /**
     * Initializes newly allocated attachments once after creation/resize (Windows NVIDIA VRAM safety).
     * On normal frames, opaque G-Buffer & shadow passes use LOAD_OP_CLEAR and all fullscreen composite/compute
     * passes overwrite 100% of pixels, so redundant per-frame clears are skipped to save memory bandwidth.
     */
    public void clearPerFrameBuffers(CommandEncoder encoder) {
        if (!initialized) return;
        if (needsHistoryClear) {
            encoder.clearColorAndDepthTextures(colortex0, CLEAR_BLACK, depthtex0, 0.0);
            encoder.clearColorTexture(colortex1, CLEAR_NORMAL_DEFAULT);
            encoder.clearColorTexture(colortex2, CLEAR_BLACK);
            encoder.clearColorTexture(colortex3, CLEAR_BLACK);
            encoder.clearColorTexture(colortex9, CLEAR_BLACK);
            encoder.clearColorTexture(bloomTight, CLEAR_BLACK);
            encoder.clearColorTexture(bloomWide, CLEAR_BLACK);
            encoder.clearColorTexture(pointLightTex, CLEAR_BLACK);
            encoder.clearColorTexture(displayLdr, CLEAR_BLACK);
            encoder.clearColorTexture(taaHistoryA, CLEAR_BLACK);
            encoder.clearColorTexture(taaHistoryB, CLEAR_BLACK);
            encoder.clearColorAndDepthTextures(shadowcolor0, CLEAR_BLACK, shadowtex0, 1.0);
            needsHistoryClear = false;
        }
        if (needsAutoExpClear) {
            encoder.clearColorTexture(autoExposureTex, CLEAR_BLACK);
            needsAutoExpClear = false;
        }
    }

    /** Snapshots opaque scene depth (depthtex0 -> depthtex1) before translucent/water pass. */
    public void snapshotOpaqueDepth(CommandEncoder encoder) {
        if (!initialized) return;
        encoder.copyTextureToTexture(depthtex0, depthtex1, 0, 0, 0, 0, 0, width, height);
    }

    /** Begins the shadow render pass (shadowcolor0 + shadowtex0). */
    public RenderPass beginShadowPass(CommandEncoder encoder) {
        RenderPassDescriptor desc = RenderPassDescriptor.builder(() -> "raindio_shadow_pass")
                .withColorAttachment(shadowcolor0View, Optional.of(CLEAR_BLACK))
                .withDepthAttachment(shadowtex0View, OptionalDouble.of(1.0))
                .build();
        return encoder.createRenderPass(desc);
    }

    /** Begins the opaque G-Buffer MRT pass (colortex0 HDR + colortex1 normal/skyLight + colortex2 RGBA8 albedo + depthtex0). */
    public RenderPass beginOpaqueGBufferPass(CommandEncoder encoder, boolean clear) {
        RenderPassDescriptor.Builder b = RenderPassDescriptor.builder(() -> "raindio_gbuffer_opaque");
        if (clear) {
            b.withColorAttachment(colortex0View, Optional.of(CLEAR_BLACK))
                    .withColorAttachment(colortex1View, Optional.of(CLEAR_NORMAL_DEFAULT))
                    .withColorAttachment(colortex2View, Optional.of(CLEAR_BLACK))
                    .withDepthAttachment(depthtex0View, OptionalDouble.of(0.0));
        } else {
            b.withColorAttachment(colortex0View)
                    .withColorAttachment(colortex1View)
                    .withColorAttachment(colortex2View)
                    .withDepthAttachment(depthtex0View);
        }
        return encoder.createRenderPass(b.build());
    }

    /** Begins the multi-layer alpha-blended translucent tint pass (blends stacked glass/ice/slime onto colortex0, read-only depthtex0). */
    public RenderPass beginTranslucentTintPass(CommandEncoder encoder) {
        RenderPassDescriptor desc = RenderPassDescriptor.builder(() -> "raindio_translucent_tint")
                .withColorAttachment(colortex0View)
                .withDepthAttachment(depthtex0View)
                .build();
        return encoder.createRenderPass(desc);
    }

    /** Begins the water/translucent G-Buffer MRT pass (colortex1 + colortex2 RGBA8 + depthtex0). */
    public RenderPass beginWaterGBufferPass(CommandEncoder encoder) {
        RenderPassDescriptor desc = RenderPassDescriptor.builder(() -> "raindio_gbuffer_water")
                .withColorAttachment(colortex1View)
                .withColorAttachment(colortex2View)
                .withDepthAttachment(depthtex0View)
                .build();
        return encoder.createRenderPass(desc);
    }

    public void swapHistory() {
        this.historyIndex = !this.historyIndex;
    }

    public boolean isInitialized() { return initialized; }
    public int width() { return width; }
    public int height() { return height; }
    public int displayWidth() { return displayWidth; }
    public int displayHeight() { return displayHeight; }

    public GpuTexture colortex0() { return colortex0; }
    public GpuTexture colortex1() { return colortex1; }
    public GpuTexture colortex2() { return colortex2; }
    public GpuTexture colortex3() { return colortex3; }
    public GpuTexture colortex6() { return colortex6; }
    public GpuTexture autoExposureTex() { return autoExposureTex; }
    public GpuTexture colortex9() { return colortex9; }
    public GpuTexture pointLightTex() { return pointLightTex; }
    public GpuTexture displayLdr() { return displayLdr; }
    public GpuTexture depthtex0() { return depthtex0; }
    public GpuTexture depthtex1() { return depthtex1; }
    public GpuTexture shadowtex0() { return shadowtex0; }
    public GpuTexture shadowcolor0() { return shadowcolor0; }

    public GpuTextureView colortex0View() { return colortex0View; }
    public GpuTextureView colortex0ScratchView() { return colortex0ScratchView; }
    public GpuTextureView colortex1View() { return colortex1View; }
    public GpuTextureView colortex2View() { return colortex2View; }
    public GpuTextureView colortex3View() { return colortex3View; }
    public GpuTextureView bloomTightView() { return bloomTightView; }
    public GpuTextureView bloomWideView() { return bloomWideView; }
    public GpuTextureView colortex6View() { return colortex6View; }
    public GpuTextureView autoExposureView() { return autoExposureView; }
    // Legacy Compute GI stubs (colortex8A/B no longer allocated)
    public GpuTextureView colortex8ReadView() { return null; }
    public GpuTextureView colortex8WriteView() { return null; }
    public GpuTexture colortex8WriteTexture() { return null; }
    public GpuTextureView taaHistoryReadView() { return historyIndex ? taaHistoryBView : taaHistoryAView; }
    public GpuTextureView taaHistoryWriteView() { return historyIndex ? taaHistoryAView : taaHistoryBView; }
    public GpuTextureView displayLdrView() { return displayLdrView; }
    public GpuTextureView colortex9View() { return colortex9View; }
    public GpuTextureView pointLightView() { return pointLightView; }
    public GpuTextureView depthtex0View() { return depthtex0View; }
    public GpuTextureView depthtex1View() { return depthtex1View; }
    public GpuTextureView shadowtex0View() { return shadowtex0View; }
    public GpuTextureView shadowcolor0View() { return shadowcolor0View; }

    public GpuSampler linearClampSampler() { return linearClampSampler; }
    public GpuSampler nearestClampSampler() { return nearestClampSampler; }
    public GpuSampler linearRepeatSampler() { return linearRepeatSampler; }

    private void destroyViewportTextures() {
        closeQuietly(colortex0View); colortex0View = null;
        closeQuietly(colortex0ScratchView); colortex0ScratchView = null;
        closeQuietly(colortex1View); colortex1View = null;
        closeQuietly(colortex2View); colortex2View = null;
        closeQuietly(colortex3View); colortex3View = null;
        closeQuietly(colortex9View); colortex9View = null;
        closeQuietly(bloomTightView); bloomTightView = null;
        closeQuietly(bloomWideView); bloomWideView = null;
        closeQuietly(taaHistoryAView); taaHistoryAView = null;
        closeQuietly(taaHistoryBView); taaHistoryBView = null;
        closeQuietly(displayLdrView); displayLdrView = null;
        closeQuietly(pointLightView); pointLightView = null;
        closeQuietly(depthtex0View); depthtex0View = null;
        closeQuietly(depthtex1View); depthtex1View = null;

        closeQuietly(colortex0); colortex0 = null;
        closeQuietly(colortex0Scratch); colortex0Scratch = null;
        closeQuietly(colortex1); colortex1 = null;
        closeQuietly(colortex2); colortex2 = null;
        closeQuietly(colortex3); colortex3 = null;
        closeQuietly(colortex9); colortex9 = null;
        closeQuietly(bloomTight); bloomTight = null;
        closeQuietly(bloomWide); bloomWide = null;
        closeQuietly(taaHistoryA); taaHistoryA = null;
        closeQuietly(taaHistoryB); taaHistoryB = null;
        closeQuietly(displayLdr); displayLdr = null;
        closeQuietly(pointLightTex); pointLightTex = null;
        closeQuietly(depthtex0); depthtex0 = null;
        closeQuietly(depthtex1); depthtex1 = null;
    }

    @Override
    public void close() {
        destroyViewportTextures();
        closeQuietly(colortex6View); colortex6View = null;
        closeQuietly(autoExposureView); autoExposureView = null;
        closeQuietly(shadowtex0View); shadowtex0View = null;
        closeQuietly(shadowcolor0View); shadowcolor0View = null;
        closeQuietly(colortex6); colortex6 = null;
        closeQuietly(autoExposureTex); autoExposureTex = null;
        closeQuietly(shadowtex0); shadowtex0 = null;
        closeQuietly(shadowcolor0); shadowcolor0 = null;
        closeQuietly(linearClampSampler); linearClampSampler = null;
        closeQuietly(nearestClampSampler); nearestClampSampler = null;
        closeQuietly(linearRepeatSampler); linearRepeatSampler = null;
        initialized = false;
        width = -1;
        height = -1;
    }

    public static boolean isVulkanBackend(CommandEncoder encoder) {
        if (encoder instanceof VulkanCommandEncoder) return true;
        return (encoder instanceof FrontendCommandEncoder fe) && (fe.backend() instanceof VulkanCommandEncoder);
    }

    public static void waitVulkanDeviceIdle(CommandEncoder encoder) {
        if (encoder == null) return;
        VulkanCommandEncoder vkEncoder = null;
        if (encoder instanceof VulkanCommandEncoder vk) {
            vkEncoder = vk;
        } else if (encoder instanceof FrontendCommandEncoder fe && fe.backend() instanceof VulkanCommandEncoder vk) {
            vkEncoder = vk;
        }
        if (vkEncoder != null) {
            VulkanDevice dev = ((VulkanCommandEncoderAccessor) vkEncoder).raindio$getDevice();
            if (dev != null && dev.vkDevice() != null) {
                VK12.vkDeviceWaitIdle(dev.vkDevice());
            }
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
