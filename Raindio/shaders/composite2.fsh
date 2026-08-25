#version 410 core

in vec2 texCoord;

uniform sampler2D colortex5;

layout(location = 6) out vec4 fragData6;

void main() {
    float avg = 0.0;
    // 仅读取 .r 单通道，降低带宽占用
    for (float x = 0.125; x < 1.0; x += 0.25) {
        for (float y = 0.125; y < 1.0; y += 0.25) {
            avg += texture(colortex5, vec2(x, y)).r;
        }
    }

    avg *= 0.0625;
    float lum = max(avg, 0.0001);
    fragData6 = vec4(lum, lum, lum, 1.0);
}