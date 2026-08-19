#version 120
/* DRAWBUFFERS:078 */

#define EXPOSURE 1.5           // [0.5 0.8 1.0 1.2 1.5 1.8 2.0 2.5 3.0]
#define EXP_MIN 0.2            // [0.05 0.1 0.15 0.2 0.3 0.4 0.5]
#define EXP_MAX 3.0            // [1.5 2.0 2.5 3.0 3.5 4.0 5.0]
#define BLOOM_STRENGTH 0.02    // [0.00 0.01 0.02 0.05 0.08 0.10 0.15 0.20]
#define CROSS_BLUR_STRENGTH 1.5 // [0.0 0.5 1.0 1.5 2.0 2.5 3.0]

#define SATURATION 1.0         // [0.0 0.2 0.5 0.8 1.0 1.2 1.5 2.0]
#define LUMA_GAMMA 1.0         // [0.5 0.7 0.8 0.9 1.0 1.1 1.2 1.5 2.0]
#define WHITE_CLIP 1.0         // [0.8 0.9 1.0 1.1 1.2]

#define TERRAIN_FOG_DENSITY 0.0003  // [0.0000 0.0002 0.0003 0.0005 0.0010 0.0020]

// 全局湿润地面反射强度控制
#define WET_REFLECTION_INTENSITY 0.40  // [0.10 0.20 0.30 0.40 0.50 0.60 0.80 1.00]

#define SSGI_ENABLED 1         // [0 1]
#define SSGI_STRENGTH 0.8      // [0.8 1.0 1.5 2.5 3.5 4.5 5.0]
#define SSGI_BOUNCE_BOOST 1.5  // [0.8 1.0 1.5 2.5 3.5 4.5 5.0]
#define SSGI_SAMPLES 8         // [2 4 6 8 12]
#define SSGI_STEPS 8          // [4 6 8 10 12]
#define SSGI_RADIUS 1.5        // [0.5 1.0 1.5 2.0 3.0]

varying vec2 texCoord;

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex3;
uniform sampler2D colortex4;
uniform sampler2D colortex6;
uniform sampler2D colortex7;
uniform sampler2D colortex8;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;
uniform sampler2D noisetex;

uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferModelView;
uniform mat4 gbufferPreviousProjection;
uniform mat4 gbufferPreviousModelView;

uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
uniform float rainStrength;
uniform float frametime;
uniform float frameTimeCounter;
uniform float viewWidth;
uniform float viewHeight;
uniform float near;
uniform float far;

uniform int isEyeInWater;

float saturate(float x) { return clamp(x, 0.0, 1.0); }

const float ATM_R       = 1.0;
const float ATM_R_INNER = 0.985;
const float ATM_SCALE_H = 4.0 / (ATM_R - ATM_R_INNER);
const float ATM_SCALE_L = 1.0 / (ATM_R - ATM_R_INNER);
const float ATM_K_R     = 0.166;
const float ATM_K_M     = 0.0025;
const float ATM_E       = 14.3;
const vec3  ATM_C_R     = vec3(0.3, 0.52, 1.0);
const vec3  ATM_EYE     = vec3(0.0, ATM_R_INNER + 0.00025, 0.0);

float phase_rayleigh(float cc) { return 0.75 * (1.0 + cc); }

float phase_mie_opt(float c, float cc) {
    float b = max(1.7225 + 1.7 * c, 0.0001);
    return (0.15289256 * (1.0 + cc)) / (b * sqrt(b));
}

float atm_density(vec3 p) { return exp(-(length(p) - ATM_R_INNER) * ATM_SCALE_H) * 2.0; }

vec2 RaySphereIntersect(vec3 p, vec3 dir, float r) {
    float b = dot(p, dir);
    float d = b * b - dot(p, p) + r * r;
    if (d < 0.0) return vec2(-1.0);
    d = sqrt(d);
    return vec2(-b - d, -b + d);
}

