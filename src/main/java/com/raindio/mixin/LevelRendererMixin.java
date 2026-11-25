package com.raindio.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.raindio.pipeline.RaindioFrameController;
import com.raindio.pipeline.RaindioPipelineSpec;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Minecraft 26.3's LevelRenderer lifecycle:
 * - render HEAD: snapshot entityRenderStates & blockEntityRenderStates before vanilla submitFeatures() clears them,
 *   upload FrameUniforms, allocate/clear Vulkan attachments
 * - addMainPass HEAD: capture the frame's ChunkSectionsToRender for terrain/water G-Buffers
 * - render RETURN: build an independent shadow-pass ChunkSectionsToRender from ViewArea.sections
 *   (bypassing main-camera frustum & cave occlusion culling so overhead/behind mountains always cast shadows),
 *   then dispatch shadow -> terrain -> entity G-Buffer -> depth snapshot -> translucent -> water -> composite0..5 -> final.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    @Final
    private GameRenderer gameRenderer;

    @Shadow
    @Final
    private EntityRenderDispatcher entityRenderDispatcher;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Shadow
    private ViewArea viewArea;

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    public abstract ChunkSectionsToRender prepareChunkRenders(Matrix4fc modelView, boolean sortTranslucent);

    @Shadow
    public abstract ChunkSectionsToRender prepareChunkRendersIndirect(Matrix4fc modelView, boolean sortTranslucent);

    @Unique
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> raindio$savedVisibleSections =
            new ObjectArrayList<>();

    @Unique
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> raindio$shadowSections =
            new ObjectArrayList<>();

    @Unique
    private final ObjectArrayList<EntityRenderState> raindio$capturedEntities =
            new ObjectArrayList<>();

    @Unique
    private final ObjectArrayList<BlockEntityRenderState> raindio$capturedBlockEntities =
            new ObjectArrayList<>();

    @Inject(method = "render", at = @At("HEAD"))
    private void raindio$onRenderHead(
            GraphicsResourceAllocator allocator,
            boolean renderBlockOutline,
            CameraRenderState cameraState,
            GpuBufferSlice fogBuffer,
            Vector4f fogColor,
            boolean shouldRenderSky,
            boolean renderChunkDebug,
            CallbackInfo ci
    ) {
        // Vanilla LevelRenderer.submitFeatures() clears levelRenderState.entityRenderStates and
        // blockEntityRenderStates mid-frame, so snapshot both lists at HEAD before they are wiped.
        raindio$capturedEntities.clear();
        raindio$capturedBlockEntities.clear();
        if (RaindioFrameController.INSTANCE.isPipelineEnabled() && levelRenderState != null) {
            if (levelRenderState.entityRenderStates != null && !levelRenderState.entityRenderStates.isEmpty()) {
                raindio$capturedEntities.addAll(levelRenderState.entityRenderStates);
            }
            if (levelRenderState.blockEntityRenderStates != null && !levelRenderState.blockEntityRenderStates.isEmpty()) {
                raindio$capturedBlockEntities.addAll(levelRenderState.blockEntityRenderStates);
            }
        }

        RenderTarget mainTarget = gameRenderer != null ? gameRenderer.mainRenderTarget() : null;
        RaindioFrameController.INSTANCE.onLevelRenderBegin(cameraState, mainTarget);
    }

    @Inject(method = "addMainPass", at = @At("HEAD"))
    private void raindio$onAddMainPass(
            FrameGraphBuilder frameGraph,
            FeatureRenderDispatcher.PreparedFrame preparedFrame,
            GpuBufferSlice fogBuffer,
            ChunkSectionsToRender chunkSections,
            boolean renderBlockOutline,
            CallbackInfo ci
    ) {
        RaindioFrameController.INSTANCE.setCurrentChunkSections(chunkSections);
    }

    /**
     * Prevents async CPU translucent quad re-sorting from dropping water sections while moving
     * when vanilla Improved Transparency (OIT) is turned OFF (Raindio uses hardware Z-buffer).
     */
    @ModifyVariable(method = "prepareChunkRendersIndirect", at = @At("HEAD"), argsOnly = true, index = 2)
    private boolean raindio$disableTranslucentSortIndirect(boolean sortTranslucent) {
        return RaindioFrameController.INSTANCE.isPipelineEnabled() ? false : sortTranslucent;
    }

    @ModifyVariable(method = "prepareChunkRenders", at = @At("HEAD"), argsOnly = true, index = 2)
    private boolean raindio$disableTranslucentSortSeparate(boolean sortTranslucent) {
        return RaindioFrameController.INSTANCE.isPipelineEnabled() ? false : sortTranslucent;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void raindio$onRenderReturn(
            GraphicsResourceAllocator allocator,
            boolean renderBlockOutline,
            CameraRenderState cameraState,
            GpuBufferSlice fogBuffer,
            Vector4f fogColor,
            boolean shouldRenderSky,
            boolean renderChunkDebug,
            CallbackInfo ci
    ) {
        try {
            RenderTarget mainTarget = gameRenderer != null ? gameRenderer.mainRenderTarget() : null;
            ChunkSectionsToRender shadowSections = RaindioFrameController.INSTANCE.isPipelineEnabled()
                    ? raindio$buildShadowChunkSections(cameraState)
                    : null;
            RaindioFrameController.INSTANCE.onLevelRenderEnd(
                    mainTarget,
                    entityRenderDispatcher,
                    levelRenderState,
                    raindio$capturedEntities,
                    raindio$capturedBlockEntities,
                    cameraState,
                    shadowSections
            );
        } finally {
            raindio$capturedEntities.clear();
            raindio$capturedBlockEntities.clear();
        }
    }

    /**
     * Builds an independent ChunkSectionsToRender for the shadow pass directly from ViewArea.sections
     * within SHADOW_DISTANCE & the sun's orthographic frustum, bypassing main-camera frustum and cave
     * occlusion culling so overhead cave ceilings and behind-player mountains never pop out of shadowtex0.
     */
    @Unique
    private ChunkSectionsToRender raindio$buildShadowChunkSections(CameraRenderState cameraState) {
        if (viewArea == null || visibleSections == null || cameraState == null || cameraState.pos == null) {
            return null;
        }
        RotatingSectionStorage<SectionRenderDispatcher.RenderSection> storage =
                ((ViewAreaAccessor) viewArea).raindio$getSections();
        if (storage == null) return null;

        Matrix4f shadowMV = RaindioFrameController.INSTANCE.uniforms().shadowModelView();
        float d = RaindioPipelineSpec.SHADOW_DISTANCE;
        float zFar = d * 2.5f; // 300.0m ortho Z span matching shadowProjection
        double maxDist = d + 14.0; // 16x16x16 half-diagonal ~= 13.86m
        double maxDistSq = maxDist * maxDist;

        double camX = cameraState.pos.x;
        double camY = cameraState.pos.y;
        double camZ = cameraState.pos.z;

        // Extract all 3 rows of shadowModelView (JOML column-major) + exact 16x16x16 AABB projection half-extents
        float m00 = shadowMV.m00(), m10 = shadowMV.m10(), m20 = shadowMV.m20(), m30 = shadowMV.m30();
        float m01 = shadowMV.m01(), m11 = shadowMV.m11(), m21 = shadowMV.m21(), m31 = shadowMV.m31();
        float m02 = shadowMV.m02(), m12 = shadowMV.m12(), m22 = shadowMV.m22(), m32 = shadowMV.m32();
        float extX = (Math.abs(m00) + Math.abs(m10) + Math.abs(m20)) * 8.0f;
        float extY = (Math.abs(m01) + Math.abs(m11) + Math.abs(m21)) * 8.0f;
        float extZ = (Math.abs(m02) + Math.abs(m12) + Math.abs(m22)) * 8.0f;

        raindio$shadowSections.clear();
        for (SectionRenderDispatcher.RenderSection section : storage) {
            if (section == null) continue;
            SectionMesh mesh = section.getSectionMesh();
            if (mesh == null || !mesh.hasRenderableLayers()) continue;

            BlockPos origin = section.getRenderOrigin();
            double dx = (origin.getX() + 8.0) - camX;
            double dy = (origin.getY() + 8.0) - camY;
            double dz = (origin.getZ() + 8.0) - camZ;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

            // Exact 3-axis sun orthographic frustum AABB test (X, Y, and [0, 300m] Z depth)
            float fx = (float) dx;
            float fy = (float) dy;
            float fz = (float) dz;
            float lsX = m00 * fx + m10 * fy + m20 * fz + m30;
            if (Math.abs(lsX) - extX > d) continue;
            float lsY = m01 * fx + m11 * fy + m21 * fz + m31;
            if (Math.abs(lsY) - extY > d) continue;
            float depthSV = -(m02 * fx + m12 * fy + m22 * fz + m32);
            if (depthSV + extZ < 0.0f || depthSV - extZ > zFar) continue;

            raindio$shadowSections.add(section);
        }

        if (raindio$shadowSections.isEmpty()) {
            return null;
        }

        raindio$savedVisibleSections.clear();
        raindio$savedVisibleSections.addAll(visibleSections);
        try {
            visibleSections.clear();
            visibleSections.addAll(raindio$shadowSections);
            boolean useIndirect = levelRenderState != null && levelRenderState.shouldUseMultiDrawIndirectForTerrain;
            return useIndirect
                    ? prepareChunkRendersIndirect(shadowMV, false)
                    : prepareChunkRenders(shadowMV, false);
        } catch (Throwable ignored) {
            return null;
        } finally {
            visibleSections.clear();
            visibleSections.addAll(raindio$savedVisibleSections);
            raindio$savedVisibleSections.clear();
            raindio$shadowSections.clear();
        }
    }

    @Inject(method = "resize", at = @At("TAIL"))
    private void raindio$onResize(int width, int height, CallbackInfo ci) {
        RaindioFrameController.INSTANCE.onResize(width, height);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void raindio$onClose(CallbackInfo ci) {
        RaindioFrameController.INSTANCE.onClose();
    }
}
