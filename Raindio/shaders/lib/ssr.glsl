// ============================================================================
// Screen-space ray-traced reflections (march + binary refinement).
// Requires ScreenToView (/lib/projection.glsl) and apply_terrain_fog (/lib/fog.glsl).
// Requires depthtex0, colortex0, gbufferProjectionInverse uniforms.
// ============================================================================

vec4 ray_trace_ssr(vec3 direction, vec3 start, float dither) {
    float rayLength = length(start);
    vec3 testPoint = start + direction * (0.08 + 0.015 * dither);

    float stepLen = 0.08 + rayLength * 0.006;
    vec2 invProjDiag = vec2(gbufferProjectionInverse[0][0], gbufferProjectionInverse[1][1]);

    for (int i = 0; i < 32; i++) {
        testPoint += direction * stepLen;
        if (testPoint.z >= -0.1) break;

        vec2 uv = (testPoint.xy / (-testPoint.z * invProjDiag)) * 0.5 + 0.5;
        if (uv.x <= 0.001 || uv.x >= 0.999 || uv.y <= 0.001 || uv.y >= 0.999) break;

        float rawDepth0 = texture(depthtex0, uv).r;
        if (rawDepth0 < 0.9999) {
            vec3 sv = ScreenToView(uv, rawDepth0);
            float diff = sv.z - testPoint.z;

            float maxThickness = stepLen * 2.0 + 0.12;
            if (diff > -0.08 && diff < maxThickness) {

                vec3 minPoint = testPoint - direction * stepLen;
                vec3 maxPoint = testPoint;
                vec3 hitPoint = testPoint;

                for (int j = 0; j < 5; j++) {
                    hitPoint = mix(minPoint, maxPoint, 0.5);
                    vec2 hitUV = (hitPoint.xy / (-hitPoint.z * invProjDiag)) * 0.5 + 0.5;
                    float hitDepth = texture(depthtex0, hitUV).r;
                    vec3 hitSv = ScreenToView(hitUV, hitDepth);

                    if (hitSv.z > hitPoint.z) {
                        maxPoint = hitPoint;
                    } else {
                        minPoint = hitPoint;
                    }
                }

                vec2 finalUV = (hitPoint.xy / (-hitPoint.z * invProjDiag)) * 0.5 + 0.5;
                vec3 finalSv = ScreenToView(finalUV, texture(depthtex0, finalUV).r);

                vec2 edge = smoothstep(vec2(0.0), vec2(0.04), finalUV) * smoothstep(vec2(1.0), vec2(0.96), finalUV);
                float edgeAlpha = edge.x * edge.y;

                vec3 hitColor = max(vec3(0.0), texture(colortex0, finalUV).rgb);
                hitColor = apply_terrain_fog(hitColor, finalSv);

                float depthWeight = smoothstep(maxThickness, 0.0, max(0.0, abs(finalSv.z - hitPoint.z)));
                return vec4(hitColor, edgeAlpha * depthWeight);
            }
            stepLen *= 1.06;
        } else {
            stepLen *= 1.10;
        }
    }
    return vec4(0.0);
}
