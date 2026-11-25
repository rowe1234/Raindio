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

    // ==================== 1. PTGI Stabilized SSR + Gated Shadow-Map RSM Fallback ====================
    if (isWater) {
        vec3 waterViewPos = ScreenToView(uv, waterDepth);
        vec3 waterViewDir = normalize(waterViewPos);

        // Read precomputed PTGI water normal from colortex3
        vec4 waterData = textureLod(colortex3, uv, 0.0);
        vec3 waterWorldNormal = octDecode(waterData.rg);
        vec3 waterNormal = normalize(mat3(gbufferModelView) * waterWorldNormal);

        float NdotV_raw = dot(waterNormal, -waterViewDir);
        if (NdotV_raw < 0.002) {
            waterNormal = normalize(waterNormal - waterViewDir * (0.002 - NdotV_raw));
        }

        vec3 geometricWorldNormal = octDecode(encodedDataN);
        geometricWorldNormal.y = underwater ? -abs(geometricWorldNormal.y) : abs(geometricWorldNormal.y);
        vec3 flatViewNormal = normalize(mat3(gbufferModelView) * normalize(geometricWorldNormal));

        // Full wave-perturbed reflection ray for sky, clouds & off-screen RSM fallback
        vec3 liftedReflectDir = reflect(waterViewDir, waterNormal);
        if (!underwater) {
            float up = dot(liftedReflectDir, flatViewNormal);
            if (up < 0.020) {
                liftedReflectDir = normalize(liftedReflectDir + flatViewNormal * (0.020 - up));
            }
        }
        liftedReflectDir = normalize(liftedReflectDir);

        // Stabilized SSR normal blend (0.45) to keep reflections coherent & unbroken while undulating naturally
        vec3 ssrNormal = normalize(mix(flatViewNormal, waterNormal, underwater ? 0.40 : 0.45));
        vec3 ssrReflectDir = reflect(waterViewDir, ssrNormal);
        if (!underwater) {
            float upSSR = dot(ssrReflectDir, flatViewNormal);
            if (upSSR < 0.025) {
                ssrReflectDir = normalize(ssrReflectDir + flatViewNormal * (0.025 - upSSR));
            }
        }
        ssrReflectDir = normalize(ssrReflectDir);

        vec2 microPerturbUV = (waterNormal - ssrNormal).xy * 0.028;
        vec3 startWaterPos = waterViewPos + flatViewNormal * 0.055;
        vec4 ssr = ray_trace_ssr(ssrReflectDir, startWaterPos, ssrDither, microPerturbUV);
        reflection = ssr.rgb;

        float rsmDebugAlpha = 0.0;

        if (ssr.a < 0.99) {
            vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(liftedReflectDir, 0.0)).xyz);

            if (!underwater) {
                vec3 skyReflDir = normalize(vec3(reflectWorldDir.x, max(reflectWorldDir.y, 0.015), reflectWorldDir.z));
                vec3 skyRefl = ComputeFullSkyHDR(colortex6, skyReflDir, realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false);

                // Gently damp overly luminous horizon sky belt in water reflections
                skyRefl *= mix(0.76, 1.0, smoothstep(0.0, 0.28, skyReflDir.y));
                skyRefl *= mix(1.0, 0.68, saturate(rainStrength));

                // SkyLight occlusion so cave/overhang water doesn't reflect bright outdoor sky
                float skyLightFactor = smoothstep(0.08, 0.88, skyLightData);
                vec3 indoorAmbient = vec3(0.004, 0.008, 0.014) * nightDim;
                skyRefl = mix(indoorAmbient, skyRefl, skyLightFactor);

                // Sun-elevation & upward-ray gate: avoids low-sun cliff ghosting and wasted sky-ray tracing
                float rsmGate = smoothstep(0.15, 0.40, abs(realSunDir.y))
                              * (1.0 - smoothstep(0.55, 0.85, reflectWorldDir.y));

                vec3 fallbackRefl = skyRefl;
                if (skyLightFactor > 0.05 && rsmGate > 0.01) {
                    vec3 rsmStartPos = waterViewPos + flatViewNormal * 0.08;
                    vec4 shadowRefl = ray_trace_shadow_reflection(rsmStartPos, liftedReflectDir, realSunDir, ssrDither);
                    float gatedAlpha = shadowRefl.a * rsmGate;
                    rsmDebugAlpha = gatedAlpha;
                    vec3 shadedRsm = mix(indoorAmbient, shadowRefl.rgb, skyLightFactor);
                    fallbackRefl = mix(skyRefl, shadedRsm, gatedAlpha);
                }

                reflection = mix(fallbackRefl, ssr.rgb, ssr.a);
            } else {
                float depthGrad = saturate(-reflectWorldDir.y * 0.5 + 0.5);
                vec3 deepScatter = mix(vec3(0.022, 0.165, 0.148), vec3(0.012, 0.095, 0.088), depthGrad) * nightDim;
                if (isDay) deepScatter *= mix(0.42, 1.0, mainLightVis);
                deepScatter *= mix(0.28, 1.0, smoothstep(0.1, 0.9, skyLightData));
                reflection = mix(deepScatter, ssr.rgb, ssr.a);
            }
        }

        // Subtle aqua-teal tint on water reflections to prevent distant horizon wash-out
        if (!underwater) {
            reflection *= mix(vec3(1.0), vec3(0.84, 0.98, 0.94), 0.42);
        }

