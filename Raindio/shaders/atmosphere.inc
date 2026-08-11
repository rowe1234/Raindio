// Atmospheric Scattering Functions
// Physically-based Rayleigh + Mie scattering for sky rendering
// Ported from SEUS Renewed E2 Common.inc, adapted for GLSL 120
//
// Core reference: Eric Bruneton's atmospheric scattering model
// Key physics: Rayleigh scattering (λ⁻⁴, blue sky + red sunset)
//               Mie scattering (aerosol, white glow around sun)

// ---- Math helpers ----
float saturate(float x) {
    return clamp(x, 0.0, 1.0);
}

vec3 saturate(vec3 x) {
    return clamp(x, vec3(0.0), vec3(1.0));
}

// ---- Scattering constants ----
// Normalized planet/atmosphere radii (unitless, planet radius ≈ 1.0)
const float R         = 1.0;           // atmosphere outer radius
const float R_INNER   = 0.985;         // planet surface radius
const float SCALE_H   = 4.0 / (R - R_INNER);   // density scale (height)
const float SCALE_L   = 1.0 / (R - R_INNER);   // density scale (linear)

// Scattering coefficients
const float K_R  = 0.166;              // Rayleigh scattering strength
const float K_M  = 0.0025;            // Mie scattering strength
const float E    = 14.3;              // Sun intensity / exposure

// Rayleigh scattering color (shorter wavelengths scatter more)
// vec3(0.3, 0.7, 1.0) — blue scatters ~3x more than red
const vec3  C_R  = vec3(0.3, 0.7, 1.0);

// Mie phase function anisotropy
// Negative = more back-scattering, positive = more forward-scattering
const float G_M  = -0.85;

// ---- Phase Functions ----
// Rayleigh phase function: symmetric (scatters equally forward and backward)
float phase_rayleigh(float cc) {
    return 0.75 * (1.0 + cc);
}

// Mie phase function (Henyey-Greenstein)
// g: anisotropy (-1 = back-scatter, 0 = isotropic, 1 = forward-scatter)
// c: cos(angle between view and light)
float phase_mie(float g, float c, float cc) {
    float gg = g * g;
    float a = (1.0 - gg) * (1.0 + cc);
    float b = 1.0 + gg - 2.0 * g * c;
    b *= sqrt(b);
    b *= 2.0 + gg;
    return 1.5 * a / b;
}

// ---- Atmospheric density ----
// Exponential density falloff with height above planet surface
float density(vec3 p) {
    return exp(-(length(p) - R_INNER) * SCALE_H) * 2.0;
}

// ---- Ray-Sphere Intersection ----
// Returns vec2(entryDist, exitDist) for a ray intersecting a sphere
// If no intersection: vec2(large, -large)
vec2 RaySphereIntersect(vec3 p, vec3 dir, float r) {
    float b = dot(p, dir);
    float c = dot(p, p) - r * r;
    float d = b * b - c;
    if (d < 0.0) {
        return vec2(10000.0, -10000.0);
    }
    d = sqrt(d);
    return vec2(-b - d, -b + d);
}

// ---- Optical Depth ----
// Integrates density between two points for extinction calculation
float optic(vec3 p, vec3 q) {
    const int STEPS = 4;
    vec3 step = (q - p) / float(STEPS);
    vec3 v = p + step * 0.5;
    float sum = 0.0;
    for (int i = 0; i < STEPS; i++) {
        sum += density(v);
        v += step;
    }
    sum *= length(step) * SCALE_L;
    return sum;
}