float atm_optic(vec3 p, vec3 q) {
    vec3 step = (q - p) * 0.25;
    vec3 v = p + step * 0.5;
    float sum = 0.0;
    for (int i = 0; i < 4; i++) { 
        sum += atm_density(v); 
        v += step; 
    }
    return sum * length(step) * ATM_SCALE_L;
}

vec3 atm_in_scatter(vec3 o, vec3 dir, vec2 e, vec3 l, float mie, float rayAmt) {
    if (e.y < 0.0) return vec3(0.0);

    float boosty = saturate(l.y + 0.1) * 0.95 + 0.05;
    boosty = clamp(1.0 / sin(boosty), 1.0, 2.0);
    float len = e.y * 0.125;
    vec3 step = dir * (len * 2.0);
    vec3 p = o; 
    vec3 v = p + dir * (len * 0.5);
    
    float rayK = ATM_K_R * rayAmt;
    float mieK = ATM_K_M * 0.045 * mie;
    vec3 rayMieVec = rayK * ATM_C_R + vec3(mieK);

    vec3 sum = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        vec2 f = RaySphereIntersect(v, l, ATM_R);
        if (f.y > 0.0) {
            float n = (atm_optic(p, v) + atm_optic(v, v + l * f.y)) * 12.56637;
            sum += atm_density(v) * exp(-n * rayMieVec);
        }
        v += step;
    }
    sum *= len * ATM_SCALE_L;
    float c = dot(dir, -l);
    float cc = c * c;
    return sum * (rayK * ATM_C_R * phase_rayleigh(cc) + mieK * phase_mie_opt(c, cc)) * (ATM_E * boosty);
}

vec3 AtmosphericScattering(vec3 rayDir, vec3 lightVector, float mieAmount) {
    vec3 dir = rayDir;
    if (dir.y < 0.0) dir.y = 0.0;
    vec2 e = RaySphereIntersect(ATM_EYE, dir, ATM_R);
    vec3 atmosphere = atm_in_scatter(ATM_EYE, dir, e, lightVector, mieAmount, 1.0) * vec3(0.78, 0.85, 1.0);
    return max(atmosphere, vec3(0.0));
}

float SunDisc(vec3 worldDir, vec3 sunDir) {
    float d = dot(worldDir, sunDir);
    float disc = saturate((d - 0.99805) * 1000.0);
    disc = pow(disc * disc * (3.0 - 2.0 * disc), 2.0);
    return disc * saturate(worldDir.y * 30.0);
}

vec3 ACESFilm(vec3 x) {
    x *= 0.9;
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec3 ScreenToView(vec2 uv, float depth) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = gbufferProjectionInverse * ndc;
    return view.xyz / view.w;
}

vec3 apply_terrain_fog(vec3 color, vec3 viewPos) {
    float viewDist = length(viewPos);
    vec3 worldDir = normalize((gbufferModelViewInverse * vec4(normalize(viewPos), 0.0)).xyz);
    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    bool isDay = realSunDir.y > 0.0;

    vec3 fogAtmColor = isDay ? AtmosphericScattering(worldDir, realSunDir, 0.5) : 
                               AtmosphericScattering(worldDir, -realSunDir, 0.1) * 0.02 + vec3(0.001, 0.005, 0.018);

    fogAtmColor *= mix(1.0, 0.30, saturate(rainStrength));
    float dynamicFogDensity = mix(TERRAIN_FOG_DENSITY, TERRAIN_FOG_DENSITY * 3.5, saturate(rainStrength));

    float fogFactor = 1.0 - exp(-viewDist * dynamicFogDensity);
    return mix(color, fogAtmColor, fogFactor);
}

#define SEA_HEIGHT 0.65
#define SEA_CHOPPY 7.5
#define SEA_SPEED 0.85
#define SEA_FREQ 0.25

float noise(vec2 p) {
    return texture2D(noisetex, p * 0.00390625).r * 2.0 - 1.0;
}

