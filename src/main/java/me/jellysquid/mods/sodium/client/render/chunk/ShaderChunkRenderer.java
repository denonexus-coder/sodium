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
        boolean colored = ColoredLighting.isActive();
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
        ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, pass, this.vertexType,
                surfaceReflection, makeupProfile);

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
