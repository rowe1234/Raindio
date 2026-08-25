#version 410 core
/* DRAWBUFFERS:5 */

in vec2 texCoord;

uniform sampler2D colortex0;

layout(location = 0) out vec4 fragData0;

void main() {
    float logSum = 0.0;

    // 全屏 4x4 均匀采样计算 Log 平均亮度 (优化常量步长)
    for (float x = 0.125; x < 1.0; x += 0.25) {
        for (float y = 0.125; y < 1.0; y += 0.25) {
            vec3 hdr = texture(colortex0, vec2(x, y)).rgb;
            float lum = dot(hdr, vec3(0.299, 0.587, 0.114));

            lum = clamp(lum, 0.0001, 8.0);
            logSum += log(lum + 1.0);
        }
    }

    float avgLog = logSum * 0.0625;
    float lum = exp(avgLog) - 1.0;
    lum = max(lum, 0.0001);

    fragData0 = vec4(lum, lum, lum, 1.0);
}