float sea_octave_micro(vec2 uv, float choppy) {
    uv += noise(uv);
    vec2 wv = 1.0 - abs(sin(uv));
    vec2 swv = abs(cos(uv));
    wv = mix(wv, swv, wv);
    float baseVal = max(1.0 - pow(max(wv.x * wv.y, 0.0), 0.75), 0.0);
    return pow(baseVal, choppy);
}

const mat2 octave_m = mat2(1.4, 1.1, -1.2, 1.4);
const float height_mul[4] = float[4](0.32, 0.24, 0.20, 0.22);
const float rcp_total_height = 2.37898;

float getwave2(vec3 p, float lod) {
    float freq = SEA_FREQ;
    float amp = SEA_HEIGHT;
    float choppy = SEA_CHOPPY;
    vec2 uv = p.xz - vec2(frameTimeCounter * 0.5, 0.0); 
    uv.x *= 0.75;
    float wave_speed = frameTimeCounter * SEA_SPEED;
    float h = 0.0;
    for (int i = 0; i < 4; i++) {
        float d = sea_octave_micro((uv + wave_speed) * freq, choppy);
        h += d * amp;
        uv *= octave_m; 
        freq *= 1.9; 
        amp *= height_mul[i]; 
        wave_speed *= -1.1;
        choppy = mix(choppy, 1.0, 0.2);
    }
    return h * rcp_total_height * lod;
}

vec3 get_water_normal(vec3 wwpos, float lod) {
    const float eps = 0.05;
    float h0 = getwave2(wwpos, lod);
    float hx = getwave2(wwpos + vec3(eps, 0.0, 0.0), lod);
    float hz = getwave2(wwpos + vec3(0.0, 0.0, eps), lod);

    vec2 slope = vec2(h0 - hx, h0 - hz) / eps;
    float bumpStrength = 0.18;

    return normalize(vec3(slope.x * bumpStrength, 1.0, slope.y * bumpStrength));
}

vec4 ray_trace_ssr(vec3 direction, vec3 start, float dither) {
    float rayLength = length(start);
    vec3 testPoint = start + direction * (0.05 + 0.02 * dither);
    
    float stepLen = 0.08 + rayLength * 0.005;
    vec2 invProjDiag = vec2(gbufferProjectionInverse[0][0], gbufferProjectionInverse[1][1]);

    for (int i = 0; i < 28; i++) {
        testPoint += direction * stepLen;
        if (testPoint.z >= -0.1) break;

        vec2 uv = (testPoint.xy / (-testPoint.z * invProjDiag)) * 0.5 + 0.5;
        
        vec2 edge = smoothstep(vec2(0.0), vec2(0.04), uv) * smoothstep(vec2(1.0), vec2(0.96), uv);
        float edgeAlpha = edge.x * edge.y;
        if (edgeAlpha <= 0.001) break;

        float rawDepth0 = texture2D(depthtex0, uv).r;
        if (rawDepth0 < 0.9999) {
            vec3 sv = ScreenToView(uv, rawDepth0);
            float diff = sv.z - testPoint.z;

            float maxThickness = stepLen * 1.5 + 0.10;
            if (diff > -0.05 && diff < maxThickness) {
                float rawDepth1 = texture2D(depthtex1, uv).r;
                if (abs(rawDepth1 - rawDepth0) < 0.001) {
                    vec3 hitColor = max(vec3(0.0), texture2D(colortex0, uv).rgb);
                    hitColor = apply_terrain_fog(hitColor, sv);

                    float depthWeight = smoothstep(maxThickness, 0.0, max(0.0, diff));
                    return vec4(hitColor, edgeAlpha * depthWeight);
                }
            }
            stepLen *= 1.05;
        } else {
            stepLen *= 1.10;
        }
    }
    return vec4(0.0);
}

