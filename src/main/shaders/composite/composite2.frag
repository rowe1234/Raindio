#version 450
#include "common/raindio_uniforms.glsl"

/* DRAWBUFFERS:9 */

layout(location = 0) in vec2 texCoord;

layout(set = 0, binding = 1) uniform sampler2D colortex0;
layout(set = 0, binding = 2) uniform sampler2D colortex1;
layout(set = 0, binding = 3) uniform sampler2D colortex3;
layout(set = 0, binding = 4) uniform sampler2D colortex6;
layout(set = 0, binding = 5) uniform sampler2D depthtex0;
layout(set = 0, binding = 6) uniform sampler2D depthtex1;
layout(set = 0, binding = 7) uniform sampler2D noisetex;
layout(set = 0, binding = 8) uniform sampler2D shadowtex0;
layout(set = 0, binding = 9) uniform sampler2D shadowcolor0;



#include "lib/common.glsl"
#include "lib/projection.glsl"
#include "lib/lighting.glsl"
#include "lib/atmosphere.glsl"
#include "lib/clouds.glsl"
#include "lib/sky.glsl"
#include "lib/water.glsl"
#include "lib/fog.glsl"
#include "lib/ssr.glsl"

#define GTAO_ENABLED
#define CONTACT_SHADOWS

// Modern 4-direction x 3-step Horizon-Based GTAO with golden-ratio temporal rotation
float computeGTAO(vec2 uv, vec3 viewPos, vec3 viewNormal, float dither) {
    float viewDist = length(viewPos);
    if (viewDist > 64.0) return 1.0;

    // Shrink world-space AO radius smoothly when very close to a block to prevent dark close-up borders
    float radiusVS = clamp(viewDist * 0.28, 0.10, 1.9);
    float projScale = abs(gbufferProjection[1][1]) * 0.5;
    float radiusSS = clamp((radiusVS * projScale) / max(-viewPos.z, 0.15), 0.003, 0.036);
    float aspect = viewHeight / max(viewWidth, 1.0);
    float maxDistSq = radiusVS * radiusVS * 2.2;

#ifndef GTAO_DIRS
#define GTAO_DIRS 4
#endif
#ifndef GTAO_STEPS
#define GTAO_STEPS 3
#endif
#ifndef GTAO_TEMPORAL_JITTER
#define GTAO_TEMPORAL_JITTER 0
#endif

    // Static spatial IGN on Low/Balanced/High (denoised cleanly); golden-ratio temporal jitter on Cinematic 8x5
#if GTAO_TEMPORAL_JITTER == 1 && defined(TAA_ENABLED) && TAA_ENABLED == 1
    float tDither = fract(dither + fract(float(frameCounter & 1023) * 0.61803398875));
#else
    float tDither = dither;
#endif
    float occ = 0.0;
    const int DIRS = GTAO_DIRS;
    const int STEPS = GTAO_STEPS;
    for (int d = 0; d < DIRS; d++) {
        float angle = (float(d) + tDither) * (PI / float(DIRS));
        vec2 dir = vec2(cos(angle) * aspect, sin(angle));

        float maxSinPos = 0.0;
        float maxSinNeg = 0.0;
        for (int step = 1; step <= STEPS; step++) {
            float frac = (float(step) - 0.40 + 0.40 * tDither) / float(STEPS);
            vec2 deltaUV = dir * (radiusSS * frac);

            vec2 sUV1 = uv + deltaUV;
            if (sUV1.x > 0.002 && sUV1.x < 0.998 && sUV1.y > 0.002 && sUV1.y < 0.998) {
                float sDepth = texture(depthtex1, sUV1).r;
                if (isSceneDepth(sDepth)) {
                    vec3 diff = ScreenToView(sUV1, sDepth) - viewPos;
                    float distSq = dot(diff, diff);
                    if (distSq > 0.0006 && distSq < maxDistSq) {
                        float h = dot(diff, viewNormal) * inversesqrt(distSq);
                        float falloff = saturate(1.0 - distSq / maxDistSq);
                        maxSinPos = max(maxSinPos, saturate(h - 0.14) * falloff);
                    }
                }
            }

            vec2 sUV2 = uv - deltaUV;
            if (sUV2.x > 0.002 && sUV2.x < 0.998 && sUV2.y > 0.002 && sUV2.y < 0.998) {
                float sDepth = texture(depthtex1, sUV2).r;
                if (isSceneDepth(sDepth)) {
                    vec3 diff = ScreenToView(sUV2, sDepth) - viewPos;
                    float distSq = dot(diff, diff);
                    if (distSq > 0.0006 && distSq < maxDistSq) {
                        float h = dot(diff, viewNormal) * inversesqrt(distSq);
                        float falloff = saturate(1.0 - distSq / maxDistSq);
                        maxSinNeg = max(maxSinNeg, saturate(h - 0.14) * falloff);
                    }
                }
            }
        }
        occ += 0.5 * (maxSinPos + maxSinNeg);
    }

    float nearFade = smoothstep(0.22, 1.0, viewDist);
    float distFade = (1.0 - smoothstep(38.0, 64.0, viewDist)) * nearFade;
    float ao = 1.0 - (occ / float(DIRS)) * 0.78 * distFade;
    return clamp(ao, 0.24, 1.0);
}

