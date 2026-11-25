package com.raindio.vulkan;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.raindio.RaindioClientMod;
import com.raindio.config.RaindioConfig;
import com.raindio.mixin.ChunkSectionsToRenderAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * Compiles and dispatches Raindio's real Minecraft 26.3 chunk geometry pipelines:
 * 1. shadow (4096x4096 shadowtex0 + shadowcolor0)
 * 2. terrain G-Buffer (colortex0 HDR lit + colortex1 normal/skyLight + depthtex0)
 * 3. opaque depth snapshot (depthtex0 -> depthtex1)
 * 4. water/translucent G-Buffer (colortex1 normal/waterMask + colortex2 biomeColor + depthtex0)
 */
public final class RaindioChunkPipelines implements AutoCloseable {
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final ColorTargetState RGBA16F_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL);
    private static final ColorTargetState RGBA16F_TRANSLUCENT_TARGET =
            new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL);
    private static final ColorTargetState RGBA8_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL);
    // Shadow pass uses our own standard [0,1] ortho matrix (near=0, far=1)
    private static final DepthStencilState SHADOW_DEPTH_WRITE =
            new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true);
    // Scene terrain uses Reverse-Z (near=1, far=0); water uses strict GREATER to eliminate coplanar Z-fighting
    private static final DepthStencilState SCENE_DEPTH_WRITE = DepthStencilState.DEFAULT;
    private static final DepthStencilState TRANSLUCENT_DEPTH_TEST_ONLY =
            new DepthStencilState(CompareOp.GREATER_THAN, false);
    private static final DepthStencilState WATER_DEPTH_WRITE =
            new DepthStencilState(CompareOp.GREATER_THAN, true);

    private final RaindioShaderSource shaderSource = new RaindioShaderSource();
    private PipelineCache pipelineCache;

    private RenderPipeline shadowCutoutSingle, shadowCutoutMulti;
    private RenderPipeline terrainSingle, terrainMulti;
    private RenderPipeline translucentTintSingle, translucentTintMulti;
    private RenderPipeline waterSingle, waterMulti;
    private boolean initialized = false;
    private int compiledShadowRes = -1;
    private boolean compiledTaaEnabled = true;
    private int compiledPcssBlocker = -1;
    private int compiledPcssPcf = -1;

    public void ensureCompiled(CommandEncoder encoder, RaindioConfig config) {
        int shadowResolution = config.shadowResolution;
        boolean taaEnabled = config.taaEnabled;
        int pcssBlocker = config.pcssBlockerTaps;
        int pcssPcf = config.pcssPcfTaps;
        if (initialized && compiledShadowRes == shadowResolution && compiledTaaEnabled == taaEnabled
                && compiledPcssBlocker == pcssBlocker && compiledPcssPcf == pcssPcf) {
            return;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return;

        if (pipelineCache != null) {
            RaindioAttachments.waitVulkanDeviceIdle(encoder);
            pipelineCache.close();
            pipelineCache = null;
        }
        this.pipelineCache = new PipelineCache(device, shaderSource);
        this.compiledShadowRes = shadowResolution;
        this.compiledTaaEnabled = taaEnabled;
        this.compiledPcssBlocker = pcssBlocker;
        this.compiledPcssPcf = pcssPcf;

        this.shadowCutoutSingle = buildAndCache(device, "chunks/chunk_shadow", "_cutout_single", false,
                RenderPipelines.SOLID_TERRAIN,
                new String[] { "FrameUniforms", "Globals", "ChunkSection" },
                new String[] { "Sampler0" },
                new ColorTargetState[] { RGBA8_TARGET },
                SHADOW_DEPTH_WRITE,
                false);
        this.shadowCutoutMulti = buildAndCache(device, "chunks/chunk_shadow", "_cutout_multidraw", true,
                RenderPipelines.SOLID_TERRAIN_MULTIDRAW,
                new String[] { "FrameUniforms", "Globals" },
                new String[] { "Sampler0" },
                new ColorTargetState[] { RGBA8_TARGET },
                SHADOW_DEPTH_WRITE,
                false);

        this.terrainSingle = buildAndCache(device, "chunks/chunk_terrain", false,
                RenderPipelines.SOLID_TERRAIN,
                new String[] { "FrameUniforms", "Globals", "TerrainUniform", "ChunkSection" },
                new String[] { "Sampler0", "Sampler2", "shadowtex0" },
                new ColorTargetState[] { RGBA16F_TARGET, RGBA16F_TARGET, RGBA8_TARGET },
                SCENE_DEPTH_WRITE,
                true);
        this.terrainMulti = buildAndCache(device, "chunks/chunk_terrain", true,
                RenderPipelines.SOLID_TERRAIN_MULTIDRAW,
                new String[] { "FrameUniforms", "Globals", "TerrainUniform" },
                new String[] { "Sampler0", "Sampler2", "shadowtex0" },
                new ColorTargetState[] { RGBA16F_TARGET, RGBA16F_TARGET, RGBA8_TARGET },
                SCENE_DEPTH_WRITE,
                true);

        this.translucentTintSingle = buildAndCache(device, "chunks/chunk_translucent_tint", false,
                RenderPipelines.TRANSLUCENT_TERRAIN,
                new String[] { "FrameUniforms", "Globals", "TerrainUniform", "ChunkSection" },
                new String[] { "Sampler0", "Sampler2" },
                new ColorTargetState[] { RGBA16F_TRANSLUCENT_TARGET },
                TRANSLUCENT_DEPTH_TEST_ONLY,
                true);
        this.translucentTintMulti = buildAndCache(device, "chunks/chunk_translucent_tint", true,
                RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW,
                new String[] { "FrameUniforms", "Globals", "TerrainUniform" },
                new String[] { "Sampler0", "Sampler2" },
                new ColorTargetState[] { RGBA16F_TRANSLUCENT_TARGET },
                TRANSLUCENT_DEPTH_TEST_ONLY,
                true);

        this.waterSingle = buildAndCache(device, "chunks/chunk_water", false,
                RenderPipelines.TRANSLUCENT_TERRAIN,
                new String[] { "FrameUniforms", "Globals", "TerrainUniform", "ChunkSection" },
                new String[] { "Sampler0", "Sampler2" },
                new ColorTargetState[] { RGBA16F_TARGET, RGBA8_TARGET },
                WATER_DEPTH_WRITE,
                true);
        this.waterMulti = buildAndCache(device, "chunks/chunk_water", true,
                RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW,
                new String[] { "FrameUniforms", "Globals", "TerrainUniform" },
                new String[] { "Sampler0", "Sampler2" },
                new ColorTargetState[] { RGBA16F_TARGET, RGBA8_TARGET },
                WATER_DEPTH_WRITE,
                true);

        this.initialized = true;
        RaindioClientMod.LOGGER.info("[Raindio] Compiled chunk geometry Vulkan pipelines.");
    }

    private RenderPipeline buildAndCache(
            GpuDevice device,
            String shaderPath,
            boolean multidraw,
            RenderPipeline vanillaTemplate,
            String[] uboNames,
            String[] samplerNames,
            ColorTargetState[] targets,
            DepthStencilState depthState,
            boolean cullBackfaces
    ) {
        return buildAndCache(device, shaderPath, multidraw ? "_multidraw" : "_single", multidraw,
                vanillaTemplate, uboNames, samplerNames, targets, depthState, cullBackfaces);
    }

    private RenderPipeline buildAndCache(
            GpuDevice device,
            String shaderPath,
            String idSuffix,
            boolean multidraw,
            RenderPipeline vanillaTemplate,
            String[] uboNames,
            String[] samplerNames,
            ColorTargetState[] targets,
            DepthStencilState depthState,
            boolean cullBackfaces
    ) {
        BindGroupLayout.Builder set0 = BindGroupLayout.builder();
        for (String u : uboNames) {
            set0.withUniform(u, UniformType.UNIFORM_BUFFER);
        }
        for (String s : samplerNames) {
            set0.withUniform(s, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, shaderPath + idSuffix))
                .withVertexShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, shaderPath))
                .withFragmentShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, shaderPath))
                .withBindGroupLayout(set0.build())
                .withPrimitiveTopology(vanillaTemplate.getPrimitiveTopology())
                .withCull(cullBackfaces)
                .withDepthStencilState(depthState);

        b.withShaderDefine("SHADOW_MAP_RES", (float) Math.max(512, compiledShadowRes));
        b.withShaderDefine("TAA_ENABLED", compiledTaaEnabled ? 1 : 0);
        b.withShaderDefine("PCSS_BLOCKER_TAPS", compiledPcssBlocker);
        b.withShaderDefine("PCSS_PCF_TAPS", compiledPcssPcf);
        if (multidraw) {
            b.withShaderDefine("MULTIDRAW_TERRAIN");
        }

        List<VertexFormat> formats = vanillaTemplate.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            b.withVertexBinding(i, formats.get(i));
        }
        for (int i = 0; i < targets.length; i++) {
            b.withColorTargetState(i, targets[i]);
        }

        RenderPipeline pipeline = b.build();
        CompiledRenderPipeline compiled = device
                .compilePipeline(pipeline, shaderSource, DIRECT_EXECUTOR)
                .join()
                .finishCompile();
        pipelineCache.insert(pipeline, compiled);
        return pipeline;
    }

    public boolean renderShadowAndGBuffers(
            CommandEncoder encoder,
            ChunkSectionsToRender chunkSections,
            ChunkSectionsToRender shadowChunkSections,
            RaindioAttachments att,
            RaindioUniformManager uniforms,
            RaindioEntityShadowRenderer entityShadows,
            RaindioEntityGBufferRenderer entityGBuffer,
            RaindioConfig config
    ) {
        if (chunkSections == null) return false;
        ensureCompiled(encoder, config);
        if (!initialized) return false;

        Minecraft mc = Minecraft.getInstance();
        GpuTextureView blockAtlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        GpuTextureView lightmap = mc.gameRenderer.lightmap();
        if (blockAtlas == null || lightmap == null) return false;

        ChunkSectionsToRenderAccessor mainAccessor = (ChunkSectionsToRenderAccessor) chunkSections;
        ChunkSectionsToRenderAccessor shadowAccessor = (ChunkSectionsToRenderAccessor)
                (shadowChunkSections != null ? shadowChunkSections : chunkSections);
        PipelineCache previousCache = RenderSystem.setCurrentPipelineCache(pipelineCache);
        try {
            // 1. Shadow Pass (shadowcolor0 + shadowtex0): full shadow-culled chunks (no camera/cave culling) + real-time entities
            try (RenderPass shadowPass = att.beginShadowPass(encoder)) {
                shadowPass.setUniform("FrameUniforms", uniforms.frameBuffer());
                if (RenderSystem.getGlobalSettingsUniform() != null) {
                    shadowPass.setUniform("Globals", RenderSystem.getGlobalSettingsUniform());
                }
                // Always render shadow pass double-sided (withCull(false), matching Iris/OptiFine)
                // because Minecraft chunk meshes are single-sided hollow shells where culling backfaces
                // removes downward-facing cave ceilings and causes cave light leaks & shadow twitching.
                shadowAccessor.raindio$renderLayers(
                        ChunkSectionLayerGroup.OPAQUE.layers(),
                        att.nearestClampSampler(),
                        shadowPass,
                        blockAtlas,
                        lightmap,
                        shadowCutoutSingle,
                        shadowCutoutMulti
                );
                if (entityShadows != null) {
                    entityShadows.drawIntoShadowPass(shadowPass, uniforms);
                }
            }

            // 2. Opaque Terrain G-Buffer Pass (colortex0 + colortex1 + colortex2 + depthtex0)
            try (RenderPass terrainPass = att.beginOpaqueGBufferPass(encoder, true)) {
                terrainPass.setUniform("FrameUniforms", uniforms.frameBuffer());
                if (RenderSystem.getGlobalSettingsUniform() != null) {
                    terrainPass.setUniform("Globals", RenderSystem.getGlobalSettingsUniform());
                }
                terrainPass.setUniform("shadowtex0", att.shadowtex0View(), att.nearestClampSampler());
                mainAccessor.raindio$renderLayers(
                        ChunkSectionLayerGroup.OPAQUE.layers(),
                        att.nearestClampSampler(),
                        terrainPass,
                        blockAtlas,
                        lightmap,
                        terrainSingle,
                        terrainMulti
                );
            }

            // 2b. Native Entity & Block-Entity G-Buffer Pass (LOAD_OP_LOAD on colortex0/1/2 + Reverse-Z depthtex0)
            //     Isolated from terrainPass PipelineCache state, runs BEFORE snapshotOpaqueDepth for underwater refraction.
            if (entityGBuffer != null && entityGBuffer.hasGeometry()) {
                try (RenderPass entityPass = att.beginOpaqueGBufferPass(encoder, false)) {
                    entityGBuffer.drawIntoGBufferPass(entityPass, uniforms, att);
                }
            }

            // 3. Snapshot opaque terrain + entity depth (depthtex0 -> depthtex1) before translucent/water pass
            att.snapshotOpaqueDepth(encoder);

            // 4a. Multi-Layer Translucent Alpha-Blend Pass (stacked stained glass / ice / slime -> colortex0, depth-test only)
            try (RenderPass tintPass = att.beginTranslucentTintPass(encoder)) {
                tintPass.setUniform("FrameUniforms", uniforms.frameBuffer());
                if (RenderSystem.getGlobalSettingsUniform() != null) {
                    tintPass.setUniform("Globals", RenderSystem.getGlobalSettingsUniform());
                }
                mainAccessor.raindio$renderLayers(
                        ChunkSectionLayerGroup.TRANSLUCENT.layers(),
                        att.nearestClampSampler(),
                        tintPass,
                        blockAtlas,
                        lightmap,
                        translucentTintSingle,
                        translucentTintMulti
                );
            }

            // 4b. Front-Surface Water / Translucent G-Buffer Pass (colortex1 + colortex2 + depthtex0)
            try (RenderPass waterPass = att.beginWaterGBufferPass(encoder)) {
                waterPass.setUniform("FrameUniforms", uniforms.frameBuffer());
                if (RenderSystem.getGlobalSettingsUniform() != null) {
                    waterPass.setUniform("Globals", RenderSystem.getGlobalSettingsUniform());
                }
                mainAccessor.raindio$renderLayers(
                        ChunkSectionLayerGroup.TRANSLUCENT.layers(),
                        att.nearestClampSampler(),
                        waterPass,
                        blockAtlas,
                        lightmap,
                        waterSingle,
                        waterMulti
                );
            }

            return true;
        } finally {
            RenderSystem.setCurrentPipelineCache(previousCache);
        }
    }


    @Override
    public void close() {
        if (pipelineCache != null) {
            pipelineCache.close();
            pipelineCache = null;
        }
        shaderSource.close();
        initialized = false;
    }
}
