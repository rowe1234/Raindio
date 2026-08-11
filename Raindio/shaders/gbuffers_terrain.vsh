#version 120

attribute vec4 mc_Entity;

varying vec3 vNormal;
varying vec4 vColor;
varying vec4 vTexCoord;
varying vec3 vEyePos;
varying vec2 vLightmap;
varying float vBlockId;

void main() {
    gl_Position = ftransform();

    vTexCoord = gl_MultiTexCoord0;
    vNormal = normalize(gl_NormalMatrix * gl_Normal);
    vColor = gl_Color;
    vEyePos = (gl_ModelViewMatrix * gl_Vertex).xyz;
    vLightmap = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;

    vBlockId = mc_Entity.x;
}