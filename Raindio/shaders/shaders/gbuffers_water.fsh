#version 120
/* DRAWBUFFERS:1 */

varying vec4 vTexCoord;
varying vec3 vNormal;
varying vec4 vColor;

uniform sampler2D texture;

void main() {
    vec3 N = normalize(vNormal);
    
    // 写入法线，Alpha = 0.85 统一作为半透明材质信号传给 composite3
    gl_FragData[0] = vec4(N * 0.5 + 0.5, 0.85);
}