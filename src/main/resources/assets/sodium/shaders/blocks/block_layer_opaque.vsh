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
uniform highp sampler3D u_ColorLightTex; // RGB of the light that reaches each grid cell
uniform vec3 u_ColorLightOrigin;   // Camera-relative corner of the grid
uniform vec3 u_ColorLightScale;    // Reciprocal of the grid size

uniform vec4 u_HandLight;          // xyz: camera-relative hand position, w: intensity
uniform vec3 u_HandLightColor;

vec3 _colored_light(vec3 worldPos, vec2 lightCoord) {
    // The block column of the vanilla lightmap is a single warm ramp, so it is not used here:
    // only the sky column is sampled, and the block term comes from the colored grid.
    vec3 sky = texture(u_LightTex, vec2(0.0, lightCoord.y)).rgb;

    vec3 gridPos = clamp((worldPos - u_ColorLightOrigin) * u_ColorLightScale, 0.0, 1.0);
    vec3 colored = texture(u_ColorLightTex, gridPos).rgb;

    // The grid already carries the source colour multiplied by its strength. Multiplying it by
    // lightCoord.x would apply the vanilla warm ramp a second time, which is why a placed source
    // used to look duller than the same source in the hand. Keep the warm base only where the
    // grid found no colour; the gain compensates for the grid's 0.84 per-block diffusion so the
    // placed light fades at roughly the same rate as the hand light.
    vec3 warm = vec3(0.62, 0.50, 0.32) * lightCoord.x;
    float colorAmount = clamp(max(colored.r, max(colored.g, colored.b)) * 1.50, 0.0, 1.0);
    vec3 block = mix(warm, colored * 1.50, colorAmount);

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
