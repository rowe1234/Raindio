#version 330 compatibility

out vec2 texcoord;
out vec4 color;

void main() {
    // 顶点基础变换
    gl_Position = ftransform();

    // 传递采样坐标与顶点颜色（兼容 Sodium / Iris 显式声明）
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    color = gl_Color;

    // 阴影贴图扭曲（Distortion）压缩算法
    float l = length(gl_Position.xy);
    if (l > 0.00001) {
        float distortFactor = l * 0.8 + 0.2;
        gl_Position.xy /= distortFactor;
    }
}
