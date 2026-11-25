// ============================================================================
// Unified Sky HDR (Makoto Shinkai / Crisp Anime Artistic Style):
//   - Deep cobalt zenith -> clean translucent cyan-white horizon during daytime
//   - Authentic silver-gray overcast rain sky that dims with sun elevation
//   - 3-band magic-hour sunset/sunrise
//   - Clear indigo-sapphire night sky with circular twinkling pinpoint stars
// Requires /lib/common.glsl, /lib/atmosphere.glsl, and /lib/clouds.glsl.
// ============================================================================

#define SKY_ZENITH_DEPTH 1.0  // [0.5 0.75 1.0 1.25 1.5]
#define SKY_HORIZON_CYAN 1.0  // [0.5 0.75 1.0 1.25 1.5]

vec2 skyDirToUV(vec3 dir) {
    vec3 d = normalize(dir);
    float u = fract(atan(d.z, d.x) / (2.0 * PI) + 0.5);
    float sy = clamp(d.y, -1.0, 1.0);
    float nonLinearY = sign(sy) * sqrt(abs(sy));
    float v = clamp(nonLinearY * 0.5 + 0.5, 0.004, 0.996);
    return vec2(u, v);
}

vec3 skyUVToDir(vec2 uv) {
    float phi = (uv.x - 0.5) * (2.0 * PI);
    float ny = uv.y * 2.0 - 1.0;
    float y = sign(ny) * ny * ny;
    float r = sqrt(max(0.0, 1.0 - y * y));
    return vec3(cos(phi) * r, y, sin(phi) * r);
}

vec3 SampleSkyLUT(sampler2D skyLutTex, vec3 dir) {
    vec2 uv = skyDirToUV(dir);
    float texelX = 1.0 / 128.0;
    if (uv.x < texelX || uv.x > 1.0 - texelX) {
        vec3 c0 = texture(skyLutTex, vec2(0.5 * texelX, uv.y)).rgb;
        vec3 c1 = texture(skyLutTex, vec2(1.0 - 0.5 * texelX, uv.y)).rgb;
        float t = (uv.x < 0.5) ? (0.5 + uv.x / (2.0 * texelX)) : ((uv.x - (1.0 - texelX)) / (2.0 * texelX));
        return mix(c1, c0, clamp(t, 0.0, 1.0));
    }
    return texture(skyLutTex, uv).rgb;
}

