package com.raindio.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.raindio.pipeline.RaindioPipelineSpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Packs and uploads Raindio's strict std140 UBOs:
 * - set=0, binding=0: FrameUniforms (608 bytes)
 * - set=0, binding=1: DrawUniforms  (336 bytes)
 * Byte offsets match src/main/shaders/common/raindio_uniforms.glsl 1:1.
 */
public final class RaindioUniformManager implements AutoCloseable {
    public static final int FRAME_UBO_SIZE = 608;
    public static final int DRAW_UBO_SIZE = 336;

    private static float halton(int index, int base) {
        float f = 1.0f;
        float r = 0.0f;
        int i = index;
        while (i > 0) {
            f /= (float) base;
            r += f * (float) (i % base);
            i /= base;
        }
        return r;
    }

    private GpuBuffer frameBuffer;
    private GpuBuffer drawBuffer;

    private final ByteBuffer frameStaging = ByteBuffer.allocateDirect(FRAME_UBO_SIZE).order(ByteOrder.nativeOrder());
    private final ByteBuffer drawStaging = ByteBuffer.allocateDirect(DRAW_UBO_SIZE).order(ByteOrder.nativeOrder());

    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4f modelViewInv = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f projectionInv = new Matrix4f();
    private final Matrix4f prevModelView = new Matrix4f();
    private final Matrix4f prevProjection = new Matrix4f();
    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();

    private final Vector3f cameraPos = new Vector3f();
    private final Vector3f prevCameraPos = new Vector3f();
    private final Vector3f sunDirWorld = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f sunPosView = new Vector3f(0.0f, 100.0f, 0.0f);

    private float smoothBlockLight = 0.0f;
    private float smoothSkyLight = 240.0f;
    private long startNanos = System.nanoTime();
    private int frameCounter = 0;
    private boolean hasPreviousFrame = false;
    private float prevJitterX = 0.0f;
    private float prevJitterY = 0.0f;

    public void ensureInitialized() {
        if (frameBuffer != null && !frameBuffer.isClosed()) {
            return;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }
        int usage = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;
        this.frameBuffer = device.createBuffer(() -> "raindio_frame_ubo", usage, FRAME_UBO_SIZE);
        this.drawBuffer = device.createBuffer(() -> "raindio_draw_ubo", usage, DRAW_UBO_SIZE);
        this.startNanos = System.nanoTime();
    }

    public void updateFrameUniforms(
            CommandEncoder encoder,
            CameraRenderState cameraState,
            int viewportWidth,
            int viewportHeight,
            int shadowResolution,
            boolean taaEnabled
    ) {
        updateFrameUniforms(encoder, cameraState, viewportWidth, viewportHeight, shadowResolution, taaEnabled, 1.0f, false);
    }

