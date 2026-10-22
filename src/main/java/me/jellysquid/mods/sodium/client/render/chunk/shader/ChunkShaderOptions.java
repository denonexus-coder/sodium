package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

public record ChunkShaderOptions(ChunkFogMode fog, TerrainRenderPass pass, ChunkVertexType vertexType) {
    public ShaderConstants constants() {
        return this.constants(false, false, 0);
    }

    public ShaderConstants constants(boolean coloredLighting, boolean analyticLighting, int maxLights) {
        ShaderConstants.Builder constants = ShaderConstants.builder();
        constants.addAll(this.fog.getDefines());

        if (this.pass.supportsFragmentDiscard()) {
            constants.add("USE_FRAGMENT_DISCARD");
        }

        constants.add("USE_VERTEX_COMPRESSION"); // TODO: allow compact vertex format to be disabled

        if (coloredLighting) {
            constants.add("COLORED_LIGHTING");

            if (analyticLighting) {
                constants.add("ANALYTIC_LIGHTING");
                constants.add("MAX_LIGHTS", Integer.toString(maxLights));
            }
        }

        return constants.build();
    }
}
