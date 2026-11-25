package com.raindio.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.raindio.RaindioClientMod;
import com.raindio.config.RaindioConfig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Native Vulkan First-Person Hand & Held-Item Renderer.
 *
 * Replaces vanilla GameRenderer.renderItemInHand() when Raindio shaders are active,
 * rendering the player's arm and held items with Raindio's unified sunlight, directional sky ambient,
 * real-time world shadows, blocklight, held-item emissive glow, pixel-selective GGX PBR,
 * GPU auto-exposure, AgX filmic tonemapping, and color grading at native display resolution.
 */
public final class RaindioHandRenderer implements AutoCloseable {
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final int FLOATS_PER_VERTEX = 14; // pos(3) + normal(3) + uv(2) + color(4) + lightmap(2)
    private static final int VERTEX_STRIDE = FLOATS_PER_VERTEX * 4; // 56 bytes
    private static final int INITIAL_MAX_VERTICES = 16_384;
    private static final int HARD_MAX_VERTICES = 131_072;
    private static final int HAND_UBO_SIZE = 128; // mat4 handProjection (64) + handEmissive (16) + 3x pointLight vec4 (48)

    private static final DepthStencilState HAND_DEPTH_STATE = DepthStencilState.DEFAULT;

    private static final VertexFormat HAND_VERTEX_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Normal", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA32_FLOAT)
            .addAttribute("UV2", GpuFormat.RG32_FLOAT)
            .build();

    private final RaindioShaderSource shaderSource = new RaindioShaderSource();
    private final HandQuadConsumer quadConsumer = new HandQuadConsumer();
    private final HandSubmitCollector submitCollector = new HandSubmitCollector();
    private final ByteBuffer handUboStaging = ByteBuffer.allocateDirect(HAND_UBO_SIZE).order(ByteOrder.nativeOrder());
    private final float[] handPointLightScratch = new float[12];

    private final Map<GpuTextureView, TextureBatch> activeBatches = new LinkedHashMap<>();
    private final List<TextureBatch> batchPool = new ArrayList<>();
    private final List<DrawBatch> drawBatches = new ArrayList<>();
    private final Map<RenderType, GpuTextureView> renderTypeTextureCache = new IdentityHashMap<>();
    private final Map<RenderType, Boolean> renderTypeSkipCache = new IdentityHashMap<>();

    private CompiledRenderPipeline pipeline;
    private GpuBuffer handUniformBuffer;
    private GpuBuffer vertexBuffer;
    private ByteBuffer vertexStaging;
    private int capacityVertices = 0;

    private boolean initialized = false;
    private int compiledShadowRes = -1;
    private float compiledExposure = -1.0f;
    private float compiledSaturation = -1.0f;
    private GpuFormat compiledOutputFormat = null;
    private GpuTextureView fallbackBlockAtlas = null;

