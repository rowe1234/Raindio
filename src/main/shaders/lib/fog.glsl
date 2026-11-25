// ============================================================================
// Distance aerial-perspective fog & underwater absorption fog.
// Requires /lib/common.glsl and /lib/sky.glsl included first.
// Requires colortex6, gbufferModelViewInverse, sunPosition, rainStrength,
// cameraPosition, far uniforms.
// ============================================================================

#define TERRAIN_FOG_DENSITY 0.0015
#define WATER_FOG_DENSITY 1.0 // [0.5 0.75 1.0 1.25 1.5 2.0]

vec3 apply_terrain_fog(vec3 color, vec3 viewPos) {
    float viewDist = length(viewPos);
    vec3 worldDir = normalize((gbufferModelViewInverse * vec4(normalize(viewPos), 0.0)).xyz);
    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);

    float safeFar = max(far, 64.0);
    float borderFade = smoothstep(safeFar * 0.62, safeFar * 0.98, viewDist);

    vec3 fogDir = normalize(vec3(worldDir.x, max(worldDir.y, 0.0), worldDir.z));
    vec3 horizonSkyColor = SampleSkyLUT(colortex6, fogDir);
    vec3 fogAtmColor = horizonSkyColor;

    // Subtle cool-silver aerial perspective (clean, non-milky distance fog)
    float sunHeight = realSunDir.y;
    float sunVis = smoothstep(-0.08, 0.15, sunHeight) * (1.0 - saturate(rainStrength));
    if (sunVis > 0.001) {
        float cosTheta = max(0.0, dot(worldDir, realSunDir));
        float mieGlow = pow(cosTheta, 6.0) * 0.35 + pow(cosTheta, 24.0) * 0.45;
        vec3 sunFogTint = mix(vec3(1.00, 0.54, 0.36), vec3(0.76, 0.92, 1.00), saturate(sunHeight * 2.5));
        fogAtmColor += sunFogTint * mieGlow * sunVis * 0.16 * (1.0 - borderFade);
    }

    // Avoid double-darkening rain fog against the overcast sky LUT at the chunk border
    fogAtmColor *= mix(1.0, mix(0.75, 1.0, borderFade), saturate(rainStrength));

    // Height-attenuated aerial perspective + seamless 100% render-distance horizon fade
    vec3 fragWorldPos = (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz + cameraPosition;
    float heightDensity = mix(0.70, 1.35, exp(-max(0.0, fragWorldPos.y - 62.0) * 0.015));
    float nearProtect = smoothstep(10.0, 38.0, viewDist);
    float dynamicFogDensity = mix(TERRAIN_FOG_DENSITY, TERRAIN_FOG_DENSITY * 2.8, saturate(rainStrength)) * heightDensity;
    float expFog = (1.0 - exp(-viewDist * dynamicFogDensity)) * nearProtect;

    float fogFactor = clamp(max(expFog, borderFade), 0.0, 1.0);
    return mix(color, fogAtmColor, fogFactor);
}

// Crystal-clear PTGI underwater aquatic mist/fog with physical spectral absorption
vec3 apply_underwater_fog(vec3 color, vec3 viewPos, float nightDim, float skyLight) {
    float dist = length(viewPos);
    vec3 worldDir = normalize((gbufferModelViewInverse * vec4(normalize(viewPos), 0.0)).xyz);
    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);

    // Gentle spectral absorption so nearby underwater terrain keeps color while distance shifts teal
    vec3 absorb = exp(vec3(-0.18, -0.042, -0.055) * dist * WATER_FOG_DENSITY);
    vec3 absorbedColor = color * absorb;

    float nearMist = 1.0 - exp(-dist * 0.075 * WATER_FOG_DENSITY);
    float deepMist = 1.0 - exp(-dist * dist * 0.0036 * WATER_FOG_DENSITY * WATER_FOG_DENSITY);
    float fogFactor = clamp(mix(nearMist, deepMist, 0.45), 0.0, 0.96);

    // Maintain a luminous deep-water floor so deep ocean / shaded seabed never crushes to pure black
    float lightScale = mix(0.28, 1.0, saturate(skyLight));
    vec3 baseMist = vec3(0.024, 0.165, 0.155) * nightDim * lightScale;

    float sunVis = smoothstep(-0.05, 0.20, realSunDir.y) * (1.0 - saturate(rainStrength) * 0.7);
    float sunAlign = max(0.0, dot(worldDir, realSunDir));
    vec3 sunMistGlow = vec3(0.045, 0.28, 0.24) * pow(sunAlign, 3.2) * sunVis * lightScale;

    float upGrad = smoothstep(-0.6, 0.7, worldDir.y);
    vec3 waterFogColor = mix(baseMist * 0.72, baseMist * 1.45 + sunMistGlow, upGrad);

    return mix(absorbedColor, waterFogColor, fogFactor);
}
