#version 450

#include "common/raindio_uniforms.glsl"
#include "lib/common.glsl"

layout(set = 0, binding = 1) uniform sampler2D Sampler0;
layout(set = 0, binding = 2) uniform sampler2D shadowtex0;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vLightmap;
layout(location = 3) in vec3 vWorldRel;
layout(location = 4) in vec3 vWorldNormal;
layout(location = 5) in vec3 vSkyColor;
layout(location = 6) in vec3 vSunlight;
layout(location = 7) in vec4 vParams;

layout(location = 0) out vec4 fragData0;
layout(location = 1) out vec4 fragData1;
layout(location = 2) out vec4 fragData2;

vec2 vogelDiskSample(int sampleIdx, int totalSamples, float phi) {
    float r = sqrt((float(sampleIdx) + 0.5) / float(totalSamples));
    float theta = float(sampleIdx) * 2.399963229728653 + phi;
    return vec2(cos(theta), sin(theta)) * r;
}

float entityD_GGX(float NdotH, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / max(3.14159265 * denom * denom, 1e-5);
}

float entityG_Smith(float NdotV, float NdotL, float rough) {
    float k = (rough + 1.0);
    k = (k * k) * 0.125;
    float gv = NdotV / (NdotV * (1.0 - k) + k);
    float gl = NdotL / (NdotL * (1.0 - k) + k);
    return gv * gl;
}

vec3 entityF_Schlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// Same contact-hardening PCSS as chunk_terrain.frag so entity shadows match terrain seamlessly
float sampleShadowPCSS(vec3 worldRel, vec3 geomN, float NdotL, float skyLight) {
    vec4 baseClip = shadowProjection * shadowModelView * vec4(worldRel, 1.0);
    float baseL = length(baseClip.xy / max(abs(baseClip.w), 1e-5));
    float distortFactor = (baseL > 0.00001) ? (baseL * 0.8 + 0.2) : 0.2;
    float texelScale = clamp(distortFactor * distortFactor * 3.2, 0.30, 1.75);

    float slopeSin = sqrt(max(0.0, 1.0 - NdotL * NdotL));
    vec3 biasedPos = worldRel + geomN * ((0.010 + 0.020 * slopeSin) * texelScale);

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
    float baseBias = (0.00008 + 0.00015 * slopeSin) * texelScale;
    float receiverZ = shadowCoord.z - baseBias;

#if defined(TAA_ENABLED) && TAA_ENABLED == 1
    float phi = fract(interleavedGradientNoise(gl_FragCoord.xy) + fract(float(frameCounter & 1023) * 0.61803398875)) * 6.2831853;
#else
    float phi = interleavedGradientNoise(gl_FragCoord.xy) * 6.2831853;
#endif

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

    if (blockerCount == 0) {
        return 1.0;
    }

    float avgBlockerZ = blockerDepthSum / float(blockerCount);
    float blockerDistMeters = max(shadowCoord.z - avgBlockerZ, 0.0) * 300.0;
    float penumbraTexels = clamp(1.0 + blockerDistMeters * 0.35, 1.0, 3.4);
    float filterRadius = penumbraTexels * oneTexel * mix(1.0, invDistort * 0.36, 0.55);

    float vis = 0.0;
    for (int i = 0; i < PCF_TAPS; i++) {
        vec2 tapUV = clamp(shadowCoord.xy + vogelDiskSample(i, PCF_TAPS, phi + 1.17) * filterRadius, vec2(0.001), vec2(0.999));
        float mapZ = textureLod(shadowtex0, tapUV, 0.0).r;
        vis += (receiverZ <= mapZ) ? 1.0 : 0.0;
    }
    float shadow = vis * (1.0 / float(PCF_TAPS));

    vec2 edgeDist = abs(shadowCoord.xy - 0.5) * 2.0;
    float borderFade = 1.0 - smoothstep(0.86, 0.98, max(edgeDist.x, edgeDist.y));
    return mix(outdoorFallback, shadow, borderFade);
}

void main() {
    vec4 tex = texture(Sampler0, vTexCoord);
    if (tex.a < 0.1) {
        discard;
    }

    // Normalize model world normal; fallback to geometric derivative normal if degenerate
    float nLen = length(vWorldNormal);
    vec3 worldN;
    if (nLen > 1e-4) {
        worldN = vWorldNormal / nLen;
    } else {
        vec3 dpdx = dFdx(vWorldRel);
        vec3 dpdy = dFdy(vWorldRel);
        vec3 cn = cross(dpdx, dpdy);
        float cLen = length(cn);
        worldN = (cLen > 1e-8) ? (cn / cLen) : vec3(0.0, 1.0, 0.0);
    }
    // Flip backfaces cleanly for double-sided entity geometry (clothing, wings, capes, foliage-like hats)
    if (!gl_FrontFacing) {
        worldN = -worldN;
    }

    vec4 albedo = tex * vColor;
    float wetness = 0.0;
    if (rainStrength > 0.005) {
        float skyExposure = smoothstep(0.50, 0.90, vLightmap.y);
        float upFactor = clamp(worldN.y * 0.65 + 0.35, 0.0, 1.0);
        wetness = saturate(rainStrength) * skyExposure * upFactor;
        albedo.rgb *= mix(1.0, 0.78, wetness);
    }

    vec3 linearAlbedo = pow(max(albedo.rgb, vec3(0.0)), vec3(2.1));

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    vec3 worldLightDir = (worldSunDir.y >= 0.0) ? worldSunDir : -worldSunDir;
    vec3 worldViewDir = normalize(-vWorldRel);

    float dayFactor = vParams.x;
    float sunIntensity = vParams.y;
    float emissive = vParams.z;
    float rainClear = 1.0 - saturate(rainStrength);
    float NdotL = max(dot(worldN, worldLightDir), 0.0);

    float skyShadowGate = smoothstep(0.20, 0.55, vLightmap.y);
    float shadow = 0.0;
    if (NdotL > 0.001 && skyShadowGate > 0.001) {
        shadow = sampleShadowPCSS(vWorldRel, worldN, NdotL, vLightmap.y) * skyShadowGate;
    }

    float nightSkyLift = mix(1.10, 1.0, smoothstep(-0.10, 0.18, worldSunDir.y));
    vec3 sunLightCol = vSunlight * (sunIntensity * (3.1 * rainClear + 0.30) * nightSkyLift);
    vec3 directLight = sunLightCol * (NdotL * shadow);

    // Same 3-Zone Directional Ambient as chunk_terrain.frag (strictly sky-gated, zero vanilla lighting)
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
    vec3 caveAmb = vec3(0.0);
    vec3 blockLight = vec3(0.0);

    // Pixel-selective GGX PBR specular & emissive (only high-brightness pixels glint/glow)
    vec3 H = normalize(worldLightDir + worldViewDir);
    float NdotH = max(dot(worldN, H), 0.0);
    float NdotV = max(dot(worldN, worldViewDir), 0.001);
    float texLum = luminance(tex.rgb);
    float peakTex = max(tex.r, max(tex.g, tex.b));
    float pbrHighlight = max(texLum, peakTex * 0.92);
    float specBrightMask = smoothstep(0.62, 0.90, pbrHighlight);
    float baseRough = mix(0.94, 0.20, specBrightMask);
    float rough = mix(baseRough, 0.16, wetness);
    float D = entityD_GGX(NdotH, rough);
    float G = entityG_Smith(NdotV, max(NdotL, 0.001), rough);
    vec3 highlightF0 = mix(vec3(0.04), mix(vec3(0.14), linearAlbedo * 0.65 + vec3(0.18), 0.65), specBrightMask);
    vec3 F0 = mix(highlightF0, vec3(0.06), wetness);
    vec3 F = entityF_Schlick(max(dot(H, worldViewDir), 0.0), F0);
    float horizonOcc = smoothstep(0.0, 0.12, NdotL);
    vec3 spec = (D * G * F) / max(4.0 * NdotV * max(NdotL, 0.001), 0.001);
    float specGate = max(specBrightMask * 1.15, wetness);
    spec = min(spec, vec3(2.6)) * vSunlight * (NdotL * shadow * horizonOcc * sunIntensity * mix(rainClear, 0.55, wetness) * specGate);

    vec3 color = linearAlbedo * (directLight + skyAmb + caveAmb + blockLight) + spec;
    if (emissive > 0.001) {
        float pixelEmissiveMask = smoothstep(0.58, 0.86, peakTex) * smoothstep(0.42, 0.78, texLum);
        float emLum = luminance(linearAlbedo);
        vec3 vividEmissive = max(vec3(emLum) + (linearAlbedo - vec3(emLum)) * 1.25, vec3(0.0));
        color += vividEmissive * (emissive * pixelEmissiveMask * 3.4);
    }

    fragData0 = vec4(max(color, vec3(0.0)), 1.0);
    fragData1 = vec4(octEncode(worldN), 0.0, vLightmap.y);
    fragData2 = vec4(clamp(linearAlbedo, vec3(0.0), vec3(1.0)), 1.0);
}
