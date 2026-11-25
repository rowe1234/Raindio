#version 450

#include "common/raindio_uniforms.glsl"
#include "lib/lighting.glsl"

layout(std140, set = 0, binding = 1) uniform HandUniforms {
    mat4 handProjection;
    vec4 handEmissiveColorAndStrength; // rgb: held light color, a: held emissive strength [0..1]
    vec4 handPtDirAndStr;              // xyz: dominant RT point-light dir, w: active (0/1)
    vec4 handPtColor;                  // rgb: dominant RT point-light radiance
    vec4 handPtAmb;                    // rgb: omni RT point-light fill at hand
};

layout(location = 0) in vec3 Position;    // World-aligned camera-relative position (from V^-1 * handPose)
layout(location = 1) in vec3 Normal;      // World-space vertex normal
layout(location = 2) in vec2 UV0;         // Skin / block / item texture UV
layout(location = 3) in vec4 Color;       // Vertex tint * hurt overlay color
layout(location = 4) in vec2 UV2;         // Normalized (blockLight, skyLight) in [0, 1]

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec2 vLightmap;
layout(location = 3) out vec3 vWorldRel;
layout(location = 4) out vec3 vWorldNormal;
layout(location = 5) out vec3 vSkyColor;
layout(location = 6) out vec3 vSunlight;
layout(location = 7) out vec4 vParams; // x: dayFactor, y: sunIntensity, z: heldEmissiveStrength, w: unused

void main() {
    // Transform world-aligned hand position into camera view space, then project with hudProjection (hudFov)
    vec4 viewPos = gbufferModelView * vec4(Position, 1.0);
    gl_Position = handProjection * viewPos;

    vWorldRel = Position;
    vWorldNormal = Normal;
    vTexCoord = UV0;
    vColor = Color;
    vLightmap = clamp(UV2, vec2(0.0), vec2(1.0));

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float dayFactor, sunIntensity;
    computeUnifiedLighting(worldSunDir, vSkyColor, vSunlight, dayFactor, sunIntensity);

    vParams = vec4(dayFactor, sunIntensity, handEmissiveColorAndStrength.a, 0.0);
}
