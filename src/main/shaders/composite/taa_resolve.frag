#version 450
#include "common/raindio_uniforms.glsl"

// ============================================================================
// Raindio HDR Motion-Vector TAA / TAAU (Temporal Anti-Aliasing Upscaling)
//   - Input colortex0 / depthtex0 at internal render resolution (renderW x renderH)
//   - Input/Output taaHistory at native display resolution (displayW x displayH)
//   - Works seamlessly at renderScale < 1.0 (TAAU) and renderScale == 1.0 (native HDR-TAA)
//   - Reversible Karis/Reinhard HDR compression c / (1 + lum(c)) with safe 0.015 inverse floor
// ============================================================================

#include "lib/common.glsl"
#include "lib/projection.glsl"

layout(location = 0) in vec2 texCoord;

layout(set = 0, binding = 1) uniform sampler2D colortex0;
layout(set = 0, binding = 2) uniform sampler2D taaHistory;
layout(set = 0, binding = 3) uniform sampler2D depthtex0;

layout(location = 0) out vec4 fragColor;

vec3 compressHDR(vec3 c) {
    vec3 safe = max(c, vec3(0.0));
    return safe / (1.0 + luminance(safe));
}

vec3 decompressHDR(vec3 c) {
    vec3 safe = max(c, vec3(0.0));
    return safe / max(1.0 - luminance(safe), 0.015);
}

vec3 rgbToYCoCg(vec3 c) {
    float y  =  0.25 * c.r + 0.50 * c.g + 0.25 * c.b;
    float co =  0.50 * c.r              - 0.50 * c.b;
    float cg = -0.25 * c.r + 0.50 * c.g - 0.25 * c.b;
    return vec3(y, co, cg);
}

vec3 yCoCgToRgb(vec3 c) {
    float t = c.x - c.z;
    return max(vec3(t + c.y, c.x + c.z, t - c.y), vec3(0.0));
}

// Fast 5-tap Catmull-Rom bicubic filter in compressed HDR space at native display resolution
vec3 sampleHistoryCatmullRomCompressed(sampler2D tex, vec2 uv, vec2 texSize) {
    vec2 samplePos = uv * texSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;

    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);

    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / max(w12, vec2(1e-5));

    vec2 invSize = 1.0 / texSize;
    vec2 texPos0  = (texPos1 - 1.0) * invSize;
    vec2 texPos3  = (texPos1 + 2.0) * invSize;
    vec2 texPos12 = (texPos1 + offset12) * invSize;

    vec3 c0 = compressHDR(textureLod(tex, clamp(vec2(texPos12.x, texPos0.y),  vec2(0.001), vec2(0.999)), 0.0).rgb);
    vec3 c1 = compressHDR(textureLod(tex, clamp(vec2(texPos0.x,  texPos12.y), vec2(0.001), vec2(0.999)), 0.0).rgb);
    vec3 c2 = compressHDR(textureLod(tex, clamp(vec2(texPos12.x, texPos12.y), vec2(0.001), vec2(0.999)), 0.0).rgb);
    vec3 c3 = compressHDR(textureLod(tex, clamp(vec2(texPos3.x,  texPos12.y), vec2(0.001), vec2(0.999)), 0.0).rgb);
    vec3 c4 = compressHDR(textureLod(tex, clamp(vec2(texPos12.x, texPos3.y),  vec2(0.001), vec2(0.999)), 0.0).rgb);

    vec3 result = c0 * (w12.x * w0.y)
                + c1 * (w0.x  * w12.y)
                + c2 * (w12.x * w12.y)
                + c3 * (w3.x  * w12.y)
                + c4 * (w12.x * w3.y);

    float wSum = (w12.x * w0.y) + (w0.x * w12.y) + (w12.x * w12.y) + (w3.x * w12.y) + (w12.x * w3.y);
    return max(result / max(wSum, 1e-5), vec3(0.0));
}

// Clip history color toward AABB center along the ray from center -> history
vec3 clipAABB(vec3 aabbMin, vec3 aabbMax, vec3 center, vec3 history) {
    vec3 halfExt = max(0.5 * (aabbMax - aabbMin), vec3(1e-4));
    vec3 boxCenter = 0.5 * (aabbMax + aabbMin);
    vec3 delta = history - boxCenter;
    vec3 unit = abs(delta / halfExt);
    float maxComp = max(unit.x, max(unit.y, unit.z));
    if (maxComp > 1.0) {
        return boxCenter + delta / maxComp;
    }
    return history;
}

