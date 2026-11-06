package me.jellysquid.mods.sodium.client.render.chunk.compile.tasks;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.render.chunk.greedy.UltraGreedyMesher;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.telemetry.InfinitiumTelemetry;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import me.jellysquid.mods.sodium.memory.AIMemoryEngineV3;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.util.math.BlockPos;

import java.util.BitSet;
import java.util.Map;

import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.util.math.Direction;

/**
 * Rebuilds all the meshes of a chunk for each given render pass with non-occluded blocks. The result is then uploaded
 * to graphics memory on the main thread.
 *
 * This task takes a slice of the world from the thread it is created on. Since these slices require rather large
 * array allocations, they are pooled to ensure that the garbage collector doesn't become overloaded.
 */
public class ChunkBuilderMeshingTask extends ChunkBuilderTask<ChunkBuildOutput> {
    private final RenderSection render;
    private final ChunkRenderContext renderContext;

    private final int buildTime;

    // Infinitium Telemetry (MSH): guard e timestamp vivem em CAMPOS, nunca em
    // locais de execute(). Locais novos no LVT do metodo desalinhavam o
    // @Inject de captura de locais da Iris
    // (mixins.iris.compat.sodium.json -> block_id.MixinChunkRenderRebuildTask
    // .iris$setLocalPos, CAPTURE_FAILHARD): a mixin abortava com "LVT ...
    // has incompatible changes at opcode", a transformacao da CLASSE INTEIRA
    // falhava e o mundo nao abria (crash na entrada do servidor integrado).
    // Campos nao entram no LVT, entao o LVT de execute() volta a ser o do
    // Sodium original que a Iris espera. Cada task e executada uma unica vez
    // por uma unica worker, entao ha zero corrida em cima destes campos.
    private boolean telemetryEnabled;
    private long telemetryStart;

    public ChunkBuilderMeshingTask(RenderSection render, ChunkRenderContext renderContext, int time) {
        this.render = render;
        this.renderContext = renderContext;
        this.buildTime = time;
    }

