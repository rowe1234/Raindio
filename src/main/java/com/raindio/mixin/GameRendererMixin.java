package com.raindio.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.raindio.pipeline.RaindioFrameController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.level.GameType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts GameRenderer.renderItemInHand() when Raindio shaders are enabled so the first-person
 * hand and held items are rendered through Raindio's native Vulkan PBR + Shadow + AgX pipeline
 * instead of unshaded vanilla rendering.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Shadow
    @Final
    public FirstPersonHandsAndItemsRenderer firstPersonHandsAndItemsRenderer;

    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @Shadow
    @Final
    private Projection hudProjection;

    @Shadow
    protected abstract void bobHurt(CameraRenderState cameraState, PoseStack poseStack);

    @Shadow
    protected abstract void bobView(CameraRenderState cameraState, PoseStack poseStack);

    @Unique
    private final Matrix4f raindio$handProj = new Matrix4f();

    @Unique
    private final Matrix4f raindio$invViewRot = new Matrix4f();

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void raindio$onRenderItemInHand(
            CameraRenderState cameraState,
            PlayerRenderState playerState,
            GpuTextureView depthView,
            CallbackInfo ci
    ) {
        if (!RaindioFrameController.INSTANCE.isPipelineEnabled()) {
            return;
        }
        if (cameraState == null || playerState == null || cameraState.isPanoramicMode) {
            return;
        }
        if (!playerState.hasPlayer
                || !gameRenderState.optionsRenderState.cameraType.isFirstPerson()
                || cameraState.entityRenderState.isSleeping
                || gameRenderState.guiRenderState.isHudHidden
                || (minecraft.gameMode != null && minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR)) {
            return;
        }

        PoseStack poseStack = new PoseStack();
        poseStack.pushPose();
        if (cameraState.viewRotationMatrix != null) {
            cameraState.viewRotationMatrix.invert(raindio$invViewRot);
            poseStack.mulPose(raindio$invViewRot);
        }
        bobHurt(cameraState, poseStack);
        if (gameRenderState.optionsRenderState.bobView) {
            bobView(cameraState, poseStack);
        }

        hudProjection.getMatrix(raindio$handProj);

        boolean rendered = RaindioFrameController.INSTANCE.renderFirstPersonHand(
                firstPersonHandsAndItemsRenderer,
                cameraState,
                playerState,
                poseStack,
                raindio$handProj,
                mainRenderTarget,
                depthView
        );
        poseStack.popPose();

        if (rendered) {
            ci.cancel();
        }
    }
}
