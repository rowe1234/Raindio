package com.raindio.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.raindio.RaindioClientMod;
import com.raindio.config.RaindioConfig;
import java.util.Optional;
import java.util.concurrent.Executor;
import net.minecraft.resources.Identifier;

/**
 * Compiles and dispatches Raindio's input format blit (mainTarget -> RGBA16F colortex0)
 * and all 7 fullscreen post-processing passes using RenderPearl's single-set (set=0) Vulkan layout:
 * input_blit -> composite (128x64 LUT) -> composite1 (Water + Temporal SSGI) -> composite2 (SSR + GTAO)
 * -> composite3 (Main Lighting + Sky/Clouds + Water + Volumetrics + SSGI + DEBUG_VIEW)
 * -> composite4 (Dual Bloom) -> composite5 (Exposure + Contrast + Bloom) -> final (Tonemap + FXAA).
 */
public final class RaindioCompositePipelines implements AutoCloseable {
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final ColorTargetState RGBA16F_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL);

    private final RaindioShaderSource shaderSource = new RaindioShaderSource();

    private CompiledRenderPipeline inputBlit;
    private CompiledRenderPipeline composite0;
    private CompiledRenderPipeline composite1;
    private CompiledRenderPipeline composite2;
    private CompiledRenderPipeline composite3;
    private CompiledRenderPipeline composite4;
    private CompiledRenderPipeline composite5;
    private CompiledRenderPipeline taaResolve;
    private CompiledRenderPipeline fsrEasu;
    private CompiledRenderPipeline finalPass;

    private boolean initialized = false;
    private boolean atmLutReady = false;
    private String compiledSignature = "";
    private GpuFormat compiledFinalFormat = GpuFormat.RGBA8_UNORM;

    public void ensureCompiled(CommandEncoder encoder, RaindioConfig config, int debugView, GpuFormat finalOutputFormat) {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return;

        String sig = config.shaderDefineSignature(debugView);
        if (initialized && sig.equals(compiledSignature) && compiledFinalFormat == finalOutputFormat) {
            return;
        }

        if (!initialized) {
            inputBlit = compileInputBlit(device);
            composite0 = compileFullscreen(device, "composite/composite", config, debugView,
                    new String[] {}, new ColorTargetState[] { RGBA16F_TARGET });
            composite4 = compileFullscreen(device, "composite/composite4", config, debugView,
                    new String[] { "colortex0" },
                    new ColorTargetState[] { RGBA16F_TARGET, RGBA16F_TARGET });
            fsrEasu = compileFullscreen(device, "composite/fsr_easu", config, debugView,
                    new String[] { "colortex0" },
                    new ColorTargetState[] { RGBA16F_TARGET });
        } else {
            RaindioAttachments.waitVulkanDeviceIdle(encoder);
            closeQuietly(composite1);
            closeQuietly(composite2);
            closeQuietly(composite3);
            closeQuietly(composite5);
            closeQuietly(taaResolve);
            closeQuietly(finalPass);
        }

        composite1 = compileFullscreen(device, "composite/composite1", config, debugView,
                new String[] { "depthtex0", "colortex1", "noisetex" },
                new ColorTargetState[] { RGBA16F_TARGET });
        composite2 = compileFullscreen(device, "composite/composite2", config, debugView,
                new String[] { "colortex0", "colortex1", "colortex3", "colortex6", "depthtex0", "depthtex1", "noisetex", "shadowtex0", "shadowcolor0" },
                new ColorTargetState[] { RGBA16F_TARGET });
        composite3 = compileFullscreen(device, "composite/composite3", config, debugView,
                new String[] { "colortex0", "colortex1", "colortex2", "colortex3", "colortex6", "colortex9", "depthtex0", "depthtex1", "noisetex", "shadowtex0", "pointLightTex" },
                new ColorTargetState[] { RGBA16F_TARGET });
        composite5 = compileFullscreen(device, "composite/composite5", config, debugView,
                new String[] { "colortex0", "colortex3", "colortex4", "autoExposureTex" },
                new ColorTargetState[] { RGBA16F_TARGET });
        taaResolve = compileFullscreen(device, "composite/taa_resolve", config, debugView,
                new String[] { "colortex0", "taaHistory", "depthtex0" },
                new ColorTargetState[] { RGBA16F_TARGET });
        finalPass = compileFullscreen(device, "composite/final", config, debugView,
                new String[] { "colortex0" },
                new ColorTargetState[] { new ColorTargetState(Optional.empty(), finalOutputFormat, ColorTargetState.WRITE_ALL) });

        compiledSignature = sig;
        compiledFinalFormat = finalOutputFormat;
        initialized = true;
        RaindioClientMod.LOGGER.info(
                "[Raindio] Compiled Vulkan post-processing pipelines (preset={}, shadow={}, DEBUG_VIEW={}).",
                config.preset.displayName, config.shadowResolution, debugView
        );
    }

    private CompiledRenderPipeline compileInputBlit(GpuDevice device) {
        BindGroupLayout set0 = BindGroupLayout.builder()
                .withUniform("uMainColor", UniformType.COMBINED_IMAGE_SAMPLER)
                .build();

        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "composite/input_blit"))
                .withVertexShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "composite/input_blit"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "composite/input_blit"))
                .withBindGroupLayout(set0)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(Optional.empty())
                .withColorTargetState(0, RGBA16F_TARGET)
                .build();

        return device.compilePipeline(pipeline, shaderSource, DIRECT_EXECUTOR).join().finishCompile();
    }

    private CompiledRenderPipeline compileFullscreen(
            GpuDevice device,
            String shaderPath,
            RaindioConfig config,
            int debugView,
            String[] samplerNames,
            ColorTargetState[] targets
    ) {
        // RenderPearl flattens all bindings into a single Vulkan descriptor set (set = 0):
        // binding 0 = FrameUniforms, bindings 1..N = samplers in exact order.
        BindGroupLayout.Builder set0 = BindGroupLayout.builder()
                .withUniform("FrameUniforms", UniformType.UNIFORM_BUFFER);
        for (String s : samplerNames) {
            set0.withUniform(s, UniformType.COMBINED_IMAGE_SAMPLER);
        }

        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, shaderPath))
                .withVertexShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, shaderPath))
                .withFragmentShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, shaderPath))
                .withShaderDefine("DEBUG_VIEW", debugView)
                .withShaderDefine("SHADOW_MAP_RES", (float) config.shadowResolution)
                .withShaderDefine("HALF_RES_COLORTEX9", config.halfResColortex9 ? 1 : 0)
                .withShaderDefine("GTAO_DIRS", config.gtaoDirs)
                .withShaderDefine("GTAO_STEPS", config.gtaoSteps)
                .withShaderDefine("GTAO_TEMPORAL_JITTER", config.gtaoTemporalJitter ? 1 : 0)
                .withShaderDefine("SSR_MAX_STEPS", config.ssrMaxSteps)
                .withShaderDefine("SSR_BINARY_STEPS", config.ssrBinarySteps)
                .withShaderDefine("SHADOW_REFL_STEPS", config.shadowReflSteps)
                .withShaderDefine("POINT_LIGHT_BILATERAL", config.pointLightBilateral ? 1 : 0)
                .withShaderDefine("VL_STEPS", config.vlSteps)
                .withShaderDefine("VL_TEMPORAL_JITTER", config.vlTemporalJitter ? 1 : 0)
                .withShaderDefine("VOL_CLOUDS_ENABLED", config.volumetricCloudsEnabled ? 1 : 0)
                .withShaderDefine("VOL_CLOUD_STEPS", config.volumetricCloudSteps)
                .withShaderDefine("VOL_CLOUD_COVERAGE", config.volumetricCloudCoverage)
                .withShaderDefine("VOL_CLOUD_DENSITY", config.volumetricCloudDensity)
                .withShaderDefine("WATER_CLARITY", config.waterClarity)
                .withShaderDefine("EXPOSURE", config.exposure)
                .withShaderDefine("BLOOM_STRENGTH", config.bloomStrength)
                .withShaderDefine("TAA_ENABLED", config.taaEnabled ? 1 : 0)
                .withShaderDefine("FXAA_ENABLED", config.fxaaEnabled ? 1 : 0)
                .withShaderDefine("UPSCALE_MODE", config.effectiveUpscaleMode())
                .withShaderDefine("FSR_SHARPNESS", config.fsrSharpness)
                .withShaderDefine("SATURATION", config.saturation);
        builder
                .withBindGroupLayout(set0.build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(Optional.empty());

        for (int i = 0; i < targets.length; i++) {
            builder.withColorTargetState(i, targets[i]);
        }

        CompiledRenderPipeline.Pending pending = device
                .compilePipeline(builder.build(), shaderSource, DIRECT_EXECUTOR)
                .join();
        return pending.finishCompile();
    }

    /**
     * Dispatches input_blit -> composite -> composite1 -> composite2 -> composite3 -> composite4 -> composite5 -> final.
     */
    public void executeAll(
            CommandEncoder encoder,
            RaindioAttachments att,
            RaindioUniformManager uniforms,
            RaindioNoiseTexture noise,
            RaindioLpvManager lpvManager,
            RaindioPointLightManager pointLights,
            RaindioRtReflectionManager rtReflections,
            RaindioMetalRtManager metalRt,
            RaindioAutoExposure autoExposure,
            RaindioConfig config,
            GpuTextureView mainColorView,
            GpuFormat mainColorFormat,
            int debugView,
            boolean skipInputBlit
    ) {
        ensureCompiled(encoder, config, debugView, mainColorFormat);
        if (!initialized || mainColorView == null) return;

        GpuSampler linearClamp = att.linearClampSampler();
        GpuSampler nearestClamp = att.nearestClampSampler();
        GpuSampler linearRepeat = att.linearRepeatSampler();

        // 0. input_blit: only needed if G-Buffer chunk pass did not populate colortex0
        if (!skipInputBlit) {
            try (RenderPass pass = encoder.createRenderPass(
                    RenderPassDescriptor.builder(() -> "raindio_input_blit")
                            .withColorAttachment(att.colortex0View())
                            .build())) {
                pass.setPipeline(inputBlit);
                pass.setUniform("uMainColor", mainColorView, nearestClamp);
                pass.draw(3, 1, 0, 0);
            }
        }

        // 1. composite -> colortex6 (128x64 Atmosphere LUT; refresh every 4 frames since sun moves slowly)
        if (!atmLutReady || (uniforms.currentFrameCounter() & 3) == 0) {
            try (RenderPass pass = encoder.createRenderPass(
                    RenderPassDescriptor.builder(() -> "raindio_composite0_atm_lut")
                            .withColorAttachment(att.colortex6View())
                            .build())) {
                pass.setPipeline(composite0);
                pass.setUniform("FrameUniforms", uniforms.frameBuffer());
                pass.draw(3, 1, 0, 0);
            }
            atmLutReady = true;
        }

        // 2. composite1 -> colortex3 (full-res PTGI water normals & roughness)
        try (RenderPass pass = encoder.createRenderPass(
                RenderPassDescriptor.builder(() -> "raindio_composite1_water")
                        .withColorAttachment(att.colortex3View())
                        .build())) {
            pass.setPipeline(composite1);
            pass.setUniform("FrameUniforms", uniforms.frameBuffer());
            pass.setUniform("depthtex0", att.depthtex0View(), nearestClamp);
            pass.setUniform("colortex1", att.colortex1View(), nearestClamp);
            pass.setUniform("noisetex", noise.view(), linearRepeat);
            pass.draw(3, 1, 0, 0);
        }

        // 2b. Unified 64x64x64 Toroidal Voxel L1 SH-LPV GI (1..4 propagation steps/frame per preset)
        if (lpvManager != null) {
            lpvManager.dispatch(encoder, att, uniforms, config.lpvStepsPerFrame);
        }

        // 2c. ReSTIR GI + Clustered Point Lights (0-latency on-GPU Vulkan compute) -> pointLightTex
        if (pointLights != null && pointLights.isAvailable()) {
            pointLights.dispatch(encoder, att, uniforms, lpvManager, debugView, config.pointLightDdaTaps, config.pointLightTopK);
        }

        // 3. composite2 -> colortex9 (half-resolution SSR + shadow reflection fallback + GTAO)
        try (RenderPass pass = encoder.createRenderPass(
                RenderPassDescriptor.builder(() -> "raindio_composite2_ssr_gtao_half")
                        .withColorAttachment(att.colortex9View())
                        .build())) {
            pass.setPipeline(composite2);
            pass.setUniform("FrameUniforms", uniforms.frameBuffer());
            pass.setUniform("colortex0", att.colortex0View(), linearClamp);
            pass.setUniform("colortex1", att.colortex1View(), nearestClamp);
            pass.setUniform("colortex3", att.colortex3View(), linearClamp);
            pass.setUniform("colortex6", att.colortex6View(), linearClamp);
            pass.setUniform("depthtex0", att.depthtex0View(), nearestClamp);
            pass.setUniform("depthtex1", att.depthtex1View(), nearestClamp);
            pass.setUniform("noisetex", noise.view(), linearRepeat);
            pass.setUniform("shadowtex0", att.shadowtex0View(), nearestClamp);
            pass.setUniform("shadowcolor0", att.shadowcolor0View(), linearClamp);
            pass.draw(3, 1, 0, 0);
        }

        // 3b. Hybrid Ray-Traced Reflections (Apple M3+ Metal Hardware RT Cores when available, else Vulkan Voxel RT) -> colortex9.rgb
        if (rtReflections != null && rtReflections.isAvailable()) {
            rtReflections.dispatch(encoder, att, uniforms, noise, lpvManager, metalRt);
        }

        // 4. composite3: reads colortex0 -> writes colortex0Scratch (ZERO fullscreen copy ping-pong!)
        try (RenderPass pass = encoder.createRenderPass(
                RenderPassDescriptor.builder(() -> "raindio_composite3_main_lighting")
                        .withColorAttachment(att.colortex0ScratchView())
                        .build())) {
            pass.setPipeline(composite3);
            pass.setUniform("FrameUniforms", uniforms.frameBuffer());
            pass.setUniform("colortex0", att.colortex0View(), linearClamp);
            pass.setUniform("colortex1", att.colortex1View(), nearestClamp);
            pass.setUniform("colortex2", att.colortex2View(), linearClamp);
            pass.setUniform("colortex3", att.colortex3View(), linearClamp);
            pass.setUniform("colortex6", att.colortex6View(), linearClamp);
            pass.setUniform("colortex9", att.colortex9View(), linearClamp);
            pass.setUniform("depthtex0", att.depthtex0View(), nearestClamp);
            pass.setUniform("depthtex1", att.depthtex1View(), nearestClamp);
            pass.setUniform("noisetex", noise.view(), linearRepeat);
            pass.setUniform("shadowtex0", att.shadowtex0View(), nearestClamp);
            pass.setUniform("pointLightTex", att.pointLightView(), nearestClamp);
            pass.draw(3, 1, 0, 0);
        }

        int effectiveUpscale = config.effectiveUpscaleMode();
        boolean runTaaResolve = config.taaEnabled && (effectiveUpscale != RaindioConfig.UPSCALE_FSR1);
        boolean needsSpatialUpscale = (att.width() != att.displayWidth() || att.height() != att.displayHeight());

        // 4b. Path A (TAAU / Native HDR-TAA): resolves internal HDR -> native displayW x displayH taaHistoryWrite
        //     Path B (FSR 1.0) or Path C (OFF without TAA): stays at internal renderW x renderH colortex0Scratch
        GpuTextureView hdrPostTaaView = att.colortex0ScratchView();
        int hdrWidth = att.width();
        int hdrHeight = att.height();
        if (runTaaResolve) {
            try (RenderPass pass = encoder.createRenderPass(
                    RenderPassDescriptor.builder(() -> "raindio_hdr_taau_resolve")
                            .withColorAttachment(att.taaHistoryWriteView())
                            .build())) {
                pass.setPipeline(taaResolve);
                pass.setUniform("FrameUniforms", uniforms.frameBuffer());
                pass.setUniform("colortex0", att.colortex0ScratchView(), linearClamp);
                pass.setUniform("taaHistory", att.taaHistoryReadView(), linearClamp);
                pass.setUniform("depthtex0", att.depthtex0View(), nearestClamp);
                pass.draw(3, 1, 0, 0);
            }
            hdrPostTaaView = att.taaHistoryWriteView();
            hdrWidth = att.displayWidth();
            hdrHeight = att.displayHeight();
        }

        // 4c. GPU Luminance Histogram Auto-Exposure (dispatched with exact HDR source dimensions)
        if (autoExposure != null && autoExposure.isAvailable()) {
            autoExposure.dispatch(encoder, att, hdrPostTaaView, hdrWidth, hdrHeight);
        }

        // 5. composite4 -> 1/4 or 1/2-res bloomTight + bloomWide
        try (RenderPass pass = encoder.createRenderPass(
                RenderPassDescriptor.builder(() -> "raindio_composite4_bloom")
                        .withColorAttachment(att.bloomTightView())
                        .withColorAttachment(att.bloomWideView())
                        .build())) {
            pass.setPipeline(composite4);
            pass.setUniform("FrameUniforms", uniforms.frameBuffer());
            pass.setUniform("colortex0", hdrPostTaaView, linearClamp);
            pass.draw(3, 1, 0, 0);
        }

        // 6. composite5 (AgX Tonemap + Vignette + Dither):
        //    - When TAAU/TAA already produced native displayW x displayH HDR -> write directly to displayLdr
        //    - Otherwise (FSR 1.0 or OFF without TAA) -> write to internal renderW x renderH colortex0
        GpuTextureView tonemapOutView = runTaaResolve ? att.displayLdrView() : att.colortex0View();
        try (RenderPass pass = encoder.createRenderPass(
                RenderPassDescriptor.builder(() -> "raindio_composite5_exposure")
                        .withColorAttachment(tonemapOutView)
                        .build())) {
            pass.setPipeline(composite5);
            pass.setUniform("FrameUniforms", uniforms.frameBuffer());
            pass.setUniform("colortex0", hdrPostTaaView, linearClamp);
            pass.setUniform("colortex3", att.bloomTightView(), linearClamp);
            pass.setUniform("colortex4", att.bloomWideView(), linearClamp);
            pass.setUniform("autoExposureTex", att.autoExposureView(), nearestClamp);
            pass.draw(3, 1, 0, 0);
        }

        // 6b. Path B (FSR 1.0 EASU Spatial Upscaling): only when FSR1 mode & renderScale < 1.0
        GpuTextureView finalInputView = tonemapOutView;
        if (effectiveUpscale == RaindioConfig.UPSCALE_FSR1 && needsSpatialUpscale && debugView == 0) {
            try (RenderPass pass = encoder.createRenderPass(
                    RenderPassDescriptor.builder(() -> "raindio_fsr1_easu")
                            .withColorAttachment(att.displayLdrView())
                            .build())) {
                pass.setPipeline(fsrEasu);
                pass.setUniform("FrameUniforms", uniforms.frameBuffer());
                pass.setUniform("colortex0", att.colortex0View(), linearClamp);
                pass.draw(3, 1, 0, 0);
            }
            finalInputView = att.displayLdrView();
        }

        // 7. final -> mainColorView (Native-res FSR 1.0 RCAS in TAAU/FSR1, or CAS/FXAA in OFF + Color Grading)
        try (RenderPass pass = encoder.createRenderPass(
                RenderPassDescriptor.builder(() -> "raindio_final_tonemap_aa")
                        .withColorAttachment(mainColorView)
                        .build())) {
            pass.setPipeline(finalPass);
            pass.setUniform("FrameUniforms", uniforms.frameBuffer());
            pass.setUniform("colortex0", finalInputView, linearClamp);
            pass.draw(3, 1, 0, 0);
        }
    }

    @Override
    public void close() {
        closeQuietly(inputBlit); inputBlit = null;
        closeQuietly(composite0); composite0 = null;
        closeQuietly(composite1); composite1 = null;
        closeQuietly(composite2); composite2 = null;
        closeQuietly(composite3); composite3 = null;
        closeQuietly(composite4); composite4 = null;
        closeQuietly(composite5); composite5 = null;
        closeQuietly(taaResolve); taaResolve = null;
        closeQuietly(fsrEasu); fsrEasu = null;
        closeQuietly(finalPass); finalPass = null;
        shaderSource.close();
        initialized = false;
        atmLutReady = false;
        compiledSignature = "";
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
