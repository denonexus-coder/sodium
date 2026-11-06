package me.jellysquid.mods.sodium.client.render.chunk;

import net.minecraft.client.render.RenderLayer;

/**
 * Tipos de chunk para seleção adaptativa de método de desenho.
 * Cada tipo tem características de GPU diferentes:
 *
 * SOLID      - Blocos opacos (pedra, terra, madeira). Maioria dos chunks.
 *            - VBO estático, poucos mudanças. Ideal para UBO instanciado.
 * CUTOUT     - Blocos com máscara (vidro, folhas, grade).
 *            - Alfate test, pode precisar de merge mais agressivo.
 * TRANSLUCENT- Blocos translúcidos (água, gelo, vidro colorido).
 *            - Ordem de desenho importa, não pode instanciar livremente.
 * ENTITY     - Entidades (mobs, itens, blocos de entidade).
 *            - Poucos draws, mudanças frequentes. Ideal para unroll.
 * FLUID      - Fluidos em movimento (lava, água corrente).
 *            - Animação, textura muda. Necessita re-upload frequente.
 * CUSTOM     - Chunks de shader/custom (shaders de água, nuvens, etc).
 *            - Shader específico, pode precisar de VBO separado.
 */
public enum ChunkType {
    SOLID(1),
    CUTOUT(2),
    TRANSLUCENT(3),
    ENTITY(4),
    FLUID(5),
    CUSTOM(6);

    public final int id;

    ChunkType(int id) {
        this.id = id;
    }

    public static ChunkType fromRenderLayer(int layerOrdinal) {
        // RenderLayer ordinal aproximado:
        // 0 = SOLID, 1 = CUTOUT, 2 = CUTOUT_MIPPED, 3 = TRANSLUCENT, etc.
        switch (layerOrdinal) {
            case 0: return SOLID;
            case 1:
            case 2: return CUTOUT;
            case 3: return TRANSLUCENT;
            default: return CUSTOM;
        }
    }

    /**
     * Converte uma instância de {@link RenderLayer} — que é abstract class no
     * Yarn 1.20.1 (class_1921) e <b>não</b> tem {@code ordinal()} — para o
     * ordinal esperado por {@link #fromRenderLayer(int)}.
     *
     * <p>Compara identidade contra os singletons estáticos do RenderLayer.
     */
    public static int ordinalOf(RenderLayer layer) {
        if (layer == null) {
            return 0;
        }
        if (layer == RenderLayer.getSolid()) {
            return 0;
        }
        if (layer == RenderLayer.getCutout()) {
            return 1;
        }
        if (layer == RenderLayer.getCutoutMipped()) {
            return 2;
        }
        if (layer == RenderLayer.getTranslucent()) {
            return 3;
        }
        return 0;
    }
}
