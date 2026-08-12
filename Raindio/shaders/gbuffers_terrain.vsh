#version 120

varying vec3 vNormal;
varying vec4 vColor;
varying vec4 vTexCoord;
varying vec3 vEyePos;
varying vec2 vLightmap;
varying float vBlockId;

attribute vec4 mc_Entity;

void main() {
    gl_Position = ftransform();
    vTexCoord = gl_MultiTexCoord0;
    vColor = gl_Color;
    
    // 正确计算 Eye Space 法线与眼睛相对坐标
    vNormal = normalize(gl_NormalMatrix * gl_Normal);
    vEyePos = (gl_ModelViewMatrix * gl_Vertex).xyz;
    vLightmap = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    vBlockId = mc_Entity.x;
}
