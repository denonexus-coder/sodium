#version 330 core

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

out float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif

#ifdef USE_FOG
out float v_FragDistance;
#endif

#ifdef SURFACE_REFLECTION
out vec3 v_ViewPos;
out vec3 v_SkyColor;
out float v_SkyLight;
#endif

#ifdef MAKEUP_NO_EFFECTS
out vec3 v_MakeupBlock;
out float v_MakeupSky;
out vec3 v_MakeupViewPos;
out vec3 v_MakeupWorldPos;
out float v_MaterialWater;
out float v_MaterialTranslucent;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform vec2 u_TexCoordShrink;

uniform sampler2D u_LightTex; // The light map texture sampler

// The hand light is shared by the colored-light path and the MakeUp profile. With neither one
// compiled in, the uniforms do not exist and the program does not pay for them.
#if defined(COLORED_LIGHTING) || defined(MAKEUP_NO_EFFECTS)
uniform vec4 u_HandLight;          // xyz: camera-relative hand position, w: intensity
uniform vec3 u_HandLightColor;
#endif

#ifdef COLORED_LIGHTING
// Positions in this shader are camera-relative. The shadow depth needs absolute world height,
// so the camera's Y is passed separately instead of being folded into every vertex.
uniform float u_CameraY;

// Cheap colored shadows. These only touch the ambient part of the light, so block lights keep
// lighting the surface while the sky contribution is darkened and tinted where it is occluded.
uniform float u_ShadowStrength;
uniform float u_ShadowDepth;
uniform float u_ShadowTint;

// Luz vibrante: lidos por frame das opções da aba Experimental, por isso ligar/desligar vale
// imediatamente e não obriga a recarregar o renderizador.
uniform float u_VibrantFalloff;       // 0.0 = rampa linear antiga; 1.0 = smoothstep da cor real
uniform float u_VibrantToneMap;       // 0.0 = clamp 1.30; 1.0 = ombro exponencial a partir de 80%
uniform float u_InvLightRadiusSq;     // 1 / raio^2 (12^2 ou 16^2), partilhado pela curva analítica e pela luz na mão

#ifdef ANALYTIC_LIGHTING
// A short list of nearby lights: position is camera-relative, w is the peak intensity, and the
// color is the same palette value the hand light uses. There is no texture in this path.
uniform vec4 u_LightPos[MAX_LIGHTS];
uniform vec3 u_LightColor[MAX_LIGHTS];
uniform int u_LightCount;
#else
uniform highp sampler2D u_ColorLightTex; // 2D atlas: X by (Y * Z) RGB cells
uniform vec3 u_ColorLightOrigin;   // Camera-relative corner of the grid
uniform vec3 u_ColorLightScale;    // Reciprocal of the grid size
uniform float u_ColorLightHeadroom; // 1.0 padrão; 1.5 com o pico ligado (sincronizado com upload())
#endif

/**
 * The block part of the colored-light model. The profile also uses this, so a torch keeps its
 * color while the sky and the direct sun come from MakeUp's palette.
 */