vec3 ray_trace_ssgi(vec3 startViewPos, vec3 rayDir, vec3 surfaceNormal, vec2 invProjDiag, float dither) {
    vec3 testPoint = startViewPos + surfaceNormal * (0.08 + 0.02 * length(startViewPos));
    float stepLen = SSGI_RADIUS / float(SSGI_STEPS);
    testPoint += rayDir * (stepLen * (0.1 + 0.9 * dither));
    
    for (int i = 0; i < SSGI_STEPS; i++) {
        if (testPoint.z >= -0.1) break;

        vec2 sampleUV = (testPoint.xy / (-testPoint.z * invProjDiag)) * 0.5 + 0.5;
        if (sampleUV.x <= 0.005 || sampleUV.x >= 0.995 || sampleUV.y <= 0.005 || sampleUV.y >= 0.995) break;

        float rawDepth = texture2D(depthtex0, sampleUV).r;
        if (rawDepth < 0.9999) {
            vec3 sampleViewPos = ScreenToView(sampleUV, rawDepth);
            float diff = sampleViewPos.z - testPoint.z;

            if (diff > -0.05 && diff < (stepLen * 1.2 + 0.12)) {
                vec2 sampleEncN = texture2D(colortex1, sampleUV).rg;
                vec2 sn2 = sampleEncN * 2.0 - 1.0;
                float snz = sqrt(max(0.0, 1.0 - dot(sn2, sn2)));
                vec3 sampleNormal = normalize(vec3(sn2, snz));

                float normalWeight = max(0.0, dot(sampleNormal, -rayDir));
                float dist = length(sampleViewPos - startViewPos);
                float distFade = max(0.0, 1.0 - (dist / SSGI_RADIUS));

                vec3 hitColor = max(vec3(0.0), texture2D(colortex0, sampleUV).rgb);
                vec2 edge = smoothstep(vec2(0.0), vec2(0.08), sampleUV) * smoothstep(vec2(1.0), vec2(0.92), sampleUV);
                
                return hitColor * edge.x * edge.y * normalWeight * distFade * SSGI_BOUNCE_BOOST;
            }
        }
        testPoint += rayDir * stepLen;
    }
    return vec3(0.0);
}

