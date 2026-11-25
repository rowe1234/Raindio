#version 450
#include "common/raindio_uniforms.glsl"

// ============================================================================
// Pure GLSL 450 AMD FidelityFX Super Resolution 1.0 — EASU Pass
// (Edge-Adaptive Spatial Upsampling: 12-tap anisotropic Lanczos2 + Anti-Ringing)
//   - Reads tonemapped low-res LDR colortex0 (renderW x renderH)
//   - Writes native display resolution displayLdr (displayW x displayH)
//   - MoltenVK / Vulkan 1.2 compliant, single descriptor set (set = 0)
// ============================================================================

layout(location = 0) in vec2 texCoord;

layout(set = 0, binding = 1) uniform sampler2D colortex0;

layout(location = 0) out vec4 fragColor;

float fsrLuma(vec3 c) {
    return 0.5 * c.g + 0.25 * (c.r + c.b);
}

// Accumulate local directional gradient & anisotropy for one of the 4 core bilinear taps
void easuSet(
    inout vec2 dir,
    inout float len,
    float w,
    float lA, float lB, float lC, float lD, float lE
) {
    // Horizontal gradient (D - B) and edge contrast ratio
    float lenX = max(abs(lD - lC), abs(lC - lB));
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) / max(lenX, 1e-4), 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;

    // Vertical gradient (E - A) and edge contrast ratio
    float lenY = max(abs(lE - lC), abs(lC - lA));
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) / max(lenY, 1e-4), 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

// Anisotropic Lanczos2 approximation tap accumulation
void easuTap(
    inout vec3 aC,
    inout float aW,
    vec2 off,
    vec2 dir,
    vec2 len,
    float lob,
    float clp,
    vec3 c
) {
    // Rotate offset into local edge frame (v.x along edge, v.y across edge)
    vec2 v;
    v.x = (off.x * dir.x) + (off.y * dir.y);
    v.y = (off.x * (-dir.y)) + (off.y * dir.x);
    v *= len;
    float d2 = min(dot(v, v), clp);

    // Fast Lanczos2 polynomial: ((2/5 * d2 - 1)^2 * (25/16) - (25/16 - 1)) * (lob * d2 - 1)^2
    float wB = (0.4 * d2) - 1.0;
    float wA = (lob * d2) - 1.0;
    wB *= wB;
    wA *= wA;
    wB = (1.5625 * wB) - 0.5625;
    float w = wB * wA;

    aC += c * w;
    aW += w;
}

vec3 fetchClamp(ivec2 coord, ivec2 maxCoord) {
    return max(texelFetch(colortex0, clamp(coord, ivec2(0), maxCoord), 0).rgb, vec3(0.0));
}

void main() {
    ivec2 srcSizeI = max(textureSize(colortex0, 0), ivec2(1));
    ivec2 maxCoord = srcSizeI - ivec2(1);
    vec2 srcSize = vec2(srcSizeI);

    // Exact source pixel position centered on texel centers
    vec2 pp = texCoord * srcSize - 0.5;
    vec2 fp = floor(pp);
    ivec2 ip = ivec2(fp);
    vec2 f = pp - fp; // fractional position in [0, 1) within core 2x2 (f, g, j, k)

    // 12-tap cross neighborhood around core 2x2:
    //       b  c
    //    e  f  g  h
    //    i  j  k  l
    //       n  o
    vec3 b = fetchClamp(ip + ivec2( 0, -1), maxCoord);
    vec3 c = fetchClamp(ip + ivec2( 1, -1), maxCoord);
    vec3 e = fetchClamp(ip + ivec2(-1,  0), maxCoord);
    vec3 fC = fetchClamp(ip + ivec2( 0,  0), maxCoord);
    vec3 g = fetchClamp(ip + ivec2( 1,  0), maxCoord);
    vec3 h = fetchClamp(ip + ivec2( 2,  0), maxCoord);
    vec3 iC = fetchClamp(ip + ivec2(-1,  1), maxCoord);
    vec3 j = fetchClamp(ip + ivec2( 0,  1), maxCoord);
    vec3 k = fetchClamp(ip + ivec2( 1,  1), maxCoord);
    vec3 l = fetchClamp(ip + ivec2( 2,  1), maxCoord);
    vec3 n = fetchClamp(ip + ivec2( 0,  2), maxCoord);
    vec3 o = fetchClamp(ip + ivec2( 1,  2), maxCoord);

    float bL = fsrLuma(b),  cL = fsrLuma(c);
    float eL = fsrLuma(e),  fL = fsrLuma(fC), gL = fsrLuma(g), hL = fsrLuma(h);
    float iL = fsrLuma(iC), jL = fsrLuma(j),  kL = fsrLuma(k), lL = fsrLuma(l);
    float nL = fsrLuma(n),  oL = fsrLuma(o);

    // Bilinearly blend directional gradients across the 4 core texels
    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - f.x) * (1.0 - f.y), bL, eL, fL, gL, jL);
    easuSet(dir, len, f.x         * (1.0 - f.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, (1.0 - f.x) * f.y,         fL, iL, jL, kL, nL);
    easuSet(dir, len, f.x         * f.y,         gL, jL, kL, lL, oL);

    // Normalize edge direction (fallback to horizontal if flat)
    float dirR = dot(dir, dir);
    if (dirR < 1e-6) {
        dir = vec2(1.0, 0.0);
    } else {
        dir *= inversesqrt(dirR);
    }

    // Convert anisotropy strength into filter stretch along edge & sharpening lobe
    len *= 0.5;
    len *= len;
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((0.25 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;

    // Anti-ringing bounds from core 2x2 neighborhood
    vec3 min4 = min(min(fC, g), min(j, k));
    vec3 max4 = max(max(fC, g), max(j, k));

    // 12-tap anisotropic Lanczos2 reconstruction
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2( 0.0, -1.0) - f, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2( 1.0, -1.0) - f, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0,  0.0) - f, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2( 0.0,  0.0) - f, dir, len2, lob, clp, fC);
    easuTap(aC, aW, vec2( 1.0,  0.0) - f, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2( 2.0,  0.0) - f, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2(-1.0,  1.0) - f, dir, len2, lob, clp, iC);
    easuTap(aC, aW, vec2( 0.0,  1.0) - f, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2( 1.0,  1.0) - f, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2( 2.0,  1.0) - f, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2( 0.0,  2.0) - f, dir, len2, lob, clp, n);
    easuTap(aC, aW, vec2( 1.0,  2.0) - f, dir, len2, lob, clp, o);

    vec3 upscaled = clamp(aC / max(aW, 1e-5), min4, max4);
    if (any(isnan(upscaled)) || any(isinf(upscaled))) {
        upscaled = fC;
    }
    fragColor = vec4(clamp(upscaled, 0.0, 1.0), 1.0);
}
