#version 410 core

out vec3 vNormal;
out vec4 vColor;
out vec4 vTexCoord;
out vec3 vEyePos;
out vec2 vLightmap;

out vec3 vSkyColor;
out vec3 vSunlight;
out float vDayFactor;
out float vSunIntensity;

in vec3 vaPosition;
in vec4 vaColor;
in vec2 vaUV0;
in ivec2 vaUV2;
in vec3 vaNormal;

uniform vec3 sunPosition;
uniform mat4 gbufferModelViewInverse;
uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
uniform mat3 normalMatrix;

const mat4 TEXTURE_MATRIX_2 = mat4(
    vec4(0.00390625, 0.0, 0.0, 0.0),
    vec4(0.0, 0.00390625, 0.0, 0.0),
    vec4(0.0, 0.0, 0.00390625, 0.0),
    vec4(0.03125, 0.03125, 0.03125, 1.0)
);

void main() {
    vTexCoord = vec4(vaUV0, 0.0, 1.0);
    vLightmap = (TEXTURE_MATRIX_2 * vec4(vec2(vaUV2), 0.0, 1.0)).xy;
    vColor = vaColor;

    vec4 viewPos = modelViewMatrix * vec4(vaPosition, 1.0);
    vEyePos = viewPos.xyz;
    vNormal = normalize(normalMatrix * vaNormal);

    // 修改：实体顶点着色器同步降低 Y 轴
    vec3 rawSunDir = (gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz;
    rawSunDir.y *= 0.4;
    vec3 worldSunDir = normalize(rawSunDir);

    float sunHeight = worldSunDir.y;

    vDayFactor = smoothstep(-0.05, 0.15, sunHeight);
    float noonFactor = smoothstep(0.00, 0.60, sunHeight);

    const vec3 skyNight  = vec3(0.008, 0.010, 0.045);
    const vec3 skySunset = vec3(0.55, 0.25, 0.06);
    const vec3 skyNoon   = vec3(0.18, 0.35, 0.75);
    const vec3 sunNight  = vec3(0.00, 0.00, 0.00);
    const vec3 sunSunset = vec3(0.90, 0.40, 0.08);
    const vec3 sunNoon   = vec3(1.00, 0.92, 0.75);

    vSkyColor = mix(skyNight, mix(skySunset, skyNoon, noonFactor), vDayFactor);
    vSunlight = (sunHeight > 0.0) ? mix(sunNight, mix(sunSunset, sunNoon, noonFactor), vDayFactor) : vec3(0.15, 0.20, 0.35);
    vSunIntensity = (sunHeight > 0.0) ? 1.0 : 0.4;

    gl_Position = projectionMatrix * viewPos;
}