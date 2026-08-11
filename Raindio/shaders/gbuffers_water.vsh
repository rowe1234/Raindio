#version 120

varying vec4 vTexCoord;
varying vec3 vNormal;

void main() {
    gl_Position = ftransform();
    vTexCoord = gl_MultiTexCoord0;
    vNormal   = normalize(gl_NormalMatrix * gl_Normal);
}