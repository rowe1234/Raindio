#version 120
varying vec2 texCoord;

uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int targetW = int(viewWidth) / 4;
    int targetH = int(viewHeight) / 4;

    if (pixel.x >= targetW || pixel.y >= targetH) discard;

    // Log-average luminance (perceptual, avoids sky dominating)
    float logSum = 0.0;
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            ivec2 samplePos = pixel * 4 + ivec2(i, j);
            vec2 uv = (vec2(samplePos) + 0.5) / vec2(viewWidth, viewHeight);
            vec3 hdr = texture2D(colortex0, uv).rgb;
            float lum = dot(hdr, vec3(0.299, 0.587, 0.114));
            logSum += log(max(lum, 0.0001) + 1.0);
        }
    }
    float avgLog = logSum / 16.0;
    float lum = exp(avgLog) - 1.0;
    lum = max(lum, 0.0001);

    gl_FragData[5] = vec4(lum, lum, lum, 1.0);
}
