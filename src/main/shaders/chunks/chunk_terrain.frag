#version 450

#include "common/raindio_uniforms.glsl"
#include "lib/common.glsl"

layout(set = 0, binding = 1) uniform sampler2D Sampler0;
layout(set = 0, binding = 2) uniform sampler2D Sampler2;
layout(set = 0, binding = 3) uniform sampler2D shadowtex0;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vLightmap;
layout(location = 3) in vec3 vWorldRel;
layout(location = 4) in vec3 vViewPos;
layout(location = 5) in vec3 vSkyColor;
layout(location = 6) in vec3 vSunlight;
layout(location = 7) in vec4 vParams;

layout(location = 0) out vec4 fragData0;
layout(location = 1) out vec4 fragData1;
layout(location = 2) out vec4 fragData2; // Pure unlit linear surface albedo for physical GI & multi-bounce AO

#define PLANT_SSS_INTENSITY 0.32
#define PLANT_SSS_POWER     3.0
#define PLANT_SSS_WRAP      0.38
#define AUTO_BEVEL_NORMALS

// Golden-angle Vogel disk sample generator (optimal low-discrepancy 2D disk distribution)
vec2 vogelDiskSample(int sampleIdx, int totalSamples, float phi) {
    float r = sqrt((float(sampleIdx) + 0.5) / float(totalSamples));
    float theta = float(sampleIdx) * 2.399963229728653 + phi;
    return vec2(cos(theta), sin(theta)) * r;
}

float chunkD_GGX(float NdotH, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / max(3.14159265 * denom * denom, 1e-5);
}

float chunkG_Smith(float NdotV, float NdotL, float rough) {
    float k = (rough + 1.0);
    k = (k * k) * 0.125;
    float gv = NdotV / (NdotV * (1.0 - k) + k);
    float gl = NdotL / (NdotL * (1.0 - k) + k);
    return gv * gl;
}

