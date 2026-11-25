package com.raindio.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.raindio.RaindioClientMod;
import com.raindio.pipeline.RaindioPipelineSpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Renders real-time articulated 3D shadows for the local player (including first-person)
 * and all nearby animals/mobs/entities into Raindio's shadowtex0 + shadowcolor0 map
 * in a single batched Vulkan draw call.
 */
public final class RaindioEntityShadowRenderer implements AutoCloseable {
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final int MAX_VERTICES = 131_072; // ~21,800 quads (~1.5 MB)
    private static final int VERTEX_STRIDE = 12;     // vec3 Position (3 * 4 bytes)
    private static final int BUFFER_BYTES = MAX_VERTICES * VERTEX_STRIDE;
    private static final double MAX_SHADOW_ENTITY_DIST_SQ =
            (RaindioPipelineSpec.SHADOW_DISTANCE * 0.75) * (RaindioPipelineSpec.SHADOW_DISTANCE * 0.75);

    private static final ColorTargetState RGBA8_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL);
    private static final DepthStencilState SHADOW_DEPTH_WRITE =
            new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true);

    private final RaindioShaderSource shaderSource = new RaindioShaderSource();
    private final ByteBuffer staging = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder());
    private final PoseStack poseStack = new PoseStack();
    private final ShadowQuadConsumer quadConsumer = new ShadowQuadConsumer();
    private final ShadowSubmitCollector submitCollector = new ShadowSubmitCollector();

    private CompiledRenderPipeline pipeline;
    private GpuBuffer vertexBuffer;
    private int vertexCount = 0;
    private boolean initialized = false;

    public void ensureInitialized() {
        if (initialized) return;
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return;

        BindGroupLayout set0 = BindGroupLayout.builder()
                .withUniform("FrameUniforms", UniformType.UNIFORM_BUFFER)
                .build();

        RenderPipeline desc = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "chunks/entity_shadow"))
                .withVertexShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "chunks/entity_shadow"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "chunks/entity_shadow"))
                .withBindGroupLayout(set0)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withCull(false)
                .withDepthStencilState(SHADOW_DEPTH_WRITE)
                .withColorTargetState(0, RGBA8_TARGET)
                .build();

        this.pipeline = device.compilePipeline(desc, shaderSource, DIRECT_EXECUTOR).join().finishCompile();
        this.vertexBuffer = device.createBuffer(
                () -> "raindio_entity_shadow_vbo",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                BUFFER_BYTES
        );
        this.initialized = true;
        RaindioClientMod.LOGGER.info("[Raindio] Compiled entity shadow Vulkan pipeline (players & mobs).");
    }

    /**
     * Collects animated 3D vertices for all visible entities and the local player (even in first person)
     * and uploads them to the Vulkan vertex buffer before the shadow RenderPass begins.
     */
    public void prepareAndUpload(
            CommandEncoder encoder,
            EntityRenderDispatcher dispatcher,
            LevelRenderState levelRenderState,
            List<EntityRenderState> capturedEntities,
            List<BlockEntityRenderState> capturedBlockEntities,
            CameraRenderState cameraState,
            Matrix4f shadowMV,
            float maxEntityShadowDist
    ) {
        this.vertexCount = 0;
        if (dispatcher == null || cameraState == null || cameraState.pos == null) return;
        ensureInitialized();
        if (!initialized || vertexBuffer == null) return;

        List<EntityRenderState> entityList = (capturedEntities != null && !capturedEntities.isEmpty())
                ? capturedEntities
                : (levelRenderState != null ? levelRenderState.entityRenderStates : null);
        List<BlockEntityRenderState> blockEntityList = (capturedBlockEntities != null && !capturedBlockEntities.isEmpty())
                ? capturedBlockEntities
                : (levelRenderState != null ? levelRenderState.blockEntityRenderStates : null);

        staging.clear();
        double camX = cameraState.pos.x;
        double camY = cameraState.pos.y;
        double camZ = cameraState.pos.z;
        double maxDist = Math.max(8.0, Math.min(RaindioPipelineSpec.SHADOW_DISTANCE, maxEntityShadowDist));
        double maxDistSq = maxDist * maxDist;

        // Sun orthographic frustum bounds (3.0m padding covers tall/wide mobs like iron golems & dragons)
        boolean hasShadowMV = (shadowMV != null);
        float d = RaindioPipelineSpec.SHADOW_DISTANCE;
        float xyLimit = d + 3.0f;
        float zFar = d * 2.5f;
        float m00 = hasShadowMV ? shadowMV.m00() : 1f, m10 = hasShadowMV ? shadowMV.m10() : 0f, m20 = hasShadowMV ? shadowMV.m20() : 0f, m30 = hasShadowMV ? shadowMV.m30() : 0f;
        float m01 = hasShadowMV ? shadowMV.m01() : 0f, m11 = hasShadowMV ? shadowMV.m11() : 1f, m21 = hasShadowMV ? shadowMV.m21() : 0f, m31 = hasShadowMV ? shadowMV.m31() : 0f;
        float m02 = hasShadowMV ? shadowMV.m02() : 0f, m12 = hasShadowMV ? shadowMV.m12() : 0f, m22 = hasShadowMV ? shadowMV.m22() : 1f, m32 = hasShadowMV ? shadowMV.m32() : 0f;

        // 1. All extracted world entities (animals, mobs, villagers, other players, third-person local player)
        if (entityList != null) {
            for (EntityRenderState state : entityList) {
                if (state == null || state.isInvisible) continue;
                if (vertexCount >= MAX_VERTICES - 6) break;

                double relX = state.x - camX;
                double relY = state.y - camY;
                double relZ = state.z - camZ;
                if (relX * relX + relY * relY + relZ * relZ > maxDistSq) continue;

                if (hasShadowMV) {
                    float fx = (float) relX;
                    float fy = (float) relY;
                    float fz = (float) relZ;
                    float lsX = m00 * fx + m10 * fy + m20 * fz + m30;
                    if (Math.abs(lsX) > xyLimit) continue;
                    float lsY = m01 * fx + m11 * fy + m21 * fz + m31;
                    if (Math.abs(lsY) > xyLimit) continue;
                    float depthSV = -(m02 * fx + m12 * fy + m22 * fz + m32);
                    if (depthSV + 3.0f < 0.0f || depthSV - 3.0f > zFar) continue;
                }

                submitSingleEntity(dispatcher, state, cameraState, relX, relY, relZ);
            }
        }

        // 2. First-person local player shadow (vanilla skips extracting local player in first-person)
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (cameraState.isFirstPerson && player != null && !player.isSpectator() && !player.isInvisible()
                && vertexCount < MAX_VERTICES - 6) {
            try {
                EntityRenderState playerState = dispatcher.extractEntity(player, cameraState.cameraEntityPartialTicks);
                if (playerState != null && !playerState.isInvisible) {
                    double relX = playerState.x - camX;
                    double relY = playerState.y - camY;
                    double relZ = playerState.z - camZ;
                    submitSingleEntity(dispatcher, playerState, cameraState, relX, relY, relZ);
                }
            } catch (Throwable ignored) {
                // Skip safely if player render state cannot be extracted on a transition tick
            }
        }

        // 3. Block Entities (chests, beds, signs, banners, bells, shulker boxes, etc.)
        BlockEntityRenderDispatcher beDispatcher = mc.getBlockEntityRenderDispatcher();
        if (beDispatcher != null && blockEntityList != null) {
            for (BlockEntityRenderState beState : blockEntityList) {
                if (beState == null || beState.blockPos == null) continue;
                if (vertexCount >= MAX_VERTICES - 6) break;
                BlockPos bp = beState.blockPos;
                double relX = bp.getX() - camX;
                double relY = bp.getY() - camY;
                double relZ = bp.getZ() - camZ;
                double cx = relX + 0.5, cy = relY + 0.5, cz = relZ + 0.5;
                if (cx * cx + cy * cy + cz * cz > maxDistSq) continue;
                if (hasShadowMV) {
                    float fx = (float) cx, fy = (float) cy, fz = (float) cz;
                    float lsX = m00 * fx + m10 * fy + m20 * fz + m30;
                    if (Math.abs(lsX) > xyLimit) continue;
                    float lsY = m01 * fx + m11 * fy + m21 * fz + m31;
                    if (Math.abs(lsY) > xyLimit) continue;
                    float depthSV = -(m02 * fx + m12 * fy + m22 * fz + m32);
                    if (depthSV + 3.0f < 0.0f || depthSV - 3.0f > zFar) continue;
                }
                try {
                    poseStack.pushPose();
                    poseStack.translate(relX, relY, relZ);
                    beDispatcher.submit(beState, poseStack, submitCollector, cameraState);
                    poseStack.popPose();
                } catch (Throwable ignored) {
                    while (!poseStack.isEmpty()) poseStack.popPose();
                }
            }
        }

        if (vertexCount > 0) {
            staging.position(0).limit(vertexCount * VERTEX_STRIDE);
            encoder.writeToBuffer(vertexBuffer.slice(0, vertexCount * (long) VERTEX_STRIDE), staging);
        }
    }

    private void submitSingleEntity(
            EntityRenderDispatcher dispatcher,
            EntityRenderState state,
            CameraRenderState cameraState,
            double relX,
            double relY,
            double relZ
    ) {
        try {
            poseStack.pushPose();
            dispatcher.submit(state, cameraState, relX, relY, relZ, poseStack, submitCollector);
            poseStack.popPose();
        } catch (Throwable ignored) {
            while (!poseStack.isEmpty()) {
                poseStack.popPose();
            }
        }
    }

    /** Draws all collected entity shadow triangles inside the active shadow RenderPass. */
    public void drawIntoShadowPass(RenderPass shadowPass, RaindioUniformManager uniforms) {
        if (!initialized || vertexCount <= 0 || pipeline == null || vertexBuffer == null) return;
        shadowPass.setPipeline(pipeline);
        shadowPass.setUniform("FrameUniforms", uniforms.frameBuffer());
        shadowPass.setVertexBuffer(0, vertexBuffer.slice(0, vertexCount * (long) VERTEX_STRIDE));
        shadowPass.draw(vertexCount, 1, 0, 0);
    }

    @Override
    public void close() {
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        shaderSource.close();
        initialized = false;
        vertexCount = 0;
    }

    private final class ShadowQuadConsumer implements VertexConsumer {
        private final float[] qx = new float[4];
        private final float[] qy = new float[4];
        private final float[] qz = new float[4];
        private int qi = 0;

        void resetQuad() {
            qi = 0;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            qx[qi] = x;
            qy[qi] = y;
            qz[qi] = z;
            qi++;
            if (qi == 4) {
                if (vertexCount + 6 <= MAX_VERTICES) {
                    putVert(0);
                    putVert(1);
                    putVert(2);
                    putVert(0);
                    putVert(2);
                    putVert(3);
                    vertexCount += 6;
                }
                qi = 0;
            }
            return this;
        }

        private void putVert(int idx) {
            staging.putFloat(qx[idx]);
            staging.putFloat(qy[idx]);
            staging.putFloat(qz[idx]);
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int argb) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    private final class ShadowSubmitCollector implements SubmitNodeCollector {
        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return this;
        }

        @Override
        public <S> void submitModel(
                Model<? super S> model,
                S state,
                PoseStack stack,
                RenderType renderType,
                int packedLight,
                int packedOverlay,
                int tintColor,
                UvMapping uvMapping,
                int outlineColor
        ) {
            if (model == null || stack == null || vertexCount >= MAX_VERTICES - 6) return;
            try {
                quadConsumer.resetQuad();
                model.setupAnim(state);
                model.renderToBuffer(stack, quadConsumer, 0xF000F0, OverlayTexture.NO_OVERLAY, -1);
            } catch (Throwable ignored) {
                quadConsumer.resetQuad();
            }
        }

        @Override public void submitShadow(PoseStack s, float r, List<EntityRenderState.ShadowPiece> p) {}
        @Override public void submitNameTag(PoseStack s, Vec3 o, int y, Component t, boolean st, int l, CameraRenderState c) {}
        @Override public void submitText(PoseStack s, float x, float y, FormattedCharSequence t, boolean sh, Font.DisplayMode m, int l, int c, int bg, int o) {}
        @Override public void submitTextBackground(PoseStack s, float x0, float y0, float x1, float y1, int bg, Font.DisplayMode m, int l) {}
        @Override public void submitFlame(PoseStack s, EntityRenderState st, Quaternionf q) {}
        @Override public void submitLeash(PoseStack s, EntityRenderState.LeashState l) {}
        @Override public <S> void submitCrumblingOverlay(Model<? super S> m, S st, PoseStack s, RenderType rt, int l, int o, int t, ModelFeatureRenderer.CrumblingOverlay co) {}
        @Override public void submitMovingBlock(PoseStack s, MovingBlockRenderState b, int l) {}
        @Override
        public void submitBlockModel(PoseStack s, RenderType rt, List<BlockStateModelPart> parts, int[] t, int l, int o, int oc) {
            if (s == null || parts == null || vertexCount >= MAX_VERTICES - 6) return;
            try {
                PoseStack.Pose pose = s.last();
                for (BlockStateModelPart part : parts) {
                    if (part == null) continue;
                    submitQuads(pose, part.getQuads(null));
                    for (Direction dir : Direction.values()) {
                        submitQuads(pose, part.getQuads(dir));
                    }
                }
            } catch (Throwable ignored) {
                quadConsumer.resetQuad();
            }
        }

        @Override public void submitBreakingBlockModel(PoseStack s, List<BlockStateModelPart> p, int stage, boolean cr) {}
        @Override public void submitShapeOutline(PoseStack s, VoxelShape sh, RenderType rt, int c, float w, boolean st) {}

        @Override
        public void submitItem(PoseStack s, ItemDisplayContext c, int l, int o, int oc, int[] t, ItemQuads q, ItemStackRenderState.FoilType f) {
            if (s == null || q == null || q.isEmpty() || vertexCount >= MAX_VERTICES - 6) return;
            try {
                submitQuads(s.last(), q.all());
            } catch (Throwable ignored) {
                quadConsumer.resetQuad();
            }
        }

        @Override
        public void submitCustomGeometry(PoseStack s, RenderType rt, SubmitNodeCollector.CustomGeometryRenderer r) {
            if (s == null || r == null || vertexCount >= MAX_VERTICES - 6) return;
            try {
                quadConsumer.resetQuad();
                r.render(s.last(), quadConsumer);
            } catch (Throwable ignored) {
                quadConsumer.resetQuad();
            }
        }

        private void submitQuads(PoseStack.Pose pose, List<BakedQuad> quads) {
            if (quads == null || quads.isEmpty()) return;
            for (BakedQuad quad : quads) {
                if (quad == null || vertexCount >= MAX_VERTICES - 6) break;
                quadConsumer.resetQuad();
                for (int i = 0; i < 4; i++) {
                    var p = quad.position(i);
                    quadConsumer.addVertex(pose, p.x(), p.y(), p.z());
                }
            }
        }
        @Override public void submitQuadParticleGroup(QuadParticleRenderState p) {}
        @Override public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group g, CameraRenderState c, boolean st) {}
    }
}
