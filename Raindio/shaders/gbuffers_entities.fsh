#version 410 core
/* DRAWBUFFERS:01 */

#define PI 3.14159265359

in vec3 vNormal;
in vec4 vColor;
in vec4 vTexCoord;
in vec3 vEyePos;
in vec2 vLightmap;

// 优化：引入顶点着色器已算好的 Varying 变量，避免逐像素计算
in vec3 vSkyColor;
in vec3 vSunlight;
in float vDayFactor;
in float vSunIntensity;

uniform sampler2D texture;
uniform sampler2D lightmap;
uniform sampler2D shadowtex0;
uniform vec3 sunPosition;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferModelView;
uniform mat4 shadowProjection;
uniform mat4 shadowModelView;

uniform sampler2D specular;

float CurveBlockLightSky(float blockLight) {
    float inv = 1.0 - blockLight;
    float sq = inv * inv;
    float res = 1.0 - sq * sqrt(inv);
    return res * res * res;
}

const float SHADOW_RES = 4096.0;
const float LIGHT_SIZE = 2.5;
const float MAX_PENUMBRA = 6.0;
const float MIN_PENUMBRA = 0.6;

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

float interleavedGradientNoise() {
    return fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
}

float getPCSSShadowHD(vec3 eyePos, vec3 N, float NdotL, bool isThin, mat4 viewToShadow) {
    float NdotL_eff = isThin ? abs(NdotL) : NdotL;
    if (NdotL_eff <= 0.001) return 0.0;

    float normalBiasFactor = isThin ? 0.012 : 0.022;
    vec3 biasedEyePos = eyePos + N * (normalBiasFactor * (1.0 - clamp(NdotL_eff, 0.0, 1.0)));

    vec4 shadowClip = viewToShadow * vec4(biasedEyePos, 1.0);
    vec3 shadowNDC = shadowClip.xyz / shadowClip.w;

    float distortFactor = length(shadowNDC.xy) * 0.8 + 0.2;
    shadowNDC.xy /= distortFactor;

    vec3 shadowCoord = shadowNDC * 0.5 + 0.5;

    if (shadowCoord.x < 0.001 || shadowCoord.x > 0.999 || 
        shadowCoord.y < 0.001 || shadowCoord.y > 0.999 ||
        shadowCoord.z < 0.000 || shadowCoord.z > 1.000) {
        return 1.0;
    }

    float slope = sqrt(clamp(1.0 - NdotL_eff * NdotL_eff, 0.0, 1.0));
    float bias = (0.00006 + 0.00012 * slope) / distortFactor;
    if (isThin) bias *= 0.2;

    float receiverDepth = shadowCoord.z - bias;

    const float oneTexel = 1.0 / SHADOW_RES;
    float searchRadius = (LIGHT_SIZE * oneTexel) / distortFactor;
    
    int blockerCount = 0;
    float blockerDepthSum = 0.0;

    for (int i = 0; i < 12; i++) {
        float sampleDepth = texture(shadowtex0, shadowCoord.st + poissonDisk[i] * searchRadius).r;
        if (sampleDepth < receiverDepth) {
            blockerCount++;
            blockerDepthSum += sampleDepth;
        }
    }

    if (blockerCount == 0) return 1.0;

    float avgBlockerDepth = blockerDepthSum / float(blockerCount);
    float penumbra = (receiverDepth - avgBlockerDepth) / max(avgBlockerDepth, 0.00001);
    float filterRadius = clamp(penumbra * LIGHT_SIZE * 25.0, MIN_PENUMBRA, MAX_PENUMBRA);

    float randomAngle = interleavedGradientNoise() * 6.28318530718;
    float cosA = cos(randomAngle);
    float sinA = sin(randomAngle);
    mat2 rotationMatrix = mat2(cosA, sinA, -sinA, cosA);

    float shadowSum = 0.0;
    // 优化：将缩放与旋转矩阵提前合并，减少循环内向量乘法
    float stepScale = (filterRadius * oneTexel) / distortFactor;
    mat2 scaledRot = rotationMatrix * stepScale;

    for (int i = 0; i < 12; i++) {
        vec2 sampleUV = shadowCoord.st + scaledRot * poissonDisk[i];
        shadowSum += (receiverDepth > texture(shadowtex0, sampleUV).r) ? 0.0 : 1.0;
    }

    float shadow = shadowSum * 0.0833333;
    vec2 edgeDist = abs(shadowCoord.st - 0.5) * 2.0;
    float fade = 1.0 - smoothstep(0.85, 0.98, max(edgeDist.x, edgeDist.y));

    return mix(1.0, shadow, fade);
}

