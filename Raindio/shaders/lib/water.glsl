// ============================================================================
// Water surface: Gerstner-style wave height + normal + POM displacement.
// Requires `uniform sampler2D noisetex;` and `uniform float frameTimeCounter;`
// declared by the including shader.
// ============================================================================

#define SEA_HEIGHT 0.45
#define SEA_CHOPPY 25.5
#define SEA_SPEED 0.45
#define SEA_FREQ 0.45

float noise(vec2 p) {
    return texture(noisetex, p * 0.00390625).r * 2.0 - 1.0;
}

float sea_octave_micro(vec2 uv, float choppy) {
    uv += noise(uv);
    vec2 wv = 1.0 - abs(sin(uv));
    vec2 swv = abs(cos(uv));
    wv = mix(wv, swv, wv);
    float baseVal = max(1.0 - pow(max(wv.x * wv.y, 0.0), 0.75), 0.0);
    return pow(baseVal, choppy);
}

const mat2 octave_m = mat2(1.4, 1.1, -1.2, 1.4);
const float height_mul[4] = float[4](0.32, 0.24, 0.20, 0.22);
const float rcp_total_height = 2.37898;

float getwave2(vec3 p, float lod) {
    float freq = SEA_FREQ;
    float amp = SEA_HEIGHT;
    float choppy = SEA_CHOPPY;
    vec2 uv = p.xz - vec2(frameTimeCounter * 0.5, 0.0);
    uv.x *= 0.75;
    float wave_speed = frameTimeCounter * SEA_SPEED;
    float h = 0.0;
    for (int i = 0; i < 4; i++) {
        float d = sea_octave_micro((uv + wave_speed) * freq, choppy);
        h += d * amp;
        uv *= octave_m;
        freq *= 1.9;
        amp *= height_mul[i];
        wave_speed *= -1.1;
        choppy = mix(choppy, 1.0, 0.2);
    }
    return h * rcp_total_height * lod;
}

vec3 get_water_normal(vec3 wwpos, float lod, bool underwater) {
    const float eps = 0.05;
    float h0 = getwave2(wwpos, lod);
    float hx = getwave2(wwpos + vec3(eps, 0.0, 0.0), lod);
    float hz = getwave2(wwpos + vec3(0.0, 0.0, eps), lod);

    vec2 slope = vec2(h0 - hx, h0 - hz) / eps;
    float bumpStrength = 0.25;

    float dirY = underwater ? -1.0 : 1.0;
    return normalize(vec3(slope.x * bumpStrength, dirY, slope.y * bumpStrength));
}

vec3 raymarch_water_pom(vec3 origWorldPos, vec3 worldRayDir, float lod) {
    vec3 rayDir = worldRayDir;
    if (rayDir.y > -0.001) rayDir.y = -0.001;

    float stepScale = 0.05;
    vec3 p = origWorldPos;
    vec3 stepVec = rayDir * (stepScale / abs(rayDir.y));

    vec3 prevP = p;
    float prevH = p.y - (origWorldPos.y + getwave2(p, lod));

    for (int i = 0; i < 12; i++) {
        p += stepVec;
        float waveH = getwave2(p, lod);
        float currH = p.y - (origWorldPos.y + waveH);

        if (currH < 0.0) {
            float t = prevH / (prevH - currH);
            p = mix(prevP, p, clamp(t, 0.0, 1.0));
            break;
        }
        prevP = p;
        prevH = currH;
    }
    return p;
}
