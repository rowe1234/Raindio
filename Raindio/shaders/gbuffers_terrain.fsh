#version 120

varying vec3 vNormal;
varying vec4 vColor;
varying vec4 vTexCoord;
varying vec3 vEyePos;
varying vec2 vLightmap;
varying float vBlockId;

uniform sampler2D texture;
uniform sampler2D lightmap;
uniform vec3 sunPosition;
uniform mat4 gbufferModelViewInverse;

uniform sampler2D shadowtex0;
uniform mat4 shadowProjection;
uniform mat4 shadowModelView;
uniform mat4 gbufferModelView;

const float SHADOW_RES = 4096.0;

// PCSS 参数
const float LIGHT_SIZE = 2.5;     
const float MAX_PENUMBRA = 6.0;   
const float MIN_PENUMBRA = 0.6;   

const vec2 poissonDisk[16] = vec2[](
    vec2(-0.94201624, -0.39906216),
    vec2(0.94558609, -0.76890725),
    vec2(-0.094184101, -0.92938870),
    vec2(0.34495938, 0.29387760),
    vec2(-0.91588581, 0.45771432),
    vec2(-0.81544232, -0.87912464),
    vec2(-0.38277182, 0.27676845),
    vec2(0.97484398, 0.75648379),
    vec2(0.44323325, -0.97511554),
    vec2(0.53742981, -0.47373420),
    vec2(-0.26496911, -0.41893023),
    vec2(0.79197514, 0.19090160),
    vec2(-0.24188840, 0.99706507),
    vec2(-0.81409955, 0.91437590),
    vec2(0.19984126, 0.78641367),
    vec2(0.14383161, -0.14100790)
);

float CurveBlockLightSky(float blockLight) {
    blockLight = 1.0 - pow(1.0 - blockLight, 0.55);
    return blockLight * blockLight * blockLight;
}

float interleavedGradientNoise() {
    return fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
}

// 高清晰度 PCSS 软阴影 (带 Early Exit 优化)
float getPCSSShadowHD(vec3 eyePos, vec3 N, float NdotL, bool isThin) {
    float NdotL_eff = isThin ? abs(NdotL) : NdotL;
    if (NdotL_eff <= 0.001) return 0.0;

    float normalBiasFactor = isThin ? 0.012 : 0.022;
    vec3 biasedEyePos = eyePos + N * (normalBiasFactor * (1.0 - clamp(NdotL_eff, 0.0, 1.0)));

    vec4 feetPos = gbufferModelViewInverse * vec4(biasedEyePos, 1.0);
    vec4 shadowClip = shadowProjection * (shadowModelView * feetPos);
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

    // STEP 1: 遮挡物搜索
    const float oneTexel = 1.0 / SHADOW_RES;
    float searchRadius = (LIGHT_SIZE * oneTexel) / distortFactor;
    
    int blockerCount = 0;
    float blockerDepthSum = 0.0;

    for (int i = 0; i < 16; i++) {
        vec2 sampleOffset = poissonDisk[i] * searchRadius;
        float sampleDepth = texture2D(shadowtex0, shadowCoord.st + sampleOffset).r;
        if (sampleDepth < receiverDepth) {
            blockerCount++;
            blockerDepthSum += sampleDepth;
        }
        // 性能优化：采样前 4 个点后若无任何遮挡且深度较浅，极大概率无阴影，直接提前返回
        if (i == 3 && blockerCount == 0 && receiverDepth > 0.7) {
            return 1.0;
        }
    }

    if (blockerCount == 0) return 1.0;

    // STEP 2: 半影估计
    float avgBlockerDepth = blockerDepthSum / float(blockerCount);
    float penumbra = (receiverDepth - avgBlockerDepth) / max(avgBlockerDepth, 0.00001);
    
    float filterRadius = clamp(penumbra * LIGHT_SIZE * 25.0, MIN_PENUMBRA, MAX_PENUMBRA);
    if (isThin) filterRadius = min(filterRadius, 2.0);

    // STEP 3: 旋转 Dither 滤波采样
    float randomAngle = interleavedGradientNoise() * 6.28318530718;
    float cosA = cos(randomAngle);
    float sinA = sin(randomAngle);
    mat2 rotationMatrix = mat2(cosA, sinA, -sinA, cosA);

    float shadowSum = 0.0;
    vec2 filterStep = (filterRadius * oneTexel / distortFactor) * vec2(1.0);

    for (int i = 0; i < 16; i++) {
        vec2 rotatedOffset = rotationMatrix * poissonDisk[i];
        vec2 sampleUV = shadowCoord.st + rotatedOffset * filterStep;
        float sampleDepth = texture2D(shadowtex0, sampleUV).r;
        shadowSum += (receiverDepth > sampleDepth) ? 0.0 : 1.0;
    }

    float shadow = shadowSum * 0.0625; // 1/16

    vec2 edgeDist = abs(shadowCoord.st - 0.5) * 2.0;
    float fade = 1.0 - smoothstep(0.85, 0.98, max(edgeDist.x, edgeDist.y));

    return mix(1.0, shadow, fade);
}

