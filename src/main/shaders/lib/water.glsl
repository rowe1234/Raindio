// ============================================================================
// SEUS PTGI-Style Water Surface System for Raindio:
//   - C2-continuous analytical quintic noise + gentle Gerstner swell waves
//   - Single-pass analytical wave height + exact gradient (zero FD stepping)
//   - 3-step iterative Water Parallax Occlusion offset in world XZ (~0.25m depth)
//   - Flowing river directional advection, bubble columns & vertical waterfalls
//   - Analytical concentric raindrop ripples & seamless wet-ground puddle mask
//   - Wave-coherent chromatic underwater caustics (organic light filaments)
// Requires /lib/common.glsl included first.
// Requires `uniform sampler2D noisetex;` and `uniform float frameTimeCounter;`.
// ============================================================================

#define WATER_WAVE_STRENGTH 1.0  // [0.5 0.75 1.0 1.25 1.5]
#define WATER_DISPERSION 1.0     // [0.0 0.5 1.0 1.5 2.0]
#define WATER_PARALLAX           // Toggle 3D water wave parallax offset
#define SEA_SPEED 0.8

float water_time() {
    return mod(frameTimeCounter, 3600.0);
}

// ============================================================================
// Seamless Hash & C2-Continuous Analytical 2D Value Noise (value + gradient)
// ============================================================================
float ptgi_hash21(vec2 p) {
    p = mod(p, 289.0);
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22_water(vec2 p) {
    p = mod(p, 289.0);
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// Returns vec3(value in [0,1], dval/dx, dval/dy) with exact C2 quintic derivatives
vec3 ptgi_noise_grad(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);

    vec2 u  = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    vec2 du = 30.0 * f * f * (f * (f - 2.0) + 1.0);

    float a = ptgi_hash21(i + vec2(0.0, 0.0));
    float b = ptgi_hash21(i + vec2(1.0, 0.0));
    float c = ptgi_hash21(i + vec2(0.0, 1.0));
    float d = ptgi_hash21(i + vec2(1.0, 1.0));

    float k0 = a;
    float k1 = b - a;
    float k2 = c - a;
    float k3 = a - b - c + d;

    float val = k0 + k1 * u.x + k2 * u.y + k3 * u.x * u.y;
    vec2 grad = du * vec2(k1 + k3 * u.y, k2 + k3 * u.x);
    return vec3(val, grad);
}

// Smoothly rounded PTGI liquid ripple ridge from C2 noise
vec3 ptgi_ripple_octave(vec2 p, float softness) {
    vec3 n = ptgi_noise_grad(p);
    float e = n.x - 0.5;
    float r = sqrt(e * e + softness);
    float h = 1.0 - r * 1.45;
    float dh_dn = (-e / r) * 1.45;
    return vec3(h, n.yz * dh_dn);
}

const mat2 PTGI_ROT1 = mat2( 0.80, -0.60,  0.60,  0.80);
const mat2 PTGI_ROT2 = mat2(-0.54, -0.84,  0.84, -0.54);
const mat2 PTGI_ROT3 = mat2( 0.92,  0.39, -0.39,  0.92);
const mat2 PTGI_ROT4 = mat2(-0.73,  0.68, -0.68, -0.73);

// ============================================================================
// PTGI Multi-Octave Liquid Wave Height + Exact Analytical World XZ Gradient
// ============================================================================
vec3 get_ptgi_wave_grad(vec2 xz, float viewDist, float rainAmt, bool isFlowing, bool isBubbleCol) {
    float t = water_time() * SEA_SPEED;
    float flowBoost = isFlowing ? 1.75 : (isBubbleCol ? 2.15 : 1.0);
    float rainBoost = mix(1.0, 1.22, saturate(rainAmt));
    float tFlow = t * flowBoost;

    vec2 p = isFlowing ? vec2(xz.x * 1.22, xz.y * 0.88) : xz;

    float h = 0.0;
    vec2 g = vec2(0.0);

    // 1. Gentle directional wind swells
    {
        vec2 k1 = vec2(0.46, 0.21);
        float ph1 = fract((dot(p, k1) - tFlow * 0.88) / (2.0 * PI)) * (2.0 * PI);
        float s1 = sin(ph1);
        float c1 = cos(ph1);
        float amp1 = 0.20 * rainBoost;
        h += (0.5 + 0.5 * s1) * amp1;
        g += k1 * (0.5 * c1 * amp1);

        vec2 k2 = vec2(-0.28, 0.55);
        float ph2 = fract((dot(p, k2) - tFlow * 1.12) / (2.0 * PI)) * (2.0 * PI);
        float s2 = sin(ph2);
        float c2 = cos(ph2);
        float amp2 = 0.14 * rainBoost;
        h += (0.5 + 0.5 * s2) * amp2;
        g += k2 * (0.5 * c2 * amp2);
    }

    // 2. Primary silky liquid ripples (wavelength ~2.1m)
    {
        float freq = 0.48;
        float amp = 0.34 * rainBoost;
        vec2 q = (PTGI_ROT1 * p) * freq + vec2(tFlow * 0.42, -tFlow * 0.26);
        vec3 r = ptgi_ripple_octave(q, 0.055);
        h += r.x * amp;
        g += (transpose(PTGI_ROT1) * r.yz) * (freq * amp);
    }

    // 3. Secondary counter-drifting interference ripples (wavelength ~1.05m)
    {
        float octFade = 1.0 / (1.0 + viewDist * 0.018);
        if (octFade > 0.04) {
            float freq = 0.96;
            float amp = 0.23 * rainBoost * octFade;
            vec2 q = (PTGI_ROT2 * p) * freq + vec2(-tFlow * 0.55, tFlow * 0.38);
            vec3 r = ptgi_ripple_octave(q, 0.042);
            h += r.x * amp;
            g += (transpose(PTGI_ROT2) * r.yz) * (freq * amp);
        }
    }

    // 4. Medium detail ripples (wavelength ~0.52m)
    {
        float octFade = 1.0 / (1.0 + viewDist * 0.048);
        if (octFade > 0.04) {
            float freq = 1.92;
            float amp = 0.125 * rainBoost * octFade;
            vec2 q = (PTGI_ROT3 * p) * freq + vec2(tFlow * 0.72, tFlow * 0.46);
            vec3 r = ptgi_ripple_octave(q, 0.036);
            h += r.x * amp;
            g += (transpose(PTGI_ROT3) * r.yz) * (freq * amp);
        }
    }

    // 5. Fine capillary micro-ripples (wavelength ~0.26m)
    {
        float octFade = 1.0 / (1.0 + viewDist * 0.11);
        if (octFade > 0.05) {
            float freq = 3.85;
            float amp = 0.058 * rainBoost * octFade;
            vec2 q = (PTGI_ROT4 * p) * freq + vec2(-tFlow * 0.95, -tFlow * 0.62);
            vec3 r = ptgi_ripple_octave(q, 0.030);
            h += r.x * amp;
            g += (transpose(PTGI_ROT4) * r.yz) * (freq * amp);
        }
    }

    return vec3(clamp(h, 0.0, 1.25), g);
}

// Fast 2-octave height query for Water Parallax Occlusion and volumetric caustic beams
float ptgi_wave_height_fast(vec2 xz, float rainAmt, bool isFlowing, bool isBubbleCol) {
    float t = water_time() * SEA_SPEED * (isFlowing ? 1.75 : (isBubbleCol ? 2.15 : 1.0));
    float rainBoost = mix(1.0, 1.22, saturate(rainAmt));
    vec2 p = isFlowing ? vec2(xz.x * 1.22, xz.y * 0.88) : xz;

    vec2 q1 = (PTGI_ROT1 * p) * 0.48 + vec2(t * 0.42, -t * 0.26);
    vec2 q2 = (PTGI_ROT2 * p) * 0.96 + vec2(-t * 0.55, t * 0.38);
    float h1 = ptgi_ripple_octave(q1, 0.055).x * 0.58;
    float h2 = ptgi_ripple_octave(q2, 0.042).x * 0.42;
    return clamp((h1 + h2) * rainBoost, 0.0, 1.0);
}

// 3-step iterative Water Parallax Occlusion offset in world XZ (noticeable 3D wave relief up to ~0.26m)
vec2 compute_water_parallax(vec2 worldXZ, vec3 worldViewDir, float viewDist, float rainAmt, bool isFlowing, bool isBubbleCol) {
#ifdef WATER_PARALLAX
    float distFade = 1.0 - smoothstep(18.0, 52.0, viewDist);
    float angleFade = smoothstep(0.06, 0.30, abs(worldViewDir.y));
    float strength = 0.32 * WATER_WAVE_STRENGTH * distFade * angleFade;
    if (strength < 0.004) return worldXZ;

    vec2 parallaxDir = worldViewDir.xz / max(abs(worldViewDir.y), 0.26);
    float pLen = length(parallaxDir);
    if (pLen > 2.6) parallaxDir *= (2.6 / pLen);

    vec2 p = worldXZ;
    for (int i = 0; i < 3; i++) {
        float h = ptgi_wave_height_fast(p, rainAmt, isFlowing, isBubbleCol);
        p += parallaxDir * ((h - 0.5) * strength * 0.55);
    }
    return p;
#else
    return worldXZ;
#endif
}

float getwave2(vec3 p, float lod) {
    return ptgi_wave_height_fast(p.xz, 0.0, false, false) * lod;
}

vec3 get_waterfall_wave_grad(vec3 p, float viewDist) {
    float t = water_time();
    float horiz = p.x + p.z;
    vec2 uv = vec2(horiz * 1.45, p.y * 0.52 + t * 3.6);
    float fade = 1.0 / (1.0 + viewDist * 0.03);
    vec3 w1 = ptgi_ripple_octave(uv * 1.15, 0.045);
    vec3 w2 = ptgi_ripple_octave(uv * 2.35 + vec2(3.1, t * 2.1), 0.040) * (0.45 * fade);
    return (w1 + w2) * 0.58;
}

// ============================================================================
// Analytical Concentric Raindrop Ripples (continuous height + exact gradient)
// ============================================================================
vec2 get_rain_ripple_slope(vec2 worldXZ, float rainAmt, float skyLight) {
    float strength = saturate(rainAmt) * smoothstep(0.5, 0.95, skyLight);
    if (strength < 0.005) return vec2(0.0);

    float t = water_time() * 1.85;
    vec2 p = worldXZ * 1.45;
    vec2 cell = floor(p);
    vec2 f = fract(p);

    vec2 totalSlope = vec2(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 cellId = cell + g;
            vec2 rnd = hash22_water(cellId);

            float rippleSpeed = 0.50 + 0.35 * rnd.x;
            float phase = fract(t * rippleSpeed + rnd.y);
            float cycleIdx = floor(t * rippleSpeed + rnd.y);
            vec2 rndCenter = hash22_water(cellId + vec2(cycleIdx * 13.7, cycleIdx * 7.3));

            vec2 center = g + 0.15 + 0.70 * rndCenter;
            vec2 diff = f - center;
            float dist = length(diff);

            if (dist > 0.002 && dist < 1.15) {
                float radius = phase * 0.95;
                float u = dist - radius;
                float lifeEnv = sin(phase * PI) * (1.0 - phase);

                const float k = 38.0;
                const float freq = 26.0;
                float gauss = exp(-k * u * u);
                float dhdr = gauss * (freq * cos(freq * u) - 2.0 * k * u * sin(freq * u)) * lifeEnv;

                totalSlope += (diff / dist) * (dhdr * 0.030);
            }
        }
    }

    return totalSlope * strength * 0.85;
}

