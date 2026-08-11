#version 120

varying vec4 vTexCoord;

const float shadowMapBias = 1.0 - 25.6 / 256.0;  // BSL formula: 1.0 - 25.6/shadowDistance

void main() {
    gl_Position = ftransform();

    float dist = sqrt(gl_Position.x * gl_Position.x + gl_Position.y * gl_Position.y);
    float distortFactor = dist * shadowMapBias + (1.0 - shadowMapBias);
    gl_Position.xy *= 1.0 / distortFactor;
    gl_Position.z *= 0.2;  // shadowZScale

    vTexCoord = gl_MultiTexCoord0;
}
