#ifndef RAINDIO_UNIFORMS_GLSL
#define RAINDIO_UNIFORMS_GLSL

// =============================================================================
// Raindio Vulkan / MoltenVK Uniform Buffer Layouts (GLSL 450, strict std140)
// Coordinate convention:
//   - Vulkan clip space: X in [-1, 1], Y down (projection[1][1] negated on CPU), Z in [0, 1].
//   - Screen UV (0, 0) = top-left, (1, 1) = bottom-right -> ndc.xy = uv * 2.0 - 1.0.
//   - Depth textures (depthtex0, depthtex1, shadowtex0) store Vulkan NDC Z in [0, 1].
// =============================================================================

layout(set = 0, binding = 0, std140) uniform FrameUniforms {
    mat4 gbufferModelView;
    mat4 gbufferModelViewInverse;
    mat4 gbufferProjection;
    mat4 gbufferProjectionInverse;
    mat4 gbufferPreviousModelView;
    mat4 gbufferPreviousProjection;
    mat4 shadowModelView;
    mat4 shadowProjection;

    vec4 cameraPositionAndFar;          // xyz: cameraPosition, w: far
    vec4 prevCameraPositionAndTime;     // xyz: previousCameraPosition, w: frameTimeCounter
    vec4 sunPositionAndRain;            // xyz: sunPosition (view-space), w: rainStrength
    vec4 viewportAndWaterState;         // x: viewWidth, y: viewHeight, z: float(isEyeInWater), w: pad
    ivec4 eyeBrightnessAndPad;          // xy: eyeBrightnessSmooth, z: frameCounter, w: taaEnabled
    vec4 taaJitterAndPrev;              // xy: current 8-tap Halton NDC jitter, zw: previous frame NDC jitter
} uFrame;

#define gbufferModelView          uFrame.gbufferModelView
#define gbufferModelViewInverse   uFrame.gbufferModelViewInverse
#define gbufferProjection         uFrame.gbufferProjection
#define gbufferProjectionInverse  uFrame.gbufferProjectionInverse
#define gbufferPreviousModelView  uFrame.gbufferPreviousModelView
#define gbufferPreviousProjection uFrame.gbufferPreviousProjection
#define shadowModelView           uFrame.shadowModelView
#define shadowProjection          uFrame.shadowProjection

#define cameraPosition            uFrame.cameraPositionAndFar.xyz
#define far                       uFrame.cameraPositionAndFar.w
#define previousCameraPosition    uFrame.prevCameraPositionAndTime.xyz
#define frameTimeCounter          uFrame.prevCameraPositionAndTime.w
#define sunPosition               uFrame.sunPositionAndRain.xyz
#define rainStrength              uFrame.sunPositionAndRain.w
#define viewWidth                 uFrame.viewportAndWaterState.x
#define viewHeight                uFrame.viewportAndWaterState.y
#define isEyeInWater              int(uFrame.viewportAndWaterState.z + 0.5)
#define eyeBrightnessSmooth       uFrame.eyeBrightnessAndPad.xy
#define frameCounter              uFrame.eyeBrightnessAndPad.z
#define taaEnabledUniform         uFrame.eyeBrightnessAndPad.w
#define taaJitter                 uFrame.taaJitterAndPrev.xy
#define prevTaaJitter             uFrame.taaJitterAndPrev.zw

#ifdef RAINDIO_HAS_DRAW_UBO
layout(set = 0, binding = 1, std140) uniform DrawUniforms {
    mat4 modelViewMatrix;
    mat4 projectionMatrix;
    mat4 normalMatrix4;     // upper-left 3x3 is normalMatrix (mat4 avoids std140 mat3 padding traps)
    mat4 textureMatrix0;
    mat4 textureMatrix1;
    vec4 entityColor;
} uDraw;

#define modelViewMatrix           uDraw.modelViewMatrix
#define projectionMatrix          uDraw.projectionMatrix
#define normalMatrix              mat3(uDraw.normalMatrix4)
#define textureMatrix0            uDraw.textureMatrix0
#define textureMatrix1            uDraw.textureMatrix1
#define entityColor               uDraw.entityColor
#endif

#endif // RAINDIO_UNIFORMS_GLSL