void main() {
    vec2 uv = texCoord;
    float waterDepth  = texture2D(depthtex0, uv).r; 
    float seabedDepth = texture2D(depthtex1, uv).r; 
    vec3 hdr = texture2D(colortex0, uv).rgb;

    float ssrDither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);

    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    vec3 viewSunDir = normalize((gbufferModelView * vec4(realSunDir, 0.0)).xyz);

    float sunHeight = realSunDir.y;
    vec3 sunColor = mix(vec3(1.0, 0.4, 0.05), vec3(1.0, 0.98, 0.85), saturate(sunHeight * 2.5));

    float nightDim = mix(0.05, 1.0, saturate(sunHeight * 4.0 + 0.5));

    bool isDay = realSunDir.y > 0.0;
    vec3 mainLightViewDir = isDay ? viewSunDir : -viewSunDir;
    vec3 mainLightColor   = isDay ? sunColor : vec3(0.4, 0.65, 1.0);
    float mainLightVis    = isDay ? saturate(realSunDir.y * 3.0) : saturate(-realSunDir.y * 3.0);
    float mainLightPower  = isDay ? 80.0 : 18.0;

    bool isWater = (seabedDepth - waterDepth > 0.00005) && (waterDepth < 0.9999);
    bool underwater = (isEyeInWater == 1);

    if (isWater) {
        vec3 waterViewPos  = ScreenToView(uv, waterDepth);
        vec3 seabedViewPos = ScreenToView(uv, seabedDepth);
        vec3 waterViewDir  = normalize(waterViewPos);

        float viewDist = length(waterViewPos);
        float waveLod = clamp(1.0 - viewDist * 0.005, 0.15, 1.0);

        float waterThickness = max(0.0, abs(seabedViewPos.z - waterViewPos.z));
        vec3 waterWorldPos = (gbufferModelViewInverse * vec4(waterViewPos, 1.0)).xyz + cameraPosition;

        vec3 flatWorldNormal = underwater ? vec3(0.0, -1.0, 0.0) : vec3(0.0, 1.0, 0.0);
        vec3 flatViewNormal  = normalize(mat3(gbufferModelView) * flatWorldNormal);
        float flatNdotV      = max(0.0, dot(flatViewNormal, -waterViewDir));

        vec3 rawWorldNormal = get_water_normal(waterWorldPos, waveLod);
        if (underwater) rawWorldNormal.y = -rawWorldNormal.y;

        float waveWeight = smoothstep(0.01, 0.22, flatNdotV) * clamp(1.0 - viewDist * 0.003, 0.2, 1.0);
        vec3 waterWorldNormal = normalize(mix(flatWorldNormal, rawWorldNormal, waveWeight));
        vec3 waterNormal = normalize(mat3(gbufferModelView) * waterWorldNormal);

        float NdotV_raw = dot(waterNormal, -waterViewDir);
        if (NdotV_raw < 0.001) {
            waterNormal = normalize(waterNormal - waterViewDir * (0.001 - NdotV_raw));
        }

        vec3 reflectViewDir = normalize(reflect(waterViewDir, waterNormal));
        vec3 startWaterPos = waterViewPos + waterNormal * (0.08 + 0.02 * length(waterViewPos));
        vec4 ssr = ray_trace_ssr(reflectViewDir, startWaterPos, ssrDither);
        vec3 reflection = ssr.rgb;

        if (ssr.a < 0.95) {
            if (!underwater) {
                vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflectViewDir, 0.0)).xyz);
                vec3 skyReflDir = reflectWorldDir;
                skyReflDir.y = max(skyReflDir.y, 0.08);

                vec3 skyRefl;
                if (isDay) {
                    skyRefl = AtmosphericScattering(skyReflDir, realSunDir, 1.0);
                    vec3 zenithSky = AtmosphericScattering(vec3(skyReflDir.x, 0.45, skyReflDir.z), realSunDir, 1.0);
                    float horizonFade = smoothstep(0.25, 0.02, reflectWorldDir.y);
                    skyRefl = mix(skyRefl, zenithSky * 0.85, horizonFade * 0.5);
                } else {
                    vec3 moonWorldDir = -realSunDir;
                    skyRefl = AtmosphericScattering(skyReflDir, moonWorldDir, 0.1) * 0.05 + vec3(0.001, 0.005, 0.018) * nightDim;
                    float moonDiscInRefl = SunDisc(skyReflDir, moonWorldDir);
                    skyRefl += moonDiscInRefl * vec3(0.5, 0.75, 1.0) * 4.0 * mainLightVis;
                }
                
                float horizonFade = smoothstep(-0.25, 0.12, reflectWorldDir.y);
                vec3 groundAmbient = vec3(0.005, 0.012, 0.02) * nightDim;
                skyRefl = mix(groundAmbient, skyRefl, horizonFade);

                reflection = mix(skyRefl, ssr.rgb, ssr.a);
            } else {
                reflection = mix(vec3(0.005, 0.03, 0.07) * nightDim, ssr.rgb, ssr.a);
            }
        }

        vec2 refractOffset = waterNormal.xy * 0.02 * clamp(waterThickness, 0.0, 1.5);
        vec2 refractUV = clamp(uv + refractOffset, vec2(0.001), vec2(0.999));
        if (texture2D(depthtex1, refractUV).r <= waterDepth) refractUV = uv;
        vec3 refractionColor = texture2D(colortex0, refractUV).rgb;

        float NdotV = max(0.0, dot(waterNormal, -waterViewDir));
        float fresnel = clamp(0.02 + 0.98 * pow(max(1.0 - NdotV, 0.0), 5.0), 0.02, 0.82);

        if (underwater) {
            hdr = mix(refractionColor, reflection, fresnel);
        } else {
            vec3 absorb = exp(vec3(-0.65, -0.25, -0.10) * waterThickness);
            vec3 waterColor = refractionColor * absorb;

            float fogFactor = 1.0 - exp(-waterThickness * 0.22);
            vec3 waterFogColor = vec3(0.002, 0.02, 0.05) * nightDim;
            vec3 refractedColor = mix(waterColor, waterFogColor, fogFactor);

            hdr = mix(refractedColor, reflection, fresnel);

            vec3 halfVec = normalize(-waterViewDir + mainLightViewDir);
            float NdotH = max(0.0, dot(waterNormal, halfVec));
            
            float specPower = isDay ? 2048.0 : 4096.0;
            float specIntensity = isDay ? 1.0 : 0.35;
            float specPosition = pow(NdotH, specPower); 

            vec3 specColor = mix(vec3(1.0), mainLightColor, 0.3);
            float horizonSpecFade = smoothstep(0.0, 0.15, NdotV);

            hdr += specColor * specPosition * horizonSpecFade * (mainLightPower * mainLightVis * specIntensity * (1.0 - rainStrength));
        }
    }

