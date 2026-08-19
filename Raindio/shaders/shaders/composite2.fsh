#version 120
varying vec2 texCoord;

uniform sampler2D colortex5;

void main() {
    vec3 avg = vec3(0.0);
    for (float x = 0.125; x < 1.0; x += 0.25) {
        for (float y = 0.125; y < 1.0; y += 0.25) {
            avg += texture2D(colortex5, vec2(x, y)).rgb;
        }
    }

    avg /= 16.0;
    float lum = max(avg.r, 0.0001);
    gl_FragData[6] = vec4(lum, lum, lum, 1.0);
}