// ---- In-Scattering Integration ----
// Integrates light scattered into the view ray along its path through atmosphere
// o: eye position, dir: view direction, e: ray-sphere intersection distances
// l: light direction, mieAmount: Mie intensity, rayleighAmount: Rayleigh intensity
vec3 in_scatter(vec3 o, vec3 dir, vec2 e, vec3 l, float mieAmount, float rayleighAmount) {
    const float PI = 3.14159265;
    const int STEPS = 8;   // integration steps along view ray

    float boosty = saturate(l.y + 0.1) * 0.95 + 0.05;
    boosty = 1.0 / sin(boosty);   // boosts intensity when sun is low

    float len = e.y / float(STEPS);
    vec3 step = dir * len * 2.0;
    vec3 p = o;
    vec3 v = p + dir * (len * 0.5);

    float rayK = K_R * rayleighAmount;
    float mieK = K_M * 0.045 * mieAmount;  // match SEUS Mie scaling

    vec3 sum = vec3(0.0);
    for (int i = 0; i < STEPS; i++) {
        vec2 f = RaySphereIntersect(v, l, R);
        vec3 u = v + l * f.y;
        float n = (optic(p, v) + optic(v, u)) * (PI * 4.0);
        sum += density(v) * exp(-n * (rayK * C_R + mieK));
        v += step;
    }
    sum *= len * SCALE_L;

    float c  = dot(dir, -l);
    float cc = c * c;

    return sum * (rayK * C_R * phase_rayleigh(cc) + mieK * phase_mie(G_M, c, cc)) * E;
}

// ---- Secondary Scattering (ambient sky light) ----
// Simplified: samples along the up direction for ambient contribution
vec3 in_scatter_ambient(vec3 o, vec3 dir, vec2 e, vec3 l) {
    const float PI = 3.14159265;
    const int STEPS = 4;

    vec3 up = vec3(0.0, 1.0, 0.0);

    float boosty = saturate(l.y + 0.1) * 0.95 + 0.05;
    boosty = 1.0 / sin(boosty);

    float len = e.y / float(STEPS);
    vec3 step = up * len * 2.0;
    vec3 p = o;
    vec3 v = p + up * (len * 0.5);

    float rayK = K_R * 0.7;

    vec3 sum = vec3(0.0);
    for (int i = 0; i < STEPS; i++) {
        vec2 f = RaySphereIntersect(v, l, R);
        vec3 u = v + l * f.y;
        float n = (optic(p, v) + optic(v, u)) * (PI * 4.0);
        sum += density(v) * exp(-n * (rayK * C_R));
        v += step;
    }
    sum *= len * SCALE_L;

    float c  = dot(up, -l);
    float cc = c * c;

    return sum * (rayK * C_R * phase_rayleigh(cc)) * E;
}

// ---- Main Atmospheric Scattering ----
// Computes sky color for a given view direction
// rayDir: normalized world-space view direction
// lightVector: normalized world-space sun direction
// mieAmount: Mie scattering intensity (typically 1.0)
vec3 AtmosphericScattering(vec3 rayDir, vec3 lightVector, float mieAmount) {
    vec3 eye = vec3(0.0, mix(R_INNER, 1.0, 0.05), 0.0);

    vec3 dir = rayDir;

    // Clamp rays going below horizon (planet intersection)
    // This prevents dark line at horizon
    if (dir.y < 0.0) {
        dir.y = 0.0;
    }

    vec3 up = vec3(0.0, 1.0, 0.0);

    vec2 e = RaySphereIntersect(eye, dir, R);
    vec2 eup = RaySphereIntersect(eye, up, R);

    // Primary scattering
    vec3 atmosphere = in_scatter(eye, dir, e, lightVector, mieAmount, 1.0);

    // Ambient / secondary scattering
    vec3 secondary = in_scatter_ambient(eye, up, eup, lightVector);
    vec3 ambient = vec3(0.3, 0.5, 1.0);
    atmosphere += dot(secondary, vec3(0.86)) * ambient;

    // Slight tint correction
    atmosphere *= vec3(0.8, 0.89, 1.0);

    // Rain desaturation: blend toward neutral gray in rain
    atmosphere = mix(atmosphere, vec3(dot(atmosphere, vec3(0.33333))),
                     vec3(rainStrength * saturate((0.3 - rayDir.y) / 0.3) * (lightVector.y + 0.1)) * 0.5);

    return atmosphere;
}

// ---- Sun Disc ----
// Renders the visible sun disc
float SunDisc(vec3 worldDir, vec3 sunDir) {
    float d = dot(worldDir, sunDir);

    float size = 0.00195;
    float hardness = 1000.0;

    // Smooth disc edge
    float disc = saturate((d - (1.0 - size)) * hardness);
    disc = pow(disc * disc * (3.0 - 2.0 * disc), 2.0);

    // Fade sun when below horizon
    float visibility = saturate(worldDir.y * 30.0);
    visibility = visibility * visibility * (3.0 - 2.0 * visibility);

    disc *= visibility;

    return disc;
}
