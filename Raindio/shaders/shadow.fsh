#version 330 compatibility

uniform sampler2D tex;

in vec2 texcoord;
in vec4 color;

void main() {
    // 处理树叶、草等植物的透明镂空阴影，防止整块方形阴影
    vec4 albedo = texture(tex, texcoord) * color;
    if (albedo.a < 0.1) {
        discard;
    }
}