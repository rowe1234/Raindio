#version 450

#include "common/raindio_uniforms.glsl"

layout(set = 0, binding = 1) uniform sampler2D Sampler0;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec3 vShadowViewPos;

layout(location = 0) out vec4 shadowColor0;

void main() {
    vec4 tex = texture(Sampler0, vTexCoord);
    if (tex.a < 0.1) {
        discard;
    }

    vec3 dpdx = dFdx(vShadowViewPos);
    vec3 dpdy = dFdy(vShadowViewPos);
    float dxLen = length(dpdx);
    float dyLen = length(dpdy);
    vec3 rawN = (dxLen > 1e-12 && dyLen > 1e-12)
        ? cross(dpdx / dxLen, dpdy / dyLen)
        : vec3(0.0, 0.0, 1.0);
    float nLen = length(rawN);
    float shadowNdotL = (nLen > 1e-5) ? clamp(abs(rawN.z) / nLen, 0.0, 1.0) : 0.7;

    // Strip vanilla directional shading & vertex AO from neutral blocks so RSM bounce albedo is physically uniform
    vec3 vc = vColor.rgb;
    float vcMax = max(vc.r, max(vc.g, vc.b));
    float vcMin = min(vc.r, min(vc.g, vc.b));
    if (vcMax - vcMin < 0.03) {
        vc = vec3(1.0);
    } else if (vcMax > 0.01) {
        vc = clamp(vc / max(vcMax, 0.55), vec3(0.0), vec3(1.0));
    }

    shadowColor0 = vec4(tex.rgb * vc, shadowNdotL);
}
