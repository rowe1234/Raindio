#version 410 core

// =============================================================================
// Shader Options (OptiFine / Iris 菜单调控选项)
// =============================================================================

// ---- 抗锯齿开关 ----
//#define FXAA // [false true]

// ---- COLOR_GRADING 调色参数 ----
#define SATURATION 0.9  // [0.0 0.2 0.4 0.6 0.8 1.0 1.2 1.4 1.6 1.8 2.0]
#define LUMA_GAMMA 1.2  // [0.5 0.6 0.7 0.8 0.9 1.0 1.1 1.2 1.3 1.4 1.5]
#define WHITE_CLIP 1.0  // [0.7 0.8 0.85 0.9 0.95 1.0]

in vec2 texCoord;

uniform sampler2D colortex0;

out vec4 fragColor;

void main() {
    vec3 color = texture(colortex0, texCoord).rgb;

    // =========================================================================
    // COLOR GRADING (调色系统)
    // =========================================================================
    const vec3 lumaWeights = vec3(0.299, 0.587, 0.114);

    // 1. 饱和度调节 (SATURATION)
    float luma = dot(color, lumaWeights);
    color = mix(vec3(luma), color, SATURATION);

    // 2. 伽马/亮度校正 (LUMA_GAMMA)
    color = pow(max(color, vec3(0.0)), vec3(1.0 / LUMA_GAMMA));

    // 3. 高光/白点裁切 (WHITE_CLIP)
    color = clamp(color / WHITE_CLIP, 0.0, 1.0);

    // =========================================================================
    // FXAA (抗锯齿系统)
    // =========================================================================
#ifdef FXAA
    vec2 texelSize = vec2(1.0 / 1920.0, 1.0 / 1080.0);

    vec3 cUp    = texture(colortex0, texCoord + vec2(0.0, texelSize.y)).rgb;
    vec3 cDown  = texture(colortex0, texCoord - vec2(0.0, texelSize.y)).rgb;
    vec3 cLeft  = texture(colortex0, texCoord - vec2(texelSize.x, 0.0)).rgb;
    vec3 cRight = texture(colortex0, texCoord + vec2(texelSize.x, 0.0)).rgb;

    float lumaCenter = dot(color, lumaWeights);
    float lumaUp     = dot(cUp, lumaWeights);
    float lumaDown   = dot(cDown, lumaWeights);
    float lumaLeft   = dot(cLeft, lumaWeights);
    float lumaRight  = dot(cRight, lumaWeights);

    float lumaMin = min(lumaCenter, min(min(lumaUp, lumaDown), min(lumaLeft, lumaRight)));
    float lumaMax = max(lumaCenter, max(max(lumaUp, lumaDown), max(lumaLeft, lumaRight)));

    if ((lumaMax - lumaMin) > max(0.05, lumaMax * 0.12)) {
        vec3 colorAverage = (cUp + cDown + cLeft + cRight) * 0.25;
        color = mix(color, colorAverage, 0.4);
    }
#endif

    fragColor = vec4(color, 1.0);
}
