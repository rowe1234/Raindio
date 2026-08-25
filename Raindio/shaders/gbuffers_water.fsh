#version 330 compatibility
/* DRAWBUFFERS:1 */

in vec4 vTexCoord;
in vec3 vNormal;
in vec4 vColor;

layout(location = 0) out vec4 fragData0;

void main() {
    vec3 N = normalize(vNormal);
    fragData0 = vec4(N * 0.5 + 0.5, 0.85);
}