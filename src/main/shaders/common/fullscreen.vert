#version 450

// Shared fullscreen triangle vertex shader for composite0..5 and final passes.
// Emits 3 vertices without a vertex buffer (gl_VertexIndex = 0, 1, 2).
// Vulkan clip space: Y down, UV (0, 0) at top-left, (1, 1) at bottom-right.
layout(location = 0) out vec2 texCoord;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    texCoord = uv;
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
