#version 410 core
/* DRAWBUFFERS:07 */

in vec2 texCoord;

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D colortex4;
uniform sampler2D colortex6;
uniform sampler2D colortex7;
uniform sampler2D colortex8;
uniform sampler2D colortex9;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;
uniform sampler2D noisetex;

uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferModelView;

uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float frametime;
uniform float frameTimeCounter;
uniform float viewWidth;
uniform float viewHeight;

uniform int isEyeInWater;

uniform sampler2D shadowtex0;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;

#include "/lib/common.glsl"
#include "/lib/projection.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/clouds.glsl"
#include "/lib/water.glsl"
#include "/lib/fog.glsl"
#include "/lib/volumetric.glsl"
#include "/lib/ssgi.glsl"

#define EXPOSURE 1.5
#define EXP_MIN 0.2
#define EXP_MAX 3.0
#define BLOOM_STRENGTH 0.02
#define CROSS_BLUR_STRENGTH 1.5

#define SATURATION 1.0
#define LUMA_GAMMA 1.0
#define WHITE_CLIP 1.0

#define WET_REFLECTION_INTENSITY 0.40

#define BLOCK_ID_WATER 1
#define BLOCK_ID_GLASS 2

const bool CLOUD2D_ENABLED = true;

layout(location = 0) out vec4 fragData0;
layout(location = 1) out vec4 fragData1;

