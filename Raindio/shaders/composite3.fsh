#version 410 core
/* DRAWBUFFERS:078 */

#define EXPOSURE 1.5           
#define EXP_MIN 0.2            
#define EXP_MAX 3.0            
#define BLOOM_STRENGTH 0.02    
#define CROSS_BLUR_STRENGTH 1.5 

#define SATURATION 1.0         
#define LUMA_GAMMA 1.0         
#define WHITE_CLIP 1.0         

#define TERRAIN_FOG_DENSITY 0.0003  
#define WET_REFLECTION_INTENSITY 0.40  

#define SSGI_ENABLED 1         
#define SSGI_STRENGTH 0.8      
#define SSGI_BOUNCE_BOOST 1.5  
#define SSGI_SAMPLES 8         
#define SSGI_STEPS 8          
#define SSGI_RADIUS 1.5        

#define BLOCK_ID_WATER 1       
#define BLOCK_ID_GLASS 2       

const bool CLOUD2D_ENABLED = true;

in vec2 texCoord;

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2; 
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

uniform sampler2D shadowtex0;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;

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

#define VL_STEPS 32                  
#define VL_MAX_DIST 120.0            
#define VOLUMETRIC_FOG_BASE_DENSITY 0.002   
#define VOLUMETRIC_FOG_HEIGHT_FALLOFF 0.015 
#define VOLUMETRIC_FOG_HEIGHT_BASE 64.0   