// Smooth 6-step Screen-Space Contact Shadows (bias-safe, fades near camera to avoid close-up block borders)
float computeContactShadow(vec3 viewPos, vec3 viewNormal, vec3 lightViewDir, float dither) {
    float viewDist = length(viewPos);
    if (viewDist < 0.55 || viewDist > 26.0) return 1.0;
    float NdotL = dot(viewNormal, lightViewDir);
    if (NdotL <= 0.18) return 1.0;

    float rayLen = clamp(viewDist * 0.14, 0.12, 0.75);
    float stepLen = rayLen / 6.0;
    float normalBias = 0.055 + viewDist * 0.0022;
    vec3 p = viewPos + viewNormal * normalBias + lightViewDir * (stepLen * (0.35 + 0.65 * dither));

    float maxOcc = 0.0;
    for (int i = 0; i < 6; i++) {
        vec2 sUV = viewToScreenUV(p);
        if (sUV.x <= 0.004 || sUV.x >= 0.996 || sUV.y <= 0.004 || sUV.y >= 0.996) break;
        float sDepth = texture(depthtex1, sUV).r;
        if (isSceneDepth(sDepth)) {
            vec3 sv = ScreenToView(sUV, sDepth);
            float diff = sv.z - p.z;
            float hit = smoothstep(0.04, 0.12, diff) * (1.0 - smoothstep(0.20, 0.40, diff));
            maxOcc = max(maxOcc, hit);
        }
        p += lightViewDir * stepLen;
    }
    float nearFade = smoothstep(0.55, 1.8, viewDist);
    float fade = nearFade * (1.0 - smoothstep(16.0, 26.0, viewDist)) * smoothstep(0.18, 0.40, NdotL);
    return mix(1.0, 0.76, maxOcc * fade);
}

layout(location = 0) out vec4 fragData0;

void main() {
    vec2 uv = texCoord;

    // Half-resolution SSR/GTAO pass: sample full-res G-Buffer via normalized uv
    float waterDepth = textureLod(depthtex0, uv, 0.0).r;
    vec4 colortex1_data = textureLod(colortex1, uv, 0.0);
    int blockId = int(colortex1_data.z * 255.0 + 0.5);
    vec2 encodedDataN = colortex1_data.rg;
    float skyLightData = colortex1_data.a;

    float ssrDither = interleavedGradientNoise(gl_FragCoord.xy);

    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float sunHeight = realSunDir.y;
    vec3 sunColor = mix(vec3(1.0, 0.4, 0.05), vec3(1.0, 0.98, 0.85), saturate(sunHeight * 2.5));
    float twilightBlend = smoothstep(-0.12, 0.06, sunHeight);
    float nightDim = mix(0.05, 1.0, saturate(sunHeight * 4.0 + 0.5));
    bool isDay = realSunDir.y > 0.0;
    float mainLightVis = isDay ? saturate(realSunDir.y * 3.0) : saturate(-realSunDir.y * 3.0);

    bool isNotSky = isSceneDepth(waterDepth);
    bool isWater = isMatWater(blockId) && isNotSky;
    bool isGlass = isMatTranslucentNonWater(blockId) && isNotSky;
    bool underwater = (isEyeInWater == 1);

    vec3 reflection = vec3(0.0);
    float terrainOcclusion = 1.0;

    // Reflections for water, glass, and wet puddles are now traced in true 3D world space
    // by compute/rt_reflections.comp (3D Voxel DDA + 4x4x4 Sub-Voxel + Shadow-Map + LPV + Sky).
    // composite2 now focuses solely on fast Horizon-Based GTAO + Contact Shadows into colortex9.a.
    if (isNotSky && !isWater && !isGlass) {
        vec3 viewPos = ScreenToView(uv, waterDepth);
        vec3 worldNormal = octDecode(encodedDataN);
        vec3 terrainViewNormal = normalize(mat3(gbufferModelView) * worldNormal);

        float ao = 1.0;
#ifdef GTAO_ENABLED
        ao = computeGTAO(uv, viewPos, terrainViewNormal, ssrDither);
#endif
        float cs = 1.0;
#ifdef CONTACT_SHADOWS
        if (isDay && mainLightVis > 0.05 && rainStrength < 0.85) {
            vec3 viewSunDir = normalize((gbufferModelView * vec4(realSunDir, 0.0)).xyz);
            cs = mix(1.0, computeContactShadow(viewPos, terrainViewNormal, viewSunDir, ssrDither), mainLightVis * (1.0 - saturate(rainStrength)));
        }
#endif
        terrainOcclusion = ao * cs;
    }

    fragData0 = vec4(reflection, terrainOcclusion);
}
