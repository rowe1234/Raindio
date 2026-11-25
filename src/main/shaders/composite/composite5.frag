#version 450
#include "common/raindio_uniforms.glsl"

/* DRAWBUFFERS:0 */

#include "lib/common.glsl"

layout(location = 0) in vec2 texCoord;

layout(set = 0, binding = 1) uniform sampler2D colortex0;
layout(set = 0, binding = 2) uniform sampler2D colortex3;
layout(set = 0, binding = 3) uniform sampler2D colortex4;
layout(set = 0, binding = 4) uniform sampler2D autoExposureTex;


#define AUTO_EXPOSURE
#ifndef EXPOSURE
#define EXPOSURE 0.95         // [0.5 0.7 0.9 0.95 1.0 1.15 1.3 1.5 1.8 2.0 2.5]
#endif
#ifndef BLOOM_STRENGTH
#define BLOOM_STRENGTH 0.016  // [0.0 0.01 0.016 0.024 0.03 0.05 0.08 0.1]
#endif

layout(location = 0) out vec4 fragData0;

const mat3 AGX_INSET = mat3(
    0.842479062253094,  0.0423282422610123, 0.0423756549057051,
    0.0784335999999992, 0.878468636469772,  0.0784336,
    0.0792237451477643, 0.0791661274605434, 0.879142973793104
);

const mat3 AGX_OUTSET = mat3(
     1.19687900512017,   -0.0528968517574562, -0.0529716355144438,
    -0.0980208811401368,  1.15190312990417,   -0.0980434501171241,
    -0.0990297440797205, -0.0989611768448433,  1.15107367264116
);

// True Troy Sobotka / Blender AgX Filmic Tone Mapping (Log2 + 6th-order sigmoid)
// Preserves vivid foliage/sky hues while rolling off bright sunlight/torches smoothly without clipping.
vec3 agxDefaultContrastApprox(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return + 15.5     * x4 * x2
           - 40.14    * x4 * x
           + 31.96    * x4
           - 6.868    * x2 * x
           + 0.4298   * x2
           + 0.1191   * x
           - 0.00232;
}

vec3 AgXFilmic(vec3 x) {
    const float minEv = -12.47393;
    const float maxEv =   4.026069;
    vec3 v = AGX_INSET * max(x, vec3(0.0));
    v = clamp(log2(max(v, vec3(1e-10))), minEv, maxEv);
    v = (v - minEv) / (maxEv - minEv);
    v = agxDefaultContrastApprox(v);
    v = AGX_OUTSET * v;
    return clamp(v, 0.0, 1.0);
}

