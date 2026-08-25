#version 330 compatibility
/* DRAWBUFFERS:01 */

#define PI 3.14159265359

// =================【亮度快捷调节杠杆】=================
#define EMISSIVE_BRIGHTNESS 1.2
// ========================================================

in vec3 vNormal;
in vec4 vColor;
in vec4 vTexCoord;
in vec3 vEyePos;
in vec2 vLightmap;
in float vBlockId;

in vec3 vSkyColor;
in vec3 vSunlight;
in vec4 vParams; // x: vDayFactor, y: vSunIntensity, z: vEmissive

uniform sampler2D texture;
uniform sampler2D lightmap;
uniform vec3 sunPosition;
uniform mat4 gbufferModelViewInverse;
uniform sampler2D shadowtex0;
uniform mat4 gbufferModelView;
uniform sampler2D specular;

// 补充阴影计算所需 Uniform
uniform mat4 shadowProjection;
uniform mat4 shadowModelView;

// =================【雨天湿地 Uniform】=================
uniform float rainStrength;
uniform float frameTimeCounter;
uniform vec3 cameraPosition;

// 适配 4096 贴图大小的 PCSS 参数
const float SHADOW_RES = 4096.0;
const float LIGHT_SIZE = 1.2;     // 光源尺寸（控制半影扩散速度）
const float MAX_PENUMBRA = 28.0;  // 最大半影扩散半径（纹素）
const float MIN_PENUMBRA = 12.0;  // 最小滤波半径（防止接触面硬边锯齿）

const vec2 poissonDisk[16] = vec2[16](
    vec2(-0.94201624, -0.39906216), vec2(0.94558609, -0.76890725),
    vec2(-0.094184101, -0.92938870), vec2(0.34495938, 0.29387760),
    vec2(-0.91588581, 0.45771432),  vec2(-0.81544232, -0.87912464),
    vec2(-0.38277182, 0.27676845),  vec2(0.97484398, 0.75648379),
    vec2(0.44323325, -0.97511554),  vec2(0.53742981, -0.47373420),
    vec2(-0.26496911, -0.41893023), vec2(0.79197514, 0.19090160),
    vec2(-0.24188840, 0.99706507),  vec2(-0.81409955, 0.91437590),
    vec2(0.19984126, 0.78641367),   vec2(0.14383161, -0.14100790)
);

float saturate(float x) { return clamp(x, 0.0, 1.0); }

float CurveBlockLightSky(float blockLight) {
    float inv = 1.0 - blockLight;
    float sq = inv * inv;
    float res = 1.0 - sq * sqrt(inv);
    return res * res * res;
}

float interleavedGradientNoise() {
    return fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
}

float getPCSSShadowHD(vec3 eyePos, vec3 N, float NdotL, bool isThin) {
    float NdotL_eff = isThin ? abs(NdotL) : NdotL;
    if (NdotL_eff <= 0.001) return 0.0;

    // 直接在片元内部组合阴影变换矩阵
    mat4 viewToShadow = shadowProjection * shadowModelView * gbufferModelViewInverse;

    float normalBiasFactor = isThin ? 0.015 : 0.035;
    vec3 biasedEyePos = eyePos + N * (normalBiasFactor * (1.0 - clamp(NdotL_eff, 0.0, 1.0)));

    vec4 shadowClip = viewToShadow * vec4(biasedEyePos, 1.0);
    vec3 shadowNDC = shadowClip.xyz / shadowClip.w;

    float l = length(shadowNDC.xy);
    float distortFactor = 1.0;
    if (l > 0.00001) {
        distortFactor = l * 0.8 + 0.2;
        shadowNDC.xy /= distortFactor;
    }

    vec3 shadowCoord = shadowNDC * 0.5 + 0.5;

    if (shadowCoord.x < 0.001 || shadowCoord.x > 0.999 || 
        shadowCoord.y < 0.001 || shadowCoord.y > 0.999 || 
        shadowCoord.z < 0.000 || shadowCoord.z > 1.000) {
        return 1.0;
    }

    float slope = sqrt(clamp(1.0 - NdotL_eff * NdotL_eff, 0.0, 1.0));
    float baseBias = 0.00015 + 0.00035 * slope;
    float receiverDepth = shadowCoord.z - baseBias;

    const float oneTexel = 1.0 / SHADOW_RES;

    float searchRadius = LIGHT_SIZE * 12.0 * oneTexel * distortFactor;
    
    int blockerCount = 0;
    float blockerDepthSum = 0.0;
    float blockerBias = baseBias * 1.2;

    for (int i = 0; i < 16; i++) {
        float sampleDepth = textureLod(shadowtex0, shadowCoord.st + poissonDisk[i] * searchRadius, 0.0).r;
        if (sampleDepth < shadowCoord.z - blockerBias) {
            blockerCount++;
            blockerDepthSum += sampleDepth;
        }
    }

    if (blockerCount == 0) return 1.0;

    float avgBlockerDepth = blockerDepthSum / float(blockerCount);
    float dDiff = max(shadowCoord.z - avgBlockerDepth, 0.0);
    
    float penumbra = dDiff * 600.0;
    float filterRadius = clamp(penumbra * LIGHT_SIZE * 8.0 + MIN_PENUMBRA, MIN_PENUMBRA, MAX_PENUMBRA);
    filterRadius *= distortFactor;

    float randomAngle = interleavedGradientNoise() * 6.2831853;
    mat2 rotationMatrix = mat2(cos(randomAngle), sin(randomAngle), -sin(randomAngle), cos(randomAngle));

    float shadowSum = 0.0;
    float stepScale = filterRadius * oneTexel;
    mat2 scaledRot = rotationMatrix * stepScale;

    for (int i = 0; i < 16; i++) {
        vec2 sampleUV = shadowCoord.st + scaledRot * poissonDisk[i];
        shadowSum += (receiverDepth > textureLod(shadowtex0, sampleUV, 0.0).r) ? 0.0 : 1.0;
    }

    float shadow = shadowSum * 0.0625;

    float blockerWeight = smoothstep(0.0, 2.0, float(blockerCount));
    shadow = mix(1.0, shadow, blockerWeight);

    vec2 edgeDist = abs(shadowCoord.st - 0.5) * 2.0;
    float fade = 1.0 - smoothstep(0.85, 0.98, max(edgeDist.x, edgeDist.y));
    return mix(1.0, shadow, fade);
}

