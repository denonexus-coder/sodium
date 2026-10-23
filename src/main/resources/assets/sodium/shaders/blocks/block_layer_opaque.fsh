#version 330 core

#import <sodium:include/fog.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

in float v_MaterialMipBias;
in float v_MaterialAlphaCutoff;

#ifdef SURFACE_REFLECTION
in vec3 v_ViewPos;
in vec3 v_SkyColor;
in float v_SkyLight;

uniform float u_ReflectionStrength;
uniform float u_ReflectionSoftness;
#endif

#ifdef MAKEUP_NO_EFFECTS
in vec3 v_MakeupBlock;
in vec2 v_MakeupLight;
in vec3 v_MakeupViewPos;
in vec3 v_MakeupWorldPos;
in float v_MaterialWater;
in float v_MaterialTranslucent;

uniform vec4 u_HandLight;
uniform vec3 u_HandLightColor;
uniform sampler2D u_LightTex;

uniform int u_MakeupAmbient;

#ifdef PROFILE_SHADOWS
uniform sampler2D u_ProfileShadowMap;
uniform mat4 u_ProfileShadowMatrix;
uniform float u_ProfileShadowTexel;
uniform int u_ProfileShadowActive;

/**
 * Four-tap PCF against the Low depth map. The sampler stores the depth of the nearest caster;
 * the projection is orthographic and the map only covers 75 blocks around the player.
 */
float _makeup_shadow(vec3 worldPos, float ndl) {
    if (u_ProfileShadowActive == 0) {
        return 1.0;
    }

    vec4 shadowClip = u_ProfileShadowMatrix * vec4(worldPos, 1.0);
    vec3 shadowCoord = shadowClip.xyz / max(shadowClip.w, 1.0e-5);
    shadowCoord = shadowCoord * 0.5 + 0.5;

    if (shadowCoord.x <= 0.001 || shadowCoord.x >= 0.999
            || shadowCoord.y <= 0.001 || shadowCoord.y >= 0.999
            || shadowCoord.z >= 0.999) {
        return 1.0;
    }

    float bias = 0.0015 + 0.0025 * (1.0 - clamp(ndl, 0.0, 1.0));
    float angle = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
    // Low uses four taps with SHADOW_BLUR=2; the rotated axis hides the small tap pattern.
    vec2 axis = vec2(cos(angle), sin(angle)) * (u_ProfileShadowTexel * 2.0);
    vec2 tangent = vec2(-axis.y, axis.x);

    float lit = 0.0;
    lit += step(shadowCoord.z - bias, texture(u_ProfileShadowMap, shadowCoord.xy + axis).r);
    lit += step(shadowCoord.z - bias, texture(u_ProfileShadowMap, shadowCoord.xy - axis).r);
    lit += step(shadowCoord.z - bias, texture(u_ProfileShadowMap, shadowCoord.xy + tangent).r);
    lit += step(shadowCoord.z - bias, texture(u_ProfileShadowMap, shadowCoord.xy - tangent).r);

    float shadow = lit * 0.25;
    float distanceFade = 1.0 - smoothstep(60.0, 74.0, length(v_MakeupViewPos));

    return mix(1.0, shadow, distanceFade);
}
#endif
#endif

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

out vec4 fragColor; // The output fragment for the color framebuffer

#import <sodium:include/makeup.glsl>

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef MAKEUP_NO_EFFECTS
    // Sodium's chunk format carries no normals. The profile needs N·L for the direct sun and for
    // the water reflection, so the flat face normal is recovered from the screen-space
    // derivatives of the view position. This is deliberately before the cutout discard:
    // derivatives after a non-uniform discard are undefined, and leaves/plants use the cutout
    // pass. The derivation is uniform for every fragment of the primitive, so it is safe here.
    vec3 makeupViewDir = normalize(-v_MakeupViewPos);
    vec3 makeupNormal = normalize(cross(dFdx(v_MakeupViewPos), dFdy(v_MakeupViewPos)));
    makeupNormal = faceforward(makeupNormal, -makeupViewDir, makeupNormal);
#endif

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    // Apply per-vertex color
    diffuseColor.rgb *= v_Color.rgb;

    // Apply ambient occlusion "shade". The MakeUp no-effects profile disables AO: its own
    // omni/direct terms already carry the face orientation, and multiplying by the baked AO and
    // vanilla face shade here would double-shade every corner and side.
#ifndef MAKEUP_NO_EFFECTS
    diffuseColor.rgb *= v_Color.a;
#endif

