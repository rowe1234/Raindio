#version 120

varying vec4 vTexCoord;
varying vec4 vColor;

void main() {
    gl_Position = ftransform();

    // 显式执行透视除法转换到 NDC 空间，保证与片元着色器中的畸变逻辑完全一致
    vec3 ndc = gl_Position.xyz / gl_Position.w;

    // 阴影空间近景扭曲压缩：将 80% 的阴影分辨率密度集中在玩家视野中心
    float distortFactor = length(ndc.xy) * 0.8 + 0.2;
    ndc.xy /= distortFactor;

    // 乘回 w 分量还原为标准的裁剪空间坐标，供硬件光栅化管线使用
    gl_Position.xy = ndc.xy * gl_Position.w;

    vTexCoord = gl_MultiTexCoord0;
    vColor = gl_Color;
}