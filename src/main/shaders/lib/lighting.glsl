// ============================================================================
// Unified day/night sun & sky lighting parameters shared by terrain, entities,
// block entities, hand, particles, and weather passes.
// ============================================================================

void computeUnifiedLighting(vec3 worldSunDir, out vec3 skyColor, out vec3 sunlight, out float dayFactor, out float sunIntensity) {
    float sunHeight = worldSunDir.y;

    dayFactor = smoothstep(-0.05, 0.15, sunHeight);
    float noonFactor = smoothstep(0.00, 0.60, sunHeight);

    const vec3 skyNight  = vec3(0.004, 0.009, 0.024);
    const vec3 skySunset = vec3(0.62, 0.30, 0.42);
    const vec3 skyNoon   = vec3(0.26, 0.52, 0.94);
    const vec3 sunNight  = vec3(0.00, 0.00, 0.00);
    const vec3 sunSunset = vec3(1.00, 0.48, 0.22);
    const vec3 sunNoon   = vec3(1.00, 0.97, 0.92);

    skyColor = mix(skyNight, mix(skySunset, skyNoon, noonFactor), dayFactor);
    // Smoothly fade sun & moon direct intensity to 0 near the horizon (|sunHeight| < 0.02..0.12)
    // so horizontal grazing rays never shoot underneath distant chunks into caves or rooms.
    float horizonFade = smoothstep(0.02, 0.12, abs(sunHeight));
    sunlight = (sunHeight > 0.0)
        ? mix(sunNight, mix(sunSunset, sunNoon, noonFactor), dayFactor)
        : vec3(0.08, 0.14, 0.26);
    sunIntensity = ((sunHeight > 0.0) ? 1.0 : 0.16) * horizonFade;
}
