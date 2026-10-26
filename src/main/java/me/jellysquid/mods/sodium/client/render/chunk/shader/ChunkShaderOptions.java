package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

public record ChunkShaderOptions(ChunkFogMode fog, TerrainRenderPass pass, ChunkVertexType vertexType,
                                 boolean surfaceReflection, boolean makeupProfile, boolean makeupColoredLights,
                                 boolean makeupRethinking, MotorShaderState motor) {
    /**
     * Kept for binary compatibility with Iris.
     *
     * <p>Iris 1.7.6 builds this record from its own mixin with the five-argument constructor
     * ({@code invokespecial ChunkShaderOptions.<init>(...ZZ)V} in IrisChunkProgramOverrides).
     * Adding the sixth component changed the canonical constructor, so without this overload the
     * game would fail with NoSuchMethodError as soon as a shader pack is active. Iris never reads
     * the profile flags, so the colored grid is left out of that path.
     */
    public ChunkShaderOptions(ChunkFogMode fog, TerrainRenderPass pass, ChunkVertexType vertexType,
                              boolean surfaceReflection, boolean makeupProfile) {
        this(fog, pass, vertexType, surfaceReflection, makeupProfile, false, false, MotorShaderState.OFF);
    }

    /**
     * Seven-argument overload kept for callers written before the Motor engine existed. The Motor
     * state defaults to {@link MotorShaderState#OFF}, so an old call produces today's shader.
     */
    public ChunkShaderOptions(ChunkFogMode fog, TerrainRenderPass pass, ChunkVertexType vertexType,
                              boolean surfaceReflection, boolean makeupProfile, boolean makeupColoredLights,
                              boolean makeupRethinking) {
        this(fog, pass, vertexType, surfaceReflection, makeupProfile, makeupColoredLights, makeupRethinking,
                MotorShaderState.OFF);
    }

    public ChunkShaderOptions withSurfaceReflection(boolean value) {
        if (this.surfaceReflection == value) {
            return this;
        }

        return new ChunkShaderOptions(this.fog, this.pass, this.vertexType, value, this.makeupProfile,
                this.makeupColoredLights, this.makeupRethinking, this.motor);
    }

    public ChunkShaderOptions withMakeupProfile(boolean value) {
        if (this.makeupProfile == value) {
            return this;
        }

        return new ChunkShaderOptions(this.fog, this.pass, this.vertexType, this.surfaceReflection, value,
                value && this.makeupColoredLights, value && this.makeupRethinking, this.motor);
    }

    /** Returns a copy with the Motor engine state replaced; used by the compile-fallback chain. */
    public ChunkShaderOptions withMotor(MotorShaderState value) {
        if (this.motor.equals(value)) {
            return this;
        }

        return new ChunkShaderOptions(this.fog, this.pass, this.vertexType, this.surfaceReflection,
                this.makeupProfile, this.makeupColoredLights, this.makeupRethinking, value);
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

        // The MakeUp profile is another optional path. It reuses the same derivative normal and
        // the same chunk attributes; only the light and water math changes.
        if (this.makeupProfile) {
            constants.add("MAKEUP_NO_EFFECTS");

            // The profile's ground light borrows the colour of the sources placed in the world.
            // It is a switch of its own: with it off the shader compiles the warm candle curve
            // only, and u_ColorLightTex is not declared, so the interface must not bind it.
            if (this.makeupColoredLights || this.makeupRethinking) {
                constants.add("MAKEUP_COLORED_LIGHTS");
            }

            // The Rethinking mode keeps the same base palette and shader code; what changes is
            // the content of the grid (per-face occlusion, translucent conduction) and the
            // palette, both resolved on the CPU. The define is kept so the two modes can be
            // told apart later without touching the transport.
            if (this.makeupRethinking) {
                constants.add("MAKEUP_RETHINKING");
            }
        }

        // The Motor engine. Every body is additive and compiles only its own define, so with the
        // whole thing off (MotorShaderState.OFF) not a single extra line reaches the compiler and
        // the program is identical to the stock shader. Each flag is already resolved last-wins
        // against the legacy options in MotorShaderState.resolve().
        if (this.motor.any()) {
            constants.add("MOTOR_ENABLED");

            if (this.motor.shadow()) {
                constants.add("MOTOR_SHADOW");
            }
            if (this.motor.sheen()) {
                constants.add("MOTOR_SHEEN");
            }
            if (this.motor.fog()) {
                constants.add("MOTOR_FOG");
            }
            if (this.motor.tonemap()) {
                constants.add("MOTOR_TONEMAP");
            }
            // The clustered grid reads the same colour atlas the colored-light engine uploads.
            // The shader guards its body with COLORED_LIGHTING / MAKEUP_COLORED_LIGHTS, so with
            // no atlas present it is a no-op.
            if (this.motor.grid()) {
                constants.add("MOTOR_GRID");
            }
            if (this.motor.gridLod()) {
                constants.add("MOTOR_GRID_LOD");
            }
            if (this.motor.shafts()) {
                constants.add("MOTOR_SHAFTS");
            }
        }

        // Motor B carries its own atlas and its own uniforms, so it is defined independently of
        // the single-pass Motor slots above: the switch in "Luz Avançada" is enough.
        if (this.motor.motorB()) {
            constants.add("MOTOR_B");
        }

        // The clustered list transport carries its own uniform buffer; defined independently of
        // the single-pass Motor slots above.
        if (this.motor.listLights()) {
            constants.add("LIST_LIGHTS");
        }

        return constants.build();
    }
}
