package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

public record ChunkShaderOptions(ChunkFogMode fog, TerrainRenderPass pass, ChunkVertexType vertexType) {
    // Compat binario: o Iris fork (Iris-shaders-gles-suport 1.7.6+mc1.20.1,
    // IrisChunkProgramOverrides.java:263) constrói as opcoes com a assinatura
    // de CINCO argumentos da linha dev do fork
    // (..., boolean surfaceReflection, boolean makeupProfile) e sempre passa
    // false/false -- reflexo de superficie e perfil MakeUp ficam desligados de
    // proposito quando o Iris carregado (ver o comentario la no Iris). Esta
    // linha (main) nao tem nenhuma das duas features, entao ignorar os flags e
    // exatamente a semantica correta; sem este construtor o primeiro draw de
    // chunk com shaderpack morria com NoSuchMethodError.
    public ChunkShaderOptions(ChunkFogMode fog, TerrainRenderPass pass, ChunkVertexType vertexType,
                              boolean surfaceReflection, boolean makeupProfile) {
        this(fog, pass, vertexType);
    }

    public ShaderConstants constants() {
        ShaderConstants.Builder constants = ShaderConstants.builder();
        constants.addAll(this.fog.getDefines());

        if (this.pass.supportsFragmentDiscard()) {
            constants.add("USE_FRAGMENT_DISCARD");
        }

        constants.add("USE_VERTEX_COMPRESSION"); // TODO: allow compact vertex format to be disabled

        return constants.build();
    }
}
