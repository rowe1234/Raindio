#version 120

varying vec4 vTexCoord;
uniform sampler2D texture;

void main() {
    // Depth written automatically by rasterization
    // Alpha-tested discard removed — all geometry casts shadows
}
