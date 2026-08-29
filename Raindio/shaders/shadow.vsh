#version 330 compatibility

attribute vec4 mc_Entity; // 接收方块/实体 ID

out vec2 texcoord;
out vec4 color;
flat out float blockId; // 向片元着色器传递 Block ID

void main() {
    // 顶点基础变换
    gl_Position = ftransform();

    // 传递采样坐标与顶点颜色
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    color = gl_Color;

    // 传递实体 ID
    float id = mc_Entity.x;
    blockId = (id > 0.5) ? id : 0.0;

    // 阴影贴图扭曲（Distortion）压缩算法
    float l = length(gl_Position.xy);
    if (l > 0.00001) {
        float distortFactor = l * 0.8 + 0.2;
        gl_Position.xy /= distortFactor;
    }
}