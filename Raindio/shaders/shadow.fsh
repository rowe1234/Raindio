#version 330 compatibility

uniform sampler2D tex;

in vec2 texcoord;
in vec4 color;
flat in float blockId;

void main() {
    // 剔除水体方块（ID 1: 水，8/9: 流动水），防止水面将深度写入阴影贴图挡住阳光
    int id = int(blockId + 0.5);
    if (id == 1 || id == 8 || id == 9) {
        discard;
    }

    // 处理树叶、草等植物的透明镂空阴影，防止整块方形阴影
    vec4 albedo = texture(tex, texcoord) * color;
    if (albedo.a < 0.1) {
        discard;
    }
}