    @Override
    public ChunkBuildOutput execute(ChunkBuildContext buildContext, CancellationToken cancellationToken) {
        // Infinitium Telemetry: tempo de montagem da malha (MSH).
        //
        // O gate e lido uma unica vez aqui em cima: com o overlay desligado o
        // custo e um getstatic e um branch que nem entra. Os cancelamentos
        // (return null la embaixo) nao registram nada de proposito -- queremos o
        // tempo das montagens que viraram malha de verdade. Cada worker tem o
        // seu nanoTime, e a perda de passo do EWMA entre threads e inofensiva.
        //
        // ATENCAO: gate e timestamp sao CAMPOS (telemetryEnabled/telemetryStart),
        // nao locais -- ver o comentario la em cima: locais novos no LVT de
        // execute() quebram o @Inject de captura de locais da Iris e o mundo
        // nao abre.
        this.telemetryEnabled = InfinitiumTelemetry.isEnabled();
        this.telemetryStart = this.telemetryEnabled ? System.nanoTime() : 0L;

        BuiltSectionInfo.Builder renderData = new BuiltSectionInfo.Builder();
        ChunkOcclusionDataBuilder occluder = new ChunkOcclusionDataBuilder();

        ChunkBuildBuffers buffers = buildContext.buffers;
        buffers.init(renderData, this.render.getSectionIndex());

        BlockRenderCache cache = buildContext.cache;
        cache.init(this.renderContext);

        WorldSlice slice = cache.getWorldSlice();

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        int maxX = minX + 16;
        int maxY = minY + 16;
        int maxZ = minZ + 16;

        // Initialise with minX/minY/minZ so initial getBlockState crash context is correct
        BlockPos.Mutable blockPos = new BlockPos.Mutable(minX, minY, minZ);
        BlockPos.Mutable modelOffset = new BlockPos.Mutable();

        BlockRenderContext context = new BlockRenderContext(slice);

        try {
            for (int y = minY; y < maxY; y++) {
                if (cancellationToken.isCancelled()) {
                    return null;
                }

                for (int z = minZ; z < maxZ; z++) {
                    for (int x = minX; x < maxX; x++) {
                        BlockState blockState = slice.getBlockState(x, y, z);

                        if (blockState.isAir() && !blockState.hasBlockEntity()) {
                            continue;
                        }

                        blockPos.set(x, y, z);
                        modelOffset.set(x & 15, y & 15, z & 15);

                        if (blockState.getRenderType() == BlockRenderType.MODEL) {
                            BakedModel model = cache.getBlockModels()
                                .getModel(blockState);

                            long seed = blockState.getRenderingSeed(blockPos);

                            context.update(blockPos, modelOffset, blockState, model, seed);
                            cache.getBlockRenderer()
                                .renderModel(context, buffers);
                        }

                        FluidState fluidState = blockState.getFluidState();

                        if (!fluidState.isEmpty()) {
                            cache.getFluidRenderer().render(slice, fluidState, blockPos, modelOffset, buffers);
                        }

                        if (blockState.hasBlockEntity()) {
                            BlockEntity entity = slice.getBlockEntity(blockPos);

                            if (entity != null) {
                                BlockEntityRenderer<BlockEntity> renderer = MinecraftClient.getInstance().getBlockEntityRenderDispatcher().get(entity);

                                if (renderer != null) {
                                    renderData.addBlockEntity(entity, !renderer.rendersOutsideBoundingBox(entity));
                                }
                            }
                        }

                        if (blockState.isOpaqueFullCube(slice, blockPos)) {
                            occluder.markClosed(blockPos);
                        }
                    }
                }
            }
        } catch (CrashException ex) {
            // Propagate existing crashes (add context)
            throw fillCrashInfo(ex.getReport(), slice, blockPos);
        } catch (Exception ex) {
            // Create a new crash report for other exceptions (e.g. thrown in getQuads)
            throw fillCrashInfo(CrashReport.create(ex, "Encountered exception while building chunk meshes"), slice, blockPos);
        }

        Map<TerrainRenderPass, BuiltSectionMeshParts> meshes;

        if (SodiumClientMod.options().performance.greedyMeshingEnabled) {
            meshes = buildGreedyMeshes(buffers, cache, occluder, renderData);
        } else {
            meshes = new Reference2ReferenceOpenHashMap<>();

            for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
                BuiltSectionMeshParts mesh = buffers.createMesh(pass);

                if (mesh != null) {
                    meshes.put(pass, mesh);
                    renderData.addRenderPass(pass);
                }
            }
        }

        renderData.setOcclusionData(occluder.build());

        if (this.telemetryEnabled) {
            InfinitiumTelemetry.recordMeshMs((System.nanoTime() - this.telemetryStart) / 1.0e6);
        }

