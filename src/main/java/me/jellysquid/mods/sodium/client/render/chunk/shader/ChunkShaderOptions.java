package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.infinitium.InfinitiumLightEngine;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

public record ChunkShaderOptions(ChunkFogMode fog, TerrainRenderPass pass, ChunkVertexType vertexType,
                                 boolean infinitium) {
    public ShaderConstants constants() {
        ShaderConstants.Builder constants = ShaderConstants.builder();
        constants.addAll(this.fog.getDefines());

        if (this.pass.supportsFragmentDiscard()) {
            constants.add("USE_FRAGMENT_DISCARD");
        }

        constants.add("USE_VERTEX_COMPRESSION"); // TODO: allow compact vertex format to be disabled

        // O caminho do ILE e escolhido aqui: o programa com INFINITIUM_LIGHT e compilado sob
        // procura, por isso ligar o motor a meio do jogo compila o programa novo no proprio
        // frame e desligar volta ao programa stock -- sem reiniciar, sem recarregar nada.
        if (this.infinitium) {
            constants.add("INFINITIUM_LIGHT");
        }

        return constants.build();
    }

    /** Le o interruptor mestre ao vivo (e a falha de shader, se houver). */
    public static boolean infinitiumEnabled() {
        return InfinitiumLightEngine.isShaderEnabled();
    }
}