void main() {
    vec2 uv = texCoord;
    vec3 hdr = texture(colortex0, uv).rgb;
#if DEBUG_VIEW == 14
    vec3 dbgBloom = (texture(colortex3, uv).rgb + texture(colortex4, uv).rgb) * BLOOM_STRENGTH;
    fragData0 = vec4(clamp(dbgBloom * 2.5, 0.0, 1.0), 1.0);
    return;
#elif DEBUG_VIEW == 10
    vec3 dbgOut = clamp(hdr, 0.0, 1.0);
    // Live GPU Auto-Exposure HUD meter in top-left corner (DEBUG_VIEW 10 only):
    //   Top bar (cyan)  = current adaptedExp in [0.32..3.80]
    //   Bot bar (amber) = instant targetExp in [0.32..3.80]
    //   Dot (green/red) = GPU histogram active vs fallback
    if (uv.x >= 0.02 && uv.x <= 0.28 && uv.y >= 0.02 && uv.y <= 0.075) {
        vec4 expInfo = texelFetch(autoExposureTex, ivec2(0, 0), 0);
        float aNorm = clamp((expInfo.r - 0.32) / (3.80 - 0.32), 0.0, 1.0);
        float tNorm = clamp((expInfo.b - 0.32) / (3.80 - 0.32), 0.0, 1.0);
        float bx = (uv.x - 0.03) / 0.21;
        dbgOut = vec3(0.06, 0.07, 0.09);
        if (uv.x >= 0.25 && uv.x <= 0.27 && uv.y >= 0.032 && uv.y <= 0.063) {
            dbgOut = (expInfo.r > 0.01 && !isnan(expInfo.r)) ? vec3(0.15, 0.95, 0.30) : vec3(0.95, 0.15, 0.15);
        } else if (bx >= 0.0 && bx <= 1.0) {
            if (uv.y >= 0.028 && uv.y <= 0.044 && bx <= aNorm) dbgOut = vec3(0.20, 0.90, 1.00);
            if (uv.y >= 0.050 && uv.y <= 0.066 && bx <= tNorm) dbgOut = vec3(1.00, 0.68, 0.15);
        }
    }
    fragData0 = vec4(dbgOut, 1.0);
    return;
#elif DEBUG_VIEW > 0
    fragData0 = vec4(clamp(hdr, 0.0, 1.0), 1.0);
    return;
#endif

    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float sunHeight = realSunDir.y;
    vec3 sunColor = mix(vec3(1.0, 0.4, 0.05), vec3(1.0, 0.98, 0.85), saturate(sunHeight * 2.5));

    // GPU Luminance Histogram Auto-Exposure with graceful fallback to eyeBrightnessSmooth
#ifdef AUTO_EXPOSURE
    float autoExp = texelFetch(autoExposureTex, ivec2(0, 0), 0).r;
    float exposure;
    if (autoExp > 0.01 && !isnan(autoExp) && !isinf(autoExp)) {
        exposure = autoExp * EXPOSURE * mix(1.0, 0.78, saturate(rainStrength));
    } else {
        float rainDarken = mix(1.0, 0.58, saturate(rainStrength));
        float eyeSky = clamp(float(eyeBrightnessSmooth.y) / 240.0, 0.0, 1.0);
        float eyeBlock = clamp(float(eyeBrightnessSmooth.x) / 240.0, 0.0, 1.0);
        float dayBlend = smoothstep(-0.15, 0.25, sunHeight);
        float outdoorExp = mix(1.45, 1.05, dayBlend);
        float indoorExp = mix(1.75, 1.25, smoothstep(0.15, 0.75, eyeBlock));
        float baseExposure = mix(indoorExp, outdoorExp, smoothstep(0.05, 0.85, eyeSky));
        exposure = baseExposure * EXPOSURE * rainDarken;
    }
#else
    float exposure = 1.85 * EXPOSURE * mix(1.0, 0.58, saturate(rainStrength));
#endif

    // Smooth 4-tap tent upsample of quarter-resolution bloomTight (colortex3) + bloomWide (colortex4)
    // Eliminates bilinear diamond/blocky halos when upscaling 1/4-res bloom buffers
    vec2 bloomTexel = vec2(1.0) / vec2(max(textureSize(colortex3, 0), ivec2(1)));
    vec2 bOff = bloomTexel * 0.65;
    vec3 tightBloom = (
        texture(colortex3, uv + vec2(-bOff.x, -bOff.y)).rgb +
        texture(colortex3, uv + vec2( bOff.x, -bOff.y)).rgb +
        texture(colortex3, uv + vec2(-bOff.x,  bOff.y)).rgb +
        texture(colortex3, uv + vec2( bOff.x,  bOff.y)).rgb
    ) * 0.25;
    vec3 wideBloom = (
        texture(colortex4, uv + vec2(-bOff.x, -bOff.y)).rgb +
        texture(colortex4, uv + vec2( bOff.x, -bOff.y)).rgb +
        texture(colortex4, uv + vec2(-bOff.x,  bOff.y)).rgb +
        texture(colortex4, uv + vec2( bOff.x,  bOff.y)).rgb
    ) * 0.25;
    vec3 bloom = tightBloom + wideBloom;
    hdr += bloom * BLOOM_STRENGTH;

    // AgX filmic tone mapping + clean anime contrast
    vec3 result = AgXFilmic(max(hdr, vec3(0.0)) * exposure);
    result = pow(max(result, vec3(0.0)), vec3(1.05));

    // Crisp Shinkai anime split-toning: clean cerulean-blue shadows & luminous warm-clear highlights
    float luma = luminance(result);
    vec3 shadowTint = vec3(0.90, 0.96, 1.07);
    vec3 highlightTint = vec3(1.02, 1.00, 0.97);
    result *= mix(shadowTint, highlightTint, smoothstep(0.14, 0.74, luma));

    // Ultra-subtle vignette for a clean anime frame
    vec2 centered = uv - 0.5;
    float vignette = 1.0 - dot(centered, centered) * 0.08;
    result *= clamp(vignette, 0.92, 1.0);

    result += (interleavedGradientNoise(gl_FragCoord.xy) - 0.5) / 255.0;
    result = clamp(result, 0.0, 1.0);

    fragData0 = vec4(result, 1.0);
}