#if DEBUG_VIEW == 15
        // R = SSR alpha, G = RSM off-screen alpha (outside SSR), B = Sky fallback
        float effRsm = rsmDebugAlpha * (1.0 - ssr.a);
        float effSky = clamp(1.0 - ssr.a - effRsm, 0.0, 1.0);
        fragData0 = vec4(ssr.a, effRsm, effSky, 1.0);
        return;
#endif
    }
    // ==================== 2. Glass & Translucent Reflections ====================
    else if (isGlass) {
        vec3 glassViewPos = ScreenToView(uv, waterDepth);
        vec3 glassViewDir = normalize(glassViewPos);
        float skyLightFactor = smoothstep(0.05, 0.85, skyLightData);

        vec3 glassWorldNormal = octDecode(encodedDataN);
        vec3 glassNormal = normalize(mat3(gbufferModelView) * glassWorldNormal);

        vec3 reflectViewDir = normalize(reflect(glassViewDir, glassNormal));
        vec3 startGlassPos = glassViewPos + glassNormal * 0.06;
        vec4 ssr = ray_trace_ssr(reflectViewDir, startGlassPos, ssrDither, vec2(0.0));
        reflection = ssr.rgb;

        if (ssr.a < 0.98) {
            vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflectViewDir, 0.0)).xyz);
            vec3 skyReflDir = normalize(vec3(reflectWorldDir.x, max(reflectWorldDir.y, 0.04), reflectWorldDir.z));
            vec3 skyRefl = ComputeFullSkyHDR(colortex6, skyReflDir, realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false);

            vec3 indoorAmbient = vec3(0.005, 0.008, 0.012) * nightDim;
            skyRefl = mix(indoorAmbient, skyRefl, skyLightFactor);

            reflection = mix(skyRefl, ssr.rgb, ssr.a);
        }
    }
    // ==================== 3. Opaque Terrain: GTAO + Contact Shadows + Wet Reflections ====================
    else if (isNotSky) {
        vec3 viewPos = ScreenToView(uv, waterDepth);
        vec3 viewDir = normalize(viewPos);
        float viewDist = length(viewPos);

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

        if (rainStrength > 0.01) {
        float isFloor = smoothstep(0.45, 0.85, worldNormal.y);
        float skyLightFactor = smoothstep(0.60, 0.90, skyLightData);

        if (isFloor > 0.01 && skyLightFactor > 0.01) {
            vec3 worldPos = (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz + cameraPosition;
            float puddle = get_puddle_mask(worldPos.xz);
            float wetness = saturate(rainStrength) * skyLightFactor * isFloor * mix(0.65, 1.0, puddle);

            if (wetness > 0.01) {
                float rippleFade = 1.0 / (1.0 + viewDist * 0.09);
                vec2 rippleSlope = get_rain_ripple_slope(worldPos.xz, rainStrength * puddle, skyLightData) * rippleFade * 0.35;
                vec3 rippledWorldN = normalize(worldNormal + vec3(rippleSlope.x, 0.0, rippleSlope.y));
                vec3 viewNormal = normalize(mat3(gbufferModelView) * worldNormal);
                vec3 rippledViewN = normalize(mat3(gbufferModelView) * rippledWorldN);

                vec3 startPos = viewPos + viewNormal * 0.05;
                vec3 reflectViewDir = normalize(reflect(viewDir, viewNormal));
                vec2 puddlePerturb = (rippledViewN - viewNormal).xy * 0.025;
                vec4 ssr = ray_trace_ssr(reflectViewDir, startPos, ssrDither, puddlePerturb);

                vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflect(viewDir, rippledViewN), 0.0)).xyz);
                vec3 skyReflDir = normalize(vec3(reflectWorldDir.x, max(reflectWorldDir.y, 0.04), reflectWorldDir.z));
                vec3 skyRefl = SampleSkyLUT(colortex6, skyReflDir) * mix(0.65, 0.95, puddle) * skyLightFactor;

                reflection = mix(skyRefl, ssr.rgb, ssr.a);
            }
        }
        }
    }

    reflection = clamp(reflection, vec3(0.0), vec3(12.0));
    fragData0 = vec4(reflection, terrainOcclusion);
}