float hgPhase(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (12.5663706 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

float getSampleShadow(vec3 viewPos, mat4 shadowMat) {
    vec4 shadowClip = shadowMat * vec4(viewPos, 1.0);
    vec3 shadowNDC = shadowClip.xyz / shadowClip.w;

    float l = length(shadowNDC.xy);
    if (l > 0.00001) {
        float distortFactor = l * 0.8 + 0.2;
        shadowNDC.xy /= distortFactor;
    }

    vec3 shadowCoord = shadowNDC * 0.5 + 0.5;

    if (shadowCoord.x < 0.001 || shadowCoord.x > 0.999 ||
        shadowCoord.y < 0.001 || shadowCoord.y > 0.999 ||
        shadowCoord.z < 0.001 || shadowCoord.z > 1.000) {
        return 1.0;
    }

    vec2 texelSize = vec2(1.0 / 4096.0); 
    float shadow = 0.0;
    float bias = 0.0015;

    shadow += (shadowCoord.z - bias > texture(shadowtex0, shadowCoord.xy + vec2(-0.5, -0.5) * texelSize).r) ? 0.0 : 1.0;
    shadow += (shadowCoord.z - bias > texture(shadowtex0, shadowCoord.xy + vec2( 0.5, -0.5) * texelSize).r) ? 0.0 : 1.0;
    shadow += (shadowCoord.z - bias > texture(shadowtex0, shadowCoord.xy + vec2(-0.5,  0.5) * texelSize).r) ? 0.0 : 1.0;
    shadow += (shadowCoord.z - bias > texture(shadowtex0, shadowCoord.xy + vec2( 0.5,  0.5) * texelSize).r) ? 0.0 : 1.0;

    return shadow * 0.25;
}

float getVolumetricFogDensity(vec3 worldPos) {
    float heightDiff = worldPos.y - VOLUMETRIC_FOG_HEIGHT_BASE;
    float density = VOLUMETRIC_FOG_BASE_DENSITY * exp(-max(0.0, heightDiff) * VOLUMETRIC_FOG_HEIGHT_FALLOFF);
    return density * mix(1.0, 3.5, saturate(rainStrength));
}

#define SEA_HEIGHT 0.65
#define SEA_CHOPPY 7.5
#define SEA_SPEED 0.85
#define SEA_FREQ 0.25

float noise(vec2 p) {
    return texture(noisetex, p * 0.00390625).r * 2.0 - 1.0;
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

vec4 CalculateVolumetricFogAndLight(vec3 viewPos, float dither, vec3 mainLightViewDir, vec3 mainLightColor, float mainLightVis, float mainLightPower) {
    float rayLength = length(viewPos);
    float maxDist = min(rayLength, VL_MAX_DIST);
    vec3 rayDir = viewPos / rayLength; // 优化 normalize 操作

    vec3 accumulatedLight = vec3(0.0);
    float transmittance = 1.0;

    float cosTheta = dot(rayDir, mainLightViewDir);
    float phase = mix(hgPhase(cosTheta, 0.6), hgPhase(cosTheta, -0.3), 0.25);

    float prevDist = 0.0;

    // 优化：将循环内高昂的矩阵乘法移至外部预计算
    mat4 shadowMat = shadowProjection * shadowModelView * gbufferModelViewInverse;
    vec3 worldStartPos = (gbufferModelViewInverse * vec4(0.0, 0.0, 0.0, 1.0)).xyz + cameraPosition;
    vec3 worldRayDir = (gbufferModelViewInverse * vec4(rayDir, 0.0)).xyz;

    for (int i = 0; i < VL_STEPS; i++) {
        float progress = (float(i) + dither) / float(VL_STEPS);
        float currentDist = maxDist * pow(progress, 1.25);
        
        float stepLen = currentDist - prevDist;
        prevDist = currentDist;

        if (currentDist > maxDist) break;

        vec3 currentViewPos = rayDir * currentDist;
        vec3 worldPos = worldStartPos + worldRayDir * currentDist; // 极大提升性能，省略了内部矩阵乘法
        float density = getVolumetricFogDensity(worldPos);

        if (density > 0.00001) {
            float shadowVis = getSampleShadow(currentViewPos, shadowMat); // 传入预计算矩阵

            vec3 directScattering = mainLightColor * phase * shadowVis * mainLightVis * (mainLightPower * 0.35);
            vec3 ambientScattering = mainLightColor * 0.2 + vec3(0.0034, 0.0139, 0.0034);

            vec3 stepScattering = (directScattering + ambientScattering) * density * stepLen;
            float stepExtinction = exp(-density * stepLen * 1.5);

            accumulatedLight += stepScattering * transmittance;
            transmittance *= stepExtinction;

            if (transmittance < 0.01) break;
        }
    }

    return vec4(accumulatedLight, transmittance);
}

vec4 CalculateUnderwaterVolumetricLight(vec3 startViewPos, vec3 endViewPos, float dither, vec3 mainLightViewDir, vec3 realSunDir, vec3 mainLightColor, float mainLightVis, float mainLightPower) {
    vec3 rayVec = endViewPos - startViewPos;
    float rayLength = length(rayVec);
    if (rayLength < 0.001) return vec4(0.0, 0.0, 0.0, 1.0);

    float maxDist = min(rayLength, VL_MAX_DIST);
    vec3 rayDir = rayVec / rayLength;

    vec3 accumulatedLight = vec3(0.0);
    float transmittance = 1.0;

    float cosTheta = dot(rayDir, mainLightViewDir);
    float phase = mix(hgPhase(cosTheta, 0.65), hgPhase(cosTheta, -0.25), 0.20);

    float prevDist = 0.0;
    vec3 waterScatterColor = vec3(0.20, 0.65, 0.95); 

    bool isDay = realSunDir.y > 0.0;
    vec3 lightWorldDir = isDay ? normalize(realSunDir) : normalize(-realSunDir);

    float waterSurfaceY = max(cameraPosition.y, 62.0) + 1.0;

    // 优化：同样将矩阵移出循环
    mat4 shadowMat = shadowProjection * shadowModelView * gbufferModelViewInverse;
    vec3 worldStartPos = (gbufferModelViewInverse * vec4(startViewPos, 1.0)).xyz + cameraPosition;
    vec3 worldRayDir = (gbufferModelViewInverse * vec4(rayDir, 0.0)).xyz;

    for (int i = 0; i < VL_STEPS; i++) {
        float progress = (float(i) + dither) / float(VL_STEPS);
        float currentDist = maxDist * pow(progress, 1.25);
        
        float stepLen = currentDist - prevDist;
        prevDist = currentDist;

        if (currentDist > maxDist) break;

        vec3 currentViewPos = startViewPos + rayDir * currentDist;
        vec3 worldPos = worldStartPos + worldRayDir * currentDist; // 优化矩阵乘法

        float heightToSurface = max(0.0, waterSurfaceY - worldPos.y);
        float rayLengthToSurface = heightToSurface / max(lightWorldDir.y, 0.08);
        vec3 surfaceHitPos = worldPos + lightWorldDir * rayLengthToSurface;

        float waveCaustic = getwave2(surfaceHitPos, 1.0);
        float lightBeamMask = pow(clamp(waveCaustic * 1.8 + 0.1, 0.0, 1.0), 3.0);

        float depthFade = exp(-heightToSurface * 0.05);
        lightBeamMask = mix(0.1, lightBeamMask, depthFade);

        float shadowVis = getSampleShadow(currentViewPos, shadowMat); // 传入预计算矩阵

        float density = 0.018; 
        float lightIntensity = mainLightPower * 0.10 * mainLightVis; 
        vec3 directScattering = mainLightColor * waterScatterColor * phase * shadowVis 
                               * lightIntensity * (0.05 + 0.95 * lightBeamMask);
        vec3 ambientScattering = waterScatterColor * 0.008 * mainLightVis;

        vec3 stepScattering = (directScattering + ambientScattering) * density * stepLen;
        accumulatedLight += stepScattering * transmittance;
        
        transmittance *= exp(-density * stepLen * 1.2);

        if (transmittance < 0.01) break;
    }

    return vec4(accumulatedLight, transmittance);
}

vec3 apply_underwater_fog(vec3 color, vec3 viewPos, float nightDim) {
    float dist = length(viewPos);
    vec3 absorb = exp(vec3(-0.08, -0.04, -0.02) * dist);
    vec3 absorbedColor = color * absorb;
    float fogFactor = 1.0 - exp(-dist * 0.025);
    vec3 waterFogColor = vec3(0.01, 0.08, 0.15) * nightDim;
    return mix(absorbedColor, waterFogColor, fogFactor);
}

vec3 get_water_normal(vec3 wwpos, float lod, bool underwater) {
    const float eps = 0.05;
    float h0 = getwave2(wwpos, lod);
    float hx = getwave2(wwpos + vec3(eps, 0.0, 0.0), lod);
    float hz = getwave2(wwpos + vec3(0.0, 0.0, eps), lod);

    vec2 slope = vec2(h0 - hx, h0 - hz) / eps;
    float bumpStrength = 0.25;

    float dirY = underwater ? -1.0 : 1.0;
    return normalize(vec3(slope.x * bumpStrength, dirY, slope.y * bumpStrength));
}

vec3 raymarch_water_pom(vec3 origWorldPos, vec3 worldRayDir, float lod) {
    vec3 rayDir = worldRayDir;
    if (rayDir.y > -0.001) rayDir.y = -0.001;

    float stepScale = 0.05;
    vec3 p = origWorldPos;
    vec3 stepVec = rayDir * (stepScale / abs(rayDir.y));
    
    vec3 prevP = p;
    float prevH = p.y - (origWorldPos.y + getwave2(p, lod));
    
    for (int i = 0; i < 12; i++) {
        p += stepVec;
        float waveH = getwave2(p, lod);
        float currH = p.y - (origWorldPos.y + waveH);
        
        if (currH < 0.0) {
            float t = prevH / (prevH - currH);
            p = mix(prevP, p, clamp(t, 0.0, 1.0));
            break;
        }
        prevP = p;
        prevH = currH;
    }
    return p;
}

vec4 ray_trace_ssr(vec3 direction, vec3 start, float dither) {
    float rayLength = length(start);
    vec3 testPoint = start + direction * (0.08 + 0.015 * dither);
    
    float stepLen = 0.08 + rayLength * 0.006;
    vec2 invProjDiag = vec2(gbufferProjectionInverse[0][0], gbufferProjectionInverse[1][1]);

    for (int i = 0; i < 48; i++) {
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

vec3 ray_trace_ssgi(vec3 startViewPos, vec3 rayDir, vec3 surfaceNormal, vec2 invProjDiag, float dither) {
    vec3 testPoint = startViewPos + surfaceNormal * (0.08 + 0.02 * length(startViewPos));
    float stepLen = SSGI_RADIUS / float(SSGI_STEPS);
    testPoint += rayDir * (stepLen * (0.1 + 0.9 * dither));
    
    for (int i = 0; i < SSGI_STEPS; i++) {
        if (testPoint.z >= -0.1) break;

        vec2 sampleUV = (testPoint.xy / (-testPoint.z * invProjDiag)) * 0.5 + 0.5;
        if (sampleUV.x <= 0.005 || sampleUV.x >= 0.995 || sampleUV.y <= 0.005 || sampleUV.y >= 0.995) break;

        float rawDepth = texture(depthtex0, sampleUV).r;
        if (rawDepth < 0.9999) {
            vec3 sampleViewPos = ScreenToView(sampleUV, rawDepth);
            float diff = sampleViewPos.z - testPoint.z;

            if (diff > -0.05 && diff < (stepLen * 1.2 + 0.12)) {
                vec2 sampleEncN = texelFetch(colortex1, ivec2(sampleUV * vec2(viewWidth, viewHeight)), 0).rg;
                vec2 sn2 = sampleEncN * 2.0 - 1.0;
                float snz = sqrt(max(0.0, 1.0 - dot(sn2, sn2)));
                vec3 sampleNormal = normalize(vec3(sn2, snz));

                float normalWeight = max(0.0, dot(sampleNormal, -rayDir));
                float dist = length(sampleViewPos - startViewPos);
                float distFade = max(0.0, 1.0 - (dist / SSGI_RADIUS));

                vec3 hitColor = max(vec3(0.0), texture(colortex0, sampleUV).rgb);
                vec2 edge = smoothstep(vec2(0.0), vec2(0.08), sampleUV) * smoothstep(vec2(1.0), vec2(0.92), sampleUV);
                
                return hitColor * edge.x * edge.y * normalWeight * distFade * SSGI_BOUNCE_BOOST;
            }
        }
        testPoint += rayDir * stepLen;
    }
    return vec3(0.0);
}

vec2 cloudHash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.0973, 0.1099));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float cloudValueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(dot(cloudHash22(i + vec2(0.0, 0.0)), f - vec2(0.0, 0.0)),
                   dot(cloudHash22(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0)), u.x),
               mix(dot(cloudHash22(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0)),
                   dot(cloudHash22(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0)), u.x), u.y);
}

float worleyNoise(vec2 p, float time) {
    vec2 n = floor(p);
    vec2 f = fract(p);
    float minDist = 1.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 o = cloudHash22(n + g);
            o = 0.5 + 0.5 * sin(time * 0.15 + 6.2831 * o);
            vec2 r = g + o - f;
            float d = dot(r, r);
            minDist = min(minDist, d);
        }
    }
    return sqrt(minDist);
}

