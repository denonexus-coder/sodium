package me.jellysquid.mods.sodium.client.render.chunk;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.*;
import me.jellysquid.mods.sodium.client.render.MakeupProfile;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.ColoredLighting;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.shader.*;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.util.Identifier;

import java.util.Map;

public abstract class ShaderChunkRenderer implements ChunkRenderer {
    private final Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> programs = new Object2ObjectOpenHashMap<>();

    protected final ChunkVertexType vertexType;
    protected final GlVertexFormat<ChunkMeshAttribute> vertexFormat;

    protected final RenderDevice device;

    protected GlProgram<ChunkShaderInterface> activeProgram;

    public ShaderChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        this.device = device;
        this.vertexType = vertexType;
        this.vertexFormat = vertexType.getVertexFormat();
    }

    protected GlProgram<ChunkShaderInterface> compileProgram(ChunkShaderOptions options) {
        GlProgram<ChunkShaderInterface> program = this.programs.get(options);

        if (program == null) {
            this.programs.put(options, program = this.createShader("blocks/block_layer_opaque", options));
        }

        return program;
    }

    private GlProgram<ChunkShaderInterface> createShader(String path, ChunkShaderOptions options) {
        // Motor B owns the transport when its switch is on: the shader must not also sum the old
        // grid or the analytic light list, or the same torch would be counted twice. The engine
        // stays compiled out (ENABLED is false), but the guard keeps the two exclusive anyway.
        boolean colored = ColoredLighting.isActive() && !options.motor().motorB();
        boolean analytic = colored && ColoredLighting.useAnalytic();
        int maxLights = analytic ? ColoredLighting.getMaxLights() : 0;

        try {
            return createShaderSafely(path, options, colored, analytic, maxLights);
        } catch (RuntimeException e) {
            if (analytic) {
                // A driver that rejects the uniform list must not lose the whole feature: rebuild
                // the same program with the grid engine before falling back to no colored light.
                SodiumClientMod.logger().error("Analytic colored-light shader failed to compile; falling back to the grid engine", e);
                ColoredLighting.markAnalyticFailed();

                try {
                    return createShaderSafely(path, options, true, false, 0);
                } catch (RuntimeException nested) {
                    return fallbackShader(path, options, nested);
                }
            }

            if (colored) {
                return fallbackShader(path, options, e);
            }

            throw e;
        }
    }

    /**
     * Compiles one program, retrying once without each optional visual path. A driver that cannot
     * compile the derivative-based reflection or the profile must lose that effect, not the whole
     * chunk shader (and certainly not colored lighting with it).
     */
    private GlProgram<ChunkShaderInterface> createShaderSafely(String path, ChunkShaderOptions options,
                                                               boolean colored, boolean analytic, int maxLights) {
        ChunkShaderOptions attempt = options;

        try {
            return createShader(path, attempt, colored, analytic, maxLights);
        } catch (RuntimeException first) {
            RuntimeException failure = first;

            // Motor B is dropped before anything else: it is the newest path and it is fully
            // optional, so a driver that rejects it keeps every other effect. The session keeps
            // useMotorB() false from here on, so the next pass does not try it again.
            if (attempt.motor().motorB()) {
                SodiumClientMod.logger().error("Motor B shader failed to compile; retrying without the six-axis transport", failure);
                ColoredLighting.markMotorBFailed();
                attempt = attempt.withMotor(attempt.motor().withMotorB(false));

                try {
                    return createShader(path, attempt, colored, analytic, maxLights);
                } catch (RuntimeException nested) {
                    nested.addSuppressed(failure);
                    failure = nested;
                }
            }

            // The list transport is the second coloured-light engine: drop it before the rest.
            if (attempt.motor().listLights()) {
                SodiumClientMod.logger().error("List transport shader failed to compile; retrying without the clustered list", failure);
                ColoredLighting.markListFailed();
                attempt = attempt.withMotor(attempt.motor().withListLights(false));

                try {
                    return createShader(path, attempt, colored, analytic, maxLights);
                } catch (RuntimeException nested) {
                    nested.addSuppressed(failure);
                    failure = nested;
                }
            }

            // The Motor engine is the newest and most optional path, so it is dropped first: a
            // driver that chokes on one of the Motor bodies loses the Motor effects but keeps the
            // colored light, the reflection and the profile. Without this the fallback chain never
            // removed the Motor defines and a Motor shader bug would blacken the terrain.
            if (attempt.motor().any()) {
                SodiumClientMod.logger().error("Motor shader failed to compile; retrying without the Motor engine", failure);
                attempt = attempt.withMotor(MotorShaderState.OFF);

                try {
                    return createShader(path, attempt, colored, analytic, maxLights);
                } catch (RuntimeException nested) {
                    nested.addSuppressed(failure);
                    failure = nested;
                }
            }

            // Drop the optional paths cumulatively: first the smaller reflection, then the
            // profile. Retrying the original options after the reflection failure would put the
            // reflection back and make the fallback fail again on the same driver.
            if (attempt.surfaceReflection()) {
                SodiumClientMod.logger().error("Surface reflection shader failed to compile; retrying without reflection", failure);
                attempt = attempt.withSurfaceReflection(false);

                try {
                    return createShader(path, attempt, colored, analytic, maxLights);
                } catch (RuntimeException nested) {
                    nested.addSuppressed(failure);
                    failure = nested;
                }
            }

            if (attempt.makeupProfile()) {
                SodiumClientMod.logger().error("MakeUp profile shader failed to compile; retrying without the profile", failure);
                attempt = attempt.withMakeupProfile(false);

                try {
                    return createShader(path, attempt, colored, analytic, maxLights);
                } catch (RuntimeException nested) {
                    nested.addSuppressed(failure);
                    throw nested;
                }
            }

            throw failure;
        }
    }

    private GlProgram<ChunkShaderInterface> fallbackShader(String path, ChunkShaderOptions options, RuntimeException e) {
        // The world falls back to the normal light path and the flag stays off until the game
        // is restarted, so a broken shader costs one failed compile, not a crash loop.
        SodiumClientMod.logger().error("Colored lighting shader failed to compile; falling back", e);
        ColoredLighting.markShaderFailed();

        return createShaderSafely(path, options.withSurfaceReflection(false), false, false, 0);
    }

    private GlProgram<ChunkShaderInterface> createShader(String path, ChunkShaderOptions options, boolean colored, boolean analytic, int maxLights) {
        ShaderConstants constants = options.constants(colored, analytic, maxLights);
        boolean essl = colored && ColoredLighting.isEssl();

        GlShader vertShader = ShaderLoader.loadShader(ShaderType.VERTEX,
                new Identifier("sodium", path + ".vsh"), constants, essl);

        GlShader fragShader = ShaderLoader.loadShader(ShaderType.FRAGMENT,
                new Identifier("sodium", path + ".fsh"), constants, essl);

        try {
            return GlProgram.builder(new Identifier("sodium", "chunk_shader"))
                    .attachShader(vertShader)
                    .attachShader(fragShader)
                    .bindAttribute("a_Position", ChunkShaderBindingPoints.ATTRIBUTE_POSITION)
                    .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                    .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE)
                    .bindAttribute("a_LightAndData", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX)
                    .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                    .link((shader) -> new ChunkShaderInterface(shader, options, colored, analytic));
        } finally {
            vertShader.delete();
            fragShader.delete();
        }
    }

    protected void begin(TerrainRenderPass pass) {
        pass.startDrawing();

        // Iris owns the render path whenever it is installed. The shadow and reflection controls
        // are Sodium shader features, so they are forced off here even if the config file still
        // has them on from a run without Iris. The reverse-order pass is translucent terrain
        // (water, stained glass); the option is about opaque surfaces, so it is left alone there.
        // Cutout is skipped as well: derivatives after a non-uniform discard are undefined, and
        // the effect is meant for solid ground rather than leaves and plants.
        boolean surfaceReflection = SodiumClientMod.options().experimental.surfaceReflection
                && !ColoredLighting.isIrisLoaded()
                && !pass.isReverseOrder()
                && !pass.supportsFragmentDiscard();
        boolean makeupProfile = MakeupProfile.isEnabled();
        boolean motorB = ColoredLighting.useMotorB();
        boolean listLights = ColoredLighting.useListLights();
        boolean makeupRethinking = makeupProfile && !motorB && !listLights && MakeupProfile.isRethinking();
        boolean makeupColoredLights = makeupProfile && !motorB && !listLights
                && (makeupRethinking || SodiumClientMod.options().experimental.makeupColoredLights);

        // The Motor engine is resolved per draw against the legacy options (last-wins). It is OFF
        // whenever the master switch is off or Iris owns the pipeline, so the default path keeps
        // today's shader. Different Motor configs produce different ChunkShaderOptions, so the
        // program cache stays correct.
        MotorShaderState motor = MotorShaderState.resolve();

        ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, pass, this.vertexType,
                surfaceReflection, makeupProfile, makeupColoredLights, makeupRethinking, motor);

        this.activeProgram = this.compileProgram(options);
        this.activeProgram.bind();
        this.activeProgram.getInterface()
                .setupState();
    }

    protected void end(TerrainRenderPass pass) {
        this.activeProgram.unbind();
        this.activeProgram = null;

        pass.endDrawing();
    }

    @Override
    public void delete(CommandList commandList) {
        this.programs.values()
                .forEach(GlProgram::delete);
    }

}
