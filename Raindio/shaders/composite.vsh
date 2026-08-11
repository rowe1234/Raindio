#version 120

varying vec2 texCoord;
varying vec3 suncolor;  // 新增

uniform vec3 sunPosition;
uniform mat4 shadowModelViewInverse;

void main() {
    gl_Position = ftransform();
    texCoord = gl_MultiTexCoord0.st;

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