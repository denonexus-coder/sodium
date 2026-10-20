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

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform vec2 u_TexCoordShrink;

uniform sampler2D u_LightTex; // The light map texture sampler

#ifdef COLORED_LIGHTING
uniform highp sampler2D u_ColorLightTex; // 2D atlas: X by (Y * Z) RGB cells
uniform vec3 u_ColorLightOrigin;   // Camera-relative corner of the grid
uniform vec3 u_ColorLightScale;    // Reciprocal of the grid size

uniform vec4 u_HandLight;          // xyz: camera-relative hand position, w: intensity
uniform vec3 u_HandLightColor;

vec3 _colored_light(vec3 worldPos, vec2 lightCoord) {
    // The block column of the vanilla lightmap is a single warm ramp, so it is not used here:
    // only the sky column is sampled, and the block term comes from the colored grid.
    vec3 sky = texture(u_LightTex, vec2(0.0, lightCoord.y)).rgb;

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
    vec3 colored = mix(texture(u_ColorLightTex, vec2(u, v0)).rgb,
                       texture(u_ColorLightTex, vec2(u, v1)).rgb, fz);

    // Do not smear the border texel over every chunk outside the scanned volume. The grid only
    // covers 32x16x32 blocks around the player; without this mask the clamped edge cell would
    // colour the whole world, and would visibly flash whenever the grid was rebuilt.
    vec3 edgeDist = min(gridCoord, vec3(1.0) - gridCoord);
    float edge = min(min(edgeDist.x, edgeDist.y), edgeDist.z);
    colored *= smoothstep(0.0, 0.08, edge);

    // The grid already carries the source colour multiplied by its strength. Multiplying it by
    // lightCoord.x would apply the vanilla warm ramp a second time, and multiplying it by a gain
    // would clip pale sources to white at the source cell. Keep the warm base only where the grid
    // found no colour; the placed source then starts at exactly the hand colour and fades only
    // through the grid's diffusion.
    vec3 warm = vec3(0.62, 0.50, 0.32) * lightCoord.x;
    float colorAmount = clamp(max(colored.r, max(colored.g, colored.b)) * 1.50, 0.0, 1.0);
    vec3 block = mix(warm, colored, colorAmount);

    if (u_HandLight.w > 0.0) {
        vec3 delta = worldPos - u_HandLight.xyz;
        float distSq = dot(delta, delta);
        float atten = max(0.0, 1.0 - distSq * (1.0 / 144.0));
        block += u_HandLightColor * (atten * atten * u_HandLight.w);
    }

    return clamp(sky * 0.90 + block + vec3(0.06), 0.0, 1.30);
}
#endif

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

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
#ifdef COLORED_LIGHTING
    v_Color = _vert_color * vec4(_colored_light(position, _vert_tex_light_coord), 1.0);
#else
    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
#endif
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
