#version 450

#include "common/raindio_uniforms.glsl"

layout(set = 0, binding = 1, std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

#ifndef MULTIDRAW_TERRAIN
layout(set = 0, binding = 2, std140) uniform ChunkSection {
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
layout(location = 2) out vec3 vShadowViewPos;

void main() {
    vec3 worldRel = Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
    vec4 shadowView = shadowModelView * vec4(worldRel, 1.0);
    vShadowViewPos = shadowView.xyz;
    vTexCoord = UV0;
    vColor = Color;

    vec4 clipPos = shadowProjection * shadowView;
    // Raindio shadow map distortion matching gbuffers_terrain / lib/ssr / lib/volumetric
    float l = length(clipPos.xy);
    if (l > 0.00001) {
        clipPos.xy /= (l * 0.8 + 0.2);
    }
    gl_Position = clipPos;
}
