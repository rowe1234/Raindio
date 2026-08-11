#version 120
varying vec2 texCoord;

uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;

// ---- Bloom Parameters ----
const float THRESHOLD  = 3.0;
const float KNEE       = 0.5;

// Gaussian weights
const float GW0 = 0.399;
const float GW1 = 0.242;
const float GW2 = 0.054;
const float GW3 = 0.004;

float luminance(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec3 sampleBright(sampler2D tex, vec2 uv, float weight) {
    vec3 hdr = texture2D(tex, uv).rgb;
    float lum = luminance(hdr);
    float knee = smoothstep(THRESHOLD - KNEE, THRESHOLD + KNEE, lum);
    return hdr * knee * weight;
}

void main() {
    float pw = 1.0 / viewWidth;
    vec2 uv = texCoord;

    vec3 centerHDR = texture2D(colortex0, uv).rgb;

    // ---- Bloom (1D Gaussian, 2 mip levels) ----
    vec3 lev0 = vec3(0.0);
    vec3 lev1_wide = vec3(0.0);

    float centerLum = luminance(centerHDR);
    float centerKnee = smoothstep(THRESHOLD - KNEE, THRESHOLD + KNEE, centerLum);
    vec3 centerBright = centerHDR * centerKnee;

    lev0      += centerBright * GW0;
    lev1_wide += centerBright * GW0;

    // lev0: ±1, ±2, ±3
    lev0 += sampleBright(colortex0, uv + vec2( pw, 0.0), GW1);
    lev0 += sampleBright(colortex0, uv + vec2(-pw, 0.0), GW1);

    lev0 += sampleBright(colortex0, uv + vec2( 2.0*pw, 0.0), GW2);
    lev1_wide += sampleBright(colortex0, uv + vec2( 2.0*pw, 0.0), GW1);
    lev0 += sampleBright(colortex0, uv + vec2(-2.0*pw, 0.0), GW2);
    lev1_wide += sampleBright(colortex0, uv + vec2(-2.0*pw, 0.0), GW1);

    lev0 += sampleBright(colortex0, uv + vec2( 3.0*pw, 0.0), GW3);
    lev0 += sampleBright(colortex0, uv + vec2(-3.0*pw, 0.0), GW3);

    // lev1_wide: ±4, ±6, ±8, ±12, ±16, ±24 (merged wide bloom)
    lev1_wide += sampleBright(colortex0, uv + vec2( 4.0*pw, 0.0), GW2);
    lev1_wide += sampleBright(colortex0, uv + vec2(-4.0*pw, 0.0), GW2);

    lev1_wide += sampleBright(colortex0, uv + vec2( 6.0*pw, 0.0), GW3);
    lev1_wide += sampleBright(colortex0, uv + vec2(-6.0*pw, 0.0), GW3);

    lev1_wide += sampleBright(colortex0, uv + vec2( 8.0*pw, 0.0), GW2);
    lev1_wide += sampleBright(colortex0, uv + vec2(-8.0*pw, 0.0), GW2);

    lev1_wide += sampleBright(colortex0, uv + vec2( 12.0*pw, 0.0), GW3);
    lev1_wide += sampleBright(colortex0, uv + vec2(-12.0*pw, 0.0), GW3);

    lev1_wide += sampleBright(colortex0, uv + vec2( 16.0*pw, 0.0), GW2);
    lev1_wide += sampleBright(colortex0, uv + vec2(-16.0*pw, 0.0), GW2);

    lev1_wide += sampleBright(colortex0, uv + vec2( 24.0*pw, 0.0), GW3);
    lev1_wide += sampleBright(colortex0, uv + vec2(-24.0*pw, 0.0), GW3);

    gl_FragData[0] = vec4(centerHDR, 1.0);          // scene HDR
    gl_FragData[3] = vec4(lev0, 1.0);                // bloom tight
    gl_FragData[4] = vec4(lev1_wide, 1.0);           // bloom wide
}