        return new ChunkBuildOutput(this.render, renderData.build(), meshes, this.buildTime);
    }

    /* =====================================================================
     *  GREEDY MESHING — caminho rápido via UltraGreedyMesher
     * ===================================================================== */
    private Map<TerrainRenderPass, BuiltSectionMeshParts> buildGreedyMeshes(
            ChunkBuildBuffers buffers,
            BlockRenderCache cache,
            ChunkOcclusionDataBuilder occluder,
            BuiltSectionInfo.Builder renderData) {

        WorldSlice slice = cache.getWorldSlice();

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        // Índice da seção local dentro do WorldSlice (3x3x3 seções)
        int relX = minX - slice.getOriginX();
        int relY = minY - slice.getOriginY();
        int relZ = minZ - slice.getOriginZ();
        int sectionIndex = WorldSlice.getLocalSectionIndex(relX >> 4, relY >> 4, relZ >> 4);

        BlockState[] blocks = slice.getBlockArrayForSection(sectionIndex);

        if (blocks == null) {
            return buildStandardMeshes(buffers, renderData);
        }

        // Calcula faceMasks: face visível se o vizinho não é opaco ou é ar
        BitSet[] faceMasks = UltraGreedyMesher.createFaceMaskArray();
        for (int i = 0; i < UltraGreedyMesher.TOTAL_BLOCKS; i++) {
            BlockState state = blocks[i];
            if (state == null || state.isAir()) continue;

            int bx = i & 0xF;
            int by = (i >> 8) & 0xF;
            int bz = (i >> 4) & 0xF;

            // Para cada face, verifica se o vizinho bloqueia a visibilidade
            for (int f = 0; f < 6; f++) {
                Direction dir = Direction.values()[f];
                int nx = bx + dir.getOffsetX();
                int ny = by + dir.getOffsetY();
                int nz = bz + dir.getOffsetZ();

                boolean visible = true;
                if (nx < 0 || nx >= 16 || ny < 0 || ny >= 16 || nz < 0 || nz >= 16) {
                    // Fora da seção — considera vizinho como ar (visível)
                    visible = true;
                } else {
                    BlockState neighbor = blocks[UltraGreedyMesher.index(nx, ny, nz)];
                    if (neighbor != null && neighbor.isOpaqueFullCube(slice, new net.minecraft.util.math.BlockPos(
                            minX + bx, minY + by, minZ + bz))) {
                        visible = false;
                    }
                }

                if (visible) {
                    faceMasks[f].set(i);
                }
            }
        }

        // Calcula mergeKeys: mesmo blockState = pode mesclar
        long[][] mergeKeys = new long[6][UltraGreedyMesher.TOTAL_BLOCKS];
        for (int i = 0; i < UltraGreedyMesher.TOTAL_BLOCKS; i++) {
            BlockState state = blocks[i];
            if (state == null || state.isAir()) continue;
            long key = System.identityHashCode(state);
            for (int f = 0; f < 6; f++) {
                mergeKeys[f][i] = key;
            }
        }

        // Executa o greedy mesher
        UltraGreedyMesher.QuadBuffer quadBuffer = UltraGreedyMesher.acquireQuadBuffer();
        UltraGreedyMesher.meshUltra(blocks, faceMasks, mergeKeys, quadBuffer);

        // Converte o QuadBuffer para BuiltSectionMeshParts
        Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = new Reference2ReferenceOpenHashMap<>();

        // Agrupa quads por passe de renderização (baseado no material do bloco)
        // Para simplificar, coloca todos no passe SOLID
        TerrainRenderPass solidPass = DefaultTerrainRenderPasses.SOLID;

        // Prepara os builders para o passe SOLID
        var builder = buffers.get(new me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material(
                solidPass, me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter.ZERO, true));

        // Como não temos acesso direto ao BakedChunkModelBuilder, usamos reflexão
        // para acessar os vertexBuffers internos. Se falhar, cai de volta para o padrão.
        try {
            java.lang.reflect.Field vbField = me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.BakedChunkModelBuilder.class
                    .getDeclaredField("vertexBuffers");
            vbField.setAccessible(true);
            ChunkMeshBufferBuilder[] vertexBuffers = (ChunkMeshBufferBuilder[]) vbField.get(builder);

            // Zera os buffers para evitar geometria duplicada com o pipeline padrão
            for (var vb : vertexBuffers) {
                vb.reset();
            }

            for (int i = 0; i < quadBuffer.size; i++) {
                int faceOrd = quadBuffer.face[i];
                int x = quadBuffer.x[i];
                int y = quadBuffer.y[i];
                int z = quadBuffer.z[i];
                int w = quadBuffer.w[i];
                int h = quadBuffer.h[i];
                int stateIdx = quadBuffer.stateIdx[i];

                BlockState state = blocks[stateIdx];
                Direction face = Direction.values()[faceOrd];
                ModelQuadFacing mqf = ModelQuadFacing.fromDirection(face);

                ChunkMeshBufferBuilder vb = vertexBuffers[mqf.ordinal()];

                ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

                float fx = x;
                float fy = y;
                float fz = z;

                switch (face) {
                    case DOWN:
                        vertices[0].x = fx; vertices[0].y = fy; vertices[0].z = fz;
                        vertices[1].x = fx + w; vertices[1].y = fy; vertices[1].z = fz;
                        vertices[2].x = fx + w; vertices[2].y = fy; vertices[2].z = fz + h;
                        vertices[3].x = fx; vertices[3].y = fy; vertices[3].z = fz + h;
                        break;
                    case UP:
                        vertices[0].x = fx; vertices[0].y = fy + 1; vertices[0].z = fz;
                        vertices[1].x = fx + w; vertices[1].y = fy + 1; vertices[1].z = fz;
                        vertices[2].x = fx + w; vertices[2].y = fy + 1; vertices[2].z = fz + h;
                        vertices[3].x = fx; vertices[3].y = fy + 1; vertices[3].z = fz + h;
                        break;
                    case NORTH:
                        vertices[0].x = fx; vertices[0].y = fy; vertices[0].z = fz;
                        vertices[1].x = fx; vertices[1].y = fy + h; vertices[1].z = fz;
                        vertices[2].x = fx; vertices[2].y = fy + h; vertices[2].z = fz + 1;
                        vertices[3].x = fx; vertices[3].y = fy; vertices[3].z = fz + 1;
                        break;
                    case SOUTH:
                        vertices[0].x = fx; vertices[0].y = fy; vertices[0].z = fz + 1;
                        vertices[1].x = fx; vertices[1].y = fy + h; vertices[1].z = fz + 1;
                        vertices[2].x = fx; vertices[2].y = fy + h; vertices[2].z = fz;
                        vertices[3].x = fx; vertices[3].y = fy; vertices[3].z = fz;
                        break;
                    case WEST:
                        vertices[0].x = fx; vertices[0].y = fy; vertices[0].z = fz;
                        vertices[1].x = fx; vertices[1].y = fy + h; vertices[1].z = fz;
                        vertices[2].x = fx; vertices[2].y = fy + h; vertices[2].z = fz + w;
                        vertices[3].x = fx; vertices[3].y = fy; vertices[3].z = fz + w;
                        break;
                    case EAST:
                    default:
                        vertices[0].x = fx + 1; vertices[0].y = fy; vertices[0].z = fz;
                        vertices[1].x = fx + 1; vertices[1].y = fy + h; vertices[1].z = fz;
                        vertices[2].x = fx + 1; vertices[2].y = fy + h; vertices[2].z = fz + w;
                        vertices[3].x = fx + 1; vertices[3].y = fy; vertices[3].z = fz + w;
                        break;
                }

                for (int v = 0; v < 4; v++) {
                    vertices[v].u = (v % 2 == 0) ? 0.0f : 1.0f;
                    vertices[v].v = (v < 2) ? 0.0f : 1.0f;
                    vertices[v].color = 0xFFFFFFFF;
                    vertices[v].light = 0xFFFFFF;
                }

                vb.push(vertices, me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials.forBlockState(state));
            }
        } catch (Exception ex) {
            // Se algo der errado com reflexão, cai de volta para o padrão
            return buildStandardMeshes(buffers, renderData);
        }

        // Cria as malhas finais para cada passe
        for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
            BuiltSectionMeshParts mesh = buffers.createMesh(pass);

            if (mesh != null) {
                meshes.put(pass, mesh);
                renderData.addRenderPass(pass);
            }
        }

        return meshes;
    }

    private Map<TerrainRenderPass, BuiltSectionMeshParts> buildStandardMeshes(
            ChunkBuildBuffers buffers,
            BuiltSectionInfo.Builder renderData) {

        Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = new Reference2ReferenceOpenHashMap<>();

        for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
            BuiltSectionMeshParts mesh = buffers.createMesh(pass);

            if (mesh != null) {
                meshes.put(pass, mesh);
                renderData.addRenderPass(pass);
            }
        }
        return meshes;
    }

    private CrashException fillCrashInfo(CrashReport report, WorldSlice slice, BlockPos pos) {
        CrashReportSection crashReportSection = report.addElement("Block being rendered", 1);

        BlockState state = null;
        try {
            state = slice.getBlockState(pos);
        } catch (Exception ignored) {}
        CrashReportSection.addBlockInfo(crashReportSection, slice, pos, state);

        crashReportSection.add("Chunk section", this.render);
        if (this.renderContext != null) {
            crashReportSection.add("Render context volume", this.renderContext.getVolume());
        }

        return new CrashException(report);
    }
}
