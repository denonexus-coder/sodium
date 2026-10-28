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

#ifdef INFINITIUM_LIGHT
// Passados ao fragmento para o modo N.L por pixel (sombreamento 2).
out vec3 v_InfWorldPos;
out vec3 v_InfLightDir;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform vec2 u_TexCoordShrink;

uniform sampler2D u_LightTex; // The light map texture sampler

// ── INFINITIUM LIGHT ENGINE (fiel ao rethinking-voxels) ─────────────────────────────
// A cor RGB vem da grelha do motor (atlas 2D) avaliada por vertice, com a matematica
// literal do RV. A luz da mao e somada como outra fonte RV. O modo de cor decide se
// substitui so o blocklight (mantem o ceu vanilla) ou tudo (sem lightmap). O modo de
// sombreamento decide se o N.L e por vertice (0), solido via luz vanilla (1) ou por
// pixel (2 -- passa posicao/direcao ao fragmento). Sem o define, nao ha uniform extra.
#ifdef INFINITIUM_LIGHT
uniform highp sampler2D u_InfinitiumTex;   // atlas 2D: X por (Y * Z) celulas RGB
uniform vec3 u_InfinitiumOrigin;           // canto da grelha, relativo a camera
uniform vec3 u_InfinitiumScale;            // reciproco do tamanho da grelha
uniform vec4 u_InfinitiumHand;             // xyz posicao relativa da mao, w nivel
uniform vec3 u_InfinitiumHandColor;        // cor do item na mao (paleta RV)
uniform int u_InfinitiumColorMode;         // 0 = so blocklight, 1 = tudo
uniform int u_InfinitiumShadingMode;       // 0 = vertice N.L, 1 = solido, 2 = por pixel

// Amostra a grelha do motor num ponto do mundo (atlas 2D com interpolacao entre fatias Z).
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

    // Queda suave na fronteira do volume varrido: sem popping nem vazar para fora.
    vec3 edgeDist = min(gridCoord, vec3(1.0) - gridCoord);
    float edge = min(min(edgeDist.x, edgeDist.y), edgeDist.z);
    colored *= clamp(edge * 8.0, 0.0, 1.0);

    return colored;
}

float _infinitium_lum(vec3 wp) {
    vec3 c = _infinitium_grid(wp);
    return max(max(c.r, c.g), c.b);
}

// Direcao da luz: gradiente do campo RV (aponta para onde e mais claro = para a fonte).
vec3 _infinitium_light_dir(vec3 wp) {
    float e = 1.0;
    vec3 g = vec3(_infinitium_lum(wp + vec3(e, 0.0, 0.0)) - _infinitium_lum(wp - vec3(e, 0.0, 0.0)),
                  _infinitium_lum(wp + vec3(0.0, e, 0.0)) - _infinitium_lum(wp - vec3(0.0, e, 0.0)),
                  _infinitium_lum(wp + vec3(0.0, 0.0, e)) - _infinitium_lum(wp - vec3(0.0, 0.0, e)));
    float len = length(g);
    return len > 1.0e-4 ? g / len : vec3(0.0, 1.0, 0.0);
}

// Normal da face embutida nos bits 3..5 do material (0 = sem face; 1..6 = ModelQuadFacing+1).
vec3 _infinitium_face_normal(uint material) {
    uint code = (material >> 3u) & 7u;

    if (code == 1u) return vec3( 1.0,  0.0,  0.0);
    if (code == 2u) return vec3( 0.0,  1.0,  0.0);
    if (code == 3u) return vec3( 0.0,  0.0,  1.0);
    if (code == 4u) return vec3(-1.0,  0.0,  0.0);
    if (code == 5u) return vec3( 0.0, -1.0,  0.0);
    if (code == 6u) return vec3( 0.0,  0.0, -1.0);
    return vec3(0.0);
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

    // Base vanilla: cor do vertice multiplicada pelo lightmap.
    vec2 lc = _vert_tex_light_coord;
    v_Color = _vert_color * texture(u_LightTex, lc);

#ifdef INFINITIUM_LIGHT
    // Luz RV no ponto (grelha) + luz da mao avaliada com a MESMA atenuacao do RV.
    vec3 rv = _infinitium_grid(position);

    float handLvl = u_InfinitiumHand.w;

    if (handLvl > 0.0) {
        vec3 delta = position - u_InfinitiumHand.xyz;
        float dist = length(delta);
        float f = max(0.0, 1.0 - dist / handLvl);
        float atten = 0.0625 * handLvl * f * f;
        rv += u_InfinitiumHandColor * atten;
    }

    // Direcao da luz (gradiente do campo RV) para o sombreamento N.L. No modo solido (1)
    // nao e necessaria -- poupa-se o custo do gradiente e o modo fica realmente o mais barato.
    vec3 lightDir = vec3(0.0, 1.0, 0.0);

    if (u_InfinitiumShadingMode != 1) {
        lightDir = _infinitium_light_dir(position);
    }

    // Sombreamento por vertice (modo 0): N.L com a normal da face; costas suavizadas.
    float shade = 1.0;

    if (u_InfinitiumShadingMode == 0) {
        vec3 nrm = _infinitium_face_normal(_material_params);

        if (nrm != vec3(0.0)) {
            float ndl = max(0.0, dot(nrm, lightDir));
            shade = mix(1.0, ndl, 0.7);
        }
    }

    // Modo de cor: 0 = so blocklight (mantem o ceu vanilla); 1 = tudo (sem lightmap).
    vec3 lightResult;

    if (u_InfinitiumColorMode == 0) {
        vec3 skyPart = texture(u_LightTex, vec2(0.0, lc.y)).rgb; // so a componente de ceu
        lightResult = skyPart + rv * shade;
    } else {
        float sky = pow(clamp(lc.y * (16.0 / 15.0), 0.0, 1.0), 1.3);
        vec3 skyAmb = sky * vec3(1.0, 0.98, 0.95);
        lightResult = skyAmb + rv * shade;
    }

    v_Color = vec4(_vert_color.rgb * lightResult, _vert_color.a);

    // Para o modo N.L por pixel (2), o fragmento precisa da posicao e da direcao da luz.
    v_InfWorldPos = position;
    v_InfLightDir = lightDir;
#endif

    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
