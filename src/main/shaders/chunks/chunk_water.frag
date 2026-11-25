#version 450

#include "common/raindio_uniforms.glsl"
#include "lib/common.glsl"

layout(set = 0, binding = 1) uniform sampler2D Sampler0;
layout(set = 0, binding = 2) uniform sampler2D Sampler2;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vLightmap;
layout(location = 3) in vec3 vWorldRel;

layout(location = 0) out vec4 fragData1; // colortex1: oct(worldN), blockFlag, skyLight
layout(location = 1) out vec4 fragData2; // colortex2: water biome tint / translucent albedo

void main() {
    vec4 tex = texture(Sampler0, vTexCoord);
    vec4 albedo = tex * vColor;
    if (albedo.a < 0.01) {
        discard;
    }

    // Normalize tangent derivatives before cross() so close-up water/glass faces never underflow
    vec3 dpdx = dFdx(vWorldRel);
    vec3 dpdy = dFdy(vWorldRel);
    float dxLen = length(dpdx);
    float dyLen = length(dpdy);
    vec3 worldN = (dxLen > 1e-12 && dyLen > 1e-12)
        ? cross(dpdx / dxLen, dpdy / dyLen)
        : vec3(0.0, 1.0, 0.0);
    float nLen = length(worldN);
    worldN = (nLen > 1e-6) ? (worldN / nLen) : vec3(0.0, 1.0, 0.0);
    if (dot(worldN, -vWorldRel) < 0.0) {
        worldN = -worldN;
    }

    // In Minecraft's TRANSLUCENT chunk layer, water always carries biome vertex tint
    // (non-neutral RGB) while glass/ice/slime/honey/portal use neutral white/gray vertex color.
    bool likelyWater = (vColor.b > vColor.r * 1.04) || (vColor.g > vColor.r * 1.04 && vColor.b > 0.25);
    if (likelyWater) {
        fragData1 = vec4(octEncode(worldN), float(MAT_WATER_STILL) / 255.0, vLightmap.y);
        fragData2 = vec4(vColor.rgb, 1.0);
    } else {
        fragData1 = vec4(octEncode(worldN), float(MAT_OTHER_TRANSLUCENT) / 255.0, vLightmap.y);
        fragData2 = vec4(pow(max(albedo.rgb, vec3(0.0)), vec3(1.5)), albedo.a);
    }
}
