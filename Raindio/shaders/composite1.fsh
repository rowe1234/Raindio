#version 120
/* DRAWBUFFERS:5 */

varying vec2 texCoord;

uniform sampler2D colortex0;

void main() {
    float logSum = 0.0;
    
    // 全屏 4x4 均匀采样计算 Log 平均亮度
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            vec2 sampleUV = vec2((float(i) + 0.5) / 4.0, (float(j) + 0.5) / 4.0);
            vec3 hdr = texture2D(colortex0, sampleUV).rgb;
            float lum = dot(hdr, vec3(0.299, 0.587, 0.114));
            
            // 截断极端高光，防止天空/太阳拉爆整体测光
            lum = clamp(lum, 0.0001, 8.0);
            logSum += log(lum + 1.0);
        }
    }
    
    float avgLog = logSum / 16.0;
    float lum = exp(avgLog) - 1.0;
    lum = max(lum, 0.0001);

    // 全屏写入干净的当前帧测光结果
    gl_FragData[0] = vec4(lum, lum, lum, 1.0);
}