// Compute smooth Shinkai-styled atmospheric scattering for 128x64 LUT
vec3 ComputeAtmosphereLUT(vec3 dir, vec3 realSunDir, float twilightBlend, float rainStrengthVal) {
    float dy = dir.y - 0.04;
    float smoothY = 0.04 + 0.5 * (dy + sqrt(dy * dy + 0.0036));
    vec3 safeDir = normalize(vec3(dir.x, smoothY, dir.z));
    float sunHeight = realSunDir.y;
    float rainAmt = saturate(rainStrengthVal);
    float clearAmt = 1.0 - rainAmt;

    vec3 daySkyHDR = vec3(0.0);
    if (twilightBlend > 0.001) {
        vec3 baseAtm = AtmosphericScattering(safeDir, realSunDir, 1.0);

        // 1. Vivid Makoto Shinkai anime daytime sky: deep cerulean-cobalt zenith -> luminous aqua-cyan horizon
        float noonAmt = smoothstep(0.10, 0.46, sunHeight);
        float horizonBand = pow(1.0 - clamp(safeDir.y, 0.0, 1.0), 3.0);
        float zenithMask = smoothstep(0.14, 0.84, safeDir.y);

        // Deep painted anime cobalt zenith
        float zenithBlend = clamp(zenithMask * noonAmt * 0.76 * SKY_ZENITH_DEPTH * clearAmt, 0.0, 0.94);
        baseAtm = mix(baseAtm, baseAtm * vec3(0.48, 0.78, 1.48), zenithBlend);

        // Luminous translucent aqua-cyan horizon band (summer anime clarity)
        float cyanBlend = clamp(horizonBand * noonAmt * 0.58 * SKY_HORIZON_CYAN * clearAmt, 0.0, 0.88);
        baseAtm = mix(baseAtm, baseAtm * vec3(0.78, 0.92, 1.12), cyanBlend);
        vec3 cyanHorizon = vec3(0.32, 0.76, 1.00) * (0.52 * noonAmt * clearAmt * SKY_HORIZON_CYAN);
        baseAtm += cyanHorizon * horizonBand;

        // 2. Iconic 4-band Makoto Shinkai "Kataware-doki" (黄昏之时) sunrise/sunset gradient:
        //    apricot-gold sun glow -> coral-pink horizon -> lavender-violet mid-sky -> deep royal indigo zenith
        float magicHour = (1.0 - smoothstep(0.02, 0.36, sunHeight)) * smoothstep(-0.15, 0.05, sunHeight) * clearAmt;
        if (magicHour > 0.001) {
            float sunSide = pow(max(0.0, dot(safeDir, normalize(vec3(realSunDir.x, max(realSunDir.y, 0.05), realSunDir.z)))), 2.0);
            float lowBelt = pow(1.0 - clamp(safeDir.y, 0.0, 1.0), 3.8);
            float midBelt = smoothstep(0.03, 0.25, safeDir.y) * (1.0 - smoothstep(0.25, 0.70, safeDir.y));
            float topBelt = smoothstep(0.32, 0.86, safeDir.y);

            vec3 apricotSunGlow = vec3(1.22, 0.58, 0.22) * (0.45 + 0.85 * sunSide) * pow(lowBelt, 1.35);
            vec3 coralPinkLow   = vec3(1.16, 0.34, 0.44) * (0.42 + 0.55 * sunSide) * lowBelt;
            vec3 lavenderMid    = vec3(0.56, 0.24, 0.74) * (0.42 + 0.36 * sunSide) * midBelt;
            vec3 royalIndigoTop = vec3(0.04, 0.12, 0.44) * topBelt;

            vec3 animeMagicSky = apricotSunGlow * 0.60 + coralPinkLow * 0.65 + lavenderMid + royalIndigoTop;
            baseAtm = mix(baseAtm, baseAtm * 0.52 + animeMagicSky, magicHour * 0.90);
        }

        // 3. Authentic overcast rain sky: dims with sun elevation & desaturates to cool silver-gray
        float overcastLight = mix(0.16, 1.0, smoothstep(-0.02, 0.45, sunHeight));
        vec3 rainOvercast = mix(vec3(0.22, 0.32, 0.44), vec3(0.12, 0.20, 0.32), smoothstep(0.05, 0.75, safeDir.y)) * overcastLight;
        rainOvercast = mix(rainOvercast, vec3(luminance(rainOvercast)), 0.48);
        daySkyHDR = mix(baseAtm, rainOvercast, rainAmt * 0.85);
    }

    vec3 nightSkyHDR = vec3(0.0);
    if (twilightBlend < 0.999) {
        vec3 nightAtm = AtmosphericScattering(safeDir, -realSunDir, 0.1) * 0.009;
        vec3 nightZenith = vec3(0.002, 0.007, 0.028);
        vec3 nightHorizon = vec3(0.008, 0.020, 0.052);
        float nightHorizMask = pow(1.0 - clamp(safeDir.y, 0.0, 1.0), 3.0);
        nightSkyHDR = nightAtm + mix(nightZenith, nightHorizon, nightHorizMask) * mix(1.0, 0.45, rainAmt);
    }

    vec3 sky = mix(nightSkyHDR, daySkyHDR, twilightBlend);

    if (dir.y < 0.0) {
        vec3 clearLower = mix(vec3(0.008, 0.020, 0.048), vec3(0.14, 0.28, 0.40), twilightBlend);
        vec3 lowerTint = mix(clearLower, sky * 0.62, rainAmt * 0.85);
        float lowerFade = 1.0 - smoothstep(-0.30, 0.0, dir.y);
        sky = mix(sky, lowerTint, lowerFade);
    }

    return max(sky, vec3(0.0));
}

// Add Physically-Lit 3D Volumetric Clouds (samples real `colortex6` Atmosphere LUT for skylight & aerial blend)
vec3 AddProceduralClouds(sampler2D skyLutTex, vec3 sky, vec3 dir, vec3 realSunDir, vec3 sunColor, float sunHeight, float rainStrengthVal, float timeVal, bool highQuality) {
#if !defined(COMPOSITE_LUT_ONLY) && defined(VOL_CLOUDS_ENABLED) && VOL_CLOUDS_ENABLED == 1
    if (dir.y > 0.025) {
        float rainAmt = saturate(rainStrengthVal);
        vec3 skyZenithLUT  = SampleSkyLUT(skyLutTex, vec3(0.0, 0.95, 0.0));
        vec3 skyHorizonLUT = SampleSkyLUT(skyLutTex, normalize(vec3(dir.x, 0.10, dir.z)));
        vec3 skySunSideLUT = SampleSkyLUT(skyLutTex, normalize(vec3(realSunDir.x, max(realSunDir.y, 0.08), realSunDir.z)));
        vec4 volCloud = traceVolumetricClouds(
            dir, realSunDir, sky, skyZenithLUT, skyHorizonLUT, skySunSideLUT,
            sunHeight, rainAmt, timeVal, highQuality
        );
        sky = sky * volCloud.a + volCloud.rgb;
    }
#endif
    return sky;
}

// Add crisp Shinkai-styled celestial bodies (circular pinpoint stars, radiant sun, clear moon halo)
vec3 AddCelestialBodies(vec3 baseSky, vec3 dir, vec3 realSunDir, vec3 sunColor, float sunHeight, float twilightBlend, float rainStrengthVal, float timeVal) {
    vec3 sky = baseSky;
    float rainAtten = 1.0 - saturate(rainStrengthVal);

    // Circular pinpoint twinkling stars (eliminates blocky grid squares)
    float starVis = (1.0 - twilightBlend) * rainAtten * smoothstep(0.02, 0.22, dir.y);
    if (starVis > 0.001 && dir.y > 0.0) {
        vec2 starCoord = (dir.xz / (dir.y + 0.35)) * 180.0;
        vec2 starGrid = floor(starCoord);
        vec2 starF = fract(starCoord);
        float starHash = fract(sin(dot(starGrid, vec2(127.1, 311.7))) * 43758.5453);
        if (starHash > 0.992) {
            vec2 starCenter = vec2(fract(starHash * 17.3), fract(starHash * 41.9)) * 0.5 + 0.25;
            vec2 diff = starF - starCenter;
            float pinpoint = exp(-dot(diff, diff) * 48.0);
            float twinkle = 0.60 + 0.40 * sin(mod(timeVal, 3600.0) * 2.8 + starHash * 120.0);
            float starBright = smoothstep(0.990, 1.0, starHash) * pinpoint * twinkle * starVis * 0.92;
            float starHue = fract(starHash * 37.3);
            vec3 starColor = mix(vec3(0.70, 0.88, 1.0), vec3(1.0, 0.94, 0.80), smoothstep(0.55, 0.95, starHue));
            sky += starColor * starBright;
        }
    }

    // Compact, softly glowing anime sun (zero hard circular boundary)
    if (sunHeight > -0.02) {
        float horizonFactor = saturate(sunHeight * 3.0);
        float sd = clamp(dot(dir, realSunDir), -1.0, 1.0);
        float oneMinusSd = max(0.0, 1.0 - sd);

        // Continuous multi-exponential falloff: smaller core that melts smoothly into sky glow
        float sunCore   = exp(-oneMinusSd * mix(6200.0, 4600.0, horizonFactor));
        float sunCorona = exp(-oneMinusSd * mix(900.0, 460.0, horizonFactor));
        float sunGlow   = exp(-oneMinusSd * mix(180.0, 48.0, horizonFactor));
        vec3 crispSunTint = mix(vec3(1.00, 0.54, 0.34), vec3(1.00, 0.98, 0.94), horizonFactor);

        sky += (sunCore * 6.8 + sunCorona * 1.15 + sunGlow * 0.48) * crispSunTint
             * smoothstep(0.0, 0.3, sunHeight) * saturate(sunHeight * 10.0) * rainAtten;
    }

    // Luminous silver-blue moon disc + airy halo
    if (realSunDir.y < 0.0) {
        float md = max(0.0, dot(dir, -realSunDir));
        float moonHalo = exp(-(1.0 - md) * 40.0) * 0.045 * saturate(-realSunDir.y * 5.0) * rainAtten;
        sky += (SunDisc(dir, -realSunDir) * vec3(0.32, 0.38, 0.48) + vec3(0.45, 0.68, 1.0) * moonHalo) * rainAtten;
    }

    return sky;
}

vec3 ComputeFullSkyHDR(sampler2D skyLutTex, vec3 dir, vec3 realSunDir, vec3 sunColor, float sunHeight, float twilightBlend, float rainStrengthVal, float timeVal, bool highQualityClouds) {
    vec3 sky = SampleSkyLUT(skyLutTex, dir);
    sky = AddCelestialBodies(sky, dir, realSunDir, sunColor, sunHeight, twilightBlend, rainStrengthVal, timeVal);
    sky = AddProceduralClouds(skyLutTex, sky, dir, realSunDir, sunColor, sunHeight, rainStrengthVal, timeVal, highQualityClouds);
    return max(sky, vec3(0.0));
}
