#version 330 compatibility

out vec4 vTexCoord;
out vec3 vNormal;
out vec4 vColor;

void main() {
    gl_Position = ftransform();
    vTexCoord = gl_MultiTexCoord0;
    vNormal = normalize(gl_NormalMatrix * gl_Normal);
    vColor = gl_Color;
}