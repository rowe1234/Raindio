// ============================================================================
// Atmospheric Scattering Shader (Nishita / O'Neil Model Approximation)
// Refactored based on open-source standards (Scratchapixel & GPU Gems 2)
// ============================================================================

// ---- Constants & Math Helpers ----
const float PI = 3.14159265359;

// Saturation utilities
float saturate(float x) {
    return clamp(x, 0.0, 1.0);
}

vec3 saturate(vec3 x) {
    return clamp(x, vec3(0.0), vec3(1.0));
}

// Atmospheric physical parameters
struct AtmosphereParams {
    float R_outer;         // Atmosphere outer radius (unitless, normalized)
    float R_inner;         // Planet surface radius
    float scaleHeight;     // Exponential density scale (height falloff)
    float scaleLinear;     // Density scale (linear normalization)
    float kRayleigh;       // Rayleigh scattering coefficient
    float kMie;            // Mie scattering coefficient
    float sunIntensity;    // Sun intensity / exposure factor
    vec3  cRayleigh;       // Rayleigh scattering RGB wavelength distribution
    float gMie;            // Mie phase function anisotropy (-1..1)
};

// Default standard atmosphere configuration
const AtmosphereParams ATMOSPHERE = AtmosphereParams(
    1.0,                    // R_outer
    0.985,                  // R_inner
    4.0 / (1.0 - 0.985),   // scaleHeight (4.0 / thickness)
    1.0 / (1.0 - 0.985),   // scaleLinear (1.0 / thickness)
    0.166,                  // kRayleigh
    0.0025,                 // kMie
    14.3,                   // sunIntensity
    vec3(0.3, 0.7, 1.0),    // cRayleigh (Blue scatters ~3x more than Red)
    -0.85                   // gMie (Mie anisotropy)
);

// ---- Phase Functions ----

// Rayleigh Phase Function: Symmetric distribution for air molecules
float phaseRayleigh(float cosThetaSq) {
    return 0.75 * (1.0 + cosThetaSq);
}

// Mie Phase Function: Henyey-Greenstein approximation for aerosols/dust
float phaseMie(float g, float cosTheta, float cosThetaSq) {
    float g2 = g * g;
    float num = (1.0 - g2) * (1.0 + cosThetaSq);
    float denom = (1.0 + g2 - 2.0 * g * cosTheta);
    denom *= sqrt(denom) * (2.0 + g2);
    return 1.5 * num / denom;
}

// ---- Atmospheric Geometry & Density ----

// Exponential atmospheric density falloff with altitude
float getDensity(vec3 pos) {
    float altitude = length(pos) - ATMOSPHERE.R_inner;
    return exp(-altitude * ATMOSPHERE.scaleHeight) * 2.0;
}

// Ray-Sphere Intersection
// Returns vec2(t_near, t_far). Returns vec2(-1.0) if no hit.
vec2 raySphereIntersect(vec3 rayOrigin, vec3 rayDir, float radius) {
    float b = dot(rayOrigin, rayDir);
    float c = dot(rayOrigin, rayOrigin) - radius * radius;
    float delta = b * b - c;

    if (delta < 0.0) {
        return vec2(-1.0);
    }

    float sqrtDelta = sqrt(delta);
    return vec2(-b - sqrtDelta, -b + sqrtDelta);
}

// Integrates density between point P and point Q (Optical Depth)
float computeOpticalDepth(vec3 p, vec3 q) {
    const int OPTICAL_STEPS = 4;
    vec3 rayStep = (q - p) / float(OPTICAL_STEPS);
    vec3 currentPos = p + rayStep * 0.5;

    float accumulatedDensity = 0.0;
    for (int i = 0; i < OPTICAL_STEPS; ++i) {
        accumulatedDensity += getDensity(currentPos);
        currentPos += rayStep;
    }

    return accumulatedDensity * length(rayStep) * ATMOSPHERE.scaleLinear;
}

// ---- In-Scattering Integration ----

// Integrates light scattered into the view ray path
vec3 computeInScattering(
    vec3 eyePos, 
    vec3 rayDir, 
    vec2 hitDistances, 
    vec3 sunDir, 
    float mieAmount, 
    float rayleighAmount
) {
    const int VIEW_STEPS = 8;

    float segmentLength = hitDistances.y / float(VIEW_STEPS);
    vec3 rayStep = rayDir * (segmentLength * 2.0);
    vec3 samplePos = eyePos + rayDir * (segmentLength * 0.5);

    float rayK = ATMOSPHERE.kRayleigh * rayleighAmount;
    float mieK = ATMOSPHERE.kMie * 0.045 * mieAmount; // Mie scaling factor

    vec3 accumulatedScattering = vec3(0.0);

    for (int i = 0; i < VIEW_STEPS; ++i) {
        vec2 lightHit = raySphereIntersect(samplePos, sunDir, ATMOSPHERE.R_outer);
        vec3 lightExitPos = samplePos + sunDir * lightHit.y;

        // Optical depth: View ray path + Sun ray path (Extinction via Beer-Lambert law)
        float totalOpticalDepth = (computeOpticalDepth(eyePos, samplePos) + computeOpticalDepth(samplePos, lightExitPos)) * (PI * 4.0);
        vec3 attenuation = exp(-totalOpticalDepth * (rayK * ATMOSPHERE.cRayleigh + mieK));
        
        accumulatedScattering += getDensity(samplePos) * attenuation;
        samplePos += rayStep;
    }

    accumulatedScattering *= segmentLength * ATMOSPHERE.scaleLinear;

    // Apply phase functions
    float cosTheta = dot(rayDir, -sunDir);
    float cosThetaSq = cosTheta * cosTheta;

    float rPhase = phaseRayleigh(cosThetaSq);
    float mPhase = phaseMie(ATMOSPHERE.gMie, cosTheta, cosThetaSq);

    vec3 inScatterLight = accumulatedScattering * (rayK * ATMOSPHERE.cRayleigh * rPhase + mieK * mPhase);

    return inScatterLight * ATMOSPHERE.sunIntensity;
}

