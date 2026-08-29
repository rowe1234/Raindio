// ============================================================================
// 2D procedural clouds (value noise + Worley + filament FBM).
// Self-contained; no uniforms required.
// ============================================================================

vec2 cloudHash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.0973, 0.1099));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float cloudValueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(dot(cloudHash22(i + vec2(0.0, 0.0)), f - vec2(0.0, 0.0)),
                   dot(cloudHash22(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0)), u.x),
               mix(dot(cloudHash22(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0)),
                   dot(cloudHash22(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0)), u.x), u.y);
}

float worleyNoise(vec2 p, float time) {
    vec2 n = floor(p);
    vec2 f = fract(p);
    float minDist = 1.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 o = cloudHash22(n + g);
            o = 0.5 + 0.5 * sin(time * 0.15 + 6.2831 * o);
            vec2 r = g + o - f;
            float d = dot(r, r);
            minDist = min(minDist, d);
        }
    }
    return sqrt(minDist);
}

float cloudFilamentFbm(vec2 p, float time) {
    vec2 warp1 = vec2(
        cloudValueNoise(p + vec2(0.0, time * 0.015)),
        cloudValueNoise(p + vec2(5.2, time * 0.012))
    );
    p += warp1 * 0.9;

    vec2 warp2 = vec2(
        cloudValueNoise(p * 2.5 - vec2(time * 0.02, 0.0)),
        cloudValueNoise(p * 2.5 + vec2(0.0, time * 0.018))
    );
    p += vec2(warp2.x * 1.2, warp2.y * 0.3);

    float f = 0.0;
    f += 0.52 * (1.0 - worleyNoise(p * 2.2, time));
    f += 0.26 * cloudValueNoise(p * 4.8);
    f += 0.13 * (1.0 - worleyNoise(p * 9.5 + time * 0.03, time));
    f += 0.09 * cloudValueNoise(p * 18.0);

    return clamp(f, 0.0, 1.0);
}

vec4 getMackerelCloudData(vec2 skyPos, float time) {
    vec2 uv1 = skyPos * 0.45 + vec2(time * 0.005, time * 0.003);
    float density1 = cloudFilamentFbm(uv1, time);
    density1 = smoothstep(0.28, 0.75, density1);

    vec2 uv2 = skyPos * 0.85 - vec2(time * 0.008, -time * 0.004);
    float density2 = cloudFilamentFbm(uv2 * vec2(2.8, 0.7), time * 1.1);
    density2 = smoothstep(0.35, 0.82, density2) * 0.5;

    float totalDensity = clamp(density1 + density2, 0.0, 1.0);
    float edgeFilament = pow(clamp(density1 * 1.4, 0.0, 1.0), 0.7);

    return vec4(totalDensity, density1, density2, edgeFilament);
}