vec3 _colored_block(vec3 worldPos, vec2 lightCoord, out vec3 tint, out float colorAmount) {
    tint = vec3(0.0);

#ifdef ANALYTIC_LIGHTING
    // Identical curve to the hand light below: same 12-block radius, same squared falloff.
    // A source therefore starts at exactly the hand intensity and fades the same way.
    vec3 colored = vec3(0.0);

    for (int i = 0; i < u_LightCount; i++) {
        vec3 delta = worldPos - u_LightPos[i].xyz;
        float distSq = dot(delta, delta);
        float atten = max(0.0, 1.0 - distSq * u_InvLightRadiusSq);
        atten *= atten;

        colored += u_LightColor[i] * (atten * u_LightPos[i].w);
    }
#else
    vec3 gridCoord = (worldPos - u_ColorLightOrigin) * u_ColorLightScale;

    // The grid is uploaded as a 2D atlas: X pixels across, then a block of Y rows for every Z
    // slice. Sample two adjacent slices and interpolate them by hand. Sampling the atlas as a
    // 3D texture is not an option here: MobileGlues crashes in glTexImage3D on some devices.
    vec3 gridSize = 1.0 / u_ColorLightScale;
    vec3 p = clamp(gridCoord, 0.0, 1.0) * gridSize;
    float u = clamp(p.x, 0.5, gridSize.x - 0.5) / gridSize.x;
    float y = clamp(p.y, 0.5, gridSize.y - 0.5);
    float z0 = clamp(floor(p.z - 0.5), 0.0, gridSize.z - 1.0);
    float z1 = min(z0 + 1.0, gridSize.z - 1.0);
    float fz = fract(p.z - 0.5);
    float v0 = (z0 * gridSize.y + y) / (gridSize.y * gridSize.z);
    float v1 = (z1 * gridSize.y + y) / (gridSize.y * gridSize.z);
    // Os valores da grelha são guardados divididos pelo headroom (1.5 com o pico ligado); o
    // shader multiplica de volta para recuperar o campo real, acima de 1.0 quando há fôlego.
    vec3 colored = mix(texture(u_ColorLightTex, vec2(u, v0)).rgb,
                       texture(u_ColorLightTex, vec2(u, v1)).rgb, fz)
            * u_ColorLightHeadroom;

    // Do not smear the border texel over every chunk outside the scanned volume. The grid only
    // covers 32x16x32 blocks around the player; without this mask the clamped edge cell would
    // colour the whole world, and would visibly flash whenever the grid was rebuilt.
    vec3 edgeDist = min(gridCoord, vec3(1.0) - gridCoord);
    float edge = min(min(edgeDist.x, edgeDist.y), edgeDist.z);
    colored *= smoothstep(0.0, 0.08, edge);
#endif

    // The vanilla block-light column is a conservative gate: it is 0 in complete darkness and
    // behind walls, and saturates from block light 2 upwards. Multiplying by it removes light
    // leaking through solid walls without dimming the lit area.
    colored *= smoothstep(0.0, 0.08, lightCoord.x);

    // Keep the warm base only where no colored source reaches, so a distant lit area that was
    // not scanned still looks like vanilla instead of going black.
    vec3 warm = vec3(0.62, 0.50, 0.32) * lightCoord.x;
    float maxc = max(colored.r, max(colored.g, colored.b));
    float amountOld = clamp(maxc * 1.50, 0.0, 1.0);
    float amountNew = smoothstep(0.0, 0.06, maxc);
    colorAmount = mix(amountOld, amountNew, u_VibrantFalloff);
    vec3 block = mix(warm, colored, colorAmount);
    tint = colored;

    if (u_HandLight.w > 0.0) {
        vec3 delta = worldPos - u_HandLight.xyz;
        float distSq = dot(delta, delta);
        float atten = max(0.0, 1.0 - distSq * u_InvLightRadiusSq);
        block += u_HandLightColor * (atten * atten * u_HandLight.w);
    }

    return block;
}

// Ombro exponencial a partir de 80% do pico: só o canal mais brilhante é comprimido e os
// restantes acompanham, preservando matiz e saturação. Alvo 8 bits — o resultado é clamped
// a 1.0 pelo chamador.
vec3 _vibrant_shoulder(vec3 c) {
    const float knee = 0.80;
    float peak = max(c.r, max(c.g, c.b));

    if (peak <= knee) {
        return c;
    }

    float span = 1.0 - knee;
    float mapped = knee + span * (1.0 - exp(-(peak - knee) / span));
    return c * (mapped / peak);
}

