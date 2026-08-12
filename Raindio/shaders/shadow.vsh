#version 120

varying vec4 vTexCoord;
varying vec4 vColor;

void main() {
    gl_Position = ftransform();
    float distortFactor = length(gl_Position.xy) * 0.8 + 0.2;
    gl_Position.xy /= distortFactor;

    vTexCoord = gl_MultiTexCoord0;
    vColor = gl_Color;
}