// ACES 电影级色调映射
vec3 ACESFilm(vec3 x) {
    x *= 0.9;
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

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
    vec3 sunColor = mix(vec3(1.0, 0.4, 0.05), vec3(1.0, 0.98, 0.85), saturate(sunHeight * 2.5));

    float nightDim = mix(0.05, 1.0, saturate(sunHeight * 4.0 + 0.5));

    bool isDay = realSunDir.y > 0.0;
    vec3 mainLightViewDir = isDay ? viewSunDir : -viewSunDir;
    vec3 mainLightColor   = isDay ? sunColor : vec3(0.4, 0.65, 1.0);
    float mainLightVis    = isDay ? saturate(realSunDir.y * 3.0) : saturate(-realSunDir.y * 3.0);
    float mainLightPower  = isDay ? 80.0 : 18.0;

    bool isWater = (blockId == BLOCK_ID_WATER) && (waterDepth < 0.9999);
    bool isGlass = (blockId == BLOCK_ID_GLASS) && (waterDepth < 0.9999);
    bool underwater = (isEyeInWater == 1);

    vec4 ndc = vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec4 vp4 = gbufferProjectionInverse * ndc;
    vec3 viewDir = normalize(vp4.xyz / vp4.w);
    vec3 worldDir = normalize((gbufferModelViewInverse * vec4(viewDir, 0.0)).xyz);

    vec3 safeSkyWorldDir = vec3(worldDir.x, max(worldDir.y, 0.02), worldDir.z);

    vec3 skyHDR = (realSunDir.y > -0.05) ?
        AtmosphericScattering(safeSkyWorldDir, realSunDir, 1.0) * mix(1.0, 0.35, saturate(rainStrength)) :
        AtmosphericScattering(safeSkyWorldDir, -realSunDir, 0.1) * 0.02 + vec3(0.001, 0.005, 0.018);
    if (CLOUD2D_ENABLED && worldDir.y > 0.0) {
        float cloudTime = frameTimeCounter * 0.12;
        vec2 skyPlaneUV = worldDir.xz / (worldDir.y + 0.18);
        vec4 cloudData = getMackerelCloudData(skyPlaneUV, cloudTime);
        float density = cloudData.x;

        if (density > 0.005) {
            vec2 sunOffset = normalize(realSunDir.xz + vec2(0.0001)) * 0.15;
            vec4 shadowCloudData = getMackerelCloudData(skyPlaneUV - sunOffset, cloudTime);
            float shadowDensity = shadowCloudData.x;

            float selfShadow = exp(-density * 3.2);
            float lightDepth = max(0.0, density - shadowDensity * 0.75);
            float powderEffect = 1.0 - exp(-density * 3.5);
            float beerLaw = exp(-lightDepth * 3.0);
            float transmittance = powderEffect * beerLaw;

            float cosTheta = max(0.0, dot(worldDir, realSunDir));
            float sunFactor = saturate(sunHeight * 3.0) * (1.0 - rainStrength);

            float forwardScattering = pow(cosTheta, 6.0) * (1.0 - density * 0.85);
            float lightPiercing = pow(clamp(1.0 - density, 0.0, 1.0), 2.5) * pow(cosTheta, 2.0);
            float cloudWrap = clamp((cosTheta + 0.5) / 1.5, 0.0, 1.0);
            float edgeSSS = pow(cloudWrap, 1.5) * (1.0 - selfShadow * 0.5);

            vec3 cloudBaseShadow = skyHDR * 0.28;
            vec3 cloudAmbient = mix(cloudBaseShadow, skyHDR * 0.85, selfShadow);
            vec3 cloudSunLight = sunColor * (transmittance * 1.1 + forwardScattering * 1.6 + lightPiercing * 0.8 + edgeSSS * 0.35) * sunFactor;
            vec3 cloudColor = cloudAmbient + cloudSunLight;

            float horizonFade = smoothstep(0.01, 0.20, worldDir.y);
            float finalAlpha = density * 0.92 * horizonFade;

            skyHDR = mix(skyHDR, cloudColor, finalAlpha);
        }
    }

    float horizonFactor = saturate(sunHeight * 3.0);
    float sd = dot(worldDir, realSunDir);
    float sunSize = 0.00195 * mix(0.5, 1.0, horizonFactor);
    float sunDiscMask = saturate((sd - (1.0 - sunSize)) * 1000.0 * mix(0.3, 1.0, horizonFactor));
    sunDiscMask = pow(sunDiscMask * sunDiscMask * (3.0 - 2.0 * sunDiscMask), 2.0);
    float sunGlow = exp(-(1.0 - sd) * mix(300.0, 50.0, horizonFactor));

    skyHDR += (sunDiscMask * sunColor * 12.0 + sunGlow * sunColor * 1.5) * smoothstep(0.0, 0.3, sunHeight) * saturate(sunHeight * 10.0) * (1.0 - rainStrength);
    if (realSunDir.y < 0.0) skyHDR += SunDisc(worldDir, -realSunDir) * vec3(0.245, 0.2625, 0.315);

    float rawLum = 0.0;
    rawLum += texture(colortex6, vec2(0.50, 0.50)).r * 0.40;
    rawLum += texture(colortex6, vec2(0.46, 0.46)).r * 0.15;
    rawLum += texture(colortex6, vec2(0.54, 0.54)).r * 0.15;
    rawLum += texture(colortex6, vec2(0.46, 0.54)).r * 0.15;
    rawLum += texture(colortex6, vec2(0.54, 0.46)).r * 0.15;
    rawLum = max(rawLum, 0.0001);

    float prevLum = texture(colortex7, vec2(0.5)).r;
    if (prevLum < 0.001) prevLum = rawLum;
    float smoothLum = prevLum + (rawLum - prevLum) * (1.0 - exp(-frametime * 3.0));
    float nightFactor = clamp(1.0 - smoothLum * 3.0, 0.0, 1.0);

    float rainDarken = mix(1.0, 0.45, saturate(rainStrength));
    float exposure = clamp(mix(0.18, 0.12, nightFactor) / smoothLum, EXP_MIN, EXP_MAX) * EXPOSURE * rainDarken;

    // ==================== 水体渲染逻辑 ====================
    if (isWater) {
        vec3 waterViewPos  = ScreenToView(uv, waterDepth);

        bool hasNoSeabed = (seabedDepth >= 0.9995);

        float safeSeabedDepth = min(seabedDepth, 0.9995);
        vec3 seabedViewPos = ScreenToView(uv, safeSeabedDepth);
        vec3 waterViewDir  = normalize(waterViewPos);

        float viewDist = length(waterViewPos);
        float waveLod = clamp(1.0 - viewDist * 0.005, 0.15, 1.0);

        vec3 rawWaterWorldPos = (gbufferModelViewInverse * vec4(waterViewPos, 1.0)).xyz + cameraPosition;
        vec3 worldViewDir = normalize((gbufferModelViewInverse * vec4(waterViewDir, 0.0)).xyz);

        vec3 waterWorldPos = underwater ? rawWaterWorldPos : raymarch_water_pom(rawWaterWorldPos, worldViewDir, waveLod);
        waterViewPos = (gbufferModelView * vec4(waterWorldPos - cameraPosition, 1.0)).xyz;

        float rawThickness = abs(seabedViewPos.z - waterViewPos.z);
        float waterThickness = hasNoSeabed ? clamp(viewDist * 0.15, 0.5, 6.0) : clamp(rawThickness, 0.0, 20.0);

        vec2 gN2 = encodedDataN * 2.0 - 1.0;
        float gNz = sqrt(max(0.0, 1.0 - dot(gN2, gN2)));
        vec3 geometricWorldNormal = normalize((gbufferModelViewInverse * vec4(normalize(vec3(gN2, gNz)), 0.0)).xyz);
        geometricWorldNormal.y = underwater ? -abs(geometricWorldNormal.y) : abs(geometricWorldNormal.y);
        geometricWorldNormal = normalize(geometricWorldNormal);

        vec3 flatWorldNormal = underwater ? vec3(0.0, -1.0, 0.0) : vec3(0.0, 1.0, 0.0);
        vec3 flatViewNormal  = normalize(mat3(gbufferModelView) * flatWorldNormal);
        float flatNdotV      = max(0.0, dot(flatViewNormal, -waterViewDir));

        vec3 rawWorldNormal = get_water_normal(waterWorldPos, waveLod, underwater);

        float surfaceHoriz = saturate(abs(geometricWorldNormal.y));
        float waveWeight = smoothstep(0.01, 0.22, flatNdotV) * clamp(1.0 - viewDist * 0.003, 0.2, 1.0);
        vec3 waterWorldNormal = normalize(mix(geometricWorldNormal, rawWorldNormal, waveWeight * surfaceHoriz));
        vec3 waterNormal = normalize(mat3(gbufferModelView) * waterWorldNormal);

        float NdotV_raw = dot(waterNormal, -waterViewDir);
        if (NdotV_raw < 0.001) {
            waterNormal = normalize(waterNormal - waterViewDir * (0.001 - NdotV_raw));
        }

        vec3 reflection = texture(colortex9, uv).rgb;

        vec2 refractOffset = waterNormal.xy * 0.02 * clamp(waterThickness, 0.0, 1.5);
        vec2 refractUV = clamp(uv + refractOffset, vec2(0.001), vec2(0.999));
        if (texture(depthtex1, refractUV).r <= waterDepth) refractUV = uv;
        vec3 refractionColor = texture(colortex0, refractUV).rgb;

        if (hasNoSeabed) {
            if (underwater) {
                float waterSurfaceY = max(cameraPosition.y, 62.0) + 1.0;
                float distToSurface = (worldDir.y > 0.02) ? clamp((waterSurfaceY - cameraPosition.y) / worldDir.y, 0.5, 20.0) : 20.0;
                vec3 skyAbsorb = exp(vec3(-0.35, -0.15, -0.05) * distToSurface);
                float expCorrection = clamp(1.0 / max(exposure * 0.5, 1.0), 0.25, 1.0);
                vec3 refractedSkyDir = normalize(worldDir + vec3(waterWorldNormal.x, 0.0, waterWorldNormal.z) * 0.6);
                refractedSkyDir.y = max(refractedSkyDir.y, 0.02);
                vec3 refractedSky = AtmosphericScattering(refractedSkyDir, realSunDir, 1.0) * mix(1.0, 0.35, saturate(rainStrength));
                float refractedSunDisc = SunDisc(refractedSkyDir, realSunDir);
                refractionColor = (refractedSky + refractedSunDisc * sunColor * 6.0 * (1.0 - rainStrength)) * skyAbsorb * expCorrection;
            } else {
                vec3 skyAbsorb = exp(vec3(-0.50, -0.20, -0.08) * waterThickness);
                refractionColor = skyHDR * skyAbsorb * 0.5;
            }
        }

        float NdotV = max(0.0, dot(waterNormal, -waterViewDir));
        float fresnel;

        if (underwater) {
            float tirFactor = smoothstep(0.72, 0.42, NdotV);
            float schlick = 0.02 + 0.98 * pow(max(1.0 - NdotV, 0.0), 4.0);
            fresnel = clamp(mix(schlick, 1.0, tirFactor), 0.02, 0.98);

            hdr = mix(refractionColor, reflection, fresnel);
            hdr = apply_underwater_fog(hdr, waterViewPos, nightDim);

            vec4 volFog = CalculateUnderwaterVolumetricLight(vec3(0.0), waterViewPos, vlDither, mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * volFog.a + volFog.rgb;
        } else {
            fresnel = clamp(0.02 + 0.98 * pow(max(1.0 - NdotV, 0.0), 5.0), 0.02, 0.82);

            vec3 absorb = exp(vec3(-0.65, -0.25, -0.10) * waterThickness);
            vec3 waterColor = refractionColor * absorb;

            float fogFactor = 1.0 - exp(-waterThickness * 0.035);
            vec3 waterFogColor = vec3(0.002, 0.02, 0.05) * nightDim;
            vec3 refractedColor = mix(waterColor, waterFogColor, fogFactor);

            vec3 underwaterEndPos = hasNoSeabed ? (waterViewPos + waterViewDir * 15.0) 
                                                : (waterViewPos + normalize(seabedViewPos - waterViewPos) * min(length(seabedViewPos - waterViewPos), 20.0));
            
            vec4 waterVolFog = CalculateUnderwaterVolumetricLight(waterViewPos, underwaterEndPos, vlDither, mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower);
            refractedColor = refractedColor * waterVolFog.a + waterVolFog.rgb;

            hdr = mix(refractedColor, reflection, fresnel);

            // ==================== 泡沫 (Foam)====================
            // 1. 波浪与泡沫掩码
            float waveH = getwave2(waterWorldPos, waveLod);
            float waveSlope = 1.0 - saturate(waterWorldNormal.y);

            float crestFoam = smoothstep(1.00, 1.02, waveH);        
            float slopeFoam = smoothstep(0.18, 0.32, waveSlope);     
            float shoreFoam = smoothstep(0.25, 0.02, waterThickness) * (1.0 - float(hasNoSeabed)); 

            float totalFoam = clamp(crestFoam * 0.35 + slopeFoam * 0.3 + shoreFoam * 0.4, 0.0, 1.0);

            // 2. 采样水面阴影
            mat4 shadowMat = shadowProjection * shadowModelView * gbufferModelViewInverse;
            float waterShadow = getSampleShadow(waterViewPos, shadowMat);

            // 3. 泡沫动态光照与水色融合（暗处自动变暗，吸收底层水色）
            vec3 foamBaseTint = mix(hdr * 1.2, vec3(0.75, 0.88, 0.95), 0.35);

            vec3 foamAmbient = skyHDR * 0.25 * max(skyLightData, 0.05);
            vec3 foamDirect  = mainLightColor * mainLightVis * (isDay ? 1.0 : 0.15) * waterShadow;
            vec3 foamLighting = foamAmbient + foamDirect + vec3(0.005);

            vec3 foamColor = foamBaseTint * foamLighting;

            // 4. 泡沫覆盖混合
            hdr = mix(hdr, foamColor, totalFoam * 0.35);

            // 5. 水面镜面高光
            vec3 halfVec = normalize(-waterViewDir + mainLightViewDir);
            float NdotH = max(0.0, dot(waterNormal, halfVec));

            float specPower = isDay ? 2048.0 : 4096.0;
            float specIntensity = isDay ? 1.0 : 0.35;
            float specPosition = pow(NdotH, specPower);

            vec3 specColor = mainLightColor;
            float horizonSpecFade = smoothstep(0.0, 0.15, NdotV);

            hdr += specColor * specPosition * horizonSpecFade * (mainLightPower * mainLightVis * specIntensity * waterShadow * (1.0 - rainStrength));
            // ==============================================================================

            vec4 volFog = CalculateVolumetricFogAndLight(waterViewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * mix(1.0, volFog.a, 0.6) + volFog.rgb * 0.7;
        }
    }
    // ==================== 玻璃单独渲染逻辑 ====================
    else if (isGlass) {
        vec3 glassViewPos = ScreenToView(uv, waterDepth);
        vec3 glassViewDir = normalize(glassViewPos);

        float skyLightFactor = smoothstep(0.05, 0.85, skyLightData);

        vec3 glassTint = texture(colortex2, uv).rgb;

        vec2 n2 = encodedDataN * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
        vec3 glassNormal = normalize(vec3(n2, nz));

        vec3 reflection = texture(colortex9, uv).rgb;

        vec3 refractionColor = hdr;

        if (seabedDepth > 0.9999) {
            refractionColor = skyHDR;
        }
        else if (underwater) {
            vec3 seabedViewPos = ScreenToView(uv, seabedDepth);
            float waterThickness = max(0.0, length(seabedViewPos - glassViewPos));
            if (waterThickness > 0.05) {
                vec3 absorb = exp(vec3(-0.08, -0.04, -0.02) * waterThickness);
                vec3 waterColor = refractionColor * absorb;
                float fogFactor = 1.0 - exp(-waterThickness * 0.025);
                vec3 waterFogColor = vec3(0.002, 0.02, 0.05) * nightDim;
                refractionColor = mix(waterColor, waterFogColor, fogFactor);
            }
        }

        float tintBrightness = max(glassTint.r, max(glassTint.g, glassTint.b));
        vec3 effectiveTint = mix(vec3(1.0), glassTint, smoothstep(0.05, 0.35, tintBrightness));
        refractionColor *= effectiveTint;

        float NdotV = max(0.0, dot(glassNormal, -glassViewDir));
        float fresnel = clamp(0.03 + 0.97 * pow(max(1.0 - NdotV, 0.0), 5.0), 0.03, 0.65);

        hdr = mix(refractionColor, reflection, fresnel);

        vec3 halfVec = normalize(-glassViewDir + mainLightViewDir);
        float NdotH = max(0.0, dot(glassNormal, halfVec));
        float specPosition = pow(NdotH, 256.0);
        hdr += mainLightColor * specPosition * mainLightPower * 0.06 * mainLightVis * skyLightFactor;

        if (!underwater) {
            hdr = apply_terrain_fog(hdr, glassViewPos);
            vec4 volFog = CalculateVolumetricFogAndLight(glassViewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * volFog.a + volFog.rgb;
        }
    }

    // ==================== 地形 水下渲染补全 ====================
    if (waterDepth < 0.9999 && !isWater && !isGlass) {
        vec3 viewPos = ScreenToView(uv, waterDepth);

        vec2 n2 = encodedDataN * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
        vec3 viewNormal = normalize(vec3(n2, nz));

    #if SSGI_ENABLED == 1
        if (!underwater) {
            hdr += texture(colortex8, uv).rgb * SSGI_STRENGTH;
        }
    #endif

        if (underwater) {
            hdr = apply_underwater_fog(hdr, viewPos, nightDim);
            vec4 volFog = CalculateUnderwaterVolumetricLight(vec3(0.0), viewPos, vlDither, mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * volFog.a + volFog.rgb;
        } else {
            vec4 volFog = CalculateVolumetricFogAndLight(viewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * volFog.a + volFog.rgb;
        }

        vec3 worldNormal = normalize((gbufferModelViewInverse * vec4(viewNormal, 0.0)).xyz);

        float isFloor = smoothstep(0.30, 0.70, worldNormal.y);
        float skyLightFactor = smoothstep(0.85, 0.98, skyLightData);
        float wetness = clamp(rainStrength, 0.0, 1.0) * skyLightFactor * isFloor;

        if (wetness > 0.01) {
            vec3 viewDir = normalize(viewPos);

            hdr *= mix(1.0, 0.88, wetness);

            float NdotV = max(0.0, dot(viewNormal, -viewDir));

            vec3 wetReflection = texture(colortex9, uv).rgb;

            float fresnel = clamp(mix(0.04, 0.35, pow(max(1.0 - NdotV, 0.0), 3.0)), 0.04, 0.35);
            float reflectFactor = fresnel * wetness * WET_REFLECTION_INTENSITY;

            hdr = mix(hdr, wetReflection, reflectFactor);
        }
    }

    vec3 rawBloom = texture(colortex3, uv).rgb + texture(colortex4, uv).rgb;
    vec3 bloom = mix(rawBloom, rawBloom * sunColor, 0.10);
    hdr += bloom * BLOOM_STRENGTH;

    float nightTerrainBoost = mix(2.2, 1.0, saturate(sunHeight * 4.0 + 0.5));
    vec3 terrainResult = ACESFilm(hdr * exposure * nightTerrainBoost);

    if (underwater) {
        float waterSurfaceY = max(cameraPosition.y, 62.0) + 1.0;
        float distToSurface = (worldDir.y > 0.02) ? clamp((waterSurfaceY - cameraPosition.y) / worldDir.y, 0.5, 30.0) : 30.0;
        vec3 skyViewPos = viewDir * distToSurface;

        vec3 skyAbsorb = exp(vec3(-0.30, -0.12, -0.04) * distToSurface);
        float expCorrection = clamp(1.0 / max(exposure * 0.5, 1.0), 0.25, 1.0);
        skyHDR = skyHDR * skyAbsorb * expCorrection;

        skyHDR = apply_underwater_fog(skyHDR, skyViewPos, nightDim);

        vec4 skyVolFog = CalculateUnderwaterVolumetricLight(vec3(0.0), skyViewPos, vlDither, mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower);
        skyHDR = skyHDR * skyVolFog.a + skyVolFog.rgb * 0.3;
    } else {
        vec3 skyViewPos = viewDir * VL_MAX_DIST;
        vec4 skyVolFog = CalculateVolumetricFogAndLight(skyViewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
        skyHDR = skyHDR * skyVolFog.a + skyVolFog.rgb;
    }
    vec3 skyResult = ACESFilm(max(skyHDR, vec3(0.0)) * exposure);

    float skyMaskDepth = underwater ? seabedDepth : waterDepth;
    float skyFactor = smoothstep(0.9998, 0.99999, skyMaskDepth);
    vec3 result = mix(terrainResult, skyResult, skyFactor);

    result = clamp(result * 1.02, 0.0, 1.0);
    float lum = dot(result, vec3(0.299, 0.587, 0.114));
    result = mix(vec3(lum), result, SATURATION);
    result += rand(texCoord) / 255.0;

    fragData0 = vec4(result, 1.0);
    fragData1 = vec4(vec3(smoothLum), 1.0);
}