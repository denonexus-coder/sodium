package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

public record ChunkShaderOptions(ChunkFogMode fog, TerrainRenderPass pass, ChunkVertexType vertexType,
                                 boolean surfaceReflection) {
    public ChunkShaderOptions withSurfaceReflection(boolean value) {
        if (this.surfaceReflection == value) {
            return this;
        }

        return new ChunkShaderOptions(this.fog, this.pass, this.vertexType, value);
    }

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

        // The reflection is a separate visual effect, not part of the colored-light engine. It
        // compiles its varyings and its derivative-based normal only when the player enables it,
        // so the default path pays nothing for it.
        if (this.surfaceReflection) {
            constants.add("SURFACE_REFLECTION");
        }

        return constants.build();
    }
}
