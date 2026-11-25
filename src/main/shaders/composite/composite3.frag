#version 450
#include "common/raindio_uniforms.glsl"

/* DRAWBUFFERS:0 */

layout(location = 0) in vec2 texCoord;

layout(set = 0, binding = 1) uniform sampler2D colortex0;
layout(set = 0, binding = 2) uniform sampler2D colortex1;
layout(set = 0, binding = 3) uniform sampler2D colortex2;
layout(set = 0, binding = 4) uniform sampler2D colortex3;
layout(set = 0, binding = 5) uniform sampler2D colortex6;
layout(set = 0, binding = 6) uniform sampler2D colortex9;
layout(set = 0, binding = 7) uniform sampler2D depthtex0;
layout(set = 0, binding = 8) uniform sampler2D depthtex1;
layout(set = 0, binding = 9) uniform sampler2D noisetex;
layout(set = 0, binding = 10) uniform sampler2D shadowtex0;
layout(set = 0, binding = 11) uniform sampler2D pointLightTex;



#include "lib/common.glsl"
#include "lib/projection.glsl"
#include "lib/pbr.glsl"
#include "lib/atmosphere.glsl"
#include "lib/clouds.glsl"
#include "lib/sky.glsl"
#include "lib/water.glsl"
#include "lib/fog.glsl"
#include "lib/volumetric.glsl"

#ifndef HALF_RES_COLORTEX9
#define HALF_RES_COLORTEX9 1
#endif
#ifndef POINT_LIGHT_BILATERAL
#define POINT_LIGHT_BILATERAL 1
#endif

#define WET_REFLECTION_INTENSITY 0.48
#ifndef WATER_CLARITY
#define WATER_CLARITY 1.0       // [0.5 0.75 1.0 1.25 1.5]
#endif
#define WATER_TINT_STRENGTH 1.0 // [0.4 0.7 1.0 1.3 1.6]
#define WATER_BRIGHTNESS 0.85   // [0.5 0.75 0.85 1.0 1.25 1.5]
#define WATER_FRESNEL_MAX 0.98  // [0.85 0.90 0.95 0.98 1.0]
//#define WATER_SHORE_FOAM

layout(location = 0) out vec4 fragData0;