// ---- Secondary (Ambient Sky) Scattering ----

// Simplified ambient contribution sampled vertically upwards
vec3 computeAmbientInScattering(vec3 eyePos, vec2 hitDistances, vec3 sunDir) {
    const int AMBIENT_STEPS = 4;
    vec3 upDir = vec3(0.0, 1.0, 0.0);

    float segmentLength = hitDistances.y / float(AMBIENT_STEPS);
    vec3 rayStep = upDir * (segmentLength * 2.0);
    vec3 samplePos = eyePos + upDir * (segmentLength * 0.5);

    float rayK = ATMOSPHERE.kRayleigh * 0.7;

    vec3 accumulatedScattering = vec3(0.0);
    for (int i = 0; i < AMBIENT_STEPS; ++i) {
        vec2 lightHit = raySphereIntersect(samplePos, sunDir, ATMOSPHERE.R_outer);
        vec3 lightExitPos = samplePos + sunDir * lightHit.y;

        float totalOpticalDepth = (computeOpticalDepth(eyePos, samplePos) + computeOpticalDepth(samplePos, lightExitPos)) * (PI * 4.0);
        accumulatedScattering += getDensity(samplePos) * exp(-totalOpticalDepth * (rayK * ATMOSPHERE.cRayleigh));
        
        samplePos += rayStep;
    }

    accumulatedScattering *= segmentLength * ATMOSPHERE.scaleLinear;

    float cosTheta = dot(upDir, -sunDir);
    float cosThetaSq = cosTheta * cosTheta;

    return accumulatedScattering * (rayK * ATMOSPHERE.cRayleigh * phaseRayleigh(cosThetaSq)) * ATMOSPHERE.sunIntensity;
}

// ---- Main Sky Scattering Entry ----

vec3 calculateAtmosphere(
    vec3 viewDir, 
    vec3 sunDir, 
    float mieAmount, 
    float rainStrength
) {
    vec3 eyePos = vec3(0.0, mix(ATMOSPHERE.R_inner, ATMOSPHERE.R_outer, 0.05), 0.0);

    // Prevent visual dark line at horizon by clamping downward rays
    vec3 rayDir = viewDir;
    rayDir.y = max(rayDir.y, 0.0);

    vec3 upDir = vec3(0.0, 1.0, 0.0);

    vec2 viewHit = raySphereIntersect(eyePos, rayDir, ATMOSPHERE.R_outer);
    vec2 upHit   = raySphereIntersect(eyePos, upDir, ATMOSPHERE.R_outer);

    // 1. Primary atmosphere scattering
    vec3 atmosphereColor = computeInScattering(eyePos, rayDir, viewHit, sunDir, mieAmount, 1.0);

    // 2. Secondary ambient sky illumination
    vec3 secondaryLight = computeAmbientInScattering(eyePos, upHit, sunDir);
    vec3 ambientTint = vec3(0.3, 0.5, 1.0);
    atmosphereColor += dot(secondaryLight, vec3(0.86)) * ambientTint;

    // 3. Post tint correction
    atmosphereColor *= vec3(0.8, 0.89, 1.0);

    // 4. Rain desaturation blend
    float horizonFade = saturate((0.3 - viewDir.y) / 0.3);
    float sunElevation = sunDir.y + 0.1;
    float desaturationFactor = rainStrength * horizonFade * sunElevation * 0.5;

    vec3 grayscaleColor = vec3(dot(atmosphereColor, vec3(0.33333)));
    atmosphereColor = mix(atmosphereColor, grayscaleColor, desaturationFactor);

    return atmosphereColor;
}

// ---- Sun Disc ----

float calculateSunDisc(vec3 viewDir, vec3 sunDir) {
    float cosAngle = dot(viewDir, sunDir);

    const float sunSize = 0.00195;
    const float hardness = 1000.0;

    // Smooth edge sun disc
    float disc = saturate((cosAngle - (1.0 - sunSize)) * hardness);
    disc = pow(disc * disc * (3.0 - 2.0 * disc), 2.0);

    // Fade sun disc when nearing or below horizon
    float horizonVisibility = saturate(viewDir.y * 30.0);
    horizonVisibility = horizonVisibility * horizonVisibility * (3.0 - 2.0 * horizonVisibility);

    return disc * horizonVisibility;
}
