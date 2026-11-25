package com.raindio.pipeline;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.raindio.RaindioClientMod;
import com.raindio.client.RaindioKeybinds;
import com.raindio.config.RaindioConfig;
import com.raindio.vulkan.RaindioAttachments;
import com.raindio.vulkan.RaindioAutoExposure;
import com.raindio.vulkan.RaindioChunkPipelines;
import com.raindio.vulkan.RaindioCompositePipelines;
import com.mojang.blaze3d.vertex.PoseStack;
import com.raindio.vulkan.RaindioEntityGBufferRenderer;
import com.raindio.vulkan.RaindioEntityShadowRenderer;
import com.raindio.vulkan.RaindioHandRenderer;
import com.raindio.vulkan.RaindioLpvManager;
import com.raindio.vulkan.RaindioMetalRtManager;
import com.raindio.vulkan.RaindioNoiseTexture;
import com.raindio.vulkan.RaindioPointLightManager;
import com.raindio.vulkan.RaindioRtReflectionManager;
import com.raindio.vulkan.RaindioSpirvLoader;
import com.raindio.vulkan.RaindioUniformManager;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;

/**
 * Full frame lifecycle controller hooked into LevelRenderer:
 * 1. onLevelRenderBegin: allocate/resize attachments, upload FrameUniforms, clear per-frame buffers
 * 2. setCurrentChunkSections: capture frame's ChunkSectionsToRender from LevelRenderer.addMainPass
 * 3. onLevelRenderEnd: render shadow (4096x4096) -> terrain G-Buffer -> depth snapshot -> water G-Buffer
 *    -> composite0..5 -> final -> mainTarget, then flip persistent colortex8 history.
 */
public final class RaindioFrameController {
    public static final RaindioFrameController INSTANCE = new RaindioFrameController();

    private final RaindioAttachments attachments = new RaindioAttachments();
    private final RaindioUniformManager uniforms = new RaindioUniformManager();
    private final RaindioSpirvLoader spirvLoader = new RaindioSpirvLoader();
    private final RaindioNoiseTexture noiseTexture = new RaindioNoiseTexture();
    private final RaindioChunkPipelines chunkPipelines = new RaindioChunkPipelines();
    private final RaindioEntityShadowRenderer entityShadowRenderer = new RaindioEntityShadowRenderer();
    private final RaindioEntityGBufferRenderer entityGBufferRenderer = new RaindioEntityGBufferRenderer();
    private final RaindioHandRenderer handRenderer = new RaindioHandRenderer();
    private final RaindioCompositePipelines compositePipelines = new RaindioCompositePipelines();
    private final RaindioLpvManager lpvManager = new RaindioLpvManager();
    private final RaindioMetalRtManager metalRtManager = new RaindioMetalRtManager();
    private final RaindioPointLightManager pointLightManager = new RaindioPointLightManager();
    private final RaindioRtReflectionManager rtReflectionManager = new RaindioRtReflectionManager();
    private final RaindioAutoExposure autoExposure = new RaindioAutoExposure();

    private RaindioConfig config = new RaindioConfig();
    private ChunkSectionsToRender currentChunkSections = null;
    private boolean spirvVerified = false;
    private boolean pipelineEnabled = true;
    private boolean runtimeFaulted = false;
    private String lastFaultSummary = null;
    private int debugViewMode = 0; // 0..15 (matching Raindio DEBUG_VIEW)

    private RaindioFrameController() {}

    public void onClientInitialize() {
        this.config = RaindioConfig.load();
        this.pipelineEnabled = this.config.shadersEnabled;
        int count = spirvLoader.verifyCoreModules();
        spirvVerified = true;
        RaindioClientMod.LOGGER.info("[Raindio] Loaded config (preset={}, shadow={}) and verified {} SPIR-V modules.",
                config.preset.displayName, config.shadowResolution, count);
    }

    public RaindioConfig config() {
        return config;
    }

    public void applyConfig(RaindioConfig updated, int newDebugView) {
        this.config.copyFrom(updated);
        this.config.save();
        this.pipelineEnabled = this.config.shadersEnabled;
        this.debugViewMode = Math.max(0, Math.min(16, newDebugView));
        if (this.pipelineEnabled) {
            this.runtimeFaulted = false;
        }
        RaindioKeybinds.sendChatAndOverlay(
                Minecraft.getInstance(),
                "[Raindio] 已应用配置: " + config.preset.displayName
                        + " | 缩放 " + Math.round(config.renderScale * 100.0f) + "%"
                        + " | 阴影 " + config.shadowResolution
        );
    }