float GGX_D(float NdotH, float roughness) {
    float a2 = roughness * roughness * roughness * roughness;
    float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
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
    float m = 1.0 - cosTheta; // 优化：移除多余 clamp
    float m2 = m * m;
    return F0 + (1.0 - F0) * (m2 * m2 * m);
}

layout(location = 0) out vec4 fragData0;
layout(location = 1) out vec4 fragData1;

void main() {
    vec4 albedo = texture(texture, vTexCoord.st) * vColor;
    if (albedo.a < 0.1) discard;

    vec4 specMap = texture(specular, vTexCoord.st);
    bool hasSpecMap = (specMap.r >= 0.01 || specMap.g >= 0.01);
    float metalness = hasSpecMap ? specMap.r : 0.0;
    float roughness = max(hasSpecMap ? specMap.g : 0.8, 0.04);

    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vEyePos);

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
    if (sunHeight > -0.01 && NdotL_raw > 0.001) {
        mat4 viewToShadow = shadowProjection * shadowModelView * gbufferModelViewInverse;
        shadow = mix(1.0, getPCSSShadowHD(vEyePos, N, NdotL_raw, false, viewToShadow), shadowStrength);
    }

    vec4 lm = texture(lightmap, vLightmap);
    float skyAmbient = CurveBlockLightSky(lm.y);
    float skyDirect  = lm.y * lm.y;
    float blockLight = pow(lm.x, 2.5);

    // 直接使用顶点着色器传过来的 Varying 预计算变量，删除了原本在此处的冗余计算
    vec3 directLightColor = mix(vSunlight, vSkyColor, 0.15 * (1.0 - vDayFactor));
    vec3 lightColor = directLightColor * skyDirect * (2.2 * vSunIntensity);

    vec3 albedoColor = albedo.rgb;
    vec3 F0 = mix(vec3(0.04), albedoColor, metalness);
    vec3 kS = fresnelSchlick(VdotH, F0);
    vec3 kD = (1.0 - kS) * (1.0 - metalness);

    vec3 diffuse = albedoColor * kD * (NdotL / PI);

    float D = GGX_D(NdotH, roughness);
    float G = GGX_G(NdotL, NdotV, roughness);
    vec3 specularColor = kS * D * G / max(4.0 * NdotL * NdotV, 0.001);

    specularColor *= (1.0 + 0.5 * smoothstep(0.5, 1.0, metalness));

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
    vec3 torchLight = vec3(1.0, 0.52, 0.18) * (blockLight * (1.0 + torchRange * 0.3) * 0.18);
    float torchNdotL = max(dot(N, vec3(0.0, -1.0, 0.0)), 0.0);
    vec3 torchContribution = albedoColor * (1.0 - metalness * 0.7) * torchLight * torchNdotL / PI;

    float minLightStr = 0.003 * (1.0 - skyDirect) + 0.008;
    vec3 minLight = mix(albedoColor, F0, metalness) * vec3(0.02, 0.025, 0.045) * minLightStr;

    vec3 color = ambient + directDiffuse + directSpecular + metalSunGradient + torchContribution + minLight;

    fragData0 = vec4(color, 1.0);
    fragData1 = vec4(N.xy * 0.5 + 0.5, 0.0, 1.0);
}