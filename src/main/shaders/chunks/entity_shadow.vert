#version 450

#include "common/raindio_uniforms.glsl"

layout(location = 0) in vec3 Position;

layout(location = 0) out vec3 vShadowViewPos;

void main() {
    // Position is already in camera-relative world space (entityPos - cameraPos + modelLocalPos)
    vec4 shadowView = shadowModelView * vec4(Position, 1.0);
    vShadowViewPos = shadowView.xyz;

    vec4 clipPos = shadowProjection * shadowView;
    float l = length(clipPos.xy);
    if (l > 0.00001) {
        clipPos.xy /= (l * 0.8 + 0.2);
    }
    gl_Position = clipPos;
}
