#version 330 core

#import <sodium:include/fog.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

in float v_MaterialMipBias;
in float v_MaterialAlphaCutoff;

#ifdef INFINITIUM_LIGHT
in vec3 v_InfWorldPos;
in vec3 v_InfLightDir;
uniform int u_InfinitiumShadingMode; // 0 = vertice N.L, 1 = solido, 2 = por pixel
#endif

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

out vec4 fragColor; // The output fragment for the color framebuffer

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    // Apply per-vertex color
    diffuseColor.rgb *= v_Color.rgb;

    // Apply ambient occlusion "shade"
    diffuseColor.rgb *= v_Color.a;

#ifdef INFINITIUM_LIGHT
    // Modo de sombreamento 2: N.L POR PIXEL. A normal e a geometrica (derivadas da posicao
    // mundial interpolada); a direcao da luz vem interpolada do vertice. As derivadas sao
    // calculadas FORA do ramo condicional (derivadas em fluxo condicional sao indefinidas em
    // alguns drivers); o custo extra so aparece na multiplicacao, e o interruptor permite
    // ver em jogo o custo de cada modo.
    vec3 nrmPix = normalize(cross(dFdx(v_InfWorldPos), dFdy(v_InfWorldPos)));
    float ndlPix = max(0.0, dot(nrmPix, normalize(v_InfLightDir)));
    float pixelShade = (u_InfinitiumShadingMode == 2) ? (0.35 + 0.65 * ndlPix) : 1.0;
    diffuseColor.rgb *= pixelShade;
#endif

    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}
