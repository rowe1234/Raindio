// ============================================================================
// Nishita-style atmospheric scattering (Rayleigh/Mie) + sun disc.
// Requires saturate (from /lib/common.glsl) included first.
// ============================================================================

const float ATM_R       = 1.0;
const float ATM_R_INNER = 0.985;
const float ATM_SCALE_H = 4.0 / (ATM_R - ATM_R_INNER);
const float ATM_SCALE_L = 1.0 / (ATM_R - ATM_R_INNER);
const float ATM_K_R     = 0.166;
const float ATM_K_M     = 0.0025;
const float ATM_E       = 14.3;
const vec3  ATM_C_R     = vec3(0.3, 0.52, 1.0);
const vec3  ATM_EYE     = vec3(0.0, ATM_R_INNER + 0.00025, 0.0);

float phase_rayleigh(float cc) { return 0.75 * (1.0 + cc); }

float phase_mie_opt(float c, float cc) {
    float b = max(1.7225 + 1.7 * c, 0.0001);
    return (0.15289256 * (1.0 + cc)) / (b * sqrt(b));
}

float atm_density(vec3 p) { return exp(-(length(p) - ATM_R_INNER) * ATM_SCALE_H) * 2.0; }

vec2 RaySphereIntersect(vec3 p, vec3 dir, float r) {
    float b = dot(p, dir);
    float d = b * b - dot(p, p) + r * r;
    if (d < 0.0) return vec2(-1.0);
    d = sqrt(d);
    return vec2(-b - d, -b + d);
}

float atm_optic(vec3 p, vec3 q) {
    vec3 step = (q - p) * 0.25;
    vec3 v = p + step * 0.5;
    float sum = 0.0;
    for (int i = 0; i < 4; i++) {
        sum += atm_density(v);
        v += step;
    }
    return sum * length(step) * ATM_SCALE_L;
}

vec3 atm_in_scatter(vec3 o, vec3 dir, vec2 e, vec3 l, float mie, float rayAmt) {
    if (e.y < 0.0) return vec3(0.0);

    float boosty = saturate(l.y + 0.1) * 0.95 + 0.05;
    boosty = clamp(1.0 / sin(boosty), 1.0, 2.0);
    float len = e.y * 0.125;
    vec3 step = dir * (len * 2.0);
    vec3 p = o;
    vec3 v = p + dir * (len * 0.5);

    float rayK = ATM_K_R * rayAmt;
    float mieK = ATM_K_M * 0.045 * mie;
    vec3 rayMieVec = rayK * ATM_C_R + vec3(mieK);

    vec3 sum = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        vec2 f = RaySphereIntersect(v, l, ATM_R);
        if (f.y > 0.0) {
            float n = (atm_optic(p, v) + atm_optic(v, v + l * f.y)) * 12.56637;
            sum += atm_density(v) * exp(-n * rayMieVec);
        }
        v += step;
    }
    sum *= len * ATM_SCALE_L;
    float c = dot(dir, -l);
    float cc = c * c;
    return sum * (rayK * ATM_C_R * phase_rayleigh(cc) + mieK * phase_mie_opt(c, cc)) * (ATM_E * boosty);
}

vec3 AtmosphericScattering(vec3 rayDir, vec3 lightVector, float mieAmount) {
    vec3 dir = rayDir;
    if (dir.y < 0.0) dir.y = 0.0;
    vec2 e = RaySphereIntersect(ATM_EYE, dir, ATM_R);
    vec3 atmosphere = atm_in_scatter(ATM_EYE, dir, e, lightVector, mieAmount, 1.0) * vec3(0.78, 0.85, 1.0);
    return max(atmosphere, vec3(0.0));
}

float SunDisc(vec3 worldDir, vec3 sunDir) {
    float d = dot(worldDir, sunDir);
    float disc = saturate((d - 0.99805) * 1000.0);
    disc = pow(disc * disc * (3.0 - 2.0 * disc), 2.0);
    return disc * saturate(worldDir.y * 30.0);
}
