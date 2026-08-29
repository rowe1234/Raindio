#version 330 compatibility

/* DRAWBUFFERS:012 */

uniform sampler2D gtexture;

in vec4 vTexCoord;
in vec3 vNormal;
in vec4 vColor;
in vec2 vLightmap; // 接收光照坐标
flat in float blockId;

layout(location = 0) out vec4 fragData0; // colortex0
layout(location = 1) out vec4 fragData1; // colortex1 (法线, ID, 天空光照度)
layout(location = 2) out vec4 fragData2; // colortex2 (玻璃固有色)

void main() {
    vec3 N = normalize(vNormal);
    int id = int(blockId + 0.5);

   // 修改前：float blockFlag = (id == 1) ? (1.0 / 255.0) : (2.0 / 255.0);
bool isWaterBlock = (id == 1 || id == 8 || id == 9);
float blockFlag = isWaterBlock ? (1.0 / 255.0) : (2.0 / 255.0);

if (isWaterBlock) {
    fragData0 = vec4(0.0);
    fragData1 = vec4(N.xy * 0.5 + 0.5, blockFlag, vLightmap.y);
    fragData2 = vec4(1.0);
} else {
    vec4 albedo = texture(gtexture, vTexCoord.xy) * vColor;
    if (albedo.a < 0.02) discard;

    fragData0 = albedo;
    fragData1 = vec4(N.xy * 0.5 + 0.5, blockFlag, vLightmap.y);
    fragData2 = vec4(albedo.rgb, 1.0);
}
}