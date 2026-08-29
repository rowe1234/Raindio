// ============================================================================
// Distance/absorption fog helpers.
// Requires AtmosphericScattering (from /lib/atmosphere.glsl) included first.
// Requires gbufferModelViewInverse, sunPosition, rainStrength uniforms.
// ============================================================================

#define TERRAIN_FOG_DENSITY 0.0003

vec3 apply_terrain_fog(vec3 color, vec3 viewPos) {
    float viewDist = length(viewPos);
    vec3 worldDir = normalize((gbufferModelViewInverse * vec4(normalize(viewPos), 0.0)).xyz);
    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    bool isDay = realSunDir.y > 0.0;

    vec3 fogAtmColor = isDay ? AtmosphericScattering(worldDir, realSunDir, 0.5) :
                               AtmosphericScattering(worldDir, -realSunDir, 0.1) * 0.02 + vec3(0.001, 0.005, 0.018);

    fogAtmColor *= mix(1.0, 0.30, saturate(rainStrength));
    float dynamicFogDensity = mix(TERRAIN_FOG_DENSITY, TERRAIN_FOG_DENSITY * 3.5, saturate(rainStrength));

    float fogFactor = 1.0 - exp(-viewDist * dynamicFogDensity);
    return mix(color, fogAtmColor, fogFactor);
}

vec3 apply_underwater_fog(vec3 color, vec3 viewPos, float nightDim) {
    float dist = length(viewPos);
    vec3 absorb = exp(vec3(-0.08, -0.04, -0.02) * dist);
    vec3 absorbedColor = color * absorb;
    float fogFactor = 1.0 - exp(-dist * 0.025);
    vec3 waterFogColor = vec3(0.01, 0.08, 0.15) * nightDim;
    return mix(absorbedColor, waterFogColor, fogFactor);
}