vec3 _colored_light(vec3 worldPos, vec2 lightCoord) {
    // The block column of the vanilla lightmap is a single warm ramp, so it is not used here:
    // only the sky column is sampled, and the block term is computed below.
    vec3 sky = texture(u_LightTex, vec2(0.0, lightCoord.y)).rgb;

    vec3 tint;
    float colorAmount;
    vec3 block = _colored_block(worldPos, lightCoord, tint, colorAmount);

    vec3 ambient = sky * 0.90;

    if (u_ShadowStrength > 0.0) {
        // Occlusion is inferred from the vanilla lightmap: a surface with little sky light and
        // little block light is in a shadow. No shadow map is involved.
        float skyLight = clamp(lightCoord.y, 0.0, 1.0);
        float blockLight = clamp(lightCoord.x, 0.0, 1.0);
        float occlusion = (1.0 - smoothstep(0.0, 0.90, skyLight))
                * (1.0 - 0.60 * smoothstep(0.0, 0.85, blockLight));

        // Lower surfaces are deeper. The config controls how much of the effect comes from that
        // depth rather than from the lightmap itself.
        float worldY = worldPos.y + u_CameraY;
        float depth = 1.0 - smoothstep(20.0, 110.0, worldY);
        float depthWeight = mix(1.0, mix(0.35, 1.0, depth), u_ShadowDepth);
        float depthColor = depth * u_ShadowDepth;

        // Daylight shadows lean blue; deep shadows lean violet. The colored-light hue is mixed in
        // so a torch-lit shadow keeps the color of the torch instead of turning grey.
        vec3 shade = mix(vec3(0.72, 0.82, 1.00), vec3(0.48, 0.38, 0.68), depthColor);
        vec3 hue = tint / max(max(tint.r, tint.g), max(tint.b, 1.0e-4));
        shade = mix(shade, hue, u_ShadowTint * colorAmount);

        float amount = clamp(u_ShadowStrength * occlusion * depthWeight, 0.0, 1.0);
        ambient *= mix(vec3(1.0), shade, amount);
        ambient *= 1.0 - amount * 0.22;

        // The block term is only touched slightly: it stays the main light in a cave, but the
        // deepest part of the shadow still carries the local light's color.
        block = mix(block, block * shade, amount * 0.30);
    }

    vec3 lit = ambient + block + vec3(0.06);

    if (u_VibrantToneMap > 0.5) {
        return clamp(_vibrant_shoulder(lit), 0.0, 1.0);
    }

    return clamp(lit, 0.0, 1.30);
}
#endif

#import <sodium:include/makeup.glsl>

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    // Explicit cast: ESSL does not convert a uint vector to a float vector implicitly.
    return vec3(_get_relative_chunk_coord(pos)) * vec3(16.0);
}

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

#ifdef SURFACE_REFLECTION
    // The fragment shader derives a flat face normal from these view-space positions. This avoids
    // adding a normal attribute to the chunk vertex format, which would cost bandwidth on every
    // chunk. The sky color is sampled once per vertex and only used when the surface sees sky.
    v_ViewPos = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;
    v_SkyColor = texture(u_LightTex, vec2(0.0, 1.0)).rgb;
    v_SkyLight = _vert_tex_light_coord.y;
#endif

#ifdef MAKEUP_NO_EFFECTS
    // The profile keeps the light components separate until the fragment shader, where the face
    // normal is available. Sodium's chunk format has no normal attribute, so the fragment derives
    // one from the view-space position with dFdx/dFdy.
    v_MakeupViewPos = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;
    v_MakeupWorldPos = position + u_MakeupCameraPos;
    v_MakeupSky = _makeup_visible_sky(_vert_tex_light_coord);

    #ifdef COLORED_LIGHTING
        vec3 makeupTint;
        float makeupColorAmount;
        v_MakeupBlock = _colored_block(position, _vert_tex_light_coord, makeupTint, makeupColorAmount);
    #else
        v_MakeupBlock = _makeup_block(position, _vert_tex_light_coord);
    #endif

    v_MaterialWater = _material_water(_material_params) ? 1.0 : 0.0;
    v_MaterialTranslucent = _material_translucent(_material_params) ? 1.0 : 0.0;
    v_Color = _vert_color;
#else
    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
    #ifdef COLORED_LIGHTING
        v_Color = _vert_color * vec4(_colored_light(position, _vert_tex_light_coord), 1.0);
    #else
        v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
    #endif
#endif

    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
