#version 450

#include "common/raindio_uniforms.glsl"

layout(location = 0) in vec3 vShadowViewPos;

layout(location = 0) out vec4 shadowColor0;

void main() {
    vec3 dpdx = dFdx(vShadowViewPos);
    vec3 dpdy = dFdy(vShadowViewPos);
    float dxLen = length(dpdx);
    float dyLen = length(dpdy);
    vec3 rawN = (dxLen > 1e-12 && dyLen > 1e-12)
        ? cross(dpdx / dxLen, dpdy / dyLen)
        : vec3(0.0, 0.0, 1.0);
    float nLen = length(rawN);
    float shadowNdotL = (nLen > 1e-5) ? clamp(abs(rawN.z) / nLen, 0.0, 1.0) : 0.7;

    // Neutral dark bounce albedo for entities in RSM shadowcolor0
    shadowColor0 = vec4(0.32, 0.32, 0.32, shadowNdotL);
}
