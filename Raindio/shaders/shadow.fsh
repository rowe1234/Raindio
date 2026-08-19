#version 120

varying vec4 vTexCoord;
varying vec4 vColor;

uniform sampler2D texture;

void main() {
    vec4 albedo = texture2D(texture, vTexCoord.st) * vColor;
    if (albedo.a < 0.1) {
        discard;
    }
}