void main() {
    vec4 albedo = texture2D(texture, vTexCoord.st) * vColor;

    if (albedo.a < 0.1) {
        discard;
    }

    // 优化：利用短路求值逻辑，优先匹配概率最高的不透明度 alpha 判断
    bool isThin = (albedo.a < 0.99) || 
                  (vBlockId >= 31.0 && vBlockId <= 38.0) || 
                  (vBlockId >= 188.0 && vBlockId <= 192.0) || 
                  vBlockId == 175.0 || vBlockId == 6.0 || vBlockId == 59.0 || 
                  vBlockId == 85.0 || vBlockId == 113.0 || 
                  vBlockId == 101.0 || vBlockId == 102.0 || 
                  vBlockId == 96.0 || vBlockId == 167.0;

    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vEyePos);

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float sunHeight = worldSunDir.y;
    vec3 L = normalize((gbufferModelView * vec4(worldSunDir, 0.0)).xyz);

    // ---- 天空与太阳色彩 ----
    const vec3 skyNight   = vec3(0.008, 0.010, 0.045);
    const vec3 skySunset  = vec3(0.55, 0.25, 0.06);
    const vec3 skyNoon    = vec3(0.18, 0.35, 0.75);

    const vec3 sunNight   = vec3(0.00, 0.00, 0.00);
    const vec3 sunSunset  = vec3(0.90, 0.40, 0.08);
    const vec3 sunNoon    = vec3(1.00, 0.92, 0.75);

    float dayFactor  = smoothstep(-0.05, 0.15, sunHeight);
    float noonFactor = smoothstep(0.00, 0.60, sunHeight);

    vec3 skyColor = mix(skyNight, mix(skySunset, skyNoon, noonFactor), dayFactor);

    vec3 sunlight;
    float sunIntensity;
    if (sunHeight > 0.0) {
        sunlight = mix(sunNight, mix(sunSunset, sunNoon, noonFactor), dayFactor);
        sunIntensity = 1.0;
    } else {
        sunlight = vec3(0.15, 0.20, 0.35);
        sunIntensity = 0.4;
    }

    float NdotL_raw = dot(N, L);

    float shadow = 1.0;
    if (sunHeight > 0.05) {
        shadow = getPCSSShadowHD(vEyePos, N, NdotL_raw, isThin);
    }

    vec4 lm = texture2D(lightmap, vLightmap);
    float skyLightRaw   = lm.y;
    float blockLightRaw = lm.x;

    float skyAmbient = CurveBlockLightSky(skyLightRaw);
    float skyDirect  = skyLightRaw * skyLightRaw;

    float blockLight = pow(blockLightRaw, 2.5);

    float sunlightDiffuse = isThin ? (abs(NdotL_raw) * 0.7 + 0.3) : clamp(NdotL_raw, 0.0, 1.0);

    vec3 effectiveN = (NdotL_raw >= 0.0) ? N : -N;
    float NdotV = max(0.0, dot(effectiveN, V));
    float ambientOcclusion = 0.5 + 0.5 * NdotV;

    vec3 ambientColor = skyColor * 0.12;
    vec3 ambient = ambientColor * skyAmbient * (0.85 + 0.15 * ambientOcclusion);

    vec3 directLightColor = mix(sunlight, skyColor, 0.15 * (1.0 - dayFactor));
    vec3 directLight = shadow * sunlightDiffuse * directLightColor * skyDirect * 0.65 * sunIntensity;

    // 火把光源
    const vec3 torchColor = vec3(1.0, 0.52, 0.18);
    float torchRange = 1.0 - blockLight;
    float torchShape = blockLight * (1.0 + torchRange * 0.3);
    vec3 torchLight = torchColor * torchShape * 0.18;

    // 暗部环境光
    float minLightStr = 0.0025 * (1.0 - skyDirect) + 0.006;
    vec3 minLight = vec3(0.015, 0.020, 0.040) * minLightStr;

    vec3 lighting = ambient + directLight + torchLight + minLight;
    vec3 color = albedo.rgb * lighting;

    gl_FragData[0] = vec4(color, albedo.a);

    float waterFlag = (vBlockId == 8.0 || vBlockId == 9.0 || vBlockId == 79.0) ? 0.79 : 1.0;
    gl_FragData[1] = vec4(N.xy * 0.5 + 0.5, waterFlag, 1.0);
}