    public void updateFrameUniforms(
            CommandEncoder encoder,
            CameraRenderState cameraState,
            int viewportWidth,
            int viewportHeight,
            int shadowResolution,
            boolean taaEnabled,
            float renderScale,
            boolean taauActive
    ) {
        ensureInitialized();
        if (frameBuffer == null) return;

        // 1. Camera matrices (Vulkan standard: Z in [0, 1], Y-down clip space)
        if (cameraState.viewRotationMatrix != null) {
            modelView.set(cameraState.viewRotationMatrix);
        } else {
            modelView.identity();
        }
        modelView.invert(modelViewInv);

        if (cameraState.projectionMatrix != null) {
            projection.set(cameraState.projectionMatrix);
        } else {
            projection.identity();
        }
        projection.invert(projectionInv);

        if (cameraState.pos != null) {
            cameraPos.set((float) cameraState.pos.x, (float) cameraState.pos.y, (float) cameraState.pos.z);
        }

        if (!hasPreviousFrame) {
            prevModelView.set(modelView);
            prevProjection.set(projection);
            prevCameraPos.set(cameraPos);
            hasPreviousFrame = true;
        }

        // 2. World time, sun direction (with sunPathRotation = -30 deg), rain, eye brightness
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float rainStrength = 0.0f;
        float celestialAngle = 0.15f;

        if (level != null) {
            float partialTicks = cameraState.cameraEntityPartialTicks;
            rainStrength = level.getRainLevel(partialTicks);
            long dayTime = level.getOverworldClockTime() % 24000L;
            celestialAngle = ((dayTime + partialTicks) / 24000.0f) - 0.25f;
            if (celestialAngle < 0.0f) celestialAngle += 1.0f;

            BlockPos eyeBlock = cameraState.blockPos != null ? cameraState.blockPos : BlockPos.ZERO;
            float targetBlock = level.getBrightness(LightLayer.BLOCK, eyeBlock) * 16.0f;
            float targetSky = level.getBrightness(LightLayer.SKY, eyeBlock) * 16.0f;
            smoothBlockLight += (targetBlock - smoothBlockLight) * 0.08f;
            smoothSkyLight += (targetSky - smoothSkyLight) * 0.08f;
        }

        computeSunAndShadowMatrices(celestialAngle, shadowResolution);

        int isEyeInWater = 0;
        if (cameraState.fogType == FogType.WATER) {
            isEyeInWater = 1;
        } else if (cameraState.fogType == FogType.LAVA) {
            isEyeInWater = 2;
        }

        float frameTimeCounter = (float) ((System.nanoTime() - startNanos) * 1.0e-9);
        float far = cameraState.depthFar > 0.0f ? cameraState.depthFar : 256.0f;

        // Compute adaptive-period Halton(2, 3) NDC sub-pixel jitter scaled strictly to internal render resolution
        int curFrame = frameCounter++;
        float curJitterX = 0.0f;
        float curJitterY = 0.0f;
        if (taaEnabled && viewportWidth > 0 && viewportHeight > 0) {
            float safeScale = Math.max(0.50f, Math.min(1.00f, renderScale));
            int jitterPeriod = (taauActive && safeScale < 0.999f)
                    ? Math.max(8, Math.min(32, (int) Math.ceil(8.0 / (safeScale * safeScale))))
                    : 8;
            int hIdx = (curFrame % jitterPeriod) + 1;
            curJitterX = (halton(hIdx, 2) - 0.5f) * 2.0f / (float) viewportWidth;
            curJitterY = (halton(hIdx, 3) - 0.5f) * 2.0f / (float) viewportHeight;
        }

        // 3. Pack strict std140 FrameUniforms (608 bytes)
        frameStaging.clear();
        putMat4(frameStaging, 0, modelView);
        putMat4(frameStaging, 64, modelViewInv);
        putMat4(frameStaging, 128, projection);
        putMat4(frameStaging, 192, projectionInv);
        putMat4(frameStaging, 256, prevModelView);
        putMat4(frameStaging, 320, prevProjection);
        putMat4(frameStaging, 384, shadowModelView);
        putMat4(frameStaging, 448, shadowProjection);

        // Offset 512: cameraPositionAndFar
        putVec4(frameStaging, 512, cameraPos.x, cameraPos.y, cameraPos.z, far);
        // Offset 528: prevCameraPositionAndTime
        putVec4(frameStaging, 528, prevCameraPos.x, prevCameraPos.y, prevCameraPos.z, frameTimeCounter);
        // Offset 544: sunPositionAndRain
        putVec4(frameStaging, 544, sunPosView.x, sunPosView.y, sunPosView.z, rainStrength);
        // Offset 560: viewportAndWaterState
        putVec4(frameStaging, 560, (float) viewportWidth, (float) viewportHeight, (float) isEyeInWater, 0.0f);
        // Offset 576: eyeBrightnessAndPad (ivec4: xy=eyeBrightnessSmooth, z=frameCounter, w=taaEnabled)
        frameStaging.putInt(576, Math.round(smoothBlockLight));
        frameStaging.putInt(580, Math.round(smoothSkyLight));
        frameStaging.putInt(584, curFrame);
        frameStaging.putInt(588, taaEnabled ? 1 : 0);
        // Offset 592: taaJitterAndPrev (vec4: xy=curJitterNDC, zw=prevJitterNDC)
        putVec4(frameStaging, 592, curJitterX, curJitterY, prevJitterX, prevJitterY);

        frameStaging.position(0).limit(FRAME_UBO_SIZE);
        encoder.writeToBuffer(frameBuffer.slice(), frameStaging);

        // Store current frame as previous frame for next tick's temporal reprojection
        prevModelView.set(modelView);
        prevProjection.set(projection);
        prevCameraPos.set(cameraPos);
        prevJitterX = curJitterX;
        prevJitterY = curJitterY;
    }