void main() {
    vec2 uv = texCoord;

    // Separate internal render size (low-res source) and native display size (high-res history/output)
    ivec2 srcSizeI = max(textureSize(colortex0, 0), ivec2(1));
    vec2 srcSize = vec2(srcSizeI);
    vec2 invSrcSize = 1.0 / srcSize;
    vec2 dstSize = vec2(max(textureSize(taaHistory, 0), ivec2(1)));

    // Unjittered UV corresponding to this display pixel in the jittered low-res current frame
    vec2 unjitteredUV = clamp(uv - taaJitter * 0.5, vec2(0.001), vec2(0.999));

#if DEBUG_VIEW > 0
    vec3 dbgCol = max(textureLod(colortex0, uv, 0.0).rgb, vec3(0.0));
    if (any(isnan(dbgCol)) || any(isinf(dbgCol))) dbgCol = vec3(0.0);
    fragColor = vec4(clamp(dbgCol, vec3(0.0), vec3(64.0)), 1.0);
    return;
#endif

    // 1. Reverse-Z 3x3 Closest-Depth Dilation in low-res depth buffer (largest depth = closest surface)
    float closestDepth = 0.0;
    vec2 closestUV = uv;
    ivec2 centerDepthCoord = clamp(ivec2(floor(uv * srcSize)), ivec2(0), srcSizeI - ivec2(1));
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            ivec2 tc = clamp(centerDepthCoord + ivec2(dx, dy), ivec2(0), srcSizeI - ivec2(1));
            float d = texelFetch(depthtex0, tc, 0).r;
            if (d > closestDepth) {
                closestDepth = d;
                closestUV = (vec2(tc) + 0.5) * invSrcSize;
            }
        }
    }

    // 2. Gather 3x3 low-res neighborhood in compressed HDR space:
    //    - Blackman-Harris / Gaussian distance-weighted reconstruction around unjitteredUV
    //    - Track closest sample center distance (minSampleDist) to modulate temporal confidence
    //    - Compute YCoCg mean & std-dev for variance clipping
    vec2 srcPos = unjitteredUV * srcSize;
    ivec2 baseCoord = clamp(ivec2(floor(srcPos)), ivec2(0), srcSizeI - ivec2(1));

    vec3 filteredCompSum = vec3(0.0);
    float filterWSum = 0.0;
    float minSampleDist = 2.0;
    vec3 m1 = vec3(0.0);
    vec3 m2 = vec3(0.0);

    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            ivec2 tc = clamp(baseCoord + ivec2(dx, dy), ivec2(0), srcSizeI - ivec2(1));
            vec3 rawTap = max(texelFetch(colortex0, tc, 0).rgb, vec3(0.0));
            if (any(isnan(rawTap)) || any(isinf(rawTap))) rawTap = vec3(0.0);
            vec3 tapComp = compressHDR(rawTap);

            vec2 tapCenter = vec2(tc) + 0.5;
            vec2 deltaPx = tapCenter - srcPos;
            float distSq = dot(deltaPx, deltaPx);
            minSampleDist = min(minSampleDist, sqrt(distSq));

            // Blackman-Harris / sharp Gaussian reconstruction kernel in source-pixel units
            float w = exp(-2.29 * distSq);
            filteredCompSum += tapComp * w;
            filterWSum += w;

            vec3 tapYCC = rgbToYCoCg(tapComp);
            m1 += tapYCC;
            m2 += tapYCC * tapYCC;
        }
    }

    vec3 currentComp = filteredCompSum / max(filterWSum, 1e-5);
    vec3 currentHDR = decompressHDR(currentComp);
    if (any(isnan(currentHDR)) || any(isinf(currentHDR))) {
        currentHDR = vec3(0.0);
    }

    // 3. Subtract current-frame Halton jitter (taaJitter * 0.5 in UV space) BEFORE reprojection
    //    so stationary scenes have strictly zero velocity.
    vec2 unjitteredClosestUV = clamp(closestUV - taaJitter * 0.5, vec2(0.001), vec2(0.999));
    vec3 cameraOffset = cameraPosition - previousCameraPosition;
    bool teleported = (length(cameraOffset) > 12.0) || (frameCounter < 2);

    vec2 prevUV = uv;
    bool validReproj = !teleported;
    if (validReproj) {
        vec3 prevWorldRel;
        if (isSceneDepth(closestDepth)) {
            vec3 viewPos = ScreenToView(unjitteredClosestUV, closestDepth);
            vec3 worldRel = (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz;
            prevWorldRel = worldRel + cameraOffset;
        } else {
            // Sky pixel: rotation-only reprojection (infinite distance)
            vec4 ndc = vec4(unjitteredClosestUV * 2.0 - 1.0, 0.5, 1.0);
            vec4 vp4 = gbufferProjectionInverse * ndc;
            vec3 viewDir = normalize(vp4.xyz / max(abs(vp4.w), 1e-5));
            prevWorldRel = normalize((gbufferModelViewInverse * vec4(viewDir, 0.0)).xyz) * 1000.0;
        }

        vec4 prevViewPos = gbufferPreviousModelView * vec4(prevWorldRel, 1.0);
        if (prevViewPos.z >= -0.02) {
            validReproj = false;
        } else {
            vec4 prevClip = gbufferPreviousProjection * prevViewPos;
            vec2 reprojectedClosestUV = (prevClip.xy / max(abs(prevClip.w), 1e-5)) * 0.5 + 0.5;
            vec2 velocity = unjitteredClosestUV - reprojectedClosestUV;
            prevUV = uv - velocity;
            if (prevUV.x <= 0.001 || prevUV.x >= 0.999 || prevUV.y <= 0.001 || prevUV.y >= 0.999) {
                validReproj = false;
            }
        }
    }

    if (!validReproj) {
        fragColor = vec4(clamp(currentHDR, vec3(0.0), vec3(64.0)), 1.0);
        return;
    }

    // 4. Sample native-res compressed history via 5-tap Catmull-Rom & clip to YCoCg variance box
    vec3 meanYCC = m1 * (1.0 / 9.0);
    vec3 sigmaYCC = sqrt(max(vec3(0.0), m2 * (1.0 / 9.0) - meanYCC * meanYCC));

    vec2 motionDstPx = (uv - prevUV) * dstSize;
    float speedPx = length(motionDstPx);
    float varGamma = mix(1.55, 0.90, smoothstep(0.0, 6.0, speedPx));

    vec3 aabbMin = meanYCC - varGamma * sigmaYCC;
    vec3 aabbMax = meanYCC + varGamma * sigmaYCC;

    vec3 histComp = sampleHistoryCatmullRomCompressed(taaHistory, prevUV, dstSize);
    vec3 histYCC = rgbToYCoCg(histComp);
    vec3 clippedYCC = clipAABB(aabbMin, aabbMax, meanYCC, histYCC);
    vec3 clippedHistComp = yCoCgToRgb(clippedYCC);

    // 5. Adaptive temporal blend modulated by motion speed, disocclusion clip distance,
    //    and sub-pixel sample proximity (closer low-res sample -> higher current-frame weight)
    float sampleProximity = clamp(1.0 - minSampleDist * 1.15, 0.0, 1.0);
    float baseAlpha = mix(0.06, 0.12, sampleProximity);
    float clipDist = length(histYCC - clippedYCC);
    float motionScale = smoothstep(0.15, 2.5, speedPx);
    float disoccBoost = smoothstep(0.06, 0.28, clipDist) * mix(0.04, 0.14, motionScale);
    float speedBoost = smoothstep(0.25, 8.0, speedPx) * 0.18;
    float blendAlpha = clamp(baseAlpha + speedBoost + disoccBoost, 0.06, 0.35);

    vec3 resolvedComp = mix(clippedHistComp, currentComp, blendAlpha);
    vec3 resolvedHDR = decompressHDR(resolvedComp);
    if (any(isnan(resolvedHDR)) || any(isinf(resolvedHDR))) {
        resolvedHDR = currentHDR;
    }
    fragColor = vec4(clamp(resolvedHDR, vec3(0.0), vec3(64.0)), 1.0);
}