    public void setCurrentChunkSections(ChunkSectionsToRender chunkSections) {
        this.currentChunkSections = chunkSections;
    }

    public void onLevelRenderBegin(CameraRenderState cameraState, RenderTarget mainTarget) {
        this.currentChunkSections = null;
        if (!pipelineEnabled || runtimeFaulted || mainTarget == null) return;

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return;

        try {
            int displayW = Math.max(2, mainTarget.width);
            int displayH = Math.max(2, mainTarget.height);
            int renderW = Math.max(2, Math.round(displayW * config.renderScale));
            int renderH = Math.max(2, Math.round(displayH * config.renderScale));
            CommandEncoder encoder = device.createCommandEncoder();
            if (!RaindioAttachments.isVulkanBackend(encoder)) {
                runtimeFaulted = true;
                pipelineEnabled = false;
                lastFaultSummary = "OpenGL backend detected — Raindio requires Vulkan.";
                RaindioClientMod.LOGGER.warn("[Raindio] Detected non-Vulkan graphics backend (OpenGL). Raindio requires Vulkan — please switch Graphics Backend to Vulkan in Video Settings and restart Minecraft.");
                RaindioKeybinds.sendChatAndOverlay(Minecraft.getInstance(), "[Raindio] 当前游戏运行在 OpenGL 后端，本光影仅支持 Vulkan！请在视频设置中将图形后端切换为 Vulkan 后重启游戏。");
                return;
            }
            attachments.ensureSize(
                    encoder, renderW, renderH, displayW, displayH,
                    config.shadowResolution, config.halfResColortex9, config.bloomDivisor
            );
            if (!attachments.isInitialized()) return;

            noiseTexture.ensureInitialized(encoder);
            boolean taauActive = (config.effectiveUpscaleMode() == RaindioConfig.UPSCALE_TAAU);
            uniforms.updateFrameUniforms(
                    encoder, cameraState, attachments.width(), attachments.height(),
                    config.shadowResolution, config.taaEnabled, config.renderScale, taauActive
            );
            attachments.clearPerFrameBuffers(encoder);
        } catch (Throwable t) {
            handleRuntimeFault("onLevelRenderBegin", t);
        }
    }