#ifdef MAKEUP_NO_EFFECTS
    vec3 viewDir = makeupViewDir;
    vec3 normal = makeupNormal;

    bool water = v_MaterialWater > 0.5 && u_MakeupWater > 0.5;
    bool translucent = v_MaterialTranslucent > 0.5 && u_MakeupWater > 0.5;

    if (water) {
        normal = _makeup_water_normal(normal, v_MakeupWorldPos);
    }

    float ndl = max(dot(normal, u_MakeupSunDirView), 0.0);

    // MakeUp's no-shadow direct term: the sun only reaches surfaces that see the sky, and the
    // ^10 ramp is what makes a roof, a cave or a north-facing wall lose the direct component
    // without a shadow map.
    float directStrength = mix(0.0, ndl, pow(v_MakeupLight.y, 10.0)) * u_MakeupSun;

#ifdef PROFILE_SHADOWS
    directStrength *= _makeup_shadow(v_MakeupWorldPos, ndl);
#endif

    float omniStrength = ((ndl + 1.0) * 0.25) + 0.75;

    // The omni and direct colours are uniforms, not varyings: both are constant for the frame,
    // and only visibleSky changes per vertex. This keeps the varying budget inside the GLES 3
    // minimum even when the surface reflection is compiled in at the same time.
    // With the MakeUp ambient palette off, fall back to Sodium's normal sky lightmap column.
    // The block and direct terms stay from the profile, so the toggle is a true A/B switch.
    vec3 omni = u_MakeupAmbient != 0
            ? mix(u_MakeupOmniMin, u_MakeupOmniColor, v_MakeupLight.y)
            : texture(u_LightTex, vec2(0.0, v_MakeupLight.x)).rgb;
    // This is MakeUp's realLight sum: omni + direct + candle, with no extra ambient term.
    // The cave floor comes from omniMin (AVOID_DARK_LEVEL), not from a constant that would
    // also brighten every lit surface in open sky.
    vec3 light = omni * omniStrength
            + v_MakeupBlock
            + u_MakeupDirectColor * directStrength;

    // The normal Sodium path tone-maps to 1.30 for the same reason: a torch next to a lit wall
    // can otherwise push the sum well past white and clip the texture.
    light = clamp(light, 0.0, 1.30);

    diffuseColor.rgb *= light;

    if (water) {
        // Sodium has no depth texture or scene-colour copy here, so the absorption is an
        // angle-based approximation: the shallower the view, the less water the ray crosses.
        float ndv = max(dot(normal, viewDir), 0.0);
        float tint = clamp(u_MakeupWaterAbsorption * (1.0 - ndv) * 4.0, 0.0, 0.85);
        diffuseColor.rgb = mix(diffuseColor.rgb, u_MakeupWaterColor, tint);
    }

    if (translucent) {
        // The translucent bit covers water, glass and ice. Water also gets the absorption above
        // and the waves below; glass only gets the sky reflection, which is what the profile's
        // water shader does for every translucent surface.
        float reflectionAmount;
        vec3 reflection = _makeup_water_reflection(normal, viewDir, reflectionAmount);
        diffuseColor.rgb = mix(diffuseColor.rgb, reflection, reflectionAmount);
    }

    if (water) {
        // Match MakeUp's water alpha curve; it makes the surface read as water instead of a
        // stained-glass pane while keeping the translucency pass.
        diffuseColor.a = sqrt(clamp(diffuseColor.a, 0.0, 1.0));
    }
#endif

#ifdef SURFACE_REFLECTION
    if (u_ReflectionStrength > 0.0) {
        // The chunk format carries no normals. A flat face normal can be recovered from the
        // screen-space derivatives of the interpolated view position, which costs a few
        // instructions per fragment and no extra vertex bandwidth.
        vec3 reflectionViewDir = normalize(-v_ViewPos);
        vec3 reflectionNormal = normalize(cross(dFdx(v_ViewPos), dFdy(v_ViewPos)));
        reflectionNormal = faceforward(reflectionNormal, -reflectionViewDir, reflectionNormal);

        float fresnel = pow(clamp(1.0 - max(dot(reflectionNormal, reflectionViewDir), 0.0), 0.0, 1.0),
                u_ReflectionSoftness);
        float skyMask = smoothstep(0.0, 0.35, v_SkyLight);
        float amount = fresnel * skyMask * u_ReflectionStrength;

        // A sky-tinted sheen, not a mirror: enough to read as a surface reflection at grazing
        // angles while keeping the block texture underneath. With the option off, the shader is
        // not compiled at all and this branch does not exist.
        diffuseColor.rgb = mix(diffuseColor.rgb, v_SkyColor, amount * 0.45);
    }
#endif

#ifdef MAKEUP_NO_EFFECTS
    if (u_MakeupFog > 0.5) {
        fragColor = _makeup_fog(diffuseColor, v_FragDistance, v_MakeupLight.y);
    } else {
        // Turning the profile fog off must not leave the world without fog, and the Nether/End
        // fallback uses the same path. Keeping this branch also keeps the three vanilla fog
        // uniforms active for ChunkShaderFogComponent.Smooth.
        fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
    }
#else
    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
#endif
}