float get_puddle_mask(vec2 worldXZ) {
    float n1 = texture(noisetex, fract(worldXZ * 0.018)).r;
    float n2 = texture(noisetex, fract(worldXZ * 0.065 + vec2(0.37, 0.71))).r;
    float mask = n1 * 0.65 + n2 * 0.35;
    return smoothstep(0.42, 0.68, mask);
}

// ============================================================================
// Main Water Surface Normal, Wave Height, and Toksvig Roughness
// ============================================================================
vec3 get_water_surface(
    vec3 wwpos,
    vec3 geometricWorldNormal,
    float viewDist,
    bool underwater,
    float rainAmt,
    float skyLight,
    bool isFlowing,
    bool isBubbleCol,
    out float outWaveH,
    out float outRoughness
) {
    float surfaceHoriz = saturate(abs(geometricWorldNormal.y));
    float distAA = 1.0 / (1.0 + viewDist * 0.014);

    if (surfaceHoriz < 0.35) {
        vec3 wf = get_waterfall_wave_grad(wwpos, viewDist);
        outWaveH = wf.x;

        vec3 tangentH = normalize(cross(vec3(0.0, 1.0, 0.0), geometricWorldNormal));
        float bumpW = 0.24 * WATER_WAVE_STRENGTH * distAA;
        vec3 perturbed = normalize(geometricWorldNormal + (tangentH * (-wf.y) + vec3(0.0, 1.0, 0.0) * (-wf.z)) * bumpW);
        outRoughness = clamp(0.08 + length(wf.yz) * 0.03 + saturate(rainAmt) * 0.08, 0.06, 0.32);
        return perturbed;
    }

    vec3 wave = get_ptgi_wave_grad(wwpos.xz, viewDist, rainAmt, isFlowing, isBubbleCol);
    outWaveH = wave.x;
    vec2 slope = -wave.yz;

    float rippleFade = 1.0 / (1.0 + viewDist * 0.075);
    if (!underwater && rainAmt > 0.01 && rippleFade > 0.04) {
        slope += get_rain_ripple_slope(wwpos.xz, rainAmt, skyLight) * rippleFade;
    }

    float baseBump = (underwater ? 0.34 : 0.24) * WATER_WAVE_STRENGTH;
    float bumpStrength = baseBump * distAA;

    vec3 n = normalize(vec3(slope.x * bumpStrength, 1.0, slope.y * bumpStrength));

    // Base roughness floor 0.028 (further anti-aliased per-pixel via dFdx/dFdy in composite3.fsh)
    float slopeMag = length(slope);
    float distBlur = clamp(viewDist * 0.0012, 0.0, 0.085);
    float flowRough = isFlowing ? 0.025 : (isBubbleCol ? 0.055 : 0.0);
    outRoughness = clamp(0.028 + slopeMag * 0.022 * (1.0 - distAA) + distBlur * 0.45 + saturate(rainAmt) * 0.085 + flowRough, 0.028, 0.28);

    return underwater ? -n : n;
}