    public void onLevelRenderEnd(
            RenderTarget mainTarget,
            EntityRenderDispatcher entityDispatcher,
            LevelRenderState levelRenderState,
            List<EntityRenderState> capturedEntities,
            List<BlockEntityRenderState> capturedBlockEntities,
            CameraRenderState cameraState,
            ChunkSectionsToRender shadowChunkSections
    ) {
        if (!pipelineEnabled || runtimeFaulted || !attachments.isInitialized() || mainTarget == null) return;

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return;

        GpuTexture mainColor = mainTarget.getColorTexture();
        GpuTextureView mainColorView = mainTarget.getColorTextureView();
        GpuTexture mainDepth = mainTarget.getDepthTexture();
        if (mainColor == null || mainColorView == null) return;

        try {
            CommandEncoder encoder = device.createCommandEncoder();

            // 0. Collect animated 3D entity shadow + G-Buffer vertices & update lights/LPV
            entityShadowRenderer.prepareAndUpload(
                    encoder, entityDispatcher, levelRenderState, capturedEntities, capturedBlockEntities, cameraState,
                    uniforms.shadowModelView(), config.entityShadowDistance
            );
            entityGBufferRenderer.prepareAndUpload(
                    encoder, entityDispatcher, levelRenderState, capturedEntities, capturedBlockEntities, cameraState, config
            );
            pointLightManager.updateLights(cameraState, config);
            lpvManager.updateVoxels(cameraState, config);
            metalRtManager.updateSections(cameraState, lpvManager);
            lpvManager.setSkipFarSoftwareVoxels(metalRtManager.isHardwareRtActive());

            // 1. Render shadow map, opaque terrain + entity G-Buffer, depthtex1 snapshot, translucent tint, and water G-Buffer
            boolean renderedChunks = chunkPipelines.renderShadowAndGBuffers(
                    encoder, currentChunkSections, shadowChunkSections, attachments, uniforms,
                    entityShadowRenderer, entityGBufferRenderer, config
            );

            // Fallback depth copy if chunk pass did not run (only when dimensions match 1:1 to avoid Vulkan mismatch)
            if (!renderedChunks && mainDepth != null
                    && mainDepth.getFormat() == attachments.depthtex0().getFormat()
                    && mainTarget.width == attachments.width()
                    && mainTarget.height == attachments.height()) {
                encoder.copyTextureToTexture(mainDepth, attachments.depthtex0(), 0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
                attachments.snapshotOpaqueDepth(encoder);
            }

            // 2. Dispatch composite0..5 -> final post-processing chain into mainTarget
            GpuFormat finalFormat = mainColor.getFormat();
            compositePipelines.executeAll(
                    encoder,
                    attachments,
                    uniforms,
                    noiseTexture,
                    lpvManager,
                    pointLightManager,
                    rtReflectionManager,
                    metalRtManager,
                    autoExposure,
                    config,
                    mainColorView,
                    finalFormat,
                    debugViewMode,
                    renderedChunks
            );

            // 3. Flip persistent colortex8A/B history for next frame's temporal SSGI reprojection
            attachments.swapHistory();
        } catch (Throwable t) {
            handleRuntimeFault("onLevelRenderEnd", t);
        } finally {
            this.currentChunkSections = null;
        }
    }

    private void handleRuntimeFault(String stage, Throwable t) {
        runtimeFaulted = true;
        pipelineEnabled = false;
        RaindioClientMod.LOGGER.error(
                "[Raindio] Runtime Vulkan pipeline fault in {} — disabling shaders gracefully (press K to retry).",
                stage, t
        );
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String msg = root.getMessage() != null ? root.getMessage().replace('\n', ' ') : "";
        String summary = stage + " -> " + root.getClass().getSimpleName() + ": " + msg;
        if (summary.length() > 260) {
            summary = summary.substring(0, 260) + "...";
        }
        this.lastFaultSummary = summary;
        RaindioKeybinds.sendChatAndOverlay(Minecraft.getInstance(), "[Raindio Error] " + summary);
    }

    public String getLastFaultSummary() { return lastFaultSummary; }

    public void onResize(int width, int height) {
        if (width > 0 && height > 0) {
            GpuDevice device = RenderSystem.tryGetDevice();
            CommandEncoder encoder = (device != null) ? device.createCommandEncoder() : null;
            if (encoder == null || !RaindioAttachments.isVulkanBackend(encoder)) {
                return;
            }
            int renderW = Math.max(2, Math.round(width * config.renderScale));
            int renderH = Math.max(2, Math.round(height * config.renderScale));
            attachments.ensureSize(
                    encoder, renderW, renderH, width, height,
                    config.shadowResolution, config.halfResColortex9, config.bloomDivisor
            );
        }
    }

    public boolean renderFirstPersonHand(
            FirstPersonHandsAndItemsRenderer vanillaHandRenderer,
            CameraRenderState cameraState,
            PlayerRenderState playerState,
            PoseStack poseStack,
            Matrix4f handProjection,
            RenderTarget mainTarget,
            GpuTextureView depthView
    ) {
        if (!pipelineEnabled || runtimeFaulted || !attachments.isInitialized() || mainTarget == null) {
            return false;
        }
        GpuTexture mainColor = mainTarget.getColorTexture();
        GpuTextureView mainColorView = mainTarget.getColorTextureView();
        if (mainColor == null || mainColorView == null) {
            return false;
        }
        try {
            return handRenderer.render(
                    vanillaHandRenderer,
                    cameraState,
                    playerState,
                    poseStack,
                    handProjection,
                    mainColorView,
                    mainColor.getFormat(),
                    depthView,
                    attachments,
                    uniforms,
                    pointLightManager,
                    config
            );
        } catch (Throwable t) {
            RaindioClientMod.LOGGER.warn("[Raindio] First-person hand render error, falling back to vanilla:", t);
            return false;
        }
    }

    public void onClose() {
        autoExposure.close();
        rtReflectionManager.close();
        metalRtManager.close();
        pointLightManager.close();
        lpvManager.close();
        handRenderer.close();
        entityGBufferRenderer.close();
        entityShadowRenderer.close();
        chunkPipelines.close();
        compositePipelines.close();
        noiseTexture.close();
        attachments.close();
        uniforms.close();
        spirvLoader.clear();
    }

    public boolean isPipelineEnabled() { return pipelineEnabled; }
    public void setPipelineEnabled(boolean enabled) {
        this.pipelineEnabled = enabled;
        this.config.shadersEnabled = enabled;
        this.config.save();
        if (enabled) {
            this.runtimeFaulted = false;
        }
    }

    public int getDebugViewMode() { return debugViewMode; }
    public void setDebugViewMode(int mode) { this.debugViewMode = Math.max(0, Math.min(16, mode)); }

    public boolean isSpirvVerified() { return spirvVerified; }
    public RaindioAttachments attachments() { return attachments; }
    public RaindioUniformManager uniforms() { return uniforms; }
    public RaindioSpirvLoader spirvLoader() { return spirvLoader; }
}
