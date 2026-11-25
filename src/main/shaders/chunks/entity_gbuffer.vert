#version 450

#include "common/raindio_uniforms.glsl"
#include "lib/lighting.glsl"

layout(location = 0) in vec3 Position;    // Camera-relative world position (worldRel)
layout(location = 1) in vec3 Normal;      // World-space vertex normal
layout(location = 2) in vec2 UV0;         // Entity / block-entity texture UV
layout(location = 3) in vec4 Color;       // Vertex tint * hurt overlay color
layout(location = 4) in vec2 UV2;         // Normalized (blockLight, skyLight) in [0, 1]

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec2 vLightmap;
layout(location = 3) out vec3 vWorldRel;
layout(location = 4) out vec3 vWorldNormal;
layout(location = 5) out vec3 vSkyColor;
layout(location = 6) out vec3 vSunlight;
layout(location = 7) out vec4 vParams; // x: dayFactor, y: sunIntensity, z: emissive

void main() {
    vec4 viewPos = gbufferModelView * vec4(Position, 1.0);
    gl_Position = gbufferProjection * viewPos;
    if (taaEnabledUniform != 0) {
        gl_Position.xy += taaJitter * gl_Position.w;
    }

    vWorldRel = Position;
    vWorldNormal = Normal;
    vTexCoord = UV0;
    vColor = Color;
    vLightmap = clamp(UV2, vec2(0.0), vec2(1.0));

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float dayFactor, sunIntensity;
    computeUnifiedLighting(worldSunDir, vSkyColor, vSunlight, dayFactor, sunIntensity);

    float emissive = smoothstep(0.965, 0.995, vLightmap.x) * 0.55;
    vParams = vec4(dayFactor, sunIntensity, emissive, 0.0);
}