    public void updateDrawUniforms(
            CommandEncoder encoder,
            Matrix4f drawModelView,
            Matrix4f drawProjection,
            Matrix4f texMat0,
            Matrix4f texMat1,
            Vector4f entityColor
    ) {
        ensureInitialized();
        if (drawBuffer == null) return;

        Matrix4f normalMat4 = new Matrix4f(drawModelView).invert().transpose();

        drawStaging.clear();
        putMat4(drawStaging, 0, drawModelView);
        putMat4(drawStaging, 64, drawProjection);
        putMat4(drawStaging, 128, normalMat4);
        putMat4(drawStaging, 192, texMat0);
        putMat4(drawStaging, 256, texMat1);
        putVec4(drawStaging, 320, entityColor.x, entityColor.y, entityColor.z, entityColor.w);

        drawStaging.position(0).limit(DRAW_UBO_SIZE);
        encoder.writeToBuffer(drawBuffer.slice(), drawStaging);
    }

    private void computeSunAndShadowMatrices(float celestialAngle, int shadowResolution) {
        double angleRad = celestialAngle * Math.PI * 2.0;
        double tiltRad = Math.toRadians(RaindioPipelineSpec.SUN_PATH_ROTATION_DEG);

        float sy = (float) Math.cos(angleRad);
        float sx = (float) -Math.sin(angleRad);
        float sz = (float) (sy * Math.sin(tiltRad));
        sy = (float) (sy * Math.cos(tiltRad));

        sunDirWorld.set(sx, sy, sz).normalize();

        // View-space sunPosition (matching Iris convention: scaled by 100.0)
        Vector4f sunV = new Vector4f(sunDirWorld.x, sunDirWorld.y, sunDirWorld.z, 0.0f).mul(modelView);
        sunPosView.set(sunV.x, sunV.y, sunV.z).normalize().mul(100.0f);

        // Shadow orthographic camera centered on player with Vulkan [0, 1] Z depth (zZeroToOne = true).
        // Eye sits 1.25*d toward the sun with ortho Z in [0, 2.5*d] (300m total: 150m toward sun, 150m past player).
        float d = RaindioPipelineSpec.SHADOW_DISTANCE;
        Vector3f lightDir = sunDirWorld.y >= 0.0f ? sunDirWorld : new Vector3f(sunDirWorld).negate();
        Vector3f eye = new Vector3f(lightDir).mul(d * 1.25f);
        shadowModelView.setLookAt(eye.x, eye.y, eye.z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        // Fine near-field texel snapping using the actual configured shadowResolution (1024/2048/4096)
        // scaled by the near-field radial distortion factor (0.2) so shadows never jump 10 texels.
        float safeRes = Math.max(512.0f, (float) shadowResolution);
        float snapTexelWorld = (2.0f * d * 0.2f) / safeRes;
        Vector4f camLS = new Vector4f(cameraPos.x, cameraPos.y, cameraPos.z, 0.0f).mul(shadowModelView);
        float snapX = (float) Math.floor(camLS.x / snapTexelWorld) * snapTexelWorld - camLS.x;
        float snapY = (float) Math.floor(camLS.y / snapTexelWorld) * snapTexelWorld - camLS.y;
        shadowModelView.m30(shadowModelView.m30() - snapX);
        shadowModelView.m31(shadowModelView.m31() - snapY);

        shadowProjection.setOrtho(-d, d, -d, d, 0.0f, d * 2.5f, true);
        // Flip Y for Vulkan Y-down clip space (matching gbufferProjection & raindio_uniforms.glsl)
        shadowProjection.m11(-shadowProjection.m11());
    }

    private static void putMat4(ByteBuffer buf, int offset, Matrix4f mat) {
        mat.get(offset, buf);
    }

    private static void putVec4(ByteBuffer buf, int offset, float x, float y, float z, float w) {
        buf.putFloat(offset, x);
        buf.putFloat(offset + 4, y);
        buf.putFloat(offset + 8, z);
        buf.putFloat(offset + 12, w);
    }

    public GpuBuffer frameBuffer() { return frameBuffer; }
    public GpuBuffer drawBuffer() { return drawBuffer; }
    public Matrix4f shadowModelView() { return shadowModelView; }
    public Matrix4f shadowProjection() { return shadowProjection; }
    public int currentFrameCounter() { return frameCounter; }

    @Override
    public void close() {
        if (frameBuffer != null) {
            frameBuffer.close();
            frameBuffer = null;
        }
        if (drawBuffer != null) {
            drawBuffer.close();
            drawBuffer = null;
        }
        hasPreviousFrame = false;
    }
}
