// ============================================================================
// Common utilities shared across shader stages.
// No #version / uniforms / dependencies — safe to #include anywhere.
// Include before /lib/pbr.glsl (it relies on PI).
// ============================================================================

#define PI 3.14159265359

float saturate(float x) {
    return clamp(x, 0.0, 1.0);
}

float luminance(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

float interleavedGradientNoise(vec2 coords) {
    return fract(52.9829189 * fract(dot(coords, vec2(0.06711056, 0.00583715))));
}