float cloudFilamentFbm(vec2 p, float time) {
    vec2 warp1 = vec2(
        cloudValueNoise(p + vec2(0.0, time * 0.015)),
        cloudValueNoise(p + vec2(5.2, time * 0.012))
    );
    p += warp1 * 0.9;

    vec2 warp2 = vec2(
        cloudValueNoise(p * 2.5 - vec2(time * 0.02, 0.0)),
        cloudValueNoise(p * 2.5 + vec2(0.0, time * 0.018))
    );
    p += vec2(warp2.x * 1.2, warp2.y * 0.3);

    float f = 0.0;
    f += 0.52 * (1.0 - worleyNoise(p * 2.2, time));
    f += 0.26 * cloudValueNoise(p * 4.8);
    f += 0.13 * (1.0 - worleyNoise(p * 9.5 + time * 0.03, time));
    f += 0.09 * cloudValueNoise(p * 18.0);

    return clamp(f, 0.0, 1.0);
}

vec4 getMackerelCloudData(vec2 skyPos, float time) {
    vec2 uv1 = skyPos * 0.45 + vec2(time * 0.005, time * 0.003);
    float density1 = cloudFilamentFbm(uv1, time);
    density1 = smoothstep(0.28, 0.75, density1);

    vec2 uv2 = skyPos * 0.85 - vec2(time * 0.008, -time * 0.004);
    float density2 = cloudFilamentFbm(uv2 * vec2(2.8, 0.7), time * 1.1);
    density2 = smoothstep(0.35, 0.82, density2) * 0.5;

    float totalDensity = clamp(density1 + density2, 0.0, 1.0);
    float edgeFilament = pow(clamp(density1 * 1.4, 0.0, 1.0), 0.7);

    return vec4(totalDensity, density1, density2, edgeFilament);
}