// SSGI 计算
vec3 accumulatedSSGI = vec3(0.0);

#if SSGI_ENABLED == 1
    if (waterDepth < 0.9999 && !isWater) {
        vec3 viewPos = ScreenToView(uv, waterDepth);
        
        vec2 encodedN = texture2D(colortex1, uv).rg;
        vec2 n2 = encodedN * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
        vec3 viewNormal = normalize(vec3(n2, nz));

        vec3 tangent = normalize(abs(viewNormal.z) < 0.999 ? cross(vec3(0.0, 0.0, 1.0), viewNormal) : cross(vec3(1.0, 0.0, 0.0), viewNormal));
        vec3 bitangent = cross(viewNormal, tangent);
        mat3 tbn = mat3(tangent, bitangent, viewNormal);

        vec2 invProjDiag = vec2(gbufferProjectionInverse[0][0], gbufferProjectionInverse[1][1]);
        
        float frameTimeMod = mod(frameTimeCounter, 100.0);
        float dither = fract(52.9829189 * fract(dot(gl_FragCoord.xy + vec2(frameTimeMod * 13.0, frameTimeMod * 17.0), vec2(0.06711056, 0.00583715))));

        vec3 sumSSGI = vec3(0.0);
        vec3 sumSqSSGI = vec3(0.0);

        for (int i = 0; i < SSGI_SAMPLES; i++) {
            float fi = float(i);
            float fi_n = (fi + dither) / float(SSGI_SAMPLES);
            float phi = fi_n * 6.28318530718;
            float cosTheta = sqrt(1.0 - fi_n);
            float sinTheta = sqrt(fi_n);

            vec3 localDir = vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
            vec3 sampleDir = normalize(tbn * localDir);

            vec3 sampleResult = ray_trace_ssgi(viewPos, sampleDir, viewNormal, invProjDiag, dither);
            sumSSGI += sampleResult;
            sumSqSSGI += sampleResult * sampleResult;
        }
        
        vec3 rawSSGI = sumSSGI / float(SSGI_SAMPLES);
        vec3 mean = rawSSGI;
        vec3 sigma = sqrt(max(vec3(0.0), (sumSqSSGI / float(SSGI_SAMPLES)) - mean * mean));

        vec3 cameraOffset = cameraPosition - previousCameraPosition;
        vec3 worldPos = (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz + cameraOffset;
        vec4 prevViewPos = gbufferPreviousModelView * vec4(worldPos, 1.0);
        vec4 prevClipPos = gbufferPreviousProjection * prevViewPos;
        vec2 prevUV = (prevClipPos.xy / prevClipPos.w) * 0.5 + 0.5;

        accumulatedSSGI = rawSSGI;
        if (prevUV.x > 0.001 && prevUV.x < 0.999 && prevUV.y > 0.001 && prevUV.y < 0.999) {
            vec3 historySSGI = texture2D(colortex8, prevUV).rgb;

            vec3 minAllowed = mean - 1.2 * sigma;
            vec3 maxAllowed = mean + 2.0 * sigma + vec3(0.01);
            vec3 clampedHistory = clamp(historySSGI, max(vec3(0.0), minAllowed), maxAllowed);
            
            float velocity = length(prevUV - uv);
            float currentFrameWeight = mix(0.15, 0.65, clamp(velocity * 40.0, 0.0, 1.0));

            if (dot(historySSGI, vec3(1.0)) < 0.0001) {
                accumulatedSSGI = rawSSGI;
            } else {
                accumulatedSSGI = mix(clampedHistory, rawSSGI, currentFrameWeight);
            }
        } else {
            accumulatedSSGI = rawSSGI;
        }
        hdr += accumulatedSSGI * SSGI_STRENGTH;
    }
#endif

    if (waterDepth < 0.9999 && !isWater) {
        vec3 terrainViewPos = ScreenToView(uv, waterDepth);
        hdr = apply_terrain_fog(hdr, terrainViewPos);
    }

    // =================【全地面湿润效果（已修正洞穴盲反射问题）】=================
    if (waterDepth < 0.9999 && !isWater) {
        float skyLight = texture2D(colortex1, uv).a;

        vec2 encodedN = texture2D(colortex1, uv).rg;
        vec2 n2 = encodedN * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
        vec3 viewNormal = normalize(vec3(n2, nz));
        vec3 worldNormal = normalize((gbufferModelViewInverse * vec4(viewNormal, 0.0)).xyz);
        
        float isFloor = smoothstep(0.30, 0.70, worldNormal.y);
        
        // 【修正 1】：提高天空光判断门槛（0.85 ~ 0.98），确保洞穴深处接收到的微弱散射天空光不会被误判为露天
        float skyLightFactor = smoothstep(0.85, 0.98, skyLight);
        float wetness = clamp(rainStrength, 0.0, 1.0) * skyLightFactor * isFloor;

        if (wetness > 0.01) {
            vec3 viewPos = ScreenToView(uv, waterDepth);
            vec3 viewDir = normalize(viewPos);

            // 地面暗化程度调柔和（0.88），防止过度变黑
            hdr *= mix(1.0, 0.88, wetness);

            // 使用稳定起点，避免 NdotV 导致的极化拉扯
            float NdotV = max(0.0, dot(viewNormal, -viewDir));
            vec3 startPos = viewPos + viewNormal * (0.06 + 0.02 * length(viewPos));

            vec3 reflectViewDir = normalize(reflect(viewDir, viewNormal));
            vec4 ssr = ray_trace_ssr(reflectViewDir, startPos, ssrDither);

            vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflectViewDir, 0.0)).xyz);
            reflectWorldDir.y = max(reflectWorldDir.y, 0.05);

            vec3 skyRefl = AtmosphericScattering(reflectWorldDir, realSunDir, 0.8);
            
            // 【修正 2】：回退环境天光相乘 skyLightFactor 遮罩，即便 SSR 追踪失败，在无光/半闭合空间内也会衰减为 0，不再亮如白昼
            vec3 rainSkyAmbient = mix(vec3(0.35, 0.40, 0.48), skyRefl, 0.6) * 0.85 * skyLightFactor; 
            vec3 wetReflection = mix(rainSkyAmbient, ssr.rgb, ssr.a);

            float fresnel = clamp(mix(0.04, 0.35, pow(max(1.0 - NdotV, 0.0), 3.0)), 0.04, 0.35);
            float reflectFactor = fresnel * wetness * WET_REFLECTION_INTENSITY;

            hdr = mix(hdr, wetReflection, reflectFactor);
        }
    }
    // =====================================================================
    // =====================================================================

    vec3 rawBloom = texture2D(colortex3, uv).rgb + texture2D(colortex4, uv).rgb;
    vec3 bloom = mix(rawBloom, rawBloom * sunColor, 0.10);
    hdr += bloom * BLOOM_STRENGTH; 

    float rawLum = 0.0;
    rawLum += texture2D(colortex6, vec2(0.50, 0.50)).r * 0.40;
    rawLum += texture2D(colortex6, vec2(0.46, 0.46)).r * 0.15;
    rawLum += texture2D(colortex6, vec2(0.54, 0.54)).r * 0.15;
    rawLum += texture2D(colortex6, vec2(0.46, 0.54)).r * 0.15;
    rawLum += texture2D(colortex6, vec2(0.54, 0.46)).r * 0.15;
    rawLum = max(rawLum, 0.0001);

    float prevLum = texture2D(colortex7, vec2(0.5)).r;
    if (prevLum < 0.001) prevLum = rawLum;
    float smoothLum = prevLum + (rawLum - prevLum) * (1.0 - exp(-frametime * 3.0));
    float nightFactor = clamp(1.0 - smoothLum * 3.0, 0.0, 1.0);
    
    float rainDarken = mix(1.0, 0.45, saturate(rainStrength));
    float exposure = clamp(mix(0.18, 0.12, nightFactor) / smoothLum, EXP_MIN, EXP_MAX) * EXPOSURE * rainDarken;

    float nightTerrainBoost = mix(2.2, 1.0, saturate(sunHeight * 4.0 + 0.5));
    vec3 terrainResult = ACESFilm(hdr * exposure * nightTerrainBoost);

    vec4 ndc = vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec4 vp4 = gbufferProjectionInverse * ndc;
    vec3 viewDir = normalize(vp4.xyz / vp4.w);
    vec3 worldDir = normalize((gbufferModelViewInverse * vec4(viewDir, 0.0)).xyz);

    vec3 skyHDR = (realSunDir.y > -0.05) ? 
        AtmosphericScattering(worldDir, realSunDir, 1.0) * mix(1.0, 0.35, saturate(rainStrength)) : 
        AtmosphericScattering(worldDir, -realSunDir, 0.1) * 0.02 + vec3(0.001, 0.005, 0.018);

    float horizonFactor = saturate(sunHeight * 3.0);
    float sd = dot(worldDir, realSunDir);
    float sunSize = 0.00195 * mix(0.5, 1.0, horizonFactor);
    float sunDiscMask = saturate((sd - (1.0 - sunSize)) * 1000.0 * mix(0.3, 1.0, horizonFactor));
    sunDiscMask = pow(sunDiscMask * sunDiscMask * (3.0 - 2.0 * sunDiscMask), 2.0);
    float sunGlow = exp(-(1.0 - sd) * mix(300.0, 50.0, horizonFactor));

    skyHDR += (sunDiscMask * sunColor * 12.0 + sunGlow * sunColor * 1.5) * smoothstep(0.0, 0.3, sunHeight) * saturate(sunHeight * 10.0) * (1.0 - rainStrength);
    if (realSunDir.y < 0.0) skyHDR += SunDisc(worldDir, -realSunDir) * vec3(0.245, 0.2625, 0.315);

    vec3 skyResult = ACESFilm(max(skyHDR, vec3(0.0)) * exposure);

    float skyFactor = smoothstep(0.998, 0.9995, waterDepth);
    vec3 result = mix(terrainResult, skyResult, skyFactor);

    float cn = length(result);
    if (cn > 0.0001) result *= pow(cn, 0.0526316);

    result = clamp(result * 1.02, 0.0, 1.0);
    float lum = dot(result, vec3(0.299, 0.587, 0.114));
    result = mix(vec3(lum), result, SATURATION);
    result += fract(sin(dot(texCoord, vec2(12.9898, 78.233))) * 43758.5453) / 255.0;

    gl_FragData[0] = vec4(result, 1.0);
    gl_FragData[1] = vec4(vec3(smoothLum), 1.0);
    gl_FragData[2] = vec4(accumulatedSSGI, 1.0);
}