#version 410 core
/* DRAWBUFFERS:034 */

#include "/lib/common.glsl"

in vec2 texCoord;

uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;

uniform vec3 sunPosition;
uniform mat4 gbufferModelViewInverse;

#define ENABLE_CROSS_GLARE
#define GLARE_LENGTH 32.0

// 预计算 Vogel Disk 采样方向
const vec2 VOGEL_SAMPLES[14] = vec2[14](
    vec2( 0.000000,  0.188982), vec2(-0.270908,  0.248107), vec2( 0.041042, -0.466367),
    vec2( 0.380387,  0.502728), vec2(-0.720815, -0.123617), vec2( 0.688177, -0.437599),
    vec2(-0.222718,  0.825723), vec2(-0.431264, -0.824955), vec2( 0.925049,  0.306042),
    vec2(-0.929864,  0.360292), vec2( 0.422731, -0.946960), vec2( 0.380482,  0.954157),
    vec2(-0.970878, -0.398249), vec2( 1.070051, -0.167145)
);

// 预计算 Vogel Disk 对应的高斯权重 w = exp(-3.0 * r * r)
const float VOGEL_WEIGHTS[14] = float[14](
    0.898399, 0.725112, 0.585250, 0.472367, 0.381258, 0.307718, 0.248368,
    0.200461, 0.161797, 0.130588, 0.105399, 0.085071, 0.068662, 0.055418
);

// 预计算 Glare 循环指数衰减 exp(-0.15 * i)
const float GLARE_WEIGHTS[12] = float[12](
    1.000000, 0.860708, 0.740818, 0.637628, 0.548812, 0.472367,
    0.406570, 0.349938, 0.301194, 0.259240, 0.223130, 0.192050
);

// 快速剪枝提取高光
vec3 extractPureLight(vec3 color, float threshold) {
    float lum = luminance(color);
    if (lum <= threshold - 0.1) return vec3(0.0);
    
    float response = max(0.0, lum - threshold + 0.1);
    response = (response * response) * 2.49937516;
    float weight = max(lum - threshold, response) / lum;
    
    return color * weight;
}

void makeDualBloom(float threshold, vec2 uv, vec2 texel, out vec3 tight, out vec3 wide) {
    vec3 sumTight = vec3(0.0);
    vec3 sumWide = vec3(0.0);
    float wTightSum = 0.0;
    float wWideSum = 0.0;

    float angle = rand(uv) * 6.28318530718;
    float ca = cos(angle), sa = sin(angle);
    mat2 rot = mat2(ca, -sa, sa, ca);

    for (int i = 0; i < 14; i++) {
        float w = VOGEL_WEIGHTS[i];

        vec2 offsetDir = rot * VOGEL_SAMPLES[i];
        vec2 baseOffset = offsetDir * texel;
        vec2 uvTight = uv + baseOffset * 4.0;
        vec2 uvWide  = uv + baseOffset * 12.0;

        if (uvTight.x >= 0.0 && uvTight.x <= 1.0 && uvTight.y >= 0.0 && uvTight.y <= 1.0) {
            sumTight += extractPureLight(texture(colortex0, uvTight).rgb, threshold) * w;
            wTightSum += w;
        }
        if (uvWide.x >= 0.0 && uvWide.x <= 1.0 && uvWide.y >= 0.0 && uvWide.y <= 1.0) {
            sumWide += extractPureLight(texture(colortex0, uvWide).rgb, threshold) * w;
            wWideSum += w;
        }
    }

    tight = sumTight / max(wTightSum, 0.0001);
    wide  = sumWide / max(wWideSum, 0.0001);
}

vec3 makeCrossGlareOptimized(float glarePixels, vec2 uv, float threshold, vec2 texel) {
    vec3 glareSum = vec3(0.0);
    float totalWeight = 0.0;

    const float rcpSamples = 1.0 / 12.0;
    float jitter = rand(uv * 1.5) * 0.5;
    vec2 stepTexel = glarePixels * texel * rcpSamples;
    
    float jitterWeight = exp(-0.15 * jitter);

    for (int i = 0; i < 12; i++) {
        float progress = (float(i) + jitter) * rcpSamples;
        vec2 offset = progress * stepTexel;

        vec2 uv0 = clamp(uv + offset, 0.0, 1.0);
        vec2 uv1 = clamp(uv - offset, 0.0, 1.0);

        vec3 c0 = extractPureLight(texture(colortex0, uv0).rgb, threshold);
        vec3 c1 = extractPureLight(texture(colortex0, uv1).rgb, threshold);

        float weight = GLARE_WEIGHTS[i] * jitterWeight;
        glareSum += (c0 + c1) * weight;
        totalWeight += 2.0 * weight;
    }

    return glareSum / max(totalWeight, 0.0001);
}

layout(location = 0) out vec4 fragData0;
layout(location = 1) out vec4 fragData1;
layout(location = 2) out vec4 fragData2;

void main() {
    vec2 uv = texCoord;
    vec2 texel = vec2(1.0 / viewWidth, 1.0 / viewHeight);
    vec3 hdr = texture(colortex0, uv).rgb;

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float nightFactor = smoothstep(0.05, -0.15, worldSunDir.y);
    float threshold = mix(0.42, 0.35, nightFactor);

    vec3 tightCore, wideAura;
    makeDualBloom(threshold, uv, texel, tightCore, wideAura);

    vec3 crossGlare = vec3(0.0);
    #ifdef ENABLE_CROSS_GLARE
        crossGlare = makeCrossGlareOptimized(GLARE_LENGTH, uv, threshold, texel);
    #endif

    float boost = mix(2.0, 5.0, nightFactor);

    vec3 outTight = (tightCore + crossGlare * 1.8) * (10.0 * boost);
    vec3 outWide  = wideAura * (15.0 * boost);

    fragData0 = vec4(hdr, 1.0);
    fragData1 = vec4(outTight, 1.0);
    fragData2 = vec4(outWide, 1.0);
}