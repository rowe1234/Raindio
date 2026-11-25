#version 450
#include "common/raindio_uniforms.glsl"

/* DRAWBUFFERS:34 */

#include "lib/common.glsl"

layout(location = 0) in vec2 texCoord;

layout(set = 0, binding = 1) uniform sampler2D colortex0;

// Clean, zero-noise quarter-resolution Dual Bloom:
// - Completely deterministic (NO per-texel IGN rotation/jitter, which gets magnified 4x-6x
//   into dirty blotchy halos when upsampling a 1/4-resolution bloom buffer).
// - Uses hardware linear filtering + Karis-weighted 13-tap prefilter (Jiminez / CoD style)
//   for tightCore and a 16-tap staggered 2-ring Gaussian disk for wideAura.
#define BLOOM_THRESHOLD 1.35    // [0.75 0.95 1.15 1.35 1.60 2.00]
#define ANAMORPHIC_STRENGTH 0.35

// Soft-knee highlight extraction with Karis firefly compression:
// Prevents sunlit snow from turning into muddy haze while keeping torches, lanterns,
// glowstone, and sun glints clean and luminous.
vec3 extractPureLight(vec3 color, float threshold) {
    vec3 safeColor = max(color, vec3(0.0));
    float lum = luminance(safeColor);
    float knee = 0.28;
    if (lum <= threshold - knee) return vec3(0.0);

    float soft = clamp(lum - threshold + knee, 0.0, 2.0 * knee);
    soft = (soft * soft) / max(4.0 * knee, 1e-4);
    float contrib = max(lum - threshold, soft) / max(lum, 1e-4);

    // Karis-style compression on extreme HDR spikes (> 5.0) to prevent firefly blobs
    float karis = 1.0 / (1.0 + max(lum - 5.0, 0.0) * 0.10);
    return safeColor * (contrib * karis);
}

vec3 sampleHighlight(vec2 uv, float threshold) {
    return extractPureLight(texture(colortex0, clamp(uv, vec2(0.001), vec2(0.999))).rgb, threshold);
}

// 13-tap deterministic Jiminez/CoD box+tent prefilter for clean, alias-free tight bloom
vec3 computeTightBloom(vec2 uv, vec2 texel, float threshold) {
    vec2 t15 = texel * 1.5;
    vec2 t30 = texel * 3.0;

    vec3 c  = sampleHighlight(uv, threshold);

    vec3 i0 = sampleHighlight(uv + vec2(-t15.x, -t15.y), threshold);
    vec3 i1 = sampleHighlight(uv + vec2( t15.x, -t15.y), threshold);
    vec3 i2 = sampleHighlight(uv + vec2(-t15.x,  t15.y), threshold);
    vec3 i3 = sampleHighlight(uv + vec2( t15.x,  t15.y), threshold);

    vec3 o0 = sampleHighlight(uv + vec2(-t30.x,  0.0),   threshold);
    vec3 o1 = sampleHighlight(uv + vec2( t30.x,  0.0),   threshold);
    vec3 o2 = sampleHighlight(uv + vec2( 0.0,   -t30.y), threshold);
    vec3 o3 = sampleHighlight(uv + vec2( 0.0,    t30.y), threshold);

    vec3 d0 = sampleHighlight(uv + vec2(-t30.x, -t30.y), threshold);
    vec3 d1 = sampleHighlight(uv + vec2( t30.x, -t30.y), threshold);
    vec3 d2 = sampleHighlight(uv + vec2(-t30.x,  t30.y), threshold);
    vec3 d3 = sampleHighlight(uv + vec2( t30.x,  t30.y), threshold);

    // Weighted average: center (0.20) + inner 4 (0.40) + cardinal 4 (0.24) + diagonal 4 (0.16)
    return c * 0.20
         + (i0 + i1 + i2 + i3) * 0.10
         + (o0 + o1 + o2 + o3) * 0.06
         + (d0 + d1 + d2 + d3) * 0.04;
}

// 16-tap deterministic staggered 2-ring Gaussian disk for silky-smooth wide aura (zero noise)
vec3 computeWideBloom(vec2 uv, vec2 texel, float threshold) {
    const vec2 RING1[8] = vec2[8](
        vec2( 1.0000,  0.0000), vec2( 0.7071,  0.7071),
        vec2( 0.0000,  1.0000), vec2(-0.7071,  0.7071),
        vec2(-1.0000,  0.0000), vec2(-0.7071, -0.7071),
        vec2( 0.0000, -1.0000), vec2( 0.7071, -0.7071)
    );
    // Staggered by 22.5 degrees for uniform circular coverage without gaps
    const vec2 RING2[8] = vec2[8](
        vec2( 0.9239,  0.3827), vec2( 0.3827,  0.9239),
        vec2(-0.3827,  0.9239), vec2(-0.9239,  0.3827),
        vec2(-0.9239, -0.3827), vec2(-0.3827, -0.9239),
        vec2( 0.3827, -0.9239), vec2( 0.9239, -0.3827)
    );

    vec3 sum = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        sum += sampleHighlight(uv + RING1[i] * texel * 6.0,  threshold) * 0.080;
        sum += sampleHighlight(uv + RING2[i] * texel * 12.5, threshold) * 0.045;
    }
    return sum;
}

// Subtle deterministic horizontal anamorphic streak (zero jitter, clean on torches/sun)
vec3 computeAnamorphicStreak(vec2 uv, vec2 texel, float threshold) {
    const float OFFSETS[4] = float[4](3.5, 7.5, 12.5, 18.5);
    const float WEIGHTS[4] = float[4](0.36, 0.28, 0.21, 0.15);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec2 dx = vec2(OFFSETS[i] * texel.x, 0.0);
        sum += (sampleHighlight(uv + dx, threshold) + sampleHighlight(uv - dx, threshold)) * (0.5 * WEIGHTS[i]);
    }
    return sum;
}

layout(location = 0) out vec4 fragTight;
layout(location = 1) out vec4 fragWide;

void main() {
    vec2 uv = texCoord;
    // Quarter-resolution Bloom pass: step in source colortex0 texels so screen-space bloom radius stays identical
    vec2 texel = vec2(1.0) / vec2(max(textureSize(colortex0, 0), ivec2(1)));

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float nightFactor = 1.0 - smoothstep(-0.15, 0.05, worldSunDir.y);
    float threshold = mix(BLOOM_THRESHOLD, 0.85, nightFactor);

    vec3 tightCore = computeTightBloom(uv, texel, threshold);
    vec3 wideAura  = computeWideBloom(uv, texel, threshold);
    vec3 streak    = computeAnamorphicStreak(uv, texel, threshold);

    // Stratified night boost: glows emissive torches/lanterns without washing out daytime snow
    float boost = mix(1.35, 3.0, nightFactor);
    vec3 outTight = (tightCore + streak * ANAMORPHIC_STRENGTH) * (5.5 * boost);
    vec3 outWide  = (tightCore * 0.25 + wideAura * 0.75) * (8.0 * boost);

    fragTight = vec4(outTight, 1.0);
    fragWide  = vec4(outWide, 1.0);
}
