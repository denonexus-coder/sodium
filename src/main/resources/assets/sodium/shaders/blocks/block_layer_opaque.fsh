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
in float v_MakeupSky;
in vec3 v_MakeupViewPos;
in vec3 v_MakeupWorldPos;
in float v_MaterialWater;
in float v_MaterialTranslucent;

uniform vec4 u_HandLight;
uniform vec3 u_HandLightColor;

// MakeUp water texture (RG format, 256x256)
uniform sampler2D u_MakeupWaterTex;
#endif

uniform sampler2D u_BlockTex; // The block texture

// ── Motor single-pass engine ─────────────────────────────────────────────────
// All inputs live under MOTOR_ENABLED; with the engine off the fragment stage receives nothing
// extra. The sliders arrive as uniforms that only exist when their own body is compiled in.
#ifdef MOTOR_ENABLED
in vec3 v_MotorViewPos;
in float v_MotorWorldY;
in vec2 v_MotorLightCoord;
#if defined(MOTOR_GRID) && !defined(ANALYTIC_LIGHTING) && (defined(COLORED_LIGHTING) || defined(MAKEUP_COLORED_LIGHTS))
in vec3 v_MotorGrid;
#endif
#ifdef MOTOR_SHADOW
uniform float u_MotorShadowStrength;
#endif
#ifdef MOTOR_SHEEN
uniform float u_MotorSheenStrength;
uniform float u_MotorSheenSoftness;
#endif
#endif

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

// ── Motor B: six-axis occluded transport ─────────────────────────────────────
// The atlas holds the colour that arrived on each face of each cell, six blocks stacked in V.
// The fragment picks the block that matches the face it is drawing, so a wall that blocks the
// source leaves the face behind it at zero -- the shading where there is no light -- while a
// hole in the same wall lets the colour through and projects it onto the surface behind.
#ifdef MOTOR_B
in vec3 v_MotorBWorldPos;

uniform sampler2D u_MotorBTex;
uniform vec3 u_MotorBOrigin;
uniform vec3 u_MotorBScale;
uniform float u_MotorBStrength;
uniform float u_MotorBCeiling;
#endif

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

#ifdef MOTOR_ENABLED
    // Flat face normal from screen-space derivatives (same trick the reflection/profile use),
    // computed BEFORE the cutout discard: derivatives after a non-uniform discard are undefined,
    // and the cutout pass (leaves/plants) would otherwise feed the sheen garbage normals.
    vec3 motorViewDir = normalize(-v_MotorViewPos);
    vec3 motorNormal = normalize(cross(dFdx(v_MotorViewPos), dFdy(v_MotorViewPos)));
    motorNormal = faceforward(motorNormal, -motorViewDir, motorNormal);
#endif