    private void ensureCompiled(CommandEncoder encoder, RaindioConfig config, GpuFormat outputFormat) {
        if (initialized
                && compiledShadowRes == config.shadowResolution
                && Math.abs(compiledExposure - config.exposure) < 1e-4f
                && Math.abs(compiledSaturation - config.saturation) < 1e-4f
                && compiledOutputFormat == outputFormat
                && pipeline != null
                && handUniformBuffer != null) {
            return;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return;

        if (pipeline != null) {
            RaindioAttachments.waitVulkanDeviceIdle(encoder);
            pipeline.close();
            pipeline = null;
        }

        BindGroupLayout set0 = BindGroupLayout.builder()
                .withUniform("FrameUniforms", UniformType.UNIFORM_BUFFER)
                .withUniform("HandUniforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("shadowtex0", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("autoExposureTex", UniformType.COMBINED_IMAGE_SAMPLER)
                .build();

        ColorTargetState targetState = new ColorTargetState(
                Optional.of(BlendFunction.TRANSLUCENT),
                outputFormat,
                ColorTargetState.WRITE_ALL
        );

        RenderPipeline desc = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "chunks/hand_gbuffer"))
                .withVertexShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "chunks/hand_gbuffer"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "chunks/hand_gbuffer"))
                .withShaderDefine("SHADOW_MAP_RES", (float) Math.max(512, config.shadowResolution))
                .withShaderDefine("EXPOSURE", config.exposure)
                .withShaderDefine("SATURATION", config.saturation)
                .withBindGroupLayout(set0)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, HAND_VERTEX_FORMAT)
                .withCull(false)
                .withDepthStencilState(HAND_DEPTH_STATE)
                .withColorTargetState(0, targetState)
                .build();

        this.pipeline = device.compilePipeline(desc, shaderSource, DIRECT_EXECUTOR).join().finishCompile();
        if (this.handUniformBuffer == null) {
            this.handUniformBuffer = device.createBuffer(
                    () -> "raindio_hand_ubo",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    HAND_UBO_SIZE
            );
        }
        ensureBufferCapacity(encoder, device, Math.max(INITIAL_MAX_VERTICES, capacityVertices));
        this.compiledShadowRes = config.shadowResolution;
        this.compiledExposure = config.exposure;
        this.compiledSaturation = config.saturation;
        this.compiledOutputFormat = outputFormat;
        this.initialized = true;
        RaindioClientMod.LOGGER.info("[Raindio] Compiled native Vulkan first-person hand pipeline.");
    }

    private void ensureBufferCapacity(CommandEncoder encoder, GpuDevice device, int requiredVertices) {
        if (requiredVertices <= capacityVertices && vertexBuffer != null && vertexStaging != null) {
            return;
        }
        int newCap = Math.max(INITIAL_MAX_VERTICES, capacityVertices == 0 ? INITIAL_MAX_VERTICES : capacityVertices);
        while (newCap < requiredVertices && newCap < HARD_MAX_VERTICES) {
            newCap = Math.min(HARD_MAX_VERTICES, newCap * 2);
        }
        if (vertexBuffer != null) {
            RaindioAttachments.waitVulkanDeviceIdle(encoder);
            vertexBuffer.close();
            vertexBuffer = null;
        }
        long bytes = (long) newCap * VERTEX_STRIDE;
        this.vertexBuffer = device.createBuffer(
                () -> "raindio_hand_vbo",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                bytes
        );
        this.vertexStaging = ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
        this.capacityVertices = newCap;
    }

    /**
     * Collects and renders the first-person hand and held items directly onto mainColorView + depthView.
     * Returns true if rendered cleanly by Raindio, or false to fall back to vanilla rendering.
     */
    public boolean render(
            FirstPersonHandsAndItemsRenderer handRenderer,
            CameraRenderState cameraState,
            PlayerRenderState playerState,
            PoseStack poseStack,
            Matrix4f handProjection,
            GpuTextureView mainColorView,
            GpuFormat mainColorFormat,
            GpuTextureView depthView,
            RaindioAttachments att,
            RaindioUniformManager uniforms,
            RaindioPointLightManager pointLights,
            RaindioConfig config
    ) {
        if (handRenderer == null || cameraState == null || playerState == null
                || playerState.firstPersonHandsAndItems == null
                || mainColorView == null || depthView == null
                || att == null || !att.isInitialized() || uniforms == null || uniforms.frameBuffer() == null) {
            return false;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return false;

        CommandEncoder encoder = device.createCommandEncoder();
        if (!RaindioAttachments.isVulkanBackend(encoder)) {
            return false;
        }

        ensureCompiled(encoder, config, mainColorFormat);
        if (!initialized || pipeline == null || handUniformBuffer == null) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        this.fallbackBlockAtlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        if (this.fallbackBlockAtlas == null) return false;

        // Reset per-frame batches
        this.drawBatches.clear();
        for (TextureBatch b : activeBatches.values()) {
            b.reset();
        }
        activeBatches.clear();
        renderTypeTextureCache.clear();
        renderTypeSkipCache.clear();

        // Collect all first-person arm & held-item quads using vanilla's exact animation & transforms
        try {
            handRenderer.submitHandsWithItems(
                    cameraState.cameraEntityPartialTicks,
                    poseStack,
                    submitCollector,
                    playerState,
                    playerState.firstPersonHandsAndItems
            );
            quadConsumer.flushQuad();
        } catch (Throwable t) {
            quadConsumer.flushQuad();
            RaindioClientMod.LOGGER.warn("[Raindio Hand] Failed to collect hand geometry, falling back to vanilla:", t);
            return false;
        }

        // Empty hand / no geometry (e.g. scoping with spyglass): handled cleanly
        int neededVerts = 0;
        for (TextureBatch b : activeBatches.values()) {
            neededVerts += b.vertexCount;
        }
        if (neededVerts <= 0) {
            return true;
        }

        int uploadVerts = Math.min(neededVerts, HARD_MAX_VERTICES);
        ensureBufferCapacity(encoder, device, uploadVerts);
        if (vertexBuffer == null || vertexStaging == null) return false;

        vertexStaging.clear();
        int cursorVerts = 0;
        for (Map.Entry<GpuTextureView, TextureBatch> entry : activeBatches.entrySet()) {
            TextureBatch batch = entry.getValue();
            if (batch.vertexCount <= 0) continue;
            int remaining = capacityVertices - cursorVerts;
            if (remaining < 3) break;
            int vertsToCopy = Math.min(batch.vertexCount, (remaining / 3) * 3);
            if (vertsToCopy <= 0) continue;

            int floatCount = vertsToCopy * FLOATS_PER_VERTEX;
            float[] data = batch.data;
            for (int i = 0; i < floatCount; i++) {
                vertexStaging.putFloat(data[i]);
            }
            drawBatches.add(new DrawBatch(entry.getKey(), cursorVerts, vertsToCopy));
            cursorVerts += vertsToCopy;
        }

        if (cursorVerts <= 0 || drawBatches.isEmpty()) {
            return true;
        }

        long byteLen = (long) cursorVerts * VERTEX_STRIDE;
        vertexStaging.position(0).limit((int) byteLen);
        encoder.writeToBuffer(vertexBuffer.slice(0, byteLen), vertexStaging);

        // Upload HandUniforms (handProjection + held-item emissive + real-time nearby RT point lights)
        float[] heldEm = detectHeldEmissive(mc.player);
        if (pointLights != null) {
            pointLights.computeHandPointLight(handPointLightScratch);
        } else {
            Arrays.fill(handPointLightScratch, 0.0f);
        }
        handUboStaging.clear();
        handProjection.get(0, handUboStaging);
        handUboStaging.putFloat(64, heldEm[0]);
        handUboStaging.putFloat(68, heldEm[1]);
        handUboStaging.putFloat(72, heldEm[2]);
        handUboStaging.putFloat(76, heldEm[3]);
        for (int i = 0; i < 12; i++) {
            handUboStaging.putFloat(80 + i * 4, handPointLightScratch[i]);
        }
        handUboStaging.position(0).limit(HAND_UBO_SIZE);
        encoder.writeToBuffer(handUniformBuffer.slice(), handUboStaging);

        // Draw into mainRenderTarget color + cleared Reverse-Z depth
        RenderPassDescriptor desc = RenderPassDescriptor.builder(() -> "raindio_first_person_hand")
                .withColorAttachment(mainColorView)
                .withDepthAttachment(depthView)
                .build();
        try (RenderPass pass = encoder.createRenderPass(desc)) {
            pass.setPipeline(pipeline);
            pass.setUniform("FrameUniforms", uniforms.frameBuffer());
            pass.setUniform("HandUniforms", handUniformBuffer);
            pass.setUniform("shadowtex0", att.shadowtex0View(), att.nearestClampSampler());
            pass.setUniform("autoExposureTex", att.autoExposureView(), att.nearestClampSampler());
            pass.setVertexBuffer(0, vertexBuffer.slice(0, byteLen));

            for (int i = 0; i < drawBatches.size(); i++) {
                DrawBatch batch = drawBatches.get(i);
                pass.setUniform("Sampler0", batch.textureView, att.nearestClampSampler());
                pass.draw(batch.vertexCount, 1, batch.firstVertex, 0);
            }
        }
        return true;
    }

    private static float[] detectHeldEmissive(LocalPlayer player) {
        if (player == null) return new float[] { 0.0f, 0.0f, 0.0f, 0.0f };
        float[] main = classifyEmissiveItem(player.getMainHandItem());
        if (main[3] > 0.01f) return main;
        return classifyEmissiveItem(player.getOffhandItem());
    }

    private static float[] classifyEmissiveItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new float[] { 0.0f, 0.0f, 0.0f, 0.0f };
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.contains("soul_torch") || path.contains("soul_lantern") || path.contains("soul_campfire")) {
            return new float[] { 0.25f, 0.85f, 1.00f, 0.85f };
        }
        if (path.contains("redstone_torch") || path.contains("redstone_lamp")) {
            return new float[] { 1.00f, 0.28f, 0.16f, 0.72f };
        }
        if (path.contains("sea_lantern") || path.contains("beacon") || path.contains("conduit") || path.contains("end_rod")) {
            return new float[] { 0.72f, 0.94f, 1.00f, 0.90f };
        }
        if (path.contains("froglight")) {
            if (path.contains("verdant")) return new float[] { 0.48f, 1.00f, 0.62f, 0.85f };
            if (path.contains("pearlescent")) return new float[] { 0.96f, 0.65f, 1.00f, 0.85f };
            return new float[] { 1.00f, 0.85f, 0.48f, 0.85f };
        }
        if (path.contains("torch") || path.contains("lantern") || path.contains("glowstone")
                || path.contains("shroomlight") || path.contains("jack_o_lantern")
                || path.contains("campfire") || path.contains("lava") || path.contains("magma")
                || path.contains("blaze") || path.contains("ochre") || path.contains("glow_berries")) {
            return new float[] { 1.00f, 0.68f, 0.34f, 0.90f };
        }
        return new float[] { 0.0f, 0.0f, 0.0f, 0.0f };
    }

    private TextureBatch getOrCreateBatch(GpuTextureView view) {
        GpuTextureView key = (view != null) ? view : fallbackBlockAtlas;
        TextureBatch batch = activeBatches.get(key);
        if (batch == null) {
            int idx = activeBatches.size();
            if (idx < batchPool.size()) {
                batch = batchPool.get(idx);
                batch.reset();
            } else {
                batch = new TextureBatch();
                batchPool.add(batch);
            }
            activeBatches.put(key, batch);
        }
        return batch;
    }

    private boolean shouldSkipRenderType(RenderType rt) {
        if (rt == null) return false;
        Boolean cached = renderTypeSkipCache.get(rt);
        if (cached != null) return cached;

        boolean skip = false;
        if (rt.isOutline()) {
            skip = true;
        } else {
            String raw = rt.toString();
            int bracket = raw.indexOf('[');
            String typeName = (bracket >= 0 ? raw.substring(0, bracket) : raw).toLowerCase(Locale.ROOT);
            if (typeName.contains("glint")
                    || typeName.contains("shadow")
                    || typeName.contains("outline")
                    || typeName.contains("lines")
                    || typeName.contains("debug")) {
                skip = true;
            }
        }
        renderTypeSkipCache.put(rt, skip);
        return skip;
    }

    private GpuTextureView resolveTexture(RenderType rt) {
        if (rt == null) return fallbackBlockAtlas;
        GpuTextureView cached = renderTypeTextureCache.get(rt);
        if (cached != null) return cached;

        GpuTextureView resolved = fallbackBlockAtlas;
        try {
            PreparedRenderType prepared = rt.prepare();
            if (prepared != null && prepared.textures() != null) {
                for (PreparedRenderType.Texture tex : prepared.textures()) {
                    if (tex != null && "Sampler0".equals(tex.name()) && tex.textureView() != null) {
                        resolved = tex.textureView();
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            resolved = fallbackBlockAtlas;
        }
        renderTypeTextureCache.put(rt, resolved);
        return resolved;
    }

    @Override
    public void close() {
        if (vertexBuffer != null) { vertexBuffer.close(); vertexBuffer = null; }
        if (handUniformBuffer != null) { handUniformBuffer.close(); handUniformBuffer = null; }
        if (pipeline != null) { pipeline.close(); pipeline = null; }
        shaderSource.close();
        vertexStaging = null;
        capacityVertices = 0;
        activeBatches.clear();
        drawBatches.clear();
        renderTypeTextureCache.clear();
        renderTypeSkipCache.clear();
        initialized = false;
    }

    private record DrawBatch(GpuTextureView textureView, int firstVertex, int vertexCount) {}

    private static final class TextureBatch {
        float[] data = new float[1024 * FLOATS_PER_VERTEX];
        int vertexCount = 0;

        void reset() { vertexCount = 0; }

        void ensureAdd(int extraVerts) {
            int neededFloats = (vertexCount + extraVerts) * FLOATS_PER_VERTEX;
            if (neededFloats > data.length) {
                int newLen = data.length;
                while (newLen < neededFloats) newLen *= 2;
                data = Arrays.copyOf(data, newLen);
            }
        }

        void putVertex(
                float px, float py, float pz,
                float nx, float ny, float nz,
                float u, float v,
                float r, float g, float b, float a,
                float bl, float sl
        ) {
            int o = vertexCount * FLOATS_PER_VERTEX;
            data[o]      = px;
            data[o + 1]  = py;
            data[o + 2]  = pz;
            data[o + 3]  = nx;
            data[o + 4]  = ny;
            data[o + 5]  = nz;
            data[o + 6]  = u;
            data[o + 7]  = v;
            data[o + 8]  = r;
            data[o + 9]  = g;
            data[o + 10] = b;
            data[o + 11] = a;
            data[o + 12] = bl;
            data[o + 13] = sl;
            vertexCount++;
        }
    }

    private final class HandQuadConsumer implements VertexConsumer {
        private final float[] qx = new float[4], qy = new float[4], qz = new float[4];
        private final float[] qnx = new float[4], qny = new float[4], qnz = new float[4];
        private final float[] qu = new float[4], qv = new float[4];
        private final float[] qr = new float[4], qg = new float[4], qb = new float[4], qa = new float[4];
        private final float[] qbl = new float[4], qsl = new float[4];

        private TextureBatch currentBatch;
        private int qi = 0;
        private int cur = -1;
        private float defBl = 0.0f;
        private float defSl = 1.0f;
        private boolean hurtOverlay = false;

        void beginBatch(TextureBatch batch, int packedLight, int packedOverlay) {
            flushQuad();
            this.currentBatch = batch;
            this.qi = 0;
            this.cur = -1;
            this.defBl = clamp01((packedLight & 0xFFFF) / 240.0f);
            this.defSl = clamp01(((packedLight >>> 16) & 0xFFFF) / 240.0f);
            int ovV = (packedOverlay >>> 16) & 0xFFFF;
            this.hurtOverlay = (ovV == OverlayTexture.RED_OVERLAY_V);
        }

        void flushQuad() {
            if (qi == 4 && currentBatch != null) {
                currentBatch.ensureAdd(6);
                emitVert(0); emitVert(1); emitVert(2);
                emitVert(0); emitVert(2); emitVert(3);
            }
            qi = 0;
            cur = -1;
        }

        private void emitVert(int idx) {
            currentBatch.putVertex(
                    qx[idx], qy[idx], qz[idx],
                    qnx[idx], qny[idx], qnz[idx],
                    qu[idx], qv[idx],
                    qr[idx], qg[idx], qb[idx], qa[idx],
                    qbl[idx], qsl[idx]
            );
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (qi == 4) {
                currentBatch.ensureAdd(6);
                emitVert(0); emitVert(1); emitVert(2);
                emitVert(0); emitVert(2); emitVert(3);
                qi = 0;
            }
            int i = qi++;
            cur = i;
            qx[i] = x; qy[i] = y; qz[i] = z;
            qnx[i] = 0.0f; qny[i] = 1.0f; qnz[i] = 0.0f;
            qu[i] = 0.0f; qv[i] = 0.0f;
            qr[i] = 1.0f;
            qg[i] = hurtOverlay ? 0.45f : 1.0f;
            qb[i] = hurtOverlay ? 0.45f : 1.0f;
            qa[i] = 1.0f;
            qbl[i] = defBl;
            qsl[i] = defSl;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            if (cur >= 0) {
                float fr = (r & 0xFF) / 255.0f;
                float fg = (g & 0xFF) / 255.0f;
                float fb = (b & 0xFF) / 255.0f;
                float fa = (a & 0xFF) / 255.0f;
                if (hurtOverlay) {
                    fr = fr * 0.55f + 0.45f;
                    fg *= 0.55f;
                    fb *= 0.55f;
                }
                qr[cur] = fr; qg[cur] = fg; qb[cur] = fb; qa[cur] = fa;
            }
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            if (argb == 0) return setColor(255, 255, 255, 255);
            int a = (argb >>> 24) & 0xFF;
            if (a == 0) a = 255;
            return setColor((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, a);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (cur >= 0) { qu[cur] = u; qv[cur] = v; }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            if (cur >= 0 && v == OverlayTexture.RED_OVERLAY_V) {
                qr[cur] = qr[cur] * 0.55f + 0.45f;
                qg[cur] *= 0.55f;
                qb[cur] *= 0.55f;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (cur >= 0) {
                qbl[cur] = clamp01(u / 240.0f);
                qsl[cur] = clamp01(v / 240.0f);
            }
            return this;
        }

        @Override public VertexConsumer setUv3(float u, float v) { return this; }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (cur >= 0) {
                float len = (float) Math.sqrt(x * x + y * y + z * z);
                if (len > 1e-5f) {
                    qnx[cur] = x / len; qny[cur] = y / len; qnz[cur] = z / len;
                }
            }
            return this;
        }

        @Override public VertexConsumer setLineWidth(float width) { return this; }

        private static float clamp01(float v) { return Math.max(0.0f, Math.min(1.0f, v)); }
    }

    private final class HandSubmitCollector implements SubmitNodeCollector {
        private final Vector3f scratchN = new Vector3f();

        @Override
        public OrderedSubmitNodeCollector order(int order) { return this; }

        @Override
        public <S> void submitModel(
                Model<? super S> model, S state, PoseStack stack, RenderType renderType,
                int packedLight, int packedOverlay, int tintColor, UvMapping uvMapping, int outlineColor
        ) {
            if (model == null || stack == null || shouldSkipRenderType(renderType)) return;
            TextureBatch batch = getOrCreateBatch(resolveTexture(renderType));
            quadConsumer.beginBatch(batch, packedLight, packedOverlay);
            VertexConsumer consumer = (uvMapping != null) ? uvMapping.wrap(quadConsumer) : quadConsumer;
            model.setupAnim(state);
            model.renderToBuffer(stack, consumer, packedLight, packedOverlay, tintColor);
            quadConsumer.flushQuad();
        }

        @Override
        public void submitModelPart(
                ModelPart part, PoseStack stack, RenderType renderType,
                int packedLight, int packedOverlay, UvMapping uvMapping, int tintColor, int outlineColor
        ) {
            if (part == null || stack == null || shouldSkipRenderType(renderType)) return;
            TextureBatch batch = getOrCreateBatch(resolveTexture(renderType));
            quadConsumer.beginBatch(batch, packedLight, packedOverlay);
            VertexConsumer consumer = (uvMapping != null) ? uvMapping.wrap(quadConsumer) : quadConsumer;
            part.render(stack, consumer, packedLight, packedOverlay, tintColor);
            quadConsumer.flushQuad();
        }

        @Override
        public void submitModelPart(
                ModelPart part, PoseStack stack, RenderType renderType,
                int packedLight, int packedOverlay, UvMapping uvMapping
        ) {
            submitModelPart(part, stack, renderType, packedLight, packedOverlay, uvMapping, -1, 0);
        }

        @Override
        public void submitModelPart(
                ModelPart part, PoseStack stack, RenderType renderType,
                int packedLight, int packedOverlay, UvMapping uvMapping, int tintColor
        ) {
            submitModelPart(part, stack, renderType, packedLight, packedOverlay, uvMapping, tintColor, 0);
        }

        @Override
        public void submitBlockModel(
                PoseStack s, RenderType rt, List<BlockStateModelPart> parts,
                int[] tintLayers, int packedLight, int packedOverlay, int outlineColor
        ) {
            if (s == null || parts == null || shouldSkipRenderType(rt)) return;
            TextureBatch batch = getOrCreateBatch(resolveTexture(rt));
            PoseStack.Pose pose = s.last();
            for (BlockStateModelPart part : parts) {
                if (part == null) continue;
                submitQuads(batch, pose, part.getQuads(null), tintLayers, packedLight, packedOverlay);
                for (Direction dir : Direction.values()) {
                    submitQuads(batch, pose, part.getQuads(dir), tintLayers, packedLight, packedOverlay);
                }
            }
        }

        @Override
        public void submitItem(
                PoseStack s, ItemDisplayContext c, int packedLight, int packedOverlay,
                int outlineColor, int[] tintLayers, ItemQuads quads, ItemStackRenderState.FoilType foil
        ) {
            if (s == null || quads == null || quads.isEmpty()) return;
            List<BakedQuad> all = quads.all();
            if (all == null || all.isEmpty()) return;
            PoseStack.Pose pose = s.last();
            for (BakedQuad quad : all) {
                if (quad == null) continue;
                RenderType rt = (quad.materialInfo() != null) ? quad.materialInfo().itemRenderType() : null;
                if (shouldSkipRenderType(rt)) continue;
                TextureBatch batch = getOrCreateBatch(resolveTexture(rt));
                submitSingleQuad(batch, pose, quad, tintLayers, packedLight, packedOverlay);
            }
        }

        @Override
        public void submitCustomGeometry(PoseStack s, RenderType rt, SubmitNodeCollector.CustomGeometryRenderer r) {
            if (s == null || r == null || shouldSkipRenderType(rt)) return;
            TextureBatch batch = getOrCreateBatch(resolveTexture(rt));
            quadConsumer.beginBatch(batch, 0x00F000F0, OverlayTexture.NO_OVERLAY);
            r.render(s.last(), quadConsumer);
            quadConsumer.flushQuad();
        }

        private void submitQuads(
                TextureBatch batch, PoseStack.Pose pose, List<BakedQuad> quads,
                int[] tintLayers, int packedLight, int packedOverlay
        ) {
            if (quads == null || quads.isEmpty()) return;
            for (BakedQuad quad : quads) {
                if (quad == null) continue;
                submitSingleQuad(batch, pose, quad, tintLayers, packedLight, packedOverlay);
            }
        }

        private void submitSingleQuad(
                TextureBatch batch, PoseStack.Pose pose, BakedQuad quad,
                int[] tintLayers, int packedLight, int packedOverlay
        ) {
            int argb = 0xFFFFFFFF;
            if (tintLayers != null && quad.materialInfo() != null && quad.materialInfo().isTinted()) {
                int tIdx = quad.materialInfo().tintIndex();
                if (tIdx >= 0 && tIdx < tintLayers.length && tintLayers[tIdx] != 0) {
                    argb = tintLayers[tIdx] | 0xFF000000;
                }
            }
            Direction dir = quad.direction();
            float nx = dir != null ? dir.getStepX() : 0.0f;
            float ny = dir != null ? dir.getStepY() : 1.0f;
            float nz = dir != null ? dir.getStepZ() : 0.0f;
            Matrix3f normMat = pose.normal();
            scratchN.set(nx, ny, nz).mul(normMat);
            float nLen = scratchN.length();
            if (nLen > 1e-5f) {
                scratchN.div(nLen);
            } else {
                scratchN.set(0.0f, 1.0f, 0.0f);
            }

            quadConsumer.beginBatch(batch, packedLight, packedOverlay);
            for (int i = 0; i < 4; i++) {
                var p = quad.position(i);
                long puv = quad.packedUV(i);
                quadConsumer.addVertex(pose, p.x(), p.y(), p.z())
                        .setColor(argb)
                        .setUv(UVPair.unpackU(puv), UVPair.unpackV(puv))
                        .setUv2(packedLight & 0xFFFF, (packedLight >>> 16) & 0xFFFF)
                        .setNormal(scratchN.x, scratchN.y, scratchN.z);
            }
            quadConsumer.flushQuad();
        }

        @Override public void submitShadow(PoseStack s, float r, List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> p) {}
        @Override public void submitNameTag(PoseStack s, Vec3 o, int y, Component t, boolean st, int l, CameraRenderState c) {}
        @Override public void submitText(PoseStack s, float x, float y, FormattedCharSequence t, boolean sh, Font.DisplayMode m, int l, int c, int bg, int o) {}
        @Override public void submitTextBackground(PoseStack s, float x0, float y0, float x1, float y1, int bg, Font.DisplayMode m, int l) {}
        @Override public void submitFlame(PoseStack s, net.minecraft.client.renderer.entity.state.EntityRenderState st, Quaternionf q) {}
        @Override public void submitLeash(PoseStack s, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState l) {}
        @Override public <S> void submitCrumblingOverlay(Model<? super S> m, S st, PoseStack s, RenderType rt, int l, int o, int t, ModelFeatureRenderer.CrumblingOverlay co) {}
        @Override public void submitMovingBlock(PoseStack s, MovingBlockRenderState b, int l) {}
        @Override public void submitBreakingBlockModel(PoseStack s, List<BlockStateModelPart> p, int stage, boolean cr) {}
        @Override public void submitShapeOutline(PoseStack s, VoxelShape sh, RenderType rt, int c, float w, boolean st) {}
        @Override public void submitQuadParticleGroup(QuadParticleRenderState p) {}
        @Override public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group g, CameraRenderState c, boolean st) {}
    }
}
