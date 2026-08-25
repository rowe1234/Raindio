#version 410 core

in vec3 vaPosition;
in vec2 vaUV0;
uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;

out vec2 texCoord;
out vec3 suncolor;

uniform vec3 sunPosition;
uniform mat4 shadowModelViewInverse;

void main() {
    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
    texCoord = vaUV0;

    // 计算太阳颜色（从 shadowModelViewInverse 获取世界空间太阳方向）
    vec3 worldSunDir = normalize((shadowModelViewInverse * vec4(0.0, 0.0, 1.0, 0.0)).xyz);
    float sunHeight = worldSunDir.y;

    // 简单的太阳颜色（可根据需要调整）
    vec3 sunNight   = vec3(0.00, 0.00, 0.00);
    vec3 sunSunset  = vec3(1.00, 0.45, 0.10);
    vec3 sunNoon    = vec3(1.00, 0.95, 0.80);

    float dayFactor  = smoothstep(-0.05, 0.15, sunHeight);
    float noonFactor = smoothstep(0.00, 0.60, sunHeight);

    if (sunHeight > 0.0) {
        suncolor = mix(sunNight, mix(sunSunset, sunNoon, noonFactor), dayFactor);
    } else {
        suncolor = vec3(0.20, 0.25, 0.40) * 0.45;
    }
}
