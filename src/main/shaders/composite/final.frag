#version 450
#include "common/raindio_uniforms.glsl"


// =============================================================================
// Shader Options (OptiFine / Iris 菜单调控选项)
// =============================================================================

// ---- 抗锯齿与超分开关 (UPSCALE_MODE: 0=OFF, 1=TAAU, 2=FSR 1.0) ----
#ifndef TAA_ENABLED
#define TAA_ENABLED 1
#endif
#ifndef FXAA_ENABLED
#define FXAA_ENABLED 1
#endif
#ifndef UPSCALE_MODE
#define UPSCALE_MODE 1
#endif
#ifndef FSR_SHARPNESS
#define FSR_SHARPNESS 0.25
#endif

// ---- COLOR_GRADING 调色参数 (Fix #23: 默认值在值列表中) ----
#ifndef SATURATION
#define SATURATION 1.16 // [0.0 0.4 0.6 0.8 0.9 1.0 1.1 1.16 1.18 1.25 1.4 1.6 1.8 2.0]
#endif
#define LUMA_GAMMA 1.02 // [0.6 0.7 0.8 0.9 1.0 1.02 1.1 1.2 1.3 1.4 1.5]
#define WHITE_CLIP 1.0  // [0.7 0.8 0.85 0.9 0.95 1.0]

layout(location = 0) in vec2 texCoord;

layout(set = 0, binding = 1) uniform sampler2D colortex0;

layout(location = 0) out vec4 fragColor;

float fxaaLuma(vec3 rgb) {
    return dot(rgb, vec3(0.299, 0.587, 0.114));
}