#ifdef MOTOR_B
    // Derived before the cutout discard for the same reason as the others: derivatives after a
    // non-uniform discard are undefined, and the cutout pass is leaves and plants.
    vec3 motorBWorldDir = normalize(-v_MotorBWorldPos);
    vec3 motorBNormal = normalize(cross(dFdx(v_MotorBWorldPos), dFdy(v_MotorBWorldPos)));
    motorBNormal = faceforward(motorBNormal, -motorBWorldDir, motorBNormal);
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

    // MakeUp uses mix(-N·L, N·L, dayNightMix) so the moon (opposite of sun) shades correctly
    // at night. Without this mix, night-time faces look flat because the moon is behind the sun.
    float ndlRaw = dot(normal, u_MakeupSunDirView);
    float ndl    = mix(-ndlRaw, ndlRaw, u_MakeupDayNightMix); // matches MakeUp's directLightStrength
    float ndlPos = max(ndl, 0.0);

    // MakeUp's no-shadow direct term: the sun only reaches surfaces that see the sky, and the
    // ^10 ramp is what makes a roof, a cave or a north-facing wall lose the direct component
    // without a shadow map.
    float directStrength = mix(0.0, ndlPos, pow(v_MakeupSky, 10.0)) * u_MakeupSun;
    // omniStrength uses the raw (pre-dayNight) dot like MakeUp's original formula
    float omniStrength = ((ndlRaw + 1.0) * 0.25) + 0.75;

    // The omni and direct colours are uniforms, not varyings: both are constant for the frame,
    // and only visibleSky changes per vertex. This keeps the varying budget inside the GLES 3
    // minimum even when the surface reflection is compiled in at the same time.
    vec3 omni = mix(u_MakeupOmniMin, u_MakeupOmniColor, v_MakeupSky);
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

    // Foliage / grass vibrance: boost green saturation proportionally to skylight.
    // This recreates the vivid biome-tinted greens visible in MakeUp without a
    // separate foliage pass — the vertex colour already carries the biome tint.
    float greenDominance = diffuseColor.g - max(diffuseColor.r, diffuseColor.b);
    if (greenDominance > 0.04) {
        float lum = dot(diffuseColor.rgb, vec3(0.2126, 0.7152, 0.0722));
        float satBoost = 1.0 + 0.22 * v_MakeupSky; // up to +22 % saturation in open sky
        diffuseColor.rgb = clamp(mix(vec3(lum), diffuseColor.rgb, satBoost), 0.0, 1.0);
    }

    if (water) {
        // Three-layer animated water texture sampling matching MakeUp's normal_waves() UV layout.
        // The RG channels of water_256_rg_8bit carry flow/foam (R) and depth/caustics (G).
        vec2 waterUv1 = (v_MakeupWorldPos.xz * 0.05 ) + vec2( u_MakeupTime * 0.025,  u_MakeupTime * 0.025);
        vec2 waterUv2 = (v_MakeupWorldPos.xz * 0.03125) - vec2( u_MakeupTime * 0.018,  u_MakeupTime * 0.018);
        vec2 waterUv3 = (v_MakeupWorldPos.xz * 0.125) + vec2( u_MakeupTime * 0.030, -u_MakeupTime * 0.020);
        vec2 waterData = (textureLod(u_MakeupWaterTex, waterUv1, 0.0).rg
                        + textureLod(u_MakeupWaterTex, waterUv2, 0.0).rg
                        + textureLod(u_MakeupWaterTex, waterUv3, 0.0).rg * 0.66) * 0.4;

        // Angle-based absorption: shallower view = more tint (like MakeUp's WATER_ABSORPTION)
        float ndv  = max(dot(normal, viewDir), 0.0);
        float tint = clamp(u_MakeupWaterAbsorption * (1.0 - ndv * 0.5) * 3.5, 0.10, 0.85);

        // Caustic glow from texture G channel, foam highlight from R
        vec3 causticTint = vec3(waterData.g * 0.30 + waterData.r * 0.10);
        vec3 waterTextureColor = u_MakeupWaterColor + causticTint;
        diffuseColor.rgb = mix(diffuseColor.rgb, waterTextureColor, tint);
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

#ifdef MOTOR_B
    {
        vec3 motorBCell = (v_MotorBWorldPos - u_MotorBOrigin) * u_MotorBScale;

        if (all(greaterThanEqual(motorBCell, vec3(0.0))) && all(lessThanEqual(motorBCell, vec3(1.0)))) {
            vec3 motorBSize = vec3(1.0) / u_MotorBScale;
            vec3 motorBAbs = abs(motorBNormal);

            // The six blocks are +X, -X, +Y, -Y, +Z, -Z: the face the light arrives on.
            float motorBLayer;
            if (motorBAbs.x >= motorBAbs.y && motorBAbs.x >= motorBAbs.z) {
                motorBLayer = motorBNormal.x >= 0.0 ? 0.0 : 1.0;
            } else if (motorBAbs.y >= motorBAbs.z) {
                motorBLayer = motorBNormal.y >= 0.0 ? 2.0 : 3.0;
            } else {
                motorBLayer = motorBNormal.z >= 0.0 ? 4.0 : 5.0;
            }

            // The atlas is one row per (z, y) and six stacked blocks, sampled at texel centres so
            // the blocks cannot bleed into each other; the two z slices are interpolated the same
            // way the colored grid does it.
            vec3 motorBP = clamp(motorBCell, 0.0, 1.0) * motorBSize;
            float motorBU = clamp(motorBP.x, 0.5, motorBSize.x - 0.5) / motorBSize.x;
            float motorBY = clamp(motorBP.y, 0.5, motorBSize.y - 0.5);
            float motorBZ0 = clamp(floor(motorBP.z - 0.5), 0.0, motorBSize.z - 1.0);
            float motorBZ1 = min(motorBZ0 + 1.0, motorBSize.z - 1.0);
            float motorBZf = clamp(motorBP.z - 0.5 - motorBZ0, 0.0, 1.0);
            float motorBBase = motorBLayer * motorBSize.y * motorBSize.z;
            float motorBRows = motorBSize.y * motorBSize.z * 6.0;
            float motorBV0 = (motorBBase + motorBZ0 * motorBSize.y + motorBY) / motorBRows;
            float motorBV1 = (motorBBase + motorBZ1 * motorBSize.y + motorBY) / motorBRows;

            vec3 motorBArrived = mix(texture(u_MotorBTex, vec2(motorBU, motorBV0)).rgb,
                                     texture(u_MotorBTex, vec2(motorBU, motorBV1)).rgb, motorBZf);

            // Additive fusion: the colour that reached this face adds to the surface...
            diffuseColor.rgb *= 1.0 + motorBArrived * (u_MotorBStrength * 1.15);

            // ...and a face the light never reached stays at the darker end of the curve, which is
            // the shadow behind a wall. The floor is not zero, so an unlit cave is dim, not black.
            float motorBReach = clamp(max(motorBArrived.r, max(motorBArrived.g, motorBArrived.b))
                    / max(u_MotorBCeiling, 0.05), 0.0, 1.0);
            diffuseColor.rgb *= mix(1.0, 0.62 + 0.38 * motorBReach, u_MotorBStrength * 0.55);

            diffuseColor.rgb = min(diffuseColor.rgb, vec3(1.30));
        }
    }
#endif

#ifdef MOTOR_ENABLED
    // --- Motor single-pass engine (additive; each block exists only when its define is set) ---
    // The engine reuses the diffuseColor the legacy paths produced. Where Motor owns a slot the
    // legacy effect for that slot was already suppressed at compile time by the last-wins
    // resolve, so these blocks never stack on the same legacy effect. HONEST SCOPE: single-pass,
    // so no real shadow map / SSR / volumetric -- the shadow, sheen, fog and shafts below are
    // ALU approximations, and the grid piggybacks on the colored-light atlas. motorNormal /
    // motorViewDir were derived above (before the cutout discard).

#if defined(MOTOR_GRID) && !defined(ANALYTIC_LIGHTING) && (defined(COLORED_LIGHTING) || defined(MAKEUP_COLORED_LIGHTS))
    // Clustered grid as a light: add the grid colour gated by the vanilla block-light column so
    // it never leaks through walls. Approximation of clustered-forward that reuses the atlas the
    // user already builds, not a real per-cluster light list.
    {
        float gate = smoothstep(0.0, 0.08, v_MotorLightCoord.x);
        diffuseColor.rgb += v_MotorGrid * gate * 0.35;
    }
#endif

#ifdef MOTOR_SHADOW
    if (u_MotorShadowStrength > 0.0) {
        // Tinted analytic shadow: the ambient light is TINTED (not greyed) where the surface is
        // occluded from the sky, inferred from the vanilla lightmap plus a height term. No
        // shadow map and no extra pass.
        float skyLight = clamp(v_MotorLightCoord.y, 0.0, 1.0);
        float blockLight = clamp(v_MotorLightCoord.x, 0.0, 1.0);
        float occlusion = (1.0 - smoothstep(0.0, 0.90, skyLight))
                * (1.0 - 0.60 * smoothstep(0.0, 0.85, blockLight));

        float depth = 1.0 - smoothstep(20.0, 110.0, v_MotorWorldY);
        // Day -> cool blue tint; deep/low -> violet tint. Never a flat grey darkening.
        vec3 shade = mix(vec3(0.72, 0.82, 1.00), vec3(0.48, 0.38, 0.68), depth);

        float amount = clamp(u_MotorShadowStrength * occlusion, 0.0, 1.0);
        diffuseColor.rgb *= mix(vec3(1.0), shade, amount);
        diffuseColor.rgb *= 1.0 - amount * 0.18;
    }
#endif

#ifdef MOTOR_SHEEN
    if (u_MotorSheenStrength > 0.0) {
        // Cheap metallic/Fresnel sheen: a view-dependent white rim, no SSR and no reflection
        // probe. Cost is one pow() per fragment.
        float fresnel = pow(clamp(1.0 - max(dot(motorNormal, motorViewDir), 0.0), 0.0, 1.0),
                u_MotorSheenSoftness);
        diffuseColor.rgb += fresnel * u_MotorSheenStrength * 0.25;
    }
#endif

#ifdef MOTOR_FOG
    {
        // Analytic height/distance fog tint folded into the fragment. The standard linear fog
        // still runs below; this only adds a height-aware colour shift so low, distant terrain
        // takes the horizon colour sooner. Approximation, not a scattering integral.
        float d = clamp(v_FragDistance / max(u_FogEnd, 1.0), 0.0, 1.0);
        float heightFade = 1.0 - smoothstep(60.0, 140.0, v_MotorWorldY);
        float amount = d * d * mix(0.25, 0.6, heightFade);
        diffuseColor.rgb = mix(diffuseColor.rgb, u_FogColor.rgb, amount);
    }
#endif

#ifdef MOTOR_SHAFTS
    {
        // Light-shafts approximation: a soft brightening that grows with distance, tinted by the
        // fog colour. NOT volumetric scattering -- a per-fragment god-ray-like cue that costs a
        // single smoothstep.
        float shaft = smoothstep(0.0, u_FogEnd, v_FragDistance) * 0.12;
        diffuseColor.rgb += u_FogColor.rgb * shaft;
    }
#endif
#endif

#ifdef MAKEUP_NO_EFFECTS
    if (u_MakeupFog > 0.5) {
        fragColor = _makeup_fog(diffuseColor, v_FragDistance, v_MakeupSky);
    } else {
        // Turning the profile fog off must not leave the world without fog, and the Nether/End
        // fallback uses the same path. Keeping this branch also keeps the three vanilla fog
        // uniforms active for ChunkShaderFogComponent.Smooth.
        fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
    }
#else
    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
#endif

#ifdef MOTOR_TONEMAP
    // Tonemap / colour grading folded into the output. A cheap Reinhard-ish curve with a slight
    // contrast lift, done in-place with no extra render target. Approximation of a grading pass,
    // applied last so it sees the fully composited, fogged fragment.
    fragColor.rgb = (fragColor.rgb / (fragColor.rgb + vec3(0.155))) * 1.155;
    fragColor.rgb = pow(clamp(fragColor.rgb, 0.0, 1.0), vec3(0.9));
#endif
}
