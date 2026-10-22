package me.jellysquid.mods.sodium.client.render.chunk;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.*;
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

        if (!colored) {
            return createShader(path, options, false, false, 0);
        }

        boolean analytic = ColoredLighting.useAnalytic();
        int maxLights = analytic ? ColoredLighting.getMaxLights() : 0;

        try {
            return createShader(path, options, true, analytic, maxLights);
        } catch (RuntimeException e) {
            if (analytic) {
                // A driver that rejects the uniform list must not lose the whole feature: rebuild
                // the same program with the grid engine before falling back to no colored light.
                SodiumClientMod.logger().error("Analytic colored-light shader failed to compile; falling back to the grid engine", e);
                ColoredLighting.markAnalyticFailed();

                try {
                    return createShader(path, options, true, false, 0);
                } catch (RuntimeException nested) {
                    return fallbackShader(path, options, nested);
                }
            }

            return fallbackShader(path, options, e);
        }
    }

    private GlProgram<ChunkShaderInterface> fallbackShader(String path, ChunkShaderOptions options, RuntimeException e) {
        // The world falls back to the normal light path and the flag stays off until the game
        // is restarted, so a broken shader costs one failed compile, not a crash loop.
        SodiumClientMod.logger().error("Colored lighting shader failed to compile; falling back", e);
        ColoredLighting.markShaderFailed();

        return createShader(path, options, false, false, 0);
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

        ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, pass, this.vertexType);

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