layout(location = 0) out vec4 fragData0;
layout(location = 1) out vec4 fragData1;
layout(location = 2) out vec4 fragData2;

void main() {
    vec2 uv = texCoord;
    ivec2 pixelCoord = ivec2(gl_FragCoord.xy);

    float waterDepth  = texture(depthtex0, uv).r; 
    float seabedDepth = texture(depthtex1, uv).r; 
    vec3 hdr = texture(colortex0, uv).rgb;
    
    // 优化：单次采样本像素 colortex1 以提取所有复合数据，极大降低 VRAM 带宽开销
    vec4 colortex1_data = texelFetch(colortex1, pixelCoord, 0);
    int blockId = int(colortex1_data.z * 255.0 + 0.5);
    vec2 encodedDataN = colortex1_data.rg;
    float skyLightData = colortex1_data.a;

    // 优化：将重复计算的抖动提至全局共享
    float ssrDither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    float vlDither = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));

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

    bool isWater = (blockId == BLOCK_ID_WATER) && (waterDepth < 0.9999);
    bool isGlass = (blockId == BLOCK_ID_GLASS) && (waterDepth < 0.9999);
    bool underwater = (isEyeInWater == 1);

    vec4 ndc = vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec4 vp4 = gbufferProjectionInverse * ndc;
    vec3 viewDir = normalize(vp4.xyz / vp4.w);
    vec3 worldDir = normalize((gbufferModelViewInverse * vec4(viewDir, 0.0)).xyz);

    vec3 skyHDR = (realSunDir.y > -0.05) ? 
        AtmosphericScattering(worldDir, realSunDir, 1.0) * mix(1.0, 0.35, saturate(rainStrength)) : 
        AtmosphericScattering(worldDir, -realSunDir, 0.1) * 0.02 + vec3(0.001, 0.005, 0.018);

    if (CLOUD2D_ENABLED && worldDir.y > 0.0) {
        float cloudTime = frameTimeCounter * 0.12;
        vec2 skyPlaneUV = worldDir.xz / (worldDir.y + 0.18);
        vec4 cloudData = getMackerelCloudData(skyPlaneUV, cloudTime);
        float density = cloudData.x;

        if (density > 0.005) {
            vec2 sunOffset = normalize(realSunDir.xz + vec2(0.0001)) * 0.15;
            vec4 shadowCloudData = getMackerelCloudData(skyPlaneUV - sunOffset, cloudTime);
            float shadowDensity = shadowCloudData.x;

            float selfShadow = exp(-density * 3.2); 
            float lightDepth = max(0.0, density - shadowDensity * 0.75);
            float powderEffect = 1.0 - exp(-density * 3.5);
            float beerLaw = exp(-lightDepth * 3.0);
            float transmittance = powderEffect * beerLaw;

            float cosTheta = max(0.0, dot(worldDir, realSunDir));
            float sunFactor = saturate(sunHeight * 3.0) * (1.0 - rainStrength);
            
            float forwardScattering = pow(cosTheta, 6.0) * (1.0 - density * 0.85);
            float lightPiercing = pow(clamp(1.0 - density, 0.0, 1.0), 2.5) * pow(cosTheta, 2.0);
            float cloudWrap = clamp((cosTheta + 0.5) / 1.5, 0.0, 1.0);
            float edgeSSS = pow(cloudWrap, 1.5) * (1.0 - selfShadow * 0.5);

            vec3 cloudBaseShadow = skyHDR * 0.28;
            vec3 cloudAmbient = mix(cloudBaseShadow, skyHDR * 0.85, selfShadow); 
            vec3 cloudSunLight = sunColor * (transmittance * 1.1 + forwardScattering * 1.6 + lightPiercing * 0.8 + edgeSSS * 0.35) * sunFactor;
            vec3 cloudColor = cloudAmbient + cloudSunLight;

            float horizonFade = smoothstep(0.01, 0.20, worldDir.y);
            float finalAlpha = density * 0.92 * horizonFade;

            skyHDR = mix(skyHDR, cloudColor, finalAlpha);
        }
    }

    float horizonFactor = saturate(sunHeight * 3.0);
    float sd = dot(worldDir, realSunDir);
    float sunSize = 0.00195 * mix(0.5, 1.0, horizonFactor);
    float sunDiscMask = saturate((sd - (1.0 - sunSize)) * 1000.0 * mix(0.3, 1.0, horizonFactor));
    sunDiscMask = pow(sunDiscMask * sunDiscMask * (3.0 - 2.0 * sunDiscMask), 2.0);
    float sunGlow = exp(-(1.0 - sd) * mix(300.0, 50.0, horizonFactor));

    skyHDR += (sunDiscMask * sunColor * 12.0 + sunGlow * sunColor * 1.5) * smoothstep(0.0, 0.3, sunHeight) * saturate(sunHeight * 10.0) * (1.0 - rainStrength);
    if (realSunDir.y < 0.0) skyHDR += SunDisc(worldDir, -realSunDir) * vec3(0.245, 0.2625, 0.315);

    float rawLum = 0.0;
    rawLum += texture(colortex6, vec2(0.50, 0.50)).r * 0.40;
    rawLum += texture(colortex6, vec2(0.46, 0.46)).r * 0.15;
    rawLum += texture(colortex6, vec2(0.54, 0.54)).r * 0.15;
    rawLum += texture(colortex6, vec2(0.46, 0.54)).r * 0.15;
    rawLum += texture(colortex6, vec2(0.54, 0.46)).r * 0.15;
    rawLum = max(rawLum, 0.0001);

    float prevLum = texture(colortex7, vec2(0.5)).r;
    if (prevLum < 0.001) prevLum = rawLum;
    float smoothLum = prevLum + (rawLum - prevLum) * (1.0 - exp(-frametime * 3.0));
    float nightFactor = clamp(1.0 - smoothLum * 3.0, 0.0, 1.0);
    
    float rainDarken = mix(1.0, 0.45, saturate(rainStrength));
    float exposure = clamp(mix(0.18, 0.12, nightFactor) / smoothLum, EXP_MIN, EXP_MAX) * EXPOSURE * rainDarken;

    // ==================== 水体渲染逻辑 ====================
    if (isWater) {
        vec3 waterViewPos  = ScreenToView(uv, waterDepth);
        vec3 seabedViewPos = ScreenToView(uv, seabedDepth);
        vec3 waterViewDir  = normalize(waterViewPos);

        float viewDist = length(waterViewPos);
        float waveLod = clamp(1.0 - viewDist * 0.005, 0.15, 1.0);

        vec3 rawWaterWorldPos = (gbufferModelViewInverse * vec4(waterViewPos, 1.0)).xyz + cameraPosition;
        vec3 worldViewDir = normalize((gbufferModelViewInverse * vec4(waterViewDir, 0.0)).xyz);

        vec3 waterWorldPos = underwater ? rawWaterWorldPos : raymarch_water_pom(rawWaterWorldPos, worldViewDir, waveLod);
        waterViewPos = (gbufferModelView * vec4(waterWorldPos - cameraPosition, 1.0)).xyz;

        float waterThickness = max(0.0, abs(seabedViewPos.z - waterViewPos.z));

        vec3 flatWorldNormal = underwater ? vec3(0.0, -1.0, 0.0) : vec3(0.0, 1.0, 0.0);
        vec3 flatViewNormal  = normalize(mat3(gbufferModelView) * flatWorldNormal);
        float flatNdotV      = max(0.0, dot(flatViewNormal, -waterViewDir));

        vec3 rawWorldNormal = get_water_normal(waterWorldPos, waveLod, underwater);

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

                    vec3 fogColor = AtmosphericScattering(reflectWorldDir, realSunDir, 0.5);
                    float reflFogFactor = 1.0 - exp(-viewDist * TERRAIN_FOG_DENSITY * 1.5);
                    skyRefl = mix(skyRefl, fogColor, reflFogFactor);
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
                vec2 refractOffset = waterNormal.xy * 0.02 * clamp(waterThickness, 0.0, 1.5);
                vec2 refractUV = clamp(uv + refractOffset, vec2(0.001), vec2(0.999));
                if (texture(depthtex1, refractUV).r <= waterDepth) refractUV = uv;
                vec3 bgRefract = texture(colortex0, refractUV).rgb;

                vec3 ambientRefl = mix(vec3(0.008, 0.06, 0.14) * nightDim, bgRefract * 0.6, 0.5);
                reflection = mix(ambientRefl, ssr.rgb, ssr.a);
            }
        }

        vec2 refractOffset = waterNormal.xy * 0.02 * clamp(waterThickness, 0.0, 1.5);
        vec2 refractUV = clamp(uv + refractOffset, vec2(0.001), vec2(0.999));
        if (texture(depthtex1, refractUV).r <= waterDepth) refractUV = uv;
        vec3 refractionColor = texture(colortex0, refractUV).rgb;

        if (seabedDepth > 0.9999) {
            if (underwater) {
                float waterSurfaceY = max(cameraPosition.y, 62.0) + 1.0;
                float distToSurface = (worldDir.y > 0.02) ? clamp((waterSurfaceY - cameraPosition.y) / worldDir.y, 0.5, 20.0) : 20.0;
                vec3 skyAbsorb = exp(vec3(-0.35, -0.15, -0.05) * distToSurface);
                float expCorrection = clamp(1.0 / max(exposure * 0.5, 1.0), 0.25, 1.0);
                refractionColor = skyHDR * skyAbsorb * expCorrection;
            } else {
                waterThickness = clamp(viewDist * 0.15, 0.5, 6.0);
                vec3 skyAbsorb = exp(vec3(-0.50, -0.20, -0.08) * waterThickness);
                refractionColor = skyHDR * skyAbsorb * 0.5; 
            }
        }

        float NdotV = max(0.0, dot(waterNormal, -waterViewDir));
        float fresnel;

        if (underwater) {
            float tirFactor = smoothstep(0.72, 0.42, NdotV);
            float schlick = 0.02 + 0.98 * pow(max(1.0 - NdotV, 0.0), 4.0);
            fresnel = clamp(mix(schlick, 1.0, tirFactor), 0.02, 0.98);

            hdr = mix(refractionColor, reflection, fresnel);
            hdr = apply_underwater_fog(hdr, waterViewPos, nightDim);

            vec4 volFog = CalculateUnderwaterVolumetricLight(vec3(0.0), waterViewPos, vlDither, mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * volFog.a + volFog.rgb;
        } else {
            fresnel = clamp(0.02 + 0.98 * pow(max(1.0 - NdotV, 0.0), 5.0), 0.02, 0.82);

            vec3 absorb = exp(vec3(-0.65, -0.25, -0.10) * waterThickness);
            vec3 waterColor = refractionColor * absorb;

            float fogFactor = 1.0 - exp(-waterThickness * 0.035);
            vec3 waterFogColor = vec3(0.002, 0.02, 0.05) * nightDim;
            vec3 refractedColor = mix(waterColor, waterFogColor, fogFactor);

            vec3 underwaterEndPos = (seabedDepth < 0.9999) ? seabedViewPos : (waterViewPos + waterViewDir * 20.0);
            vec4 waterVolFog = CalculateUnderwaterVolumetricLight(waterViewPos, underwaterEndPos, vlDither, mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower);
            refractedColor = refractedColor * waterVolFog.a + waterVolFog.rgb;

            hdr = mix(refractedColor, reflection, fresnel);

            vec3 halfVec = normalize(-waterViewDir + mainLightViewDir);
            float NdotH = max(0.0, dot(waterNormal, halfVec));
            
            float specPower = isDay ? 2048.0 : 4096.0;
            float specIntensity = isDay ? 1.0 : 0.35;
            float specPosition = pow(NdotH, specPower); 

            vec3 specColor = mix(vec3(1.0), mainLightColor, 0.3);
            float horizonSpecFade = smoothstep(0.0, 0.15, NdotV);

            hdr += specColor * specPosition * horizonSpecFade * (mainLightPower * mainLightVis * specIntensity * (1.0 - rainStrength));

            vec4 volFog = CalculateVolumetricFogAndLight(waterViewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * mix(1.0, volFog.a, 0.6) + volFog.rgb * 0.7;
        }
    }
    // ==================== 玻璃单独渲染逻辑 ====================
    else if (isGlass) {
        vec3 glassViewPos = ScreenToView(uv, waterDepth);
        vec3 glassViewDir = normalize(glassViewPos);

        float skyLightFactor = smoothstep(0.05, 0.85, skyLightData);

        vec3 glassTint = texture(colortex2, uv).rgb;

        vec2 n2 = encodedDataN * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
        vec3 glassNormal = normalize(vec3(n2, nz));

        vec3 reflectViewDir = normalize(reflect(glassViewDir, glassNormal));
        vec3 startGlassPos = glassViewPos + glassNormal * (0.15 + 0.02 * length(glassViewPos));
        vec4 ssr = ray_trace_ssr(reflectViewDir, startGlassPos, ssrDither);
        vec3 reflection = ssr.rgb;

        if (ssr.a < 0.95) {
            vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflectViewDir, 0.0)).xyz);
            reflectWorldDir.y = max(reflectWorldDir.y, 0.05);
            vec3 skyRefl = isDay ? AtmosphericScattering(reflectWorldDir, realSunDir, 1.0) : 
                                   AtmosphericScattering(reflectWorldDir, -realSunDir, 0.1) * 0.05 + vec3(0.001, 0.005, 0.018);
            
            vec3 indoorAmbient = vec3(0.005, 0.008, 0.012) * nightDim;
            skyRefl = mix(indoorAmbient, skyRefl, skyLightFactor);

            reflection = mix(skyRefl, ssr.rgb, ssr.a);
        }

        // 优化：复用未修改过的 hdr，省去了一次重复采样
        vec3 refractionColor = hdr;

        if (seabedDepth > 0.9999) {
            refractionColor = skyHDR;
        } 
        else if (underwater) {
            vec3 seabedViewPos = ScreenToView(uv, seabedDepth);
            float waterThickness = max(0.0, length(seabedViewPos - glassViewPos));
            if (waterThickness > 0.05) {
                vec3 absorb = exp(vec3(-0.08, -0.04, -0.02) * waterThickness);
                vec3 waterColor = refractionColor * absorb;
                float fogFactor = 1.0 - exp(-waterThickness * 0.025);
                vec3 waterFogColor = vec3(0.002, 0.02, 0.05) * nightDim;
                refractionColor = mix(waterColor, waterFogColor, fogFactor);
            }
        }

        float tintBrightness = max(glassTint.r, max(glassTint.g, glassTint.b));
        vec3 effectiveTint = mix(vec3(1.0), glassTint, smoothstep(0.05, 0.35, tintBrightness));
        refractionColor *= effectiveTint;

        float NdotV = max(0.0, dot(glassNormal, -glassViewDir));
        float fresnel = clamp(0.03 + 0.97 * pow(max(1.0 - NdotV, 0.0), 5.0), 0.03, 0.65);

        hdr = mix(refractionColor, reflection, fresnel);

        vec3 halfVec = normalize(-glassViewDir + mainLightViewDir);
        float NdotH = max(0.0, dot(glassNormal, halfVec));
        float specPosition = pow(NdotH, 256.0); 
        hdr += mainLightColor * specPosition * mainLightPower * 0.06 * mainLightVis * skyLightFactor;

        if (!underwater) {
            hdr = apply_terrain_fog(hdr, glassViewPos);
            vec4 volFog = CalculateVolumetricFogAndLight(glassViewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * volFog.a + volFog.rgb;
        }
    }

    vec3 accumulatedSSGI = vec3(0.0);

    // ==================== 地形 SSGI 与 水下渲染补全 ====================
    if (waterDepth < 0.9999 && !isWater && !isGlass) {
        vec3 viewPos = ScreenToView(uv, waterDepth);
        
        vec2 n2 = encodedDataN * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
        vec3 viewNormal = normalize(vec3(n2, nz));

    #if SSGI_ENABLED == 1
        if (!underwater) {
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
                vec3 historySSGI = texture(colortex8, prevUV).rgb;

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

        if (underwater) {
            hdr = apply_underwater_fog(hdr, viewPos, nightDim);
            vec4 volFog = CalculateUnderwaterVolumetricLight(vec3(0.0), viewPos, vlDither, mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * volFog.a + volFog.rgb;
        } else {
            vec4 volFog = CalculateVolumetricFogAndLight(viewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
            hdr = hdr * volFog.a + volFog.rgb;
        }

        vec3 worldNormal = normalize((gbufferModelViewInverse * vec4(viewNormal, 0.0)).xyz);
        
        float isFloor = smoothstep(0.30, 0.70, worldNormal.y);
        float skyLightFactor = smoothstep(0.85, 0.98, skyLightData);
        float wetness = clamp(rainStrength, 0.0, 1.0) * skyLightFactor * isFloor;

        if (wetness > 0.01) {
            vec3 viewDir = normalize(viewPos);

            hdr *= mix(1.0, 0.88, wetness);

            float NdotV = max(0.0, dot(viewNormal, -viewDir));
            vec3 startPos = viewPos + viewNormal * (0.06 + 0.02 * length(viewPos));

            vec3 reflectViewDir = normalize(reflect(viewDir, viewNormal));
            vec4 ssr = ray_trace_ssr(reflectViewDir, startPos, ssrDither);

            vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflectViewDir, 0.0)).xyz);
            reflectWorldDir.y = max(reflectWorldDir.y, 0.05);

            vec3 skyRefl = AtmosphericScattering(reflectWorldDir, realSunDir, 0.8);
            
            vec3 rainSkyAmbient = mix(vec3(0.35, 0.40, 0.48), skyRefl, 0.6) * 0.85 * skyLightFactor; 
            vec3 wetReflection = mix(rainSkyAmbient, ssr.rgb, ssr.a);

            float fresnel = clamp(mix(0.04, 0.35, pow(max(1.0 - NdotV, 0.0), 3.0)), 0.04, 0.35);
            float reflectFactor = fresnel * wetness * WET_REFLECTION_INTENSITY;

            hdr = mix(hdr, wetReflection, reflectFactor);
        }
    }

    vec3 rawBloom = texture(colortex3, uv).rgb + texture(colortex4, uv).rgb;
    vec3 bloom = mix(rawBloom, rawBloom * sunColor, 0.10);
    hdr += bloom * BLOOM_STRENGTH; 

    float nightTerrainBoost = mix(2.2, 1.0, saturate(sunHeight * 4.0 + 0.5));
    vec3 terrainResult = ACESFilm(hdr * exposure * nightTerrainBoost);

    if (underwater) {
        float waterSurfaceY = max(cameraPosition.y, 62.0) + 1.0;
        float distToSurface = (worldDir.y > 0.02) ? clamp((waterSurfaceY - cameraPosition.y) / worldDir.y, 0.5, 30.0) : 30.0;
        vec3 skyViewPos = viewDir * distToSurface;

        vec3 skyAbsorb = exp(vec3(-0.30, -0.12, -0.04) * distToSurface);
        float expCorrection = clamp(1.0 / max(exposure * 0.5, 1.0), 0.25, 1.0);
        skyHDR = skyHDR * skyAbsorb * expCorrection;

        skyHDR = apply_underwater_fog(skyHDR, skyViewPos, nightDim);

        vec4 skyVolFog = CalculateUnderwaterVolumetricLight(vec3(0.0), skyViewPos, vlDither, mainLightViewDir, realSunDir, mainLightColor, mainLightVis, mainLightPower);
        skyHDR = skyHDR * skyVolFog.a + skyVolFog.rgb * 0.3;
    } else {
        vec3 skyViewPos = viewDir * VL_MAX_DIST;
        vec4 skyVolFog = CalculateVolumetricFogAndLight(skyViewPos, vlDither, mainLightViewDir, mainLightColor, mainLightVis, mainLightPower);
        skyHDR = skyHDR * skyVolFog.a + skyVolFog.rgb;
    }
    vec3 skyResult = ACESFilm(max(skyHDR, vec3(0.0)) * exposure);

    
    float skyMaskDepth = underwater ? seabedDepth : waterDepth;
    float skyFactor = smoothstep(0.9998, 0.99999, skyMaskDepth);
    vec3 result = mix(terrainResult, skyResult, skyFactor);

    result = clamp(result * 1.02, 0.0, 1.0);
    float lum = dot(result, vec3(0.299, 0.587, 0.114));
    result = mix(vec3(lum), result, SATURATION);
    result += fract(sin(dot(texCoord, vec2(12.9898, 78.233))) * 43758.5453) / 255.0;

    fragData0 = vec4(result, 1.0);
    fragData1 = vec4(vec3(smoothLum), 1.0);
    fragData2 = vec4(accumulatedSSGI, 1.0);
}