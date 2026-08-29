#version 330 compatibility

attribute vec4 mc_Entity;

out vec4 vTexCoord;
out vec3 vNormal;
out vec4 vColor;
out vec2 vLightmap; // 传递光照坐标
flat out float blockId;

void main() {
    gl_Position = ftransform();
    vTexCoord = gl_MultiTexCoord0;
    vLightmap = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy; // 获取光照图坐标
    vNormal = normalize(gl_NormalMatrix * gl_Normal);
    vColor = gl_Color;
    
    float id = mc_Entity.x;
    blockId = (id > 0.5) ? id : 2.0;
}