#version 120
varying vec2 texCoord;

uniform sampler2D colortex5;
uniform float viewWidth;
uniform float viewHeight;

void main() {
    // NO discard — fill entire colortex6 so linear interpolation can't dilute the value

    int srcW = int(viewWidth) / 4;
    int srcH = int(viewHeight) / 4;

    vec3 avg = vec3(0.0);
    float count = 0.0;
    int stepSize = 4;

    for (int i = 0; i < srcW; i += stepSize) {
        for (int j = 0; j < srcH; j += stepSize) {
            vec2 uv = (vec2(float(i), float(j)) + 0.5) / vec2(viewWidth, viewHeight);
            avg += texture2D(colortex5, uv).rgb;
            count += 1.0;
        }
    }

    avg /= max(count, 1.0);
    float lum = max(avg.r, 0.0001);

    // Fill entire colortex6 — every pixel gets the same global luminance
    gl_FragData[6] = vec4(lum, lum, lum, 1.0);
}
