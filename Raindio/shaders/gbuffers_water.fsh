#version 120
/* DRAWBUFFERS:1 */

varying vec4 vTexCoord;
varying vec3 vNormal;
varying float vIsWater;
varying vec4 vColor;

uniform sampler2D texture;

void main() {
    // Discard non-water fragments (vIsWater = 0.95 for non-water)
    if (vIsWater > 0.8) discard;

    vec3 N = normalize(vNormal);
    // Alpha = 0.79 signals water to composite3
    gl_FragData[0] = vec4(N * 0.5 + 0.5, 0.79);
}
