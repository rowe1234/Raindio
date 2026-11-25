#version 450

// Blits vanilla mainRenderTarget (e.g. RGBA8_UNORM) into Raindio colortex0 (RGBA16_FLOAT)
layout(set = 0, binding = 0) uniform sampler2D uMainColor;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(uMainColor, texCoord);
}