// Proper FXAA 3.11 edge-directed anti-aliasing using dynamic viewWidth/viewHeight (Fix #22)
vec3 applyFXAA(sampler2D tex, vec2 uv, vec2 rcpFrame) {
    vec3 rgbM  = texture(tex, uv).rgb;
    vec3 rgbN  = texture(tex, uv + vec2( 0.0,  rcpFrame.y)).rgb;
    vec3 rgbS  = texture(tex, uv + vec2( 0.0, -rcpFrame.y)).rgb;
    vec3 rgbE  = texture(tex, uv + vec2( rcpFrame.x,  0.0)).rgb;
    vec3 rgbW  = texture(tex, uv + vec2(-rcpFrame.x,  0.0)).rgb;

    float lumaM = fxaaLuma(rgbM);
    float lumaN = fxaaLuma(rgbN);
    float lumaS = fxaaLuma(rgbS);
    float lumaE = fxaaLuma(rgbE);
    float lumaW = fxaaLuma(rgbW);

    float lumaMin = min(lumaM, min(min(lumaN, lumaS), min(lumaE, lumaW)));
    float lumaMax = max(lumaM, max(max(lumaN, lumaS), max(lumaE, lumaW)));
    float lumaRange = lumaMax - lumaMin;

    if (lumaRange < max(0.0312, lumaMax * 0.125)) {
        return rgbM;
    }

    vec3 rgbNW = texture(tex, uv + vec2(-rcpFrame.x,  rcpFrame.y)).rgb;
    vec3 rgbNE = texture(tex, uv + vec2( rcpFrame.x,  rcpFrame.y)).rgb;
    vec3 rgbSW = texture(tex, uv + vec2(-rcpFrame.x, -rcpFrame.y)).rgb;
    vec3 rgbSE = texture(tex, uv + vec2( rcpFrame.x, -rcpFrame.y)).rgb;

    float lumaNW = fxaaLuma(rgbNW);
    float lumaNE = fxaaLuma(rgbNE);
    float lumaSW = fxaaLuma(rgbSW);
    float lumaSE = fxaaLuma(rgbSE);

    float lumaNS = lumaN + lumaS;
    float lumaWE = lumaW + lumaE;
    float lumaNWSW = lumaNW + lumaSW;
    float lumaNENE = lumaNE + lumaSE;
    float lumaNWNE = lumaNW + lumaNE;
    float lumaSWSE = lumaSW + lumaSE;

    float edgeHorz = abs(-2.0 * lumaW + lumaNWSW) + abs(-2.0 * lumaM + lumaNS) * 2.0 + abs(-2.0 * lumaE + lumaNENE);
    float edgeVert = abs(-2.0 * lumaN + lumaNWNE) + abs(-2.0 * lumaM + lumaWE) * 2.0 + abs(-2.0 * lumaS + lumaSWSE);
    bool isHorizontal = (edgeHorz >= edgeVert);

    float luma1 = isHorizontal ? lumaS : lumaW;
    float luma2 = isHorizontal ? lumaN : lumaE;
    float gradient1 = luma1 - lumaM;
    float gradient2 = luma2 - lumaM;
    bool is1Steepest = abs(gradient1) >= abs(gradient2);
    float gradientScaled = 0.25 * max(abs(gradient1), abs(gradient2));

    float stepLength = isHorizontal ? rcpFrame.y : rcpFrame.x;
    float lumaLocalAverage = 0.0;
    if (is1Steepest) {
        stepLength = -stepLength;
        lumaLocalAverage = 0.5 * (luma1 + lumaM);
    } else {
        lumaLocalAverage = 0.5 * (luma2 + lumaM);
    }

    vec2 currentUv = uv;
    if (isHorizontal) currentUv.y += stepLength * 0.5;
    else              currentUv.x += stepLength * 0.5;

    vec2 offset = isHorizontal ? vec2(rcpFrame.x, 0.0) : vec2(0.0, rcpFrame.y);
    vec2 uv1 = currentUv - offset;
    vec2 uv2 = currentUv + offset;

    float lumaEnd1 = fxaaLuma(texture(tex, uv1).rgb) - lumaLocalAverage;
    float lumaEnd2 = fxaaLuma(texture(tex, uv2).rgb) - lumaLocalAverage;
    bool reached1 = abs(lumaEnd1) >= gradientScaled;
    bool reached2 = abs(lumaEnd2) >= gradientScaled;

    for (int i = 0; i < 6; i++) {
        if (!reached1) {
            uv1 -= offset;
            lumaEnd1 = fxaaLuma(texture(tex, uv1).rgb) - lumaLocalAverage;
            reached1 = abs(lumaEnd1) >= gradientScaled;
        }
        if (!reached2) {
            uv2 += offset;
            lumaEnd2 = fxaaLuma(texture(tex, uv2).rgb) - lumaLocalAverage;
            reached2 = abs(lumaEnd2) >= gradientScaled;
        }
        if (reached1 && reached2) break;
    }

    float distance1 = isHorizontal ? (uv.x - uv1.x) : (uv.y - uv1.y);
    float distance2 = isHorizontal ? (uv2.x - uv.x) : (uv2.y - uv.y);
    bool isDirection1 = distance1 < distance2;
    float distanceFinal = min(distance1, distance2);
    float edgeThickness = max(distance1 + distance2, 1e-5);
    float pixelOffset = -distanceFinal / edgeThickness + 0.5;

    bool isLumaCenterSmaller = lumaM < lumaLocalAverage;
    bool correctVariation = ((isDirection1 ? lumaEnd1 : lumaEnd2) < 0.0) != isLumaCenterSmaller;
    float finalOffset = correctVariation ? pixelOffset : 0.0;

    // Sub-pixel anti-aliasing blend
    float lumaAverage = (1.0 / 12.0) * (2.0 * (lumaNS + lumaWE) + lumaNWSW + lumaNENE);
    float subPixelOffset1 = clamp(abs(lumaAverage - lumaM) / lumaRange, 0.0, 1.0);
    float subPixelOffset2 = (-2.0 * subPixelOffset1 + 3.0) * subPixelOffset1 * subPixelOffset1;
    float subPixelOffsetFinal = subPixelOffset2 * subPixelOffset2 * 0.75;

    finalOffset = max(finalOffset, subPixelOffsetFinal);

    vec2 finalUv = uv;
    if (isHorizontal) finalUv.y += finalOffset * stepLength;
    else              finalUv.x += finalOffset * stepLength;

    return texture(tex, finalUv).rgb;
}

// Lightweight FidelityFX-style Contrast Adaptive Sharpening (CAS) for OFF + TAA fallback
vec3 applyLightCAS(sampler2D tex, vec2 uv, vec2 rcpFrame, float sharpness) {
    vec3 c = texture(tex, uv).rgb;
    vec3 n = texture(tex, uv + vec2(0.0,  rcpFrame.y)).rgb;
    vec3 s = texture(tex, uv + vec2(0.0, -rcpFrame.y)).rgb;
    vec3 e = texture(tex, uv + vec2( rcpFrame.x, 0.0)).rgb;
    vec3 w = texture(tex, uv + vec2(-rcpFrame.x, 0.0)).rgb;

    vec3 minC = min(c, min(min(n, s), min(e, w)));
    vec3 maxC = max(c, max(max(n, s), max(e, w)));
    vec3 amp = clamp(min(minC, vec3(1.0) - maxC) / max(maxC, vec3(1e-4)), vec3(0.0), vec3(1.0));
    vec3 weight = sqrt(amp) * (-sharpness);
    return clamp((c + (n + s + e + w) * weight) / (1.0 + 4.0 * weight), vec3(0.0), vec3(1.0));
}