float get_foam_noise(vec2 worldXZ) {
    float t = water_time();
    float n1 = texture(noisetex, fract(worldXZ * 0.09 + vec2(t * 0.012, -t * 0.008))).r;
    float n2 = texture(noisetex, fract(worldXZ * 0.27 - vec2(t * 0.018, t * 0.014))).r;
    float n3 = texture(noisetex, fract(worldXZ * 0.65 + vec2(t * 0.006, t * 0.021))).r;
    return clamp(n1 * 0.45 + n2 * 0.35 + n3 * 0.20, 0.0, 1.0);
}

// ============================================================================
// PTGI Wave-Coherent Chromatic Caustics (Organic Focusing Filaments)
// ============================================================================
float ptgi_caustic_field(vec2 xz, float time, float detailFade) {
    vec2 p1 = (PTGI_ROT1 * xz) * 0.85 + vec2(time * 0.46, -time * 0.29);
    vec2 p2 = (PTGI_ROT2 * xz) * 1.18 + vec2(-time * 0.52, time * 0.37);

    float n1 = ptgi_noise_grad(p1).x;
    float n2 = ptgi_noise_grad(p2).x;

    vec2 warp = vec2(n1 - 0.5, n2 - 0.5) * 0.45;
    float r1 = 1.0 - abs(ptgi_noise_grad(p1 + warp).x * 2.0 - 1.0);
    float r2 = 1.0 - abs(ptgi_noise_grad(p2 - warp).x * 2.0 - 1.0);

    float c1 = pow(clamp(r1, 0.0, 1.0), 4.5);
    float c2 = pow(clamp(r2, 0.0, 1.0), 4.5);

    float fine = 0.0;
    if (detailFade > 0.05) {
        vec2 p3 = (PTGI_ROT3 * xz) * 2.05 + vec2(time * 0.68, time * 0.44);
        float r3 = 1.0 - abs(ptgi_noise_grad(p3 + warp * 0.5).x * 2.0 - 1.0);
        fine = pow(clamp(r3, 0.0, 1.0), 4.0) * 0.55 * detailFade;
    }

    return clamp(c1 * 0.60 + c2 * 0.55 + (c1 * c2) * 1.45 + fine, 0.0, 1.85);
}

vec3 get_water_caustics_rgb(vec2 worldXZ, float time, float viewDist) {
    float farFade = 1.0 - smoothstep(34.0, 58.0, viewDist);
    if (farFade < 0.005) return vec3(0.0);

    float detailFade = 1.0 - smoothstep(18.0, 38.0, viewDist);
    float dispFade = (1.0 - smoothstep(12.0, 28.0, viewDist)) * WATER_DISPERSION;

    if (dispFade > 0.02) {
        vec2 d = vec2(0.042, 0.024) * dispFade;
        float cR = ptgi_caustic_field(worldXZ + d, time, detailFade);
        float cG = ptgi_caustic_field(worldXZ, time, detailFade);
        float cB = ptgi_caustic_field(worldXZ - d, time, detailFade);
        return vec3(cR, cG, cB) * farFade;
    }

    float c = ptgi_caustic_field(worldXZ, time, detailFade);
    return vec3(c * farFade);
}
