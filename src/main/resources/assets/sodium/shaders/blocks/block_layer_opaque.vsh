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

// ── INFINITIUM LIGHT ENGINE ──────────────────────────────────────────────────────
// A cor RGB e aplicada por vertice, nunca por fragmento: a grelha do motor (atlas 2D) e
// a luz da mao sao amostradas aqui e o resultado e interpolado pela rasterizacao. A
// intensidade e a oclusao continuam a vir da luz vanilla (u_LightTex) -- o ILE so troca
// o matiz pelo das fontes reais. Sem o define, nao ha nem um uniform extra.
#ifdef INFINITIUM_LIGHT
uniform highp sampler2D u_InfinitiumTex;   // atlas 2D: X por (Y * Z) celulas RGB
uniform vec3 u_InfinitiumOrigin;           // canto da grelha, relativo a camera
uniform vec3 u_InfinitiumScale;            // reciproco do tamanho da grelha
uniform float u_InfinitiumTint;            // forca da tintura (0..1)
uniform vec4 u_InfinitiumHand;             // xyz posicao relativa da mao, w intensidade
uniform vec3 u_InfinitiumHandColor;        // cor do item na mao (paleta ILE)

/**
 * A cor da luz a volta do ponto, amostrada da grelha do motor.
 *
 * A grelha sobe como atlas 2D: X pixels de largura, um bloco de Y linhas por cada fatia Z.
 * Interpolam-se as duas fatias vizinhas a mao. Textura 3D nao e opcao: o glTexImage3D do
 * MobileGlues tem um crash nativo em alguns aparelhos; 2D RGB8 trata qualquer GLES3.
 */
vec3 _infinitium_grid(vec3 worldPos) {
    vec3 gridCoord = (worldPos - u_InfinitiumOrigin) * u_InfinitiumScale;

    vec3 gridSize = 1.0 / u_InfinitiumScale;
    vec3 p = clamp(gridCoord, 0.0, 1.0) * gridSize;
    float u = clamp(p.x, 0.5, gridSize.x - 0.5) / gridSize.x;
    float y = clamp(p.y, 0.5, gridSize.y - 0.5);
    float z0 = clamp(floor(p.z - 0.5), 0.0, gridSize.z - 1.0);
    float z1 = min(z0 + 1.0, gridSize.z - 1.0);
    float fz = fract(p.z - 0.5);
    float v0 = (z0 * gridSize.y + y) / (gridSize.y * gridSize.z);
    float v1 = (z1 * gridSize.y + y) / (gridSize.y * gridSize.z);
    vec3 colored = mix(texture(u_InfinitiumTex, vec2(u, v0)).rgb,
                       texture(u_InfinitiumTex, vec2(u, v1)).rgb, fz);

    // Nao espalhar o texel da borda por tudo o que esta fora do volume varrido: queda suave
    // na fronteira para a luz vanilla, sem popping nem pisca entre fatias.
    vec3 edgeDist = min(gridCoord, vec3(1.0) - gridCoord);
    float edge = min(min(edgeDist.x, edgeDist.y), edgeDist.z);
    colored *= clamp(edge * 8.0, 0.0, 1.0);

    return colored;
}
#endif

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
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
    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);

#ifdef INFINITIUM_LIGHT
    // S5+S7: a cor das fontes em torno do vertice + a luz da mao (S8, curva da mao provada,
    // mesmo raio de 12 blocos). A tintura preserva a intensidade vanilla e troca o matiz,
    // normalizada por canal: duas fontes de cores diferentes combinam sem saturar (S5.4).
    vec3 ileColor = _infinitium_grid(position);

    vec3 ileDelta = position - u_InfinitiumHand.xyz;
    float ileAtt = max(0.0, 1.0 - dot(ileDelta, ileDelta) * (1.0 / 144.0));
    ileAtt *= ileAtt;
    ileColor += u_InfinitiumHandColor * (ileAtt * u_InfinitiumHand.w);

    float ileLum = max(max(ileColor.r, ileColor.g), ileColor.b);

    if (ileLum > 0.004) {
        vec3 ileTint = ileColor / ileLum;
        v_Color.rgb *= mix(vec3(1.0), ileTint, clamp(ileLum, 0.0, 1.0) * u_InfinitiumTint);
    }
#endif

    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