float GGX_D(float NdotH, float roughness) {
    float a2 = roughness * roughness * roughness * roughness;
    float NdotHSq = NdotH * NdotH;
    float denom = NdotHSq * (a2 - 1.0) + 1.0;
    return a2 / (PI * denom * denom);
}

float GGX_G1(float NdotV, float roughness) {
    float k = (roughness + 1.0) * (roughness + 1.0) * 0.125;
    return NdotV / (NdotV * (1.0 - k) + k);
}

float GGX_G(float NdotL, float NdotV, float roughness) {
    return GGX_G1(NdotL, roughness) * GGX_G1(NdotV, roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    float m = 1.0 - cosTheta;
    float m2 = m * m;
    return F0 + (1.0 - F0) * (m2 * m2 * m);
}

void getDefaultMetalnessRoughness(float blockId, out float metalness, out float roughness) {
    bool isMetal = (blockId == 41.0 || blockId == 42.0 || blockId == 57.0 || blockId == 133.0);
    metalness = isMetal ? ((blockId == 133.0) ? 0.8 : 1.0) : 0.0;
    
    roughness = 0.8;
    if (blockId == 57.0) roughness = 0.1;
    else if (blockId == 41.0) roughness = 0.2;
    else if (blockId == 42.0) roughness = 0.3;
    else if (blockId == 133.0) roughness = 0.4;
    else if (blockId == 2.0) roughness = 0.65;
}

layout(location = 0) out vec4 fragData0;
layout(location = 1) out vec4 fragData1;

void main() {
    float vDayFactor = vParams.x;
    float vSunIntensity = vParams.y;
    float vEmissive = vParams.z;

    vec4 texColor = texture(texture, vTexCoord.st);
    vec4 albedo = vec4(texColor.rgb * vColor.rgb, texColor.a);
    if (albedo.a < 0.1) discard;

    bool isThin = (albedo.a < 0.99) || 
                  (vBlockId >= 31.0 && vBlockId <= 38.0) || 
                  (vBlockId >= 85.0 && vBlockId <= 113.0) || 
                  (vBlockId >= 188.0 && vBlockId <= 192.0) || 
                  vBlockId == 6.0 || vBlockId == 59.0 || vBlockId == 175.0;

    vec4 specMap = texture(specular, vTexCoord.st);
    float defaultMetal, defaultRough;
    getDefaultMetalnessRoughness(vBlockId, defaultMetal, defaultRough);

    bool hasSpecMap = (specMap.r >= 0.01 || specMap.g >= 0.01);
    float metalness = hasSpecMap ? specMap.r : defaultMetal;
    float roughness = max(hasSpecMap ? specMap.g : defaultRough, 0.04);

    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vEyePos);

    vec4 lm = texture(lightmap, vLightmap);

    if (rainStrength > 0.001) {
        vec3 worldNormal = normalize((gbufferModelViewInverse * vec4(N, 0.0)).xyz);
        float skyExposure = smoothstep(0.4, 0.85, lm.y);
        float upFactor = clamp(worldNormal.y * 0.7 + 0.3, 0.0, 1.0);
        float totalWetness = saturate(rainStrength) * skyExposure * upFactor;

        albedo.rgb *= mix(1.0, 0.65, totalWetness);
        roughness = mix(roughness, 0.12, totalWetness);
    }

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float sunHeight = worldSunDir.y;
    vec3 L = normalize((gbufferModelView * vec4(worldSunDir, 0.0)).xyz);

    float NdotL_raw = dot(N, L);
    float NdotL = max(NdotL_raw, 0.0);
    float NdotV = max(dot(N, V), 0.001);
    vec3 H = normalize(L + V);
    float NdotH = max(dot(N, H), 0.001);
    float VdotH = max(dot(V, H), 0.001);

    float shadow = 1.0;
    float shadowStrength = smoothstep(-0.02, 0.15, sunHeight);

    if (sunHeight > -0.01 && shadowStrength > 0.001 && (NdotL_raw > 0.001 || isThin)) {
        shadow = mix(1.0, getPCSSShadowHD(vEyePos, N, NdotL_raw, isThin), shadowStrength);
    }

    float skyAmbient = CurveBlockLightSky(lm.y);
    float skyDirect  = lm.y * lm.y;
    float blockLight = lm.x * lm.x * sqrt(lm.x);

    vec3 directLightColor = mix(vSunlight, vSkyColor, 0.15 * (1.0 - vDayFactor));
    float rainSunFade = mix(1.0, 0.25, saturate(rainStrength));
    vec3 lightColor = directLightColor * skyDirect * (2.2 * vSunIntensity) * rainSunFade;

    vec3 albedoColor = albedo.rgb;
    vec3 F0 = mix(vec3(0.04), albedoColor, metalness);
    vec3 kS = fresnelSchlick(VdotH, F0);
    vec3 kD = (1.0 - kS) * (1.0 - metalness);

    vec3 diffuse = albedoColor * kD * (NdotL / PI);

    float D = GGX_D(NdotH, roughness);
    float G = GGX_G(NdotL, NdotV, roughness);
    vec3 specularColor = kS * D * G / max(4.0 * NdotL * NdotV, 0.001);

    specularColor *= (1.0 + 0.5 * smoothstep(0.5, 1.0, metalness));
    if (vBlockId == 2.0) specularColor *= 2.0;

    vec3 directDiffuse = diffuse * lightColor * shadow;
    vec3 directSpecular = specularColor * lightColor * (shadow * NdotL);

    vec3 R = reflect(-V, N);
    float skyReflection = clamp(R.y * 0.5 + 0.5, 0.15, 1.0);
    float sunSideFactor = mix(0.4, 1.0, clamp(NdotL * shadow + 0.15, 0.0, 1.0));

    vec3 ambientBase = mix(vec3(0.02, 0.03, 0.05), vSkyColor, skyAmbient) * 0.25;
    float ao = 0.5 + 0.5 * NdotV;

    vec3 ambientDiffuse = albedoColor * (1.0 - metalness) * ambientBase;
    vec3 ambientSpecular = F0 * mix(ambientBase, vSkyColor, 0.5 * skyReflection) * (1.0 - roughness * 0.5) * sunSideFactor;
    vec3 ambient = (ambientDiffuse + ambientSpecular) * ao;

    vec3 metalSunGradient = F0 * lightColor * (NdotL * shadow * 0.28 * metalness);

    float torchRange = 1.0 - blockLight;
    vec3 customLightColor = vec3(1.0, 0.4, 0.1); 
    float torchRainBoost = mix(1.0, 1.8, saturate(rainStrength));
    vec3 torchLight = customLightColor * (blockLight * (1.0 + torchRange * 0.3) * 0.45 * torchRainBoost);
    
    float torchNdotL = max(dot(N, vec3(0.0, 0.8, 0.2)), 0.1);
    vec3 torchContribution = albedoColor * (1.0 - metalness * 0.7) * torchLight * (torchNdotL / PI);

    float minLightStr = 0.003 * (1.0 - skyDirect) + 0.008;
    vec3 minLight = mix(albedoColor, F0, metalness) * vec3(0.02, 0.025, 0.045) * minLightStr;

    vec3 color = ambient + directDiffuse + directSpecular + metalSunGradient + torchContribution + minLight;

    if (vEmissive > 0.0) {
        vec3 rawTexture = albedoColor;
        float emissiveRainBoost = mix(1.0, 2.5, saturate(rainStrength));
        vec3 glowingColor = rawTexture * (1.0 + vEmissive * EMISSIVE_BRIGHTNESS * emissiveRainBoost);
        color = mix(color, glowingColor, vEmissive);
    }
    fragData0 = vec4(color, albedo.a);
    float waterFlag = (vBlockId == 8.0 || vBlockId == 9.0 || vBlockId == 79.0) ? 0.79 : 1.0;

    fragData1 = vec4(N.xy * 0.5 + 0.5, waterFlag, lm.y);
}