void main() {
    vec2 uv = texCoord;
    ivec2 pixelCoord = ivec2(gl_FragCoord.xy);

    float waterDepth  = texture(depthtex0, uv).r;
    float seabedDepth = texture(depthtex1, uv).r;
    vec3 hdr = texture(colortex0, uv).rgb;

    vec4 colortex1_data = texelFetch(colortex1, pixelCoord, 0);
    int blockId = int(colortex1_data.z * 255.0 + 0.5);
    vec2 encodedDataN = colortex1_data.rg;
    float skyLightData = colortex1_data.a;

    float vlDither = interleavedGradientNoise(gl_FragCoord.xy);

    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    vec3 viewSunDir = normalize((gbufferModelView * vec4(realSunDir, 0.0)).xyz);

    float sunHeight = realSunDir.y;
    vec3 sunColor = mix(vec3(1.00, 0.44, 0.20), vec3(1.00, 0.98, 0.92), saturate(sunHeight * 2.5));
    float nightDim = mix(0.03, 1.0, saturate(sunHeight * 4.0 + 0.5));
    float twilightBlend = smoothstep(-0.12, 0.06, sunHeight);

    bool isDay = realSunDir.y > 0.0;
    vec3 mainLightViewDir = isDay ? viewSunDir : -viewSunDir;
    vec3 mainLightColor   = isDay ? sunColor : vec3(0.24, 0.42, 0.74);
    float mainLightVis    = isDay ? saturate(realSunDir.y * 3.0) : saturate(-realSunDir.y * 3.0);
    float mainLightPower  = isDay ? 80.0 : 2.8;

    bool isNotSky = isSceneDepth(waterDepth);
    bool isFlowingWater = (blockId == MAT_WATER_FLOWING);
    bool isWater = isMatWater(blockId) && isNotSky;
    bool isGlass = isMatTranslucentNonWater(blockId) && isNotSky;
    bool underwater = (isEyeInWater == 1);

    vec4 ndc = vec4(uv * 2.0 - 1.0, 0.5, 1.0);
    vec4 vp4 = gbufferProjectionInverse * ndc;
    vec3 viewDir = normalize(vp4.xyz / vp4.w);
    vec3 worldDir = normalize((gbufferModelViewInverse * vec4(viewDir, 0.0)).xyz);

    // Dynamic underwater surface Y estimate from eyeBrightnessSmooth.y
    float eyeSkyLight = clamp(float(eyeBrightnessSmooth.y) / 240.0, 0.0, 1.0);
    float estUnderwaterDepth = clamp((1.0 - eyeSkyLight) * 15.0, 0.5, 16.0);
    float dynamicUnderwaterSurfaceY = cameraPosition.y + estUnderwaterDepth;

    float skyMaskDepth = (underwater && !isWater) ? seabedDepth : waterDepth;
    bool isSkyPixel = isSkyDepth(skyMaskDepth);

    // Nighttime sky/moon lift is now applied directly in chunk_terrain.frag so enclosed caves stay consistent

    vec3 dbgWaterSpec = vec3(0.0);
    vec3 dbgWaterSkyRefl = vec3(0.0);
    vec3 dbgWaterRefr = vec3(0.0);

    // Pre-read water normal & compute screen-space normal variance outside non-uniform branch
    // so dFdx/dFdy are always well-defined for specular anti-aliasing (eliminates sun glint flicker)
    vec4 preWaterData = texelFetch(colortex3, pixelCoord, 0);
    vec3 preWaterWorldN = octDecode(preWaterData.rg);
    vec3 preWaterViewN = normalize(mat3(gbufferModelView) * preWaterWorldN);
    vec3 dnx_w = dFdx(preWaterViewN);
    vec3 dny_w = dFdy(preWaterViewN);
    float waterNormalVar = min(dot(dnx_w, dnx_w) + dot(dny_w, dny_w), 0.25);

    // ==================== 1. SEUS PTGI-Style Water Shading ====================
    if (isWater) {
        vec3 waterViewPos = ScreenToView(uv, waterDepth);
        vec3 waterViewDir = normalize(waterViewPos);
        float viewDist = length(waterViewPos);
        vec3 waterWorldPos = (gbufferModelViewInverse * vec4(waterViewPos, 1.0)).xyz + cameraPosition;
        vec3 worldViewDir = normalize((gbufferModelViewInverse * vec4(waterViewDir, 0.0)).xyz);

        // Geometric flat normal in view space
        vec3 geometricWorldNormal = octDecode(encodedDataN);
        geometricWorldNormal.y = underwater ? -abs(geometricWorldNormal.y) : abs(geometricWorldNormal.y);
        vec3 geometricViewNormal = normalize(mat3(gbufferModelView) * normalize(geometricWorldNormal));
        float flatNdotV_w = max(0.001, dot(geometricViewNormal, -waterViewDir));

        // Initial optical path thickness
        bool hasNoSeabed = isSkyDepth(seabedDepth);
        vec3 seabedViewPos = hasNoSeabed ? (waterViewPos + waterViewDir * 28.0) : ScreenToView(uv, seabedDepth);
        float rawThickness = length(seabedViewPos - waterViewPos);
        float waterThickness = hasNoSeabed
            ? clamp(18.0 / max(flatNdotV_w, 0.25), 18.0, 40.0)
            : clamp(rawThickness, 0.0, 32.0);

        // Use precomputed PTGI water surface data from colortex3
        vec3 waterWorldNormal = preWaterWorldN;
        float waveH_raw = preWaterData.b;
        float waterRoughness = preWaterData.a;
        vec3 waterNormal = preWaterViewN;

        float NdotV_raw = dot(waterNormal, -waterViewDir);
        if (NdotV_raw < 0.002) {
            waterNormal = normalize(waterNormal - waterViewDir * (0.002 - NdotV_raw));
        }

        // Roughness-driven gentle 4-tap reflection blur (keeps crisp PTGI mirror when calm)
        vec3 reflection = texture(colortex9, uv).rgb;
        if (waterRoughness > 0.065) {
            vec2 blurStep = vec2(1.0 / max(viewWidth, 1.0), 1.0 / max(viewHeight, 1.0)) * clamp((waterRoughness - 0.065) * 14.0, 0.5, 3.0);
            vec3 reflSum = reflection * 2.5;
            float reflW = 2.5;
            const vec2 blurOffsets[4] = vec2[4](vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0));
            for (int k = 0; k < 4; k++) {
                vec2 sUV = clamp(uv + blurOffsets[k] * blurStep, vec2(0.002), vec2(0.998));
                int tapMat = int(texelFetch(colortex1, ivec2(sUV * vec2(viewWidth, viewHeight)), 0).z * 255.0 + 0.5);
                if (isMatWater(tapMat)) {
                    reflSum += texture(colortex9, sUV).rgb;
                    reflW += 1.0;
                }
            }
            reflection = reflSum / reflW;
        }

        // Physical Snell's Law depth-scaled refraction (zero shoreline tearing)
        vec3 refractedDirVS = refract(waterViewDir, waterNormal, underwater ? 1.333 : (1.0 / 1.333));
        vec2 snellDeltaXY = (dot(refractedDirVS, refractedDirVS) > 0.0001)
            ? (refractedDirVS - waterViewDir).xy
            : (waterNormal - geometricViewNormal).xy;

        vec2 edgeMask = smoothstep(vec2(0.0), vec2(0.05), uv) * (vec2(1.0) - smoothstep(vec2(0.95), vec2(1.0), uv));
        float refrEdgeFade = edgeMask.x * edgeMask.y;
        float refrDistFade = 1.0 / (1.0 + viewDist * 0.032);
        float refrDepthScale = smoothstep(0.0, 2.2, waterThickness);
        vec2 refractOffset = snellDeltaXY * (0.048 * refrEdgeFade * refrDistFade * mix(0.08, 1.15, refrDepthScale));

        vec2 refractUV = clamp(uv + refractOffset, vec2(0.002), vec2(0.998));
        float refrSeabedDepth = texture(depthtex1, refractUV).r;
        // Reverse-Z: seabed behind water has strictly SMALLER depth than water surface
        bool refrValid = (refrSeabedDepth < waterDepth - 1e-5) && (hasNoSeabed || isSceneDepth(refrSeabedDepth));
        if (!refrValid) {
            refractUV = uv;
            refrSeabedDepth = seabedDepth;
        }

        // Recompute effective optical thickness at the refracted seabed point (removes submerged block halos)
        vec3 effSeabedViewPos = hasNoSeabed ? seabedViewPos : ScreenToView(refractUV, refrSeabedDepth);
        float effRawThickness = hasNoSeabed ? rawThickness : max(0.0, length(effSeabedViewPos - waterViewPos));
        float effThickness = hasNoSeabed ? waterThickness : clamp(effRawThickness, 0.0, 32.0);

        // Subtle near-field RGB chromatic dispersion
        float dispDistFade = 1.0 - smoothstep(10.0, 28.0, viewDist);
        vec2 dispDir = normalize(snellDeltaXY + vec2(1e-5));
        vec2 dispOffset = dispDir * (1.8 / vec2(viewWidth, viewHeight)) * WATER_DISPERSION * dispDistFade * refrEdgeFade * clamp(length(snellDeltaXY) * 7.0 + 0.25, 0.25, 1.4);
        vec2 uvR = clamp(refractUV + dispOffset, vec2(0.002), vec2(0.998));
        vec2 uvB = clamp(refractUV - dispOffset, vec2(0.002), vec2(0.998));
        float dR = texture(depthtex1, uvR).r;
        float dB = texture(depthtex1, uvB).r;
        if (dR >= waterDepth - 1e-5 || (!hasNoSeabed && isSkyDepth(dR))) uvR = refractUV;
        if (dB >= waterDepth - 1e-5 || (!hasNoSeabed && isSkyDepth(dB))) uvB = refractUV;

        vec3 refractionColor = vec3(
            texture(colortex0, uvR).r,
            texture(colortex0, refractUV).g,
            texture(colortex0, uvB).b
        );

        vec3 rawBiomeWater = texture(colortex2, uv).rgb;
        vec3 biomeWater = pow(clamp(rawBiomeWater, vec3(0.04), vec3(1.0)), vec3(2.2));
        float skyLightFactor = smoothstep(0.05, 0.90, skyLightData);

        if (!hasNoSeabed) {
            // Seabed already includes night sky/moon lift from chunk_terrain.frag
        } else {
            if (underwater) {
                vec3 surfaceOutNormal = -waterWorldNormal;
                if (dot(worldViewDir, surfaceOutNormal) > 0.0) surfaceOutNormal = -surfaceOutNormal;

                float dispIOR = 0.007 * WATER_DISPERSION;
                vec3 refrR = refract(worldViewDir, surfaceOutNormal, 1.333 - dispIOR);
                vec3 refrG = refract(worldViewDir, surfaceOutNormal, 1.333);
                vec3 refrB = refract(worldViewDir, surfaceOutNormal, 1.333 + dispIOR);
                if (dot(refrG, refrG) > 0.0001) {
                    vec3 skyG = ComputeFullSkyHDR(colortex6, normalize(refrG), realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false);
                    float rComp = (WATER_DISPERSION > 0.01 && dot(refrR, refrR) > 0.0001) ? ComputeFullSkyHDR(colortex6, normalize(refrR), realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false).r : skyG.r;
                    float bComp = (WATER_DISPERSION > 0.01 && dot(refrB, refrB) > 0.0001) ? ComputeFullSkyHDR(colortex6, normalize(refrB), realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false).b : skyG.b;
                    refractionColor = vec3(rComp, skyG.g, bComp);
                } else {
                    refractionColor = reflection;
                }
            } else {
                vec3 ptgiDeepBase = vec3(0.004, 0.052, 0.046);
                vec3 deepOceanBase = mix(ptgiDeepBase, ptgiDeepBase * (0.65 + biomeWater * 0.70), 0.30);
                vec3 deepOcean = deepOceanBase * nightDim * (isDay ? mix(0.35, 1.0, mainLightVis) : 1.0) * mix(0.08, 1.0, skyLightFactor);
                refractionColor = deepOcean;
            }
        }

        float NdotV = max(0.001, dot(waterNormal, -waterViewDir));

        if (underwater) {
            float tirFactor = 1.0 - smoothstep(0.62, 0.70, NdotV);
            float schlick = 0.02 + 0.98 * pow(max(1.0 - NdotV, 0.0), 4.0);
            float fresnel = clamp(mix(schlick, 1.0, tirFactor), 0.02, 1.0);

            hdr = mix(refractionColor, reflection, fresnel);
            hdr = apply_underwater_fog(hdr, waterViewPos, nightDim, skyLightFactor);

            float uwSkyVis = smoothstep(0.05, 0.45, eyeSkyLight);
            if (uwSkyVis > 0.01) {
                vec4 volFog = CalculateUnderwaterVolumetricLight(
                    vec3(0.0), waterViewPos, waterWorldPos.y, vlDither,
                    mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower
                );
                hdr += volFog.rgb * uwSkyVis;
            }
        } else {
            // True PTGI wave-driven dielectric Fresnel (IOR 1.333 -> F0 = 0.02)
            float fresnelNdotV = mix(flatNdotV_w, NdotV, 0.78);
            float fresnel = clamp(0.02 + 0.98 * pow(max(1.0 - fresnelNdotV, 0.0), 5.0), 0.02, WATER_FRESNEL_MAX);

            mat4 shadowMat = shadowProjection * shadowModelView * gbufferModelViewInverse;
            float waterShadow = getSampleShadow(waterViewPos, shadowMat);
            float rainClear = 1.0 - saturate(rainStrength);

            // Wave-coherent PTGI chromatic seabed caustics
            if (!hasNoSeabed && isDay && effThickness > 0.03 && rainClear > 0.05) {
                vec3 seabedWorldPos = (gbufferModelViewInverse * vec4(effSeabedViewPos, 1.0)).xyz + cameraPosition;
                float rayToSurface = max(0.0, waterWorldPos.y - seabedWorldPos.y) / max(realSunDir.y, 0.15);
                vec3 causticPos = seabedWorldPos + realSunDir * rayToSurface;
                float cTime = water_time() * SEA_SPEED;
                vec3 causticRGB = get_water_caustics_rgb(causticPos.xz, cTime, viewDist);
                float sunElevFade = smoothstep(0.05, 0.35, realSunDir.y);
                float causticFade = exp(-effThickness * 0.115) * smoothstep(0.02, 0.38, effThickness) * sunElevFade;
                refractionColor += (refractionColor * 1.15 + vec3(0.022)) * mainLightColor * causticRGB * (1.28 * causticFade * waterShadow * rainClear);
            }

            // Teal / Blue-Green PTGI Beer-Lambert spectral absorption
            float rainTurbidity = mix(1.0, 1.42, saturate(rainStrength));
            float clarityInv = 1.0 / max(WATER_CLARITY, 0.25);
            vec3 ptgiAbsorbK = vec3(0.68, 0.135, 0.165);
            vec3 absorbK = mix(ptgiAbsorbK, ptgiAbsorbK * (0.82 + (vec3(1.0) - biomeWater) * 0.36), 0.32)
                         * rainTurbidity * WATER_TINT_STRENGTH * clarityInv;
            vec3 absorb = exp(-absorbK * effThickness);
            vec3 waterColor = refractionColor * absorb;

            // Rich blue-green volume in-scattering & water mist
            vec3 ptgiScatterAlbedo = vec3(0.012, 0.185, 0.160);
            vec3 waterAlbedo = mix(ptgiScatterAlbedo, ptgiScatterAlbedo * (0.68 + biomeWater * 0.64), 0.28);
            vec3 skyZenith = SampleSkyLUT(colortex6, vec3(0.0, 0.75, 0.0));
            vec3 skyScatterLight = skyZenith * 0.34 * skyLightFactor;
            vec3 sunScatterLight = mainLightColor * (isDay ? 0.65 : 0.035) * mainLightVis * waterShadow * mix(1.0, 0.25, saturate(rainStrength));
            vec3 waterScatterColor = waterAlbedo * (skyScatterLight + sunScatterLight + vec3(0.002, 0.009, 0.008) * nightDim * skyLightFactor) * WATER_BRIGHTNESS;

            float scatterFactor = clamp(1.0 - exp(-effThickness * 0.165 * rainTurbidity * clarityInv * WATER_FOG_DENSITY), 0.0, 0.94);
            vec3 refractedColor = mix(waterColor, waterScatterColor, scatterFactor);

            // PTGI Wave-Crest Translucent Subsurface Scattering
            if (isDay && mainLightVis > 0.05 && rainClear > 0.05) {
                float sssAlign = pow(max(0.0, dot(waterViewDir, mainLightViewDir)), 5.0);
                float crestMask = smoothstep(0.32, 0.95, waveH_raw);
                float grazingMask = pow(max(1.0 - NdotV, 0.0), 2.0);
                float depthMask = smoothstep(0.25, 2.5, effThickness);
                vec3 sssTint = vec3(0.025, 0.32, 0.27) * mainLightColor;
                refractedColor += sssTint * (sssAlign * crestMask * grazingMask * depthMask * 0.65 * mainLightVis * waterShadow * rainClear * skyLightFactor * WATER_BRIGHTNESS);
            }

            vec3 underwaterEndPos = hasNoSeabed
                ? (waterViewPos + waterViewDir * 16.0)
                : (waterViewPos + normalize(effSeabedViewPos - waterViewPos) * min(effRawThickness, 20.0));

            if (skyLightFactor > 0.05) {
                vec4 waterVolFog = CalculateUnderwaterVolumetricLight(
                    waterViewPos, underwaterEndPos, waterWorldPos.y, vlDither,
                    mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower
                );
                refractedColor += waterVolFog.rgb * (0.85 * skyLightFactor);
            }

            dbgWaterRefr = refractedColor;
#if DEBUG_VIEW == 12
            vec3 reflWDir = normalize((gbufferModelViewInverse * vec4(reflect(waterViewDir, waterNormal), 0.0)).xyz);
            vec3 skyReflDbgDir = normalize(vec3(reflWDir.x, max(reflWDir.y, 0.015), reflWDir.z));
            dbgWaterSkyRefl = ComputeFullSkyHDR(colortex6, skyReflDbgDir, realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false) * skyLightFactor;
#endif
            hdr = mix(refractedColor, reflection, fresnel);

            // Optional shoreline & waterfall foam (OFF by default; toggle in WATER menu)
#ifdef WATER_SHORE_FOAM
            float foamNoise = get_foam_noise(waterWorldPos.xz);
            float shoreBand = (1.0 - smoothstep(0.03, 0.85, effThickness)) * (1.0 - float(hasNoSeabed));
            float shoreMask = shoreBand * smoothstep(0.38, 0.78, foamNoise + shoreBand * 0.35);
            float waterfallMask = (abs(geometricWorldNormal.y) < 0.35) ? (smoothstep(0.40, 0.78, foamNoise) * 0.55) : 0.0;
            float totalFoam = clamp(shoreMask * 0.55 + waterfallMask, 0.0, 0.75);
            if (totalFoam > 0.01) {
                vec3 foamLight = skyZenith * 0.25 * max(skyLightFactor, 0.06) + mainLightColor * mainLightVis * (isDay ? 0.85 : 0.04) * waterShadow * rainClear;
                vec3 foamColor = mix(hdr * 1.1, vec3(0.84, 0.93, 0.96), 0.50) * foamLight;
                hdr = mix(hdr, foamColor, totalFoam * 0.45);
            }
#endif

            // ==================== Anti-Aliased Dual-Lobe PTGI Sun / Moon GGX Specular ====================
            vec3 V_w = -waterViewDir;
            vec3 L_w = mainLightViewDir;
            float NdotL_w = max(dot(waterNormal, L_w), 0.0);
            if (NdotL_w > 0.001 && rainClear > 0.02) {
                vec3 H_w = normalize(V_w + L_w);
                float NdotH_w = max(dot(waterNormal, H_w), 0.001);
                float VdotH_w = max(dot(V_w, H_w), 0.001);
                vec3 F_w = fresnelSchlick(VdotH_w, vec3(0.02));

                // Pixel-footprint normal-variance AA (eliminates TAA-less specular sparkle flicker, min rough 0.03)
                float specRoughAA = sqrt(clamp(waterRoughness * waterRoughness + waterNormalVar * 0.5, 0.0009, 0.35));

                // Lobe 1: Crisp, flicker-free liquid-glass sun glint core
                float coreRough = isDay
                    ? clamp(specRoughAA, 0.030, 0.28)
                    : clamp(specRoughAA + 0.022, 0.042, 0.32);
                float D_core = GGX_D(NdotH_w, coreRough);
                float G_core = GGX_G(NdotL_w, NdotV, coreRough);
                vec3 specCore = min((F_w * D_core * G_core) / max(4.0 * NdotL_w * NdotV, 0.001), vec3(24.0));

                // Lobe 2: Subtle silky wide sheen along the sun-path
                float sheenRough = clamp(specRoughAA + 0.070, 0.085, 0.36);
                float D_sheen = GGX_D(NdotH_w, sheenRough);
                float G_sheen = GGX_G(NdotL_w, NdotV, sheenRough);
                vec3 specSheen = min((F_w * D_sheen * G_sheen) / max(4.0 * NdotL_w * NdotV, 0.001), vec3(4.0)) * 0.14;

                float horizonSpecFade = smoothstep(0.0, 0.08, NdotV);

                float daylight = smoothstep(0.05, 0.45, sunHeight);
                vec3 warmSunSpec = vec3(1.0, 0.66, 0.38);
                vec3 neutralSunSpec = vec3(1.0, 0.99, 0.97);
                vec3 waterSpecularLightColor = isDay ? mix(warmSunSpec, neutralSunSpec, daylight) : vec3(0.65, 0.80, 1.0);

                float specScale = (isDay ? 0.22 : 0.11) * mainLightPower * mainLightVis * waterShadow * rainClear * skyLightFactor;
                vec3 waterSpecContrib = waterSpecularLightColor * (specCore + specSheen) * NdotL_w * horizonSpecFade * specScale;
                dbgWaterSpec = waterSpecContrib;
                hdr += waterSpecContrib;
            }

            // Clustered PBR point-light colored GGX wave reflections on PTGI water
            hdr += texelFetch(pointLightTex, pixelCoord, 0).rgb;

            hdr = apply_terrain_fog(hdr, waterViewPos);
            vec4 volFog = CalculateVolumetricFogAndLight(waterViewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
            float farVolBlend = smoothstep(max(far, 64.0) * 0.62, max(far, 64.0) * 0.98, viewDist);
            hdr = hdr * mix(1.0, volFog.a, mix(0.6, 1.0, farVolBlend)) + volFog.rgb * mix(0.7, 1.0, farVolBlend);
        }
    }
    // ==================== 2. Glass & Translucent Ice/Slime Shading ====================
    else if (isGlass) {
        vec3 glassViewPos = ScreenToView(uv, waterDepth);
        vec3 glassViewDir = normalize(glassViewPos);
        float skyLightFactor = smoothstep(0.05, 0.85, skyLightData);

        vec4 glassAlbedo = texture(colortex2, uv);
        vec3 glassWorldNormal = octDecode(encodedDataN);
        vec3 glassNormal = normalize(mat3(gbufferModelView) * glassWorldNormal);

        vec3 reflection = texture(colortex9, uv).rgb;
        vec3 refractionColor = hdr;

        if (isSkyDepth(seabedDepth)) {
            refractionColor = ComputeFullSkyHDR(colortex6, worldDir, realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false);
        } else {
            if (underwater) {
                vec3 seabedViewPos = ScreenToView(uv, seabedDepth);
                float thickness = max(0.0, length(seabedViewPos - glassViewPos));
                if (thickness > 0.05) {
                    vec3 absorb = exp(vec3(-0.20, -0.055, -0.070) * thickness * WATER_FOG_DENSITY);
                    vec3 waterColor = refractionColor * absorb;
                    float fogFactor = 1.0 - exp(-thickness * 0.065 * WATER_FOG_DENSITY);
                    vec3 waterFogColor = vec3(0.012, 0.095, 0.085) * nightDim * skyLightFactor;
                    refractionColor = mix(waterColor, waterFogColor, fogFactor);
                }
            }
        }

        vec3 glassWorldPos = (gbufferModelViewInverse * vec4(glassViewPos, 1.0)).xyz + cameraPosition;
        float NdotL = max(dot(glassNormal, mainLightViewDir), 0.0);
        float NdotV = max(0.0, dot(glassNormal, -glassViewDir));
        vec3 halfVec = normalize(-glassViewDir + mainLightViewDir);
        float NdotH = max(0.0, dot(glassNormal, halfVec));

        vec3 glassDirect = mainLightColor * mainLightVis * (isDay ? 0.65 : 0.03) * NdotL * skyLightFactor;
        vec3 glassAmbient = (isDay ? vec3(0.22, 0.32, 0.46) : vec3(0.008, 0.014, 0.025)) * mix(0.25, 1.0, skyLightFactor);

        float maxFresnel = 0.72;
        float specPow = 256.0;
        float specBoost = 0.05;

        if (blockId == MAT_ICE) {
            float frostNoise = texture(noisetex, fract(glassWorldPos.xz * 0.45 + glassWorldPos.y * 0.25)).r - 0.5;
            vec2 iceUV = clamp(uv + (glassNormal.xy * 0.008 + vec2(frostNoise * 0.006)), vec2(0.002), vec2(0.998));
            vec3 iceBehind = (texture(depthtex1, iceUV).r < waterDepth) ? texture(colortex0, iceUV).rgb : refractionColor;
            vec3 iceTint = mix(iceBehind * vec3(0.78, 0.92, 1.0), vec3(0.55, 0.80, 0.98) * (glassDirect + glassAmbient), 0.35);
            refractionColor = mix(iceTint, glassAlbedo.rgb * (glassDirect + glassAmbient), glassAlbedo.a * 0.40);
            maxFresnel = 0.62;
            specPow = 192.0;
            specBoost = 0.07;
        } else if (blockId == MAT_SLIME) {
            vec3 slimeBehind = refractionColor * mix(vec3(1.0), vec3(0.45, 0.95, 0.42), 0.65);
            vec3 slimeSurface = glassAlbedo.rgb * (glassDirect * 1.1 + glassAmbient);
            refractionColor = mix(slimeBehind, slimeSurface, 0.48);
            maxFresnel = 0.42;
            specPow = 48.0;
            specBoost = 0.035;
        } else if (blockId == MAT_HONEY) {
            vec3 honeyBehind = refractionColor * vec3(0.92, 0.62, 0.16);
            vec3 honeySurface = mix(glassAlbedo.rgb, vec3(1.0, 0.68, 0.14), 0.35) * (glassDirect * 1.15 + glassAmbient);
            refractionColor = mix(honeyBehind, honeySurface, 0.55);
            maxFresnel = 0.48;
            specPow = 72.0;
            specBoost = 0.045;
        } else if (blockId == MAT_NETHER_PORTAL) {
            float pt = water_time();
            float swirl = texture(noisetex, fract(glassWorldPos.xy * 0.25 + vec2(pt * 0.08, -pt * 0.12))).r;
            vec2 portalUV = clamp(uv + vec2(sin(pt * 3.0 + swirl * 6.28), cos(pt * 2.5 + swirl * 6.28)) * 0.012, vec2(0.002), vec2(0.998));
            vec3 portalBehind = texture(colortex0, portalUV).rgb;
            vec3 portalGlow = vec3(0.56, 0.12, 0.95) * (1.25 + 0.45 * sin(pt * 3.2 + swirl * 6.28));
            refractionColor = mix(portalBehind * vec3(0.7, 0.3, 1.0), portalGlow, 0.72);
            maxFresnel = 0.25;
            specPow = 64.0;
            specBoost = 0.02;
        } else {
            // colortex0 already contains hardware alpha-blended multi-layer stained glass / translucent layers
            // from Pass 4a, so apply only a gentle front-pane tint & surface sheen here without double-darkening.
            vec3 tintedBehind = refractionColor * mix(vec3(1.0), mix(vec3(1.0), glassAlbedo.rgb, 0.55), clamp(glassAlbedo.a * 0.65, 0.0, 0.65));
            vec3 glassSurface = glassAlbedo.rgb * (glassDirect + glassAmbient);
            refractionColor = mix(tintedBehind, glassSurface, glassAlbedo.a * 0.16);
            maxFresnel = isMatGlass(blockId) ? 0.72 : 0.45;
        }

        float fresnel = clamp(0.03 + 0.97 * pow(max(1.0 - NdotV, 0.0), 5.0), 0.03, maxFresnel);
        hdr = mix(refractionColor, reflection, fresnel);

        float specPosition = pow(NdotH, specPow);
        hdr += mainLightColor * specPosition * mainLightPower * (isDay ? specBoost : 0.02) * mainLightVis * skyLightFactor * (1.0 - saturate(rainStrength));

        // Clustered PBR point-light highlights & colored glow on glass / ice / translucent panes
        float glassPlWeight = (blockId == MAT_NETHER_PORTAL) ? 0.35 : (isMatGlass(blockId) ? 0.90 : 0.75);
        hdr += texelFetch(pointLightTex, pixelCoord, 0).rgb * glassPlWeight;

        if (!underwater) {
            hdr = apply_terrain_fog(hdr, glassViewPos);
            vec4 volFog = CalculateVolumetricFogAndLight(glassViewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * volFog.a + volFog.rgb;
        }
    }
    // ==================== 3. Opaque Terrain & Entities ====================
    else if (isNotSky) {
        vec3 viewPos = ScreenToView(uv, waterDepth);
        vec3 worldNormal = octDecode(encodedDataN);
        vec3 viewNormal = normalize(mat3(gbufferModelView) * worldNormal);

        // Read pure unlit linear surface albedo from colortex2 (written by chunk_terrain.frag MRT #2)
        // Eliminates light-color-squared hue shifts under torches/soul-lanterns/sunset and dark-shadow distortion.
        vec4 gbufAlbedo = texelFetch(colortex2, pixelCoord, 0);
        vec3 pureAlbedo = (gbufAlbedo.a > 0.5) ? clamp(gbufAlbedo.rgb, vec3(0.02), vec3(1.0)) : vec3(0.55);

        float centerZ = max(-viewPos.z, 0.05);
        ivec2 fullSize = max(textureSize(depthtex0, 0), ivec2(1));
        vec2 invFullSize = 1.0 / vec2(fullSize);

#if HALF_RES_COLORTEX9 == 1
        // Exact integer 3x3 Depth+Normal Bilateral Upsample of half-resolution GTAO (colortex9.a)
        // Uses strict texelFetch(colortex1/depthtex0, tapHalf * 2, 0) — NEVER linearly interpolates octahedral normals!
        ivec2 halfSize = max(textureSize(colortex9, 0), ivec2(1));
        ivec2 centerHalf = ivec2(floor(uv * vec2(halfSize)));
        float occSum = 0.0;
        float occWSum = 0.0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                ivec2 tapHalf = clamp(centerHalf + ivec2(dx, dy), ivec2(0), halfSize - ivec2(1));
                ivec2 tapFull = clamp(tapHalf * 2, ivec2(0), fullSize - ivec2(1));
                float tapD = texelFetch(depthtex0, tapFull, 0).r;
                if (!isSceneDepth(tapD)) continue;
                float tapZ = linearizeDepth(tapD);
                vec3 tapN = octDecode(texelFetch(colortex1, tapFull, 0).rg);
                float wZ = exp(-abs(tapZ - centerZ) / (centerZ * 0.06 + 0.05));
                float wN = pow(max(0.0, dot(worldNormal, tapN)), 8.0);
                float wS = (dx == 0 && dy == 0) ? 1.0 : ((dx == 0 || dy == 0) ? 0.70 : 0.45);
                float w = wZ * wN * wS;
                occSum += texelFetch(colortex9, tapHalf, 0).a * w;
                occWSum += w;
            }
        }
        float occ = (occWSum > 1e-4) ? (occSum / occWSum) : texelFetch(colortex9, clamp(centerHalf, ivec2(0), halfSize - ivec2(1)), 0).a;
#else
        // Full-resolution GTAO (HIGH / CINEMATIC): exact 1:1 texelFetch with zero upsample blur
        float occ = texelFetch(colortex9, pixelCoord, 0).a;
#endif

        vec3 a_mb =  2.0404 * pureAlbedo - 0.3324;
        vec3 b_mb = -4.7951 * pureAlbedo + 0.6417;
        vec3 c_mb =  2.7552 * pureAlbedo + 0.6903;
        vec3 multiBounceAO = clamp(occ * ((occ * a_mb + b_mb) * occ + c_mb), vec3(0.18), vec3(1.0));
        hdr *= multiBounceAO;

        // Coplanar Multi-Ring À-Trous Denoiser on pointLightTex (ReSTIR DI + GI):
        // Honors POINT_LIGHT_BILATERAL: 17-tap on LOW/BALANCED, crisp 9-tap on HIGH/CINEMATIC
        // while strictly stopping at block edges & corners via 3D plane-distance + normal checks.
        vec3 curWorldRel = (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz;
        vec3 plSum = texelFetch(pointLightTex, pixelCoord, 0).rgb;
        float plWSum = 1.0;
        const ivec2 PL_OFFSETS[16] = ivec2[16](
            ivec2( 1,  0), ivec2(-1,  0), ivec2( 0,  1), ivec2( 0, -1),
            ivec2( 2,  1), ivec2(-2,  1), ivec2( 2, -1), ivec2(-2, -1),
            ivec2( 4,  0), ivec2(-4,  0), ivec2( 0,  4), ivec2( 0, -4),
            ivec2( 5,  4), ivec2(-5,  4), ivec2( 5, -4), ivec2(-5, -4)
        );
        const float PL_WEIGHTS[16] = float[16](
            0.92, 0.92, 0.92, 0.92,
            0.78, 0.78, 0.78, 0.78,
            0.58, 0.58, 0.58, 0.58,
            0.38, 0.38, 0.38, 0.38
        );
#if POINT_LIGHT_BILATERAL == 1
        const int PL_TAP_COUNT = 16;
#else
        const int PL_TAP_COUNT = 8;
#endif
        for (int s = 0; s < PL_TAP_COUNT; s++) {
            ivec2 tapCoord = clamp(pixelCoord + PL_OFFSETS[s], ivec2(0), fullSize - ivec2(1));
            float tapD = texelFetch(depthtex0, tapCoord, 0).r;
            if (!isSceneDepth(tapD)) continue;
            vec3 tapN = octDecode(texelFetch(colortex1, tapCoord, 0).rg);
            if (dot(worldNormal, tapN) < 0.95) continue;
            float tapZ = linearizeDepth(tapD);
            if (abs(tapZ - centerZ) > centerZ * 0.12 + 0.18) continue;

            vec2 tapUV = (vec2(tapCoord) + 0.5) * invFullSize;
            vec3 tapWorldRel = (gbufferModelViewInverse * vec4(ScreenToView(tapUV, tapD), 1.0)).xyz;
            float planeDist = abs(dot(tapWorldRel - curWorldRel, worldNormal));
            float wPlane = exp(-planeDist * 40.0);
            float w = wPlane * PL_WEIGHTS[s];
            if (w < 0.02) continue;

            plSum += texelFetch(pointLightTex, tapCoord, 0).rgb * w;
            plWSum += w;
        }
        vec3 filteredPointLight = plSum / plWSum;

        // Unified ReSTIR DI + LPV/ReSTIR GI (keep GTAO gentle so blocks never get dark outlines)
        hdr += filteredPointLight * mix(vec3(1.0), multiBounceAO, 0.12);

        if (underwater) {
            // Soft aquatic volume scatter ambient fill so shadowed seabed/walls stay teal instead of pitch black
            float uwAmbScale = mix(0.30, 1.0, eyeSkyLight);
            hdr += vec3(0.012, 0.048, 0.050) * nightDim * uwAmbScale * multiBounceAO;

            float uwSkyVis = smoothstep(0.02, 0.35, eyeSkyLight);
            if (isDay && uwSkyVis > 0.01) {
                vec3 uwWorldPos = (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz + cameraPosition;
                float uwDepth = max(0.0, dynamicUnderwaterSurfaceY - uwWorldPos.y);
                float rayToSurface = uwDepth / max(realSunDir.y, 0.15);
                vec3 causticPos = uwWorldPos + realSunDir * rayToSurface;
                float cTime = water_time() * SEA_SPEED;
                vec3 causticRGB = get_water_caustics_rgb(causticPos.xz, cTime, length(viewPos));
                float sunElevFade = smoothstep(0.05, 0.35, realSunDir.y);
                float uwFade = exp(-uwDepth * 0.07) * sunElevFade * uwSkyVis;
                mat4 shadowMat = shadowProjection * shadowModelView * gbufferModelViewInverse;
                float uwShadow = getSampleShadow(viewPos + viewNormal * 0.15, shadowMat);
                hdr += (hdr * 1.15 + vec3(0.026)) * mainLightColor * causticRGB * (1.30 * uwFade * uwShadow * (1.0 - saturate(rainStrength)));
            }
            hdr = apply_underwater_fog(hdr, viewPos, nightDim, eyeSkyLight);
            if (uwSkyVis > 0.01) {
                vec4 volFog = CalculateUnderwaterVolumetricLight(
                    vec3(0.0), viewPos, dynamicUnderwaterSurfaceY, vlDither,
                    mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower
                );
                hdr += volFog.rgb * uwSkyVis;
            }
        } else {
            // Strictly gate outdoor fog & sun/moon volumetric shafts by surface skyLightData
            // so enclosed indoor rooms and caves never leak godrays when standing near a doorway.
            float caveOpenFactor = smoothstep(0.04, 0.45, skyLightData);
            vec3 foggedOutdoor = apply_terrain_fog(hdr, viewPos);
            vec3 caveFogCol = vec3(0.008, 0.011, 0.017) * nightDim;
            float caveFogAmt = clamp(1.0 - exp(-length(viewPos) * 0.006), 0.0, 0.72);
            vec3 foggedCave = mix(hdr, caveFogCol, caveFogAmt);
            hdr = mix(foggedCave, foggedOutdoor, caveOpenFactor);
            if (caveOpenFactor > 0.01) {
                vec4 volFog = CalculateVolumetricFogAndLight(viewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
                hdr = hdr * mix(1.0, volFog.a, caveOpenFactor) + volFog.rgb * caveOpenFactor;
            }
        }

        // Wet ground & puddles reflection blend
        if (!underwater && rainStrength > 0.01) {
            float isFloor = smoothstep(0.45, 0.85, worldNormal.y);
            float skyLightFactor = smoothstep(0.60, 0.90, skyLightData);
            if (isFloor > 0.01 && skyLightFactor > 0.01) {
                vec3 worldPos = (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz + cameraPosition;
                float puddle = get_puddle_mask(worldPos.xz);
                float wetness = saturate(rainStrength) * skyLightFactor * isFloor * mix(0.65, 1.0, puddle);

                if (wetness > 0.01) {
                    vec3 vDir = normalize(viewPos);
                    hdr *= mix(1.0, 0.82, wetness);
                    float NdotV = max(0.0, dot(viewNormal, -vDir));
                    vec3 wetReflection = texture(colortex9, uv).rgb;
                    float fresnel = clamp(mix(0.04, 0.55, pow(max(1.0 - NdotV, 0.0), 3.0)), 0.04, 0.55);
                    float reflectFactor = fresnel * wetness * mix(0.60, 1.0, puddle) * WET_REFLECTION_INTENSITY;
                    hdr = mix(hdr, wetReflection, reflectFactor);
                }
            }
        }
    }

    // ==================== 4. Sky Pixels Only ====================
    if (isSkyPixel) {
        vec3 skyHDR = ComputeFullSkyHDR(colortex6, worldDir, realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, true);

        if (underwater) {
            float distToSurface = (worldDir.y > 0.02)
                ? clamp((dynamicUnderwaterSurfaceY - cameraPosition.y) / worldDir.y, 0.5, 35.0)
                : 35.0;
            vec3 skyViewPos = viewDir * distToSurface;

            if (worldDir.y > 0.0) {
                vec3 virtualWaterPos = cameraPosition + worldDir * distToSurface;
                float dummyH, dummyR;
                vec3 vWaterNormal = get_water_surface(virtualWaterPos, vec3(0.0, -1.0, 0.0), distToSurface, true, rainStrength, eyeSkyLight, false, false, dummyH, dummyR);
                vec3 vOutNormal = -vWaterNormal;
                if (dot(worldDir, vOutNormal) > 0.0) vOutNormal = -vOutNormal;

                float dispIOR = 0.005 * WATER_DISPERSION;
                vec3 refrR = refract(worldDir, vOutNormal, 1.333 - dispIOR);
                vec3 refrG = refract(worldDir, vOutNormal, 1.333);
                vec3 refrB = refract(worldDir, vOutNormal, 1.333 + dispIOR);
                float vNdotV = max(0.0, dot(vWaterNormal, -worldDir));
                float vTir = 1.0 - smoothstep(0.62, 0.70, vNdotV);
                float vSchlick = 0.02 + 0.98 * pow(max(1.0 - vNdotV, 0.0), 4.0);
                float vFresnel = clamp(mix(vSchlick, 1.0, vTir), 0.02, 1.0);

                vec3 deepScatter = vec3(0.022, 0.145, 0.135) * nightDim * (isDay ? mix(0.40, 1.0, mainLightVis) : 1.0) * mix(0.28, 1.0, eyeSkyLight);

                if (dot(refrG, refrG) > 0.0001) {
                    vec3 sG = ComputeFullSkyHDR(colortex6, normalize(refrG), realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false);
                    float sR = (WATER_DISPERSION > 0.01 && dot(refrR, refrR) > 0.0001) ? ComputeFullSkyHDR(colortex6, normalize(refrR), realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false).r : sG.r;
                    float sB = (WATER_DISPERSION > 0.01 && dot(refrB, refrB) > 0.0001) ? ComputeFullSkyHDR(colortex6, normalize(refrB), realSunDir, sunColor, sunHeight, twilightBlend, rainStrength, frameTimeCounter, false).b : sG.b;
                    skyHDR = mix(vec3(sR, sG.g, sB), deepScatter, vFresnel);
                } else {
                    skyHDR = deepScatter;
                }
            } else {
                skyHDR = vec3(0.018, 0.125, 0.118) * nightDim * mix(0.28, 1.0, eyeSkyLight);
            }

            skyHDR = apply_underwater_fog(skyHDR, skyViewPos, nightDim, eyeSkyLight);
            float uwSkyVis = smoothstep(0.05, 0.45, eyeSkyLight);
            if (uwSkyVis > 0.01) {
                vec4 skyVolFog = CalculateUnderwaterVolumetricLight(
                    vec3(0.0), skyViewPos, dynamicUnderwaterSurfaceY, vlDither,
                    mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower
                );
                skyHDR += skyVolFog.rgb * (0.3 * uwSkyVis);
            }
        } else {
            vec3 skyViewPos = viewDir * VL_MAX_DIST;
            vec4 skyVolFog = CalculateVolumetricFogAndLight(skyViewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
            skyHDR = skyHDR * skyVolFog.a + skyVolFog.rgb;
        }

        hdr = max(skyHDR, vec3(0.0));
    }

#if DEBUG_VIEW > 0 && DEBUG_VIEW != 14
    vec3 dbg = vec3(0.0);
    #if DEBUG_VIEW == 1
        if (blockId == MAT_WATER_STILL) dbg = vec3(0.0, 0.55, 1.0);
        else if (blockId == MAT_WATER_FLOWING) dbg = vec3(0.0, 0.95, 0.95);
        else if (blockId == MAT_BUBBLE_COLUMN) dbg = vec3(0.4, 1.0, 1.0);
        else if (blockId == MAT_GLASS_CLEAR) dbg = vec3(0.9, 0.9, 0.9);
        else if (blockId == MAT_GLASS_TINTED) dbg = vec3(0.95, 0.65, 0.25);
        else if (blockId == MAT_ICE) dbg = vec3(0.55, 0.85, 1.0);
        else if (blockId == MAT_SLIME) dbg = vec3(0.25, 0.95, 0.25);
        else if (blockId == MAT_HONEY) dbg = vec3(1.0, 0.72, 0.10);
        else if (blockId == MAT_NETHER_PORTAL) dbg = vec3(0.75, 0.15, 1.0);
        else if (blockId == MAT_OTHER_TRANSLUCENT) dbg = vec3(1.0, 0.2, 0.55);
        else dbg = vec3(0.18);
    #elif DEBUG_VIEW == 2
        dbg = octDecode(encodedDataN) * 0.5 + 0.5;
    #elif DEBUG_VIEW == 3
        dbg = vec3(skyLightData);
    #elif DEBUG_VIEW == 4
        dbg = isWater ? (octDecode(texelFetch(colortex3, pixelCoord, 0).rg) * 0.5 + 0.5) : vec3(0.05);
    #elif DEBUG_VIEW == 5
        dbg = isWater ? vec3(texelFetch(colortex3, pixelCoord, 0).a * 2.5) : vec3(0.05);
    #elif DEBUG_VIEW == 6
        if (isWater) {
            vec3 wvp = ScreenToView(uv, waterDepth);
            float tNorm = isSkyDepth(seabedDepth) ? 1.0 : clamp(length(ScreenToView(uv, seabedDepth) - wvp) / 20.0, 0.0, 1.0);
            dbg = mix(vec3(0.05, 0.85, 0.35), vec3(0.05, 0.15, 0.95), tNorm);
        } else {
            dbg = vec3(0.05);
        }
    #elif DEBUG_VIEW == 7
        dbg = texture(colortex9, uv).rgb;
    #elif DEBUG_VIEW == 8
        dbg = vec3(textureLod(colortex9, uv, 0.0).a);
    #elif DEBUG_VIEW == 9
        dbg = clamp(texelFetch(pointLightTex, pixelCoord, 0).rgb * 1.4, 0.0, 1.0);
    #elif DEBUG_VIEW == 10
        dbg = clamp(hdr * 0.5, 0.0, 1.0);
    #elif DEBUG_VIEW == 11
        dbg = clamp(dbgWaterSpec * 0.35, 0.0, 1.0);
    #elif DEBUG_VIEW == 12
        dbg = clamp(dbgWaterSkyRefl * 0.5, 0.0, 1.0);
    #elif DEBUG_VIEW == 13
        dbg = clamp(dbgWaterRefr * 0.8, 0.0, 1.0);
    #elif DEBUG_VIEW == 14
        dbg = max(hdr, vec3(0.0));
    #elif DEBUG_VIEW == 15
        dbg = isWater ? texture(colortex9, uv).rgb : vec3(0.05);
    #elif DEBUG_VIEW == 16
        dbg = clamp(texelFetch(pointLightTex, pixelCoord, 0).rgb * 0.85, 0.0, 1.0);
    #endif
    fragData0 = vec4(clamp(dbg, 0.0, 1.0), 1.0);
#else
    if (any(isnan(hdr)) || any(isinf(hdr))) hdr = vec3(0.0);
    fragData0 = vec4(clamp(hdr, vec3(0.0), vec3(64.0)), 1.0);
#endif
}
