#version 450

#include "common/raindio_uniforms.glsl"
#include "lib/lighting.glsl"

layout(set = 0, binding = 1, std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

layout(set = 0, binding = 2, std140) uniform TerrainUniform {
    mat4 ModelViewMat;
    ivec2 TextureSize;
};

#ifndef MULTIDRAW_TERRAIN
layout(set = 0, binding = 3, std140) uniform ChunkSection {
    ivec3 ChunkPosition;
    float ChunkVisibility;
};
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec2 vLightmap;
layout(location = 3) out vec3 vWorldRel;
layout(location = 4) out vec3 vViewPos;
layout(location = 5) out vec3 vSkyColor;
layout(location = 6) out vec3 vSunlight;
layout(location = 7) out vec4 vParams; // x: dayFactor, y: sunIntensity, z: emissive

void main() {
    vec3 worldRel = Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
    vec4 viewPos = ModelViewMat * vec4(worldRel, 1.0);
    gl_Position = gbufferProjection * viewPos;
    gl_Position.xy += taaJitter * gl_Position.w;

    vWorldRel = worldRel;
    vViewPos = viewPos.xyz;
    vTexCoord = UV0;
    vColor = Color;
    vLightmap = clamp(vec2(UV2) / 240.0, vec2(0.0), vec2(1.0));

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float dayFactor, sunIntensity;
    computeUnifiedLighting(worldSunDir, vSkyColor, vSunlight, dayFactor, sunIntensity);

    float emissive = smoothstep(0.965, 0.995, vLightmap.x) * 0.55;
    vParams = vec4(dayFactor, sunIntensity, emissive, 0.0);
}