vec3 chunkF_Schlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// ============================================================================
// Modern Contact-Hardening PCSS (Percentage-Closer Soft Shadows)
// 1. Distortion-aware slope-scaled normal offset (zero acne + zero Peter-Panning)
// 2. 8-tap Vogel blocker search -> physical penumbra estimation
// 3. 16-tap Vogel PCF with golden-ratio temporal IGN rotation
// 4. Smooth frustum border fade
// ============================================================================
float sampleShadowPCSS(vec3 worldRel, vec3 geomN, vec3 worldLightDir, float NdotL, bool isPlant, float skyLight) {
    // Evaluate distortion factor at un-biased position so bias scales accurately with local texel size
    vec4 baseClip = shadowProjection * shadowModelView * vec4(worldRel, 1.0);
    float baseL = length(baseClip.xy / max(abs(baseClip.w), 1e-5));
    float distortFactor = (baseL > 0.00001) ? (baseL * 0.8 + 0.2) : 0.2;
    // Tighter texelScale cap prevents normal bias from poking through thin 1-block walls/roofs
    float texelScale = clamp(distortFactor * distortFactor * 3.2, 0.30, 1.75);

    float slopeSin = sqrt(max(0.0, 1.0 - NdotL * NdotL));
    vec3 biasedPos = isPlant
        ? (worldRel + worldLightDir * (0.020 * texelScale))
        : (worldRel + geomN * ((0.008 + 0.018 * slopeSin) * texelScale));

    vec4 shadowClip = shadowProjection * shadowModelView * vec4(biasedPos, 1.0);
    vec3 shadowNDC = shadowClip.xyz / max(abs(shadowClip.w), 1e-5);

    float l = length(shadowNDC.xy);
    float dFac = (l > 0.00001) ? (l * 0.8 + 0.2) : 0.2;
    shadowNDC.xy /= dFac;

    vec3 shadowCoord = vec3(shadowNDC.xy * 0.5 + 0.5, shadowNDC.z);
    float outdoorFallback = smoothstep(0.45, 0.85, skyLight);
    if (shadowCoord.x < 0.002 || shadowCoord.x > 0.998 ||
        shadowCoord.y < 0.002 || shadowCoord.y > 0.998 ||
        shadowCoord.z < 0.0005 || shadowCoord.z > 0.9995) {
        return outdoorFallback;
    }

    float oneTexel = 1.0 / max(SHADOW_MAP_RES, 512.0);
    float baseBias = (0.00005 + 0.00012 * slopeSin) * texelScale;
    float receiverZ = shadowCoord.z - baseBias;

    // With TAA enabled, unlock golden-ratio temporal rotation for silky-smooth PCSS convergence;
    // when falling back to FXAA/OFF, keep static spatial IGN to avoid shadow boiling.
#if defined(TAA_ENABLED) && TAA_ENABLED == 1
    float phi = fract(interleavedGradientNoise(gl_FragCoord.xy) + fract(float(frameCounter & 1023) * 0.61803398875)) * 6.2831853;
#else
    float phi = interleavedGradientNoise(gl_FragCoord.xy) * 6.2831853;
#endif

    // Stage 1: Vogel Blocker Search + PCF driven by QualityPreset (4/8 Low-Balanced, 8/16 High, 16/32 Cinematic)
#ifndef PCSS_BLOCKER_TAPS
#define PCSS_BLOCKER_TAPS 4
#endif
#ifndef PCSS_PCF_TAPS
#define PCSS_PCF_TAPS 8
#endif
    const int BLOCKER_TAPS = PCSS_BLOCKER_TAPS;
    const int PCF_TAPS = PCSS_PCF_TAPS;
    float invDistort = clamp(1.0 / max(dFac, 0.2), 1.0, 4.0);
    float searchRadius = 3.8 * oneTexel * invDistort * 0.38;
    float blockerDepthSum = 0.0;
    int blockerCount = 0;

    // Always test exact center texel first so thin 1-block roofs/walls never slip between outer Vogel taps
    float centerMapZ = textureLod(shadowtex0, clamp(shadowCoord.xy, vec2(0.001), vec2(0.999)), 0.0).r;
    if (centerMapZ < receiverZ) {
        blockerDepthSum += centerMapZ;
        blockerCount++;
    }
    for (int i = 0; i < BLOCKER_TAPS; i++) {
        vec2 tapUV = clamp(shadowCoord.xy + vogelDiskSample(i, BLOCKER_TAPS, phi) * searchRadius, vec2(0.001), vec2(0.999));
        float mapZ = textureLod(shadowtex0, tapUV, 0.0).r;
        if (mapZ < receiverZ) {
            blockerDepthSum += mapZ;
            blockerCount++;
        }
    }

    // Early-out: fully lit surface (zero blockers including center) -> crisp & fast
    if (blockerCount == 0) {
        return 1.0;
    }

    // Stage 2: Physical contact-hardening penumbra radius (capped at 3.4px so PCF never bleeds through 1-block walls)
    float avgBlockerZ = blockerDepthSum / float(blockerCount);
    // Depth difference in world meters (ortho Z range = 300m)
    float blockerDistMeters = max(shadowCoord.z - avgBlockerZ, 0.0) * 300.0;
    float penumbraTexels = clamp(1.0 + blockerDistMeters * 0.35, 1.0, 3.4);
    float filterRadius = penumbraTexels * oneTexel * mix(1.0, invDistort * 0.36, 0.55);

    // Stage 3: Vogel Disk PCF with contact-hardening radius
    float vis = 0.0;
    for (int i = 0; i < PCF_TAPS; i++) {
        vec2 tapUV = clamp(shadowCoord.xy + vogelDiskSample(i, PCF_TAPS, phi + 1.17) * filterRadius, vec2(0.001), vec2(0.999));
        float mapZ = textureLod(shadowtex0, tapUV, 0.0).r;
        vis += (receiverZ <= mapZ) ? 1.0 : 0.0;
    }
    float shadow = vis * (1.0 / float(PCF_TAPS));

    // Smoothly fade to sky-gated outdoor fallback near shadow frustum boundary (no indoor sun/moon leak)
    vec2 edgeDist = abs(shadowCoord.xy - 0.5) * 2.0;
    float borderFade = 1.0 - smoothstep(0.86, 0.98, max(edgeDist.x, edgeDist.y));
    return mix(outdoorFallback, shadow, borderFade);
}

