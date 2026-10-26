/*
 * The MakeUp "No Effects" profile, rebuilt inside Sodium's chunk shader.
 *
 * The palette is uploaded as uniforms instead of being compiled in: only the master switch
 * changes the program, so changing the night brightness, the water absorption or the block-light
 * temperature takes effect on the next frame without a renderer reload.
 */
#ifdef MAKEUP_NO_EFFECTS

uniform vec3 u_MakeupOmniColor;
uniform vec3 u_MakeupOmniMin;
uniform vec3 u_MakeupDirectColor;
uniform vec3 u_MakeupCandleColor;
uniform vec3 u_MakeupZenith;
uniform vec3 u_MakeupHorizon;
uniform vec3 u_MakeupWaterColor;
uniform vec3 u_MakeupFogColor;
uniform vec3 u_MakeupSunDirView;
uniform vec3 u_MakeupUpView;
uniform vec3 u_MakeupCameraPos;

uniform float u_MakeupSun;
uniform float u_MakeupWater;
uniform float u_MakeupWaterAbsorption;
uniform float u_MakeupWaterReflection;
uniform float u_MakeupWaterWaves;
uniform float u_MakeupWaveStrength;
uniform float u_MakeupFog;
uniform float u_MakeupFogDensity;
uniform float u_MakeupEyeSky;
uniform float u_MakeupRain;
uniform float u_MakeupFar;
uniform float u_MakeupTime;
uniform float u_MakeupDayNightMix;

/** MakeUp's visibleSky: the lightmap's sky column, dead zone and all. */
float _makeup_visible_sky(vec2 lightCoord) {
    // Sodium packs the full lightmap coordinate as 240/256; MakeUp expects full light at 1.0.
    // Without this normalization the ^10 direct term would only reach about half strength in
    // open sunlight, which is exactly the dark-grass symptom this profile has to fix.
    float sky = clamp(lightCoord.y * (256.0 / 240.0), 0.0, 1.0);

    return clamp(max(sky - 0.065, 0.0) * 1.06951871657754, 0.0, 1.0);
}

/** MakeUp's candle curve: CANDLE_BASELIGHT * (x^1.5 + (1.17x)^6). */
vec3 _makeup_candle(vec2 lightCoord) {
    float x = clamp(lightCoord.x * (256.0 / 240.0), 0.0, 1.0);
    float x6 = x * x * x;
    x6 *= x6;

    return u_MakeupCandleColor * (x * sqrt(x) + x6 * 2.565164);
}

/** The existing Sodium hand-light curve, reused so the held torch matches a placed one. */
vec3 _makeup_hand(vec3 cameraPos) {
    if (u_HandLight.w <= 0.0) {
        return vec3(0.0);
    }

    vec3 delta = cameraPos - u_HandLight.xyz;
    float distSq = dot(delta, delta);
    float atten = max(0.0, 1.0 - distSq * (1.0 / 144.0));

    return u_HandLightColor * (atten * atten * u_HandLight.w);
}

/** The warm block light when colored lighting is off; colored lighting replaces this. */
vec3 _makeup_block(vec3 cameraPos, vec2 lightCoord) {
    return max(_makeup_candle(lightCoord), _makeup_hand(cameraPos));
}

/**
 * MakeUp's fog curve, with the horizon colour instead of a blurred scene colour. Sodium's
 * normal path has no scene-colour buffer; the horizon is the closest cheap approximation and
 * does not add a pass.
 */
vec4 _makeup_fog(vec4 color, float dist, float visibleSky) {
    if (u_MakeupFog < 0.5) {
        return color;
    }

    // visibleSky is the raw lightmap coordinate, which stays at 1.0 in an open sky even at
    // night. The eye brightness carries the time of day, so scale it by a cave factor instead
    // of taking the maximum: an open night keeps the night reduction, a cave closes much less.
    float intensity = u_MakeupEyeSky * mix(0.35, 1.0, clamp(visibleSky, 0.0, 1.0));
    float f = pow(clamp(dist / max(u_MakeupFar, 1.0), 0.0, 1.0) * intensity,
                  mix(u_MakeupFogDensity, 1.0, u_MakeupRain));

    return vec4(mix(color.rgb, u_MakeupFogColor, clamp(f, 0.0, 1.0)), color.a);
}

/** Three-layer analytic waves matching MakeUp's normal_waves() — modulated by rain. */
vec3 _makeup_water_normal(vec3 normal, vec3 worldPos) {
    if (u_MakeupWaterWaves < 0.5) {
        return normal;
    }

    vec2 p = worldPos.xz;
    float t = u_MakeupTime;

    // Layer 1 – primary long swell (matches MakeUp wave_1 scale ~0.05)
    float w1 = sin(p.x * 0.9  + t * 1.7) * cos(p.y * 0.8  - t * 1.3);
    // Layer 2 – secondary diagonal wave (matches wave_2 scale ~0.03125)
    float w2 = sin((p.x + p.y) * 0.55 - t * 0.9) * 0.85;
    // Layer 3 – high-frequency ripple (matches wave_3 scale ~0.125, weight 0.66)
    float w3 = cos(p.x * 1.3  - t * 2.1) * sin(p.y * 1.1  + t * 1.5) * 0.66;

    // Rain calms reflections but increases choppiness (turbulence blends toward 0.3 at full rain)
    float turbulence = mix(0.9, 0.3, u_MakeupRain);

    vec2 waveXZ = vec2(w1 + w3 * 0.5, w2 + w3 * 0.5) * turbulence;

    return normalize(normal + vec3(waveXZ.x, waveXZ.y, 0.0) * (0.35 * u_MakeupWaveStrength));
}

/** A sky-coloured Fresnel reflection; no SSR, no second scene pass. */
vec3 _makeup_water_reflection(vec3 normal, vec3 viewDir, out float amount) {
    vec3 reflected = reflect(-viewDir, normal);
    float up = clamp(dot(reflected, u_MakeupUpView), 0.0, 1.0);
    vec3 sky = mix(u_MakeupHorizon, u_MakeupZenith, pow(up, 0.333333));

    // MakeUp's REFLEX_INDEX is 0.45 when REFLECTION_SLIDER is 0. The glint is deliberately
    // absent: SUN_REFLECTION=0 in the profile.
    float fresnel = pow(clamp(1.0 - max(dot(normal, viewDir), 0.0), 0.0, 1.0), 2.0);
    amount = fresnel * 0.45 * u_MakeupWaterReflection;

    return sky;
}

#endif
