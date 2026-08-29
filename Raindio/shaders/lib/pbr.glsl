// ============================================================================
// PBR (GGX specular / Schlick fresnel) + shadow sampling data.
// Shared verbatim by gbuffers_terrain.fsh and gbuffers_entities.fsh.
// Requires PI (from /lib/common.glsl) to be included first.
// ============================================================================

const vec2 poissonDisk[16] = vec2[16](
    vec2(-0.94201624, -0.39906216), vec2(0.94558609, -0.76890725),
    vec2(-0.094184101, -0.92938870), vec2(0.34495938, 0.29387760),
    vec2(-0.91588581, 0.45771432),  vec2(-0.81544232, -0.87912464),
    vec2(-0.38277182, 0.27676845),  vec2(0.97484398, 0.75648379),
    vec2(0.44323325, -0.97511554),  vec2(0.53742981, -0.47373420),
    vec2(-0.26496911, -0.41893023), vec2(0.79197514, 0.19090160),
    vec2(-0.24188840, 0.99706507),  vec2(-0.81409955, 0.91437590),
    vec2(0.19984126, 0.78641367),   vec2(0.14383161, -0.14100790)
);

float CurveBlockLightSky(float blockLight) {
    float inv = 1.0 - blockLight;
    float sq = inv * inv;
    float res = 1.0 - sq * sqrt(inv);
    return res * res * res;
}

float GGX_D(float NdotH, float roughness) {
    float a2 = roughness * roughness * roughness * roughness;
    float NdotHSq = NdotH * NdotH;
    float denom = NdotHSq * (a2 - 1.0) + 1.0;
    return a2 / (PI * denom * denom);
}

float GGX_G1(float NdotV, float roughness) {
    float k = (roughness + 1.0) * (roughness + 1.0) * 0.125;
    return NdotV / (NdotV * (1.0 - k) + k);
}

float GGX_G(float NdotL, float NdotV, float roughness) {
    return GGX_G1(NdotL, roughness) * GGX_G1(NdotV, roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    float m = 1.0 - cosTheta;
    float m2 = m * m;
    return F0 + (1.0 - F0) * (m2 * m2 * m);
}