// Standard 5-tap AMD FSR 1.0 RCAS (Robust Contrast-Adaptive Sharpening) at native display resolution
vec3 applyRCAS(sampler2D tex, vec2 uv, float sharpness) {
    ivec2 sz = max(textureSize(tex, 0), ivec2(1));
    ivec2 maxC = sz - ivec2(1);
    ivec2 p = clamp(ivec2(floor(uv * vec2(sz))), ivec2(0), maxC);

    vec3 e = texelFetch(tex, p, 0).rgb;
    if (sharpness <= 0.001) {
        return e;
    }
    vec3 b = texelFetch(tex, clamp(p + ivec2( 0, -1), ivec2(0), maxC), 0).rgb;
    vec3 d = texelFetch(tex, clamp(p + ivec2(-1,  0), ivec2(0), maxC), 0).rgb;
    vec3 f = texelFetch(tex, clamp(p + ivec2( 1,  0), ivec2(0), maxC), 0).rgb;
    vec3 h = texelFetch(tex, clamp(p + ivec2( 0,  1), ivec2(0), maxC), 0).rgb;

    float bL = 0.5 * b.g + 0.25 * (b.r + b.b);
    float dL = 0.5 * d.g + 0.25 * (d.r + d.b);
    float eL = 0.5 * e.g + 0.25 * (e.r + e.b);
    float fL = 0.5 * f.g + 0.25 * (f.r + f.b);
    float hL = 0.5 * h.g + 0.25 * (h.r + h.b);

    float min4 = min(min(bL, dL), min(fL, hL));
    float max4 = max(max(bL, dL), max(fL, hL));
    float min5 = min(min4, eL);
    float max5 = max(max4, eL);

    // Ringing-free lobe bound (capped at FSR 1.0 RCAS_LIMIT = 0.1875 to prevent white halos)
    float hitMin = min5 / max(4.0 * max4, 1e-4);
    float hitMax = (1.0 - max5) / min(4.0 * (min4 - 1.0), -1e-4);
    float lobeRGB = max(-hitMin, hitMax);
    float w = clamp(min(min4 / max(max4, 1e-4), (1.0 - max4) / max(1.0 - min4, 1e-4)), 0.0, 1.0);
    float lobe = max(-0.1875, min(lobeRGB, 0.0)) * clamp(sharpness, 0.0, 1.0) * (0.35 + 0.65 * w);

    vec3 outCol = (lobe * (b + d + f + h) + e) / max(1.0 + 4.0 * lobe, 1e-4);
    return clamp(outCol, 0.0, 1.0);
}

void main() {
    // Use actual source texture size (native display res in TAAU/FSR1, or internal res in OFF)
    vec2 rcpFrame = vec2(1.0) / vec2(max(textureSize(colortex0, 0), ivec2(1)));

#if DEBUG_VIEW > 0
    vec3 color = texture(colortex0, texCoord).rgb;
#elif UPSCALE_MODE >= 1
    vec3 color = applyRCAS(colortex0, texCoord, FSR_SHARPNESS);
#elif TAA_ENABLED == 1
    vec3 color = applyLightCAS(colortex0, texCoord, rcpFrame, 0.08);
#elif FXAA_ENABLED == 1
    vec3 color = applyFXAA(colortex0, texCoord, rcpFrame);
#else
    vec3 color = texture(colortex0, texCoord).rgb;
#endif

    // =========================================================================
    // COLOR GRADING (调色系统)
    // =========================================================================
    const vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);

    // 1. 自然鲜艳度/饱和度调节 (Vibrance-aware: 保护高饱和植被与水体不过度荧光化)
    float luma = dot(color, lumaWeights);
    float maxC = max(color.r, max(color.g, color.b));
    float minC = min(color.r, min(color.g, color.b));
    float curSat = (maxC > 1e-4) ? ((maxC - minC) / maxC) : 0.0;
    float effSat = (SATURATION > 1.0)
        ? mix(SATURATION, 1.0 + (SATURATION - 1.0) * 0.35, smoothstep(0.45, 0.90, curSat))
        : SATURATION;
    color = mix(vec3(luma), color, effSat);

    // 2. 相对伽马调节 (LUMA_GAMMA, 1.0 = 标准 sRGB)
    color = pow(max(color, vec3(0.0)), vec3(1.0 / LUMA_GAMMA));

    // 3. 高光/白点裁切 (WHITE_CLIP)
    color = clamp(color / WHITE_CLIP, 0.0, 1.0);

    fragColor = vec4(color, 1.0);
}
