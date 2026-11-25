// ============================================================================
// Common utilities & unified Material IDs shared across all shader stages.
// No #version / uniforms / dependencies — safe to #include anywhere.
// Include before /lib/pbr.glsl (it relies on PI).
// ============================================================================

#define PI 3.14159265359
#ifndef SHADOW_MAP_RES
#define SHADOW_MAP_RES 4096.0
#endif

// Unified Debug View (0=OFF, 1=MatID, 2=WorldNormal, 3=SkyLight, 4=WaterNormal,
// 5=WaterRoughness, 6=WaterThickness, 7=SSR, 8=GTAO*CS, 9=ComputeGI, 10=RawHDR,
// 11=WaterSpec, 12=WaterSkyRefl, 13=WaterRefr, 14=Bloom, 15=SSR/RSM/Sky, 16=PointLights)
#ifndef DEBUG_VIEW
#define DEBUG_VIEW 0 // [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16]
#endif

// ============================================================================
// Unified Material IDs (synced with block.properties & encoded in colortex1.b)
// ============================================================================
#define MAT_TERRAIN_DEFAULT   0
#define MAT_WATER_STILL       1
#define MAT_GLASS_CLEAR       2
#define MAT_GLASS_TINTED      3
#define MAT_PLANT             4
#define MAT_METAL             5
#define MAT_THIN              6
#define MAT_ICE               7
#define MAT_SLIME             8
#define MAT_HONEY             9
#define MAT_WATER_FLOWING     10
#define MAT_WATER_PLANT       11
#define MAT_NETHER_PORTAL     12
#define MAT_BUBBLE_COLUMN     13
#define MAT_LAVA              14
#define MAT_OTHER_TRANSLUCENT 15

#define MAT_EMISSIVE_HIGH     10000
#define MAT_EMISSIVE_MED      10001

bool isMatWater(int id) {
    return id == MAT_WATER_STILL || id == MAT_WATER_FLOWING || id == MAT_BUBBLE_COLUMN;
}

bool isMatGlass(int id) {
    return id == MAT_GLASS_CLEAR || id == MAT_GLASS_TINTED;
}

bool isMatTranslucentNonWater(int id) {
    return isMatGlass(id)
        || id == MAT_ICE
        || id == MAT_SLIME
        || id == MAT_HONEY
        || id == MAT_NETHER_PORTAL
        || id == MAT_OTHER_TRANSLUCENT;
}

float saturate(float x) {
    return clamp(x, 0.0, 1.0);
}

vec3 saturate(vec3 x) {
    return clamp(x, vec3(0.0), vec3(1.0));
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

float interleavedGradientNoise(vec2 coords) {
    return fract(52.9829189 * fract(dot(coords, vec2(0.06711056, 0.00583715))));
}

// ============================================================================
// Octahedral Normal Encoding / Decoding (Unit 3D vector <-> [0, 1]^2)
// ============================================================================
vec2 octSignNotZero(vec2 v) {
    return vec2((v.x >= 0.0) ? 1.0 : -1.0, (v.y >= 0.0) ? 1.0 : -1.0);
}

vec2 octEncode(vec3 n) {
    vec3 v = normalize(n);
    vec2 p = v.xy * (1.0 / (abs(v.x) + abs(v.y) + abs(v.z) + 1e-7));
    if (v.z <= 0.0) {
        p = (1.0 - abs(p.yx)) * octSignNotZero(p);
    }
    return p * 0.5 + 0.5;
}

vec3 octDecode(vec2 e) {
    vec2 p = e * 2.0 - 1.0;
    vec3 v = vec3(p.x, p.y, 1.0 - abs(p.x) - abs(p.y));
    if (v.z < 0.0) {
        v.xy = (1.0 - abs(v.yx)) * octSignNotZero(v.xy);
    }
    return normalize(v);
}