// ============================================================================
// Scale-Invariant Sub-Texel Beveled Procedural Normal Mapping
// Adds subtle 3D pixel relief up close on stone/wood/bricks without HiDPI underflow.
// ============================================================================
vec3 applyBevelMicroNormal(vec3 geomN, vec3 worldRel, vec2 uv, vec2 texSize, vec2 tileMin, vec2 tileMax, bool isPlant) {
#ifdef AUTO_BEVEL_NORMALS
    if (isPlant) return geomN;
    float dist = length(worldRel);
    float nearFade = 1.0 - smoothstep(10.0, 24.0, dist);
    if (nearFade < 0.02) return geomN;

    vec3 dp1 = dFdx(worldRel);
    vec3 dp2 = dFdy(worldRel);
    vec2 dpx1 = dFdx(uv) * texSize;
    vec2 dpx2 = dFdy(uv) * texSize;

    float det = dpx1.x * dpx2.y - dpx1.y * dpx2.x;
    if (abs(det) < 1e-8) return geomN;

    vec3 T = (dp1 * dpx2.y - dp2 * dpx1.y) / det;
    vec3 B = (-dp1 * dpx2.x + dp2 * dpx1.x) / det;
    float tLen = length(T);
    float bLen = length(B);
    if (tLen < 1e-6 || bLen < 1e-6) return geomN;

    vec3 tOrtho = (T / tLen) - geomN * dot(T / tLen, geomN);
    vec3 bOrtho = (B / bLen) - geomN * dot(B / bLen, geomN);
    if (dot(tOrtho, tOrtho) < 1e-6 || dot(bOrtho, bOrtho) < 1e-6) return geomN;
    T = normalize(tOrtho);
    B = normalize(bOrtho);

    vec2 texel = 1.0 / texSize;
    float hC = luminance(textureLod(Sampler0, clamp(uv, tileMin, tileMax), 0.0).rgb);
    float hL = luminance(textureLod(Sampler0, clamp(uv - vec2(texel.x, 0.0), tileMin, tileMax), 0.0).rgb);
    float hR = luminance(textureLod(Sampler0, clamp(uv + vec2(texel.x, 0.0), tileMin, tileMax), 0.0).rgb);
    float hD = luminance(textureLod(Sampler0, clamp(uv - vec2(0.0, texel.y), tileMin, tileMax), 0.0).rgb);
    float hU = luminance(textureLod(Sampler0, clamp(uv + vec2(0.0, texel.y), tileMin, tileMax), 0.0).rgb);

    vec2 f = fract(uv * texSize);
    const float bevelW = 0.26;
    float edgeL = 1.0 - smoothstep(0.0, bevelW, f.x);
    float edgeR = smoothstep(1.0 - bevelW, 1.0, f.x);
    float edgeD = 1.0 - smoothstep(0.0, bevelW, f.y);
    float edgeU = smoothstep(1.0 - bevelW, 1.0, f.y);

    vec2 bevelGrad = vec2(
        (hL - hC) * edgeL + (hC - hR) * edgeR,
        (hD - hC) * edgeD + (hC - hU) * edgeU
    );
    vec2 macroGrad = vec2(hL - hR, hD - hU) * 0.20;
    vec2 grad = (bevelGrad * 1.85 + macroGrad) * nearFade;

    return normalize(geomN + (T * grad.x + B * grad.y));
#else
    return geomN;
#endif
}

void main() {
    vec4 tex = texture(Sampler0, vTexCoord);
    if (tex.a < 0.1) {
        discard;
    }

    vec4 albedo = tex * vColor;

    // Exact per-face world normal from camera-relative world position derivatives.
    // Normalize tangent derivatives before cross() so close-up faces on HiDPI displays never underflow.
    vec3 dpdx = dFdx(vWorldRel);
    vec3 dpdy = dFdy(vWorldRel);
    float dxLen = length(dpdx);
    float dyLen = length(dpdy);
    vec3 geomN = (dxLen > 1e-12 && dyLen > 1e-12)
        ? cross(dpdx / dxLen, dpdy / dyLen)
        : vec3(0.0, 1.0, 0.0);
    float nLen = length(geomN);
    geomN = (nLen > 1e-6) ? (geomN / nLen) : vec3(0.0, 1.0, 0.0);
    if (dot(geomN, -vWorldRel) < 0.0) {
        geomN = -geomN;
    }

    // Atlas tile bounds for foliage cutout detection & sub-texel beveled normal map
    vec2 texSize = vec2(max(textureSize(Sampler0, 0), ivec2(16)));
    vec2 texel = 1.0 / texSize;
    vec2 tileSize = vec2(16.0) / texSize;
    vec2 tileMin = floor(vTexCoord / tileSize) * tileSize + texel * 0.5;
    vec2 tileMax = tileMin + tileSize - texel;

    float aMin = tex.a;
    aMin = min(aMin, textureLod(Sampler0, clamp(vTexCoord + vec2( 2.0 * texel.x,  2.0 * texel.y), tileMin, tileMax), 0.0).a);
    aMin = min(aMin, textureLod(Sampler0, clamp(vTexCoord + vec2(-2.0 * texel.x,  2.0 * texel.y), tileMin, tileMax), 0.0).a);
    aMin = min(aMin, textureLod(Sampler0, clamp(vTexCoord + vec2( 2.0 * texel.x, -2.0 * texel.y), tileMin, tileMax), 0.0).a);
    aMin = min(aMin, textureLod(Sampler0, clamp(vTexCoord + vec2(-2.0 * texel.x, -2.0 * texel.y), tileMin, tileMax), 0.0).a);
    float chroma = max(albedo.r, max(albedo.g, albedo.b)) - min(albedo.r, min(albedo.g, albedo.b));
    bool hasCutout = (aMin < 0.5);
    bool hasFastLeafTint = (abs(geomN.y) < 0.95 && vColor.g > vColor.r * 1.08 && vColor.g > vColor.b * 1.08);
    bool isPlant = (hasCutout && chroma > 0.08) || hasFastLeafTint;

    // Strip vanilla Minecraft directional face shading (0.5 / 0.6 / 0.8 / 1.0) and blocky vertex AO
    // (equivalent to Iris `oldLighting = false`) while preserving biome tint on grass/leaves/vines.
    vec3 absN = abs(geomN);
    float maxAxis = max(absN.x, max(absN.y, absN.z));
    float vanillaFaceShade = 1.0;
    if (maxAxis > 0.92 && !(hasCutout && absN.y < 0.5 && vColor.r == vColor.g && vColor.g == vColor.b && vColor.r > 0.95)) {
        if (geomN.y < -0.5)      vanillaFaceShade = 0.5;
        else if (absN.x > 0.5)   vanillaFaceShade = 0.6;
        else if (absN.z > 0.5)   vanillaFaceShade = 0.8;
        else                     vanillaFaceShade = 1.0;
    }
    vec3 unshadedVColor = clamp(vColor.rgb / vanillaFaceShade, vec3(0.0), vec3(1.0));
    // Strip 100% of vanilla blocky corner AO on neutral blocks (all lighting & AO come from our shaders)
    float vcMax = max(unshadedVColor.r, max(unshadedVColor.g, unshadedVColor.b));
    float vcMin = min(unshadedVColor.r, min(unshadedVColor.g, unshadedVColor.b));
    if (vcMax - vcMin < 0.03) {
        unshadedVColor = vec3(1.0);
    }
    albedo = vec4(tex.rgb * unshadedVColor, tex.a * vColor.a);

    // Micro-beveled shading normal (keep geometric geomN for shadow bias & G-Buffer stability)
    vec3 worldN = applyBevelMicroNormal(geomN, vWorldRel, vTexCoord, texSize, tileMin, tileMax, isPlant);

    // Physical rain wetness: porous surfaces darken and turn glossy when exposed to sky
    float wetness = 0.0;
    if (rainStrength > 0.005) {
        float skyExposure = smoothstep(0.50, 0.90, vLightmap.y);
        float upFactor = clamp(geomN.y * 0.65 + 0.35, 0.0, 1.0);
        wetness = saturate(rainStrength) * skyExposure * upFactor;
        albedo.rgb *= mix(1.0, 0.72, wetness);
    }

    vec3 linearAlbedo = pow(max(albedo.rgb, vec3(0.0)), vec3(2.1));

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    vec3 worldLightDir = (worldSunDir.y >= 0.0) ? worldSunDir : -worldSunDir;
    vec3 worldViewDir = normalize(-vWorldRel);

    float dayFactor = vParams.x;
    float sunIntensity = vParams.y;
    float emissive = vParams.z;
    float rainClear = 1.0 - saturate(rainStrength);
    float geomNdotL = max(dot(geomN, worldLightDir), 0.0);
    float NdotL_raw = dot(worldN, worldLightDir);
    float NdotL = max(NdotL_raw, 0.0);

    // Gate sun/moon light in completely sealed underground caves (skyLight == 0) while allowing
    // sunlight streaming through glass windows into roofed indoor rooms (skyLight >= 1) to stay visible
    float skyShadowGate = smoothstep(0.02, 0.16, vLightmap.y);
    float shadow = 0.0;
    if ((geomNdotL > 0.001 || isPlant) && skyShadowGate > 0.001) {
        shadow = sampleShadowPCSS(vWorldRel, geomN, worldLightDir, geomNdotL, isPlant, vLightmap.y) * skyShadowGate;
    }

    // Keep nights authentically dark & moody while preserving subtle moonlit silhouettes
    float nightSkyLift = mix(1.10, 1.0, smoothstep(-0.10, 0.18, worldSunDir.y));
    vec3 sunLightCol = vSunlight * (sunIntensity * (3.1 * rainClear + 0.30) * nightSkyLift);
    vec3 directLight = sunLightCol * (NdotL * shadow);

    // Ported Raindio Foliage Subsurface Scattering (forward sun translucency + wrap back-lighting)
    vec3 plantSSS = vec3(0.0);
    if (isPlant && vLightmap.y > 0.08) {
        float viewScatter = clamp(dot(-worldViewDir, worldLightDir), 0.0, 1.0);
        float forwardSSS = pow(viewScatter, PLANT_SSS_POWER);
        float wrapNdotL = clamp((-NdotL_raw + PLANT_SSS_WRAP) / (1.0 + PLANT_SSS_WRAP), 0.0, 1.0);
        float sideSSS = wrapNdotL * 0.45;
        vec3 sssColor = pow(max(linearAlbedo, vec3(0.0)), vec3(1.1)) * vec3(1.08, 1.15, 0.85);
        float sssShadow = mix(shadow, 1.0, 0.08);
        plantSSS = sssColor * (forwardSSS * 1.15 + sideSSS) * PLANT_SSS_INTENSITY * sunLightCol * sssShadow * pow(vLightmap.y, 1.4);
    }

    // Directional sky ambient strictly gated by outdoor sky exposure (zero indoor/cave ambient leak)
    float skyMask = pow(smoothstep(0.08, 1.0, vLightmap.y), 2.0);
    float upMask = saturate(worldN.y);
    float downMask = saturate(-worldN.y);
    float sunBounceAlign = saturate(dot(worldN, -worldLightDir) * 0.5 + 0.5);
    vec3 skyZenithFill = vSkyColor * vec3(0.24, 0.27, 0.32);
    vec3 horizonFill = mix(vSkyColor * 0.18, vSkyColor * 0.16 + vSunlight * (0.055 * dayFactor * rainClear), sunBounceAlign);
    vec3 groundBounceFill = vSkyColor * 0.10 + vSunlight * vec3(1.05, 0.88, 0.65) * (0.065 * dayFactor * rainClear);
    vec3 directionalAmb = mix(horizonFill, skyZenithFill, upMask);
    directionalAmb = mix(directionalAmb, groundBounceFill, downMask);
    vec3 skyAmb = directionalAmb * (skyMask * nightSkyLift);
    // Disable ALL vanilla lighting (zero fake cave ambient, zero vanilla blocklight)
    vec3 caveAmb = vec3(0.0);
    vec3 blockLight = vec3(0.0);

    // Pixel-selective PBR specular & emissive:
    // Only bright highlight pixels (or rain-wet surfaces) get sharp GGX specular glints;
    // dull/matte pixels stay rough with zero plastic sheen.
    vec3 H = normalize(worldLightDir + worldViewDir);
    float NdotH = max(dot(worldN, H), 0.0);
    float NdotV = max(dot(worldN, worldViewDir), 0.001);
    float texLum = luminance(tex.rgb);
    float peakTex = max(tex.r, max(tex.g, tex.b));
    float pbrHighlight = max(texLum, peakTex * 0.92);
    float specBrightMask = smoothstep(0.62, 0.90, pbrHighlight);
    float baseRough = mix(0.94, 0.18, specBrightMask);
    float rough = mix(baseRough, 0.14, wetness);
    float D = chunkD_GGX(NdotH, rough);
    float G = chunkG_Smith(NdotV, max(NdotL, 0.001), rough);
    vec3 highlightF0 = mix(vec3(0.04), mix(vec3(0.14), linearAlbedo * 0.65 + vec3(0.18), 0.65), specBrightMask);
    vec3 F0 = mix(highlightF0, vec3(0.06), wetness);
    vec3 F = chunkF_Schlick(max(dot(H, worldViewDir), 0.0), F0);
    // Horizon specular occlusion prevents grazing-angle light leaks on micro-normal edges
    float horizonOcc = smoothstep(0.0, 0.12, geomNdotL);
    vec3 spec = (D * G * F) / max(4.0 * NdotV * max(NdotL, 0.001), 0.001);
    float specGate = max(specBrightMask * 1.15, wetness);
    spec = min(spec, vec3(2.8)) * vSunlight * (NdotL * shadow * horizonOcc * sunIntensity * mix(rainClear, 0.55, wetness) * specGate);
    if (isPlant && rainStrength < 0.1) {
        spec *= 0.10;
    }

    vec3 color = linearAlbedo * (directLight + skyAmb + caveAmb + blockLight) + plantSSS + spec;

    // Pixel-selective emissive with local 8-tap texel glow spill:
    // Bright emissive pixels glow vividly, while adjacent non-emissive crevices/frames
    // are naturally illuminated by neighboring glowing texels instead of turning pitch black.
    if (emissive > 0.001) {
        float pixelEmissiveMask = smoothstep(0.58, 0.86, peakTex) * smoothstep(0.42, 0.78, texLum);
        float emLum = luminance(linearAlbedo);
        vec3 vividEmissive = max(vec3(emLum) + (linearAlbedo - vec3(emLum)) * 1.25, vec3(0.0));
        color += vividEmissive * (emissive * pixelEmissiveMask * 3.0);

        // Gather local surface illumination from 8 neighboring texels on the same 16x16 block face
        const vec2 spillOffsets[8] = vec2[8](
            vec2( 1.0,  0.0), vec2(-1.0,  0.0), vec2( 0.0,  1.0), vec2( 0.0, -1.0),
            vec2( 2.0,  1.0), vec2(-2.0,  1.0), vec2( 2.0, -1.0), vec2(-2.0, -1.0)
        );
        vec3 neighborGlow = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            vec3 nTex = textureLod(Sampler0, clamp(vTexCoord + spillOffsets[i] * texel, tileMin, tileMax), 0.0).rgb;
            float nPeak = max(nTex.r, max(nTex.g, nTex.b));
            float nLum = luminance(nTex);
            float nMask = smoothstep(0.52, 0.84, nPeak) * smoothstep(0.38, 0.75, nLum);
            neighborGlow += pow(max(nTex, vec3(0.0)), vec3(2.1)) * nMask;
        }
        neighborGlow *= (1.0 / 8.0);
        color += linearAlbedo * (neighborGlow * 1.65 + vec3(0.14)) * (emissive * (1.0 - pixelEmissiveMask * 0.75));
    }

    fragData0 = vec4(max(color, vec3(0.0)), albedo.a);
    // Write blended micro-bevel normal to colortex1 so point lights & LPV reveal 3D brick/stone relief
    vec3 gbufN = normalize(mix(geomN, worldN, 0.70));
    fragData1 = vec4(octEncode(gbufN), 0.0, vLightmap.y);
    fragData2 = vec4(clamp(linearAlbedo, vec3(0.0), vec3(1.0)), 1.0);
}
