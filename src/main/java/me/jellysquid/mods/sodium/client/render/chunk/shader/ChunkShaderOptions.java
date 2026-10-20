package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

public record ChunkShaderOptions(ChunkFogMode fog, TerrainRenderPass pass, ChunkVertexType vertexType) {
    public ShaderConstants constants() {
        return this.constants(false);
    }

    public ShaderConstants constants(boolean coloredLighting) {
        ShaderConstants.Builder constants = ShaderConstants.builder();
        constants.addAll(this.fog.getDefines());

        if (this.pass.supportsFragmentDiscard()) {
            constants.add("USE_FRAGMENT_DISCARD");
        }

        constants.add("USE_VERTEX_COMPRESSION"); // TODO: allow compact vertex format to be disabled

        if (coloredLighting) {
            constants.add("COLORED_LIGHTING");
        }

        return constants.build();
    }
}
