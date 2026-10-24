package me.jellysquid.mods.sodium.client.render.chunk;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.arena.GlBufferArena;
import me.jellysquid.mods.sodium.client.gl.arena.staging.StagingBuffer;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBuffer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.DrawCommandList;
import me.jellysquid.mods.sodium.client.gl.device.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlTessellation;
import me.jellysquid.mods.sodium.client.gl.tessellation.TessellationBinding;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.util.BitwiseMath;
import me.jellysquid.mods.sodium.client.util.FrameTrace;
import me.jellysquid.mods.sodium.client.util.MathUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultChunkRenderer extends ShaderChunkRenderer {
    private static final Logger LOGGER = LogManager.getLogger("Sodium-MultiRegionCoalescing");

    // Fallback single-region batch (used when coalescing is OFF)
    private final MultiDrawBatch batch;

    private final SharedQuadIndexBuffer sharedIndexBuffer;

    // ── Multi-Region Coalescing ────────────────────────────────────────────────
    // A pool of MultiDrawBatch objects (one per visible region per frame).  The
    // pool grows on demand and is never shrunk; individual batches are cleared at
    // the top of each fill, not freed, so no native allocations happen mid-frame.
    private final ArrayList<MultiDrawBatch> batchPool    = new ArrayList<>();
    private final ArrayList<GlTessellation> coalesceTess = new ArrayList<>();
    private final ArrayList<RenderRegion>   coalesceRegions = new ArrayList<>();
    // Log the first time the path is taken so the player/dev can confirm activation.
    private boolean coalescingFirstLog = true;

    // ── Cross-Section Instancing ────────────────────────────────────────────────
    // Cache de templates de geometria: hash -> (vertexBuffer, indexCount, vertexStride, vertexCount)
    private final Map<Long, InstancedTemplate> templateCache = new ConcurrentHashMap<>();
    // Buffer de instâncias (translation vectors) - cresce on demand
    private GlBuffer instanceBuffer;
    private StagingBuffer instanceStagingBuffer;
    private int instanceBufferCapacity = 0;
    // Log de ativação
    private boolean crossSectionFirstLog = true;

    // Record para template de geometria instanciada
    private record InstancedTemplate(
            GlBuffer vertexBuffer,
            int vertexCount,
            int vertexStride,
            int indexCount,
            GlTessellation tessellation
    ) {
        public void delete(CommandList commandList) {
            if (vertexBuffer != null) vertexBuffer.delete(commandList);
            if (tessellation != null) tessellation.delete(commandList);
        }
    }

    public DefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);

        this.batch = new MultiDrawBatch((ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE) + 1);
        this.sharedIndexBuffer = new SharedQuadIndexBuffer(device.createCommandList(), SharedQuadIndexBuffer.IndexType.INTEGER);
    }

    @Override
    public void render(ChunkRenderMatrices matrices,
                       CommandList commandList,
                       ChunkRenderListIterable renderLists,
                       TerrainRenderPass renderPass,
                       CameraTransform camera) {
        final long tRender = FrameTrace.now();
        FrameTrace.inc(FrameTrace.PASSES);

        super.begin(renderPass);

        boolean useBlockFaceCulling       = SodiumClientMod.options().performance.useBlockFaceCulling;
        boolean useInstancedFaceCompaction = SodiumClientMod.options().performance.useInstancedFaceCompaction;
        boolean useMultiRegionCoalescing   = SodiumClientMod.options().performance.useMultiRegionCoalescing;
        boolean useCrossSectionInstancing  = SodiumClientMod.options().performance.useCrossSectionInstancing;

        // Widening the visible faces to a contiguous run (see expandToGeometryRun) puts
        // back-facing triangles back into the batch on the strength of GL discarding them, so
        // whether it is safe at all comes down to one fact: is face culling actually enabled for
        // this pass. Read it from the driver rather than assume it -- pass.startDrawing() above
        // has just applied whatever the layer asks for, and layers are free to differ. When it is
        // off, the plain plane test below runs exactly as it always did and nothing is submitted
        // that was not submitted before. One query per render pass against thousands of sub-draws
        // is not a measurable cost, and the state is answered locally from the state object.
        boolean cullFaceEnabled = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        FrameTrace.inc(FrameTrace.CULL_QUERIES);

        ChunkShaderInterface shader = this.activeProgram.getInterface();
        shader.setProjectionMatrix(matrices.projection());
        shader.setModelViewMatrix(matrices.modelView());

        if (useMultiRegionCoalescing) {
            renderCoalesced(commandList, renderLists, renderPass, camera, shader,
                            useBlockFaceCulling, useInstancedFaceCompaction, useCrossSectionInstancing, cullFaceEnabled);
        } else {
            renderPerRegion(commandList, renderLists, renderPass, camera, shader,
                            useBlockFaceCulling, useInstancedFaceCompaction, useCrossSectionInstancing, cullFaceEnabled);
        }

        super.end(renderPass);
        FrameTrace.addTime(FrameTrace.T_RENDER, tRender);
    }

    // ── Path A: classic per-region fill-then-draw (original behaviour) ─────────
    private void renderPerRegion(CommandList commandList,
                                 ChunkRenderListIterable renderLists,
                                 TerrainRenderPass renderPass,
                                 CameraTransform camera,
                                 ChunkShaderInterface shader,
                                 boolean useBlockFaceCulling,
                                 boolean useInstancedFaceCompaction,
                                 boolean useCrossSectionInstancing,
                                 boolean cullFaceEnabled) {

        Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isReverseOrder());

        while (iterator.hasNext()) {
            ChunkRenderList renderList = iterator.next();
            FrameTrace.inc(FrameTrace.REGION_VISITED);

            var region  = renderList.getRegion();
            var storage = region.getStorage(renderPass);
            if (storage == null) { FrameTrace.inc(FrameTrace.REGION_NO_STORAGE); continue; }

            final long tFill = FrameTrace.now();
            fillCommandBuffer(commandList, this.batch, region, storage, renderList, camera, renderPass,
                              useBlockFaceCulling, cullFaceEnabled, useInstancedFaceCompaction, useCrossSectionInstancing);
            FrameTrace.addTime(FrameTrace.T_FILL, tFill);

            if (this.batch.isEmpty()) { FrameTrace.inc(FrameTrace.REGION_EMPTY); continue; }

            FrameTrace.inc(FrameTrace.REGION_DRAWN);
            FrameTrace.add(FrameTrace.SUBDRAWS_POST, this.batch.size);
            FrameTrace.histPostMerge(this.batch.size);

            final long tEnsure = FrameTrace.now();
            this.sharedIndexBuffer.ensureCapacity(commandList, this.batch.getIndexBufferSize());
            FrameTrace.addTime(FrameTrace.T_ENSURE, tEnsure);

            var tessellation = this.prepareTessellation(commandList, region);
            setModelMatrixUniforms(shader, region, camera);

            FrameTrace.inc(FrameTrace.MULTIDRAW_CALLS);
            FrameTrace.beforeRegionDraw();
            final long tDraw = FrameTrace.now();
            executeDrawBatch(commandList, tessellation, this.batch);
            FrameTrace.addTime(FrameTrace.T_DRAW, tDraw);
            FrameTrace.afterRegionDraw();
        }
    }

    // ── Path B: Multi-Region Coalescing ───────────────────────────────────────
    //
    // Two explicit phases:
    //   Phase 1 – FILL   : pure CPU work; iterates all visible regions, builds
    //                       one MultiDrawBatch per region from the pool (no native
    //                       alloc after warm-up), and pre-warms the shared index
    //                       buffer to the maximum size required.
    //   Phase 2 – DISPATCH: pure GPU submission; iterates the collected
    //                       (tessellation, batch, region) triples and calls
    //                       executeDrawBatch for each.
    //
    // Separating the phases keeps the CPU fill hot in L1/L2 and avoids the
    // CPU-stall that happens when the driver must flush a partially-filled
    // command queue in the middle of the fill loop on TBDR architectures.
    private void renderCoalesced(CommandList commandList,
                                 ChunkRenderListIterable renderLists,
                                 TerrainRenderPass renderPass,
                                 CameraTransform camera,
                                 ChunkShaderInterface shader,
                                 boolean useBlockFaceCulling,
                                 boolean useInstancedFaceCompaction,
                                 boolean useCrossSectionInstancing,
                                 boolean cullFaceEnabled) {

        coalesceTess.clear();
        coalesceRegions.clear();
        int poolIdx = 0;
        int maxIndexBufSize = 0;

        // ── Phase 1: FILL ─────────────────────────────────────────────────────
        Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isReverseOrder());

        while (iterator.hasNext()) {
            ChunkRenderList renderList = iterator.next();
            FrameTrace.inc(FrameTrace.REGION_VISITED);

            var region  = renderList.getRegion();
            var storage = region.getStorage(renderPass);
            if (storage == null) { FrameTrace.inc(FrameTrace.REGION_NO_STORAGE); continue; }

            // Grow pool on demand (only happens the first few frames).
            if (poolIdx >= batchPool.size()) {
                batchPool.add(new MultiDrawBatch((ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE) + 1));
            }
            MultiDrawBatch regionBatch = batchPool.get(poolIdx);

            final long tFill = FrameTrace.now();
            fillCommandBuffer(commandList, regionBatch, region, storage, renderList, camera, renderPass,
                              useBlockFaceCulling, cullFaceEnabled, useInstancedFaceCompaction, useCrossSectionInstancing);
            FrameTrace.addTime(FrameTrace.T_FILL, tFill);

            if (regionBatch.isEmpty()) { FrameTrace.inc(FrameTrace.REGION_EMPTY); continue; }

            FrameTrace.inc(FrameTrace.REGION_DRAWN);
            FrameTrace.add(FrameTrace.SUBDRAWS_POST, regionBatch.size);
            FrameTrace.histPostMerge(regionBatch.size);

            // Track the maximum index-buffer requirement across all regions so we
            // can call ensureCapacity once before the dispatch phase.
            int ibSize = regionBatch.getIndexBufferSize();
            if (ibSize > maxIndexBufSize) maxIndexBufSize = ibSize;

            // Prepare tessellation (VAO) during fill phase so it is ready for dispatch.
            GlTessellation tess = this.prepareTessellation(commandList, region);
            coalesceTess.add(tess);
            coalesceRegions.add(region);
            poolIdx++;
        }

        // Nothing visible – nothing to dispatch.
        if (coalesceTess.isEmpty()) return;

        // One-time log to confirm the path is active.
        if (coalescingFirstLog) {
            LOGGER.info("[Sodium] Multi-Region Coalescing ATIVO — {} regiões agrupadas; " +
                        "fase de preenchimento separada da fase de despacho (TBDR-friendly).",
                        coalesceTess.size());
            coalescingFirstLog = false;
        }

        // ── Ensure index buffer covers the largest batch ───────────────────────
        final long tEnsure = FrameTrace.now();
        this.sharedIndexBuffer.ensureCapacity(commandList, maxIndexBufSize);
        FrameTrace.addTime(FrameTrace.T_ENSURE, tEnsure);

        // ── Phase 2: DISPATCH ─────────────────────────────────────────────────
        for (int i = 0; i < coalesceTess.size(); i++) {
            setModelMatrixUniforms(shader, coalesceRegions.get(i), camera);
            FrameTrace.inc(FrameTrace.MULTIDRAW_CALLS);
            FrameTrace.beforeRegionDraw();
            final long tDraw = FrameTrace.now();
            executeDrawBatch(commandList, coalesceTess.get(i), batchPool.get(i));
            FrameTrace.addTime(FrameTrace.T_DRAW, tDraw);
            FrameTrace.afterRegionDraw();
        }
    }

    private void fillCommandBuffer(CommandList commandList,
                                           MultiDrawBatch batch,
                                           RenderRegion renderRegion,
                                           SectionRenderDataStorage renderDataStorage,
                                           ChunkRenderList renderList,
                                           CameraTransform camera,
                                           TerrainRenderPass pass,
                                           boolean useBlockFaceCulling,
                                           boolean cullFaceEnabled,
                                           boolean useInstancedFaceCompaction,
                                           boolean useCrossSectionInstancing) {
        batch.clear();

        var iterator = renderList.sectionsWithGeometryIterator(pass.isReverseOrder());

        // Sub-draws added before mergeContiguousCommands(); one per set face bit, which is exactly
        // what addDrawCommands() appends.
        int preMerge = 0;

        if (iterator != null) {
            int originX = renderRegion.getChunkX();
            int originY = renderRegion.getChunkY();
            int originZ = renderRegion.getChunkZ();

            // Cross-Section Instancing: collect sections by geometryHash
            // Map: geometryHash -> list of (sectionIndex, chunkX, chunkY, chunkZ, pMeshData, slices)
            Map<Long, List<SectionDrawData>> sectionsByHash = useCrossSectionInstancing ? new java.util.HashMap<>() : null;

            while (iterator.hasNext()) {
                int sectionIndex = iterator.nextByteAsInt();

                FrameTrace.inc(FrameTrace.SECTIONS_ITER);

                int chunkX = originX + LocalSectionIndex.unpackX(sectionIndex);
                int chunkY = originY + LocalSectionIndex.unpackY(sectionIndex);
                int chunkZ = originZ + LocalSectionIndex.unpackZ(sectionIndex);

                var pMeshData = renderDataStorage.getDataPointer(sectionIndex);

                final int geometryMask = SectionRenderDataUnsafe.getSliceMask(pMeshData);

                int slices;

                if (!useBlockFaceCulling) {
                    slices = ModelQuadFacing.ALL;
                } else {
                    final int visible = getVisibleFaces(camera.intX, camera.intY, camera.intZ, chunkX, chunkY, chunkZ);

                    FrameTrace.add(FrameTrace.FACES_VISIBLE, Integer.bitCount(visible & geometryMask));

                    if (cullFaceEnabled || useInstancedFaceCompaction) {
                        // Merge-aware: one contiguous run per section, folded to one draw below.
                        slices = expandToGeometryRun(visible, geometryMask);

                        FrameTrace.add(FrameTrace.FACES_EXPANDED, Integer.bitCount(slices & ~visible));
                    } else {
                        // Without a cull in the driver the plane test is the only thing keeping back
                        // faces off screen, so widen nothing: exactly what was always submitted.
                        slices = visible;
                    }
                }

                slices &= geometryMask;

                if (slices != 0) {
                    final int commands = Integer.bitCount(slices);

                    preMerge += commands;
                    FrameTrace.add(FrameTrace.FACES_SUBMITTED, commands);

                    if (useCrossSectionInstancing) {
                        // Obtem o geometryHash da RenderSection
                        RenderSection section = renderRegion.getSection(sectionIndex);
                        long geometryHash = section != null ? section.getGeometryHash() : 0L;

                        if (geometryHash != 0L) {
                            sectionsByHash.computeIfAbsent(geometryHash, k -> new java.util.ArrayList<>())
                                    .add(new SectionDrawData(sectionIndex, chunkX, chunkY, chunkZ, pMeshData, slices));
                            continue; // Nao adiciona ao batch normal; sera processado no fim
                        }
                    }

                    // Fallback: secao unica ou instancing desabilitado
                    addDrawCommands(batch, pMeshData, slices);
                } else {
                    FrameTrace.inc(FrameTrace.SECTIONS_EMPTY);
                }
            }

            // Processa grupos de cross-section instancing - IMPLEMENTACAO REAL
            if (useCrossSectionInstancing && sectionsByHash != null && !sectionsByHash.isEmpty()) {
                for (var entry : sectionsByHash.entrySet()) {
                    long geometryHash = entry.getKey();
                    List<SectionDrawData> group = entry.getValue();

                    if (group.size() > 1) {
                        // GRUPO COM MULTIPLAS SECOES IDENTICAS -> INSTANCED DRAW
                        processInstancedGroup(commandList, geometryHash, group, renderRegion, pass);
                    } else {
                        // Grupo de tamanho 1: caminho normal
                        SectionDrawData data = group.get(0);
                        addDrawCommands(batch, data.pMeshData, data.slices);
                    }
                }

                // Log apenas no primeiro frame (summary)
                if (crossSectionFirstLog) {
                    int totalGroups = 0;
                    int totalInstancedSections = 0;
                    for (var entry : sectionsByHash.entrySet()) {
                        if (entry.getValue().size() > 1) {
                            totalGroups++;
                            totalInstancedSections += entry.getValue().size();
                        }
                    }
                    if (totalGroups > 0) {
                        LOGGER.info("[Sodium] Cross-Section Instancing ATIVO -- {} grupos, {} secoes renderizadas via glDrawElementsInstanced",
                                totalGroups, totalInstancedSections);
                    }
                    crossSectionFirstLog = false;
                }
            }
        }

        FrameTrace.histPreMerge(preMerge);
        FrameTrace.add(FrameTrace.SUBDRAWS_PRE, preMerge);

        // Every command in this batch reads the single shared quad index buffer (a monotonic
        // pattern), so contiguous sub-draws can be folded into fewer draw calls. This must run
        // before render() calls ensureCapacity(getIndexBufferSize()), since a merged command
        // reports a larger element count and the template has to cover it.
        final long tMerge = FrameTrace.now();

        batch.mergeContiguousCommands();

        FrameTrace.addTime(FrameTrace.T_MERGE, tMerge);
        FrameTrace.add(FrameTrace.SUBDRAWS_FOLDED, preMerge - batch.size);
    }

    /**
     * Processa um grupo de secoes com geometria identica via draw instanciado.
     * Cria/usa template VAO, faz upload do instance buffer, e executa glDrawElementsInstanced.
     */
    private void processInstancedGroup(CommandList commandList, long geometryHash,
                                        List<SectionDrawData> group, RenderRegion renderRegion,
                                        TerrainRenderPass pass) {
        // Pega ou cria o template instanciado para este geometryHash
        InstancedTemplate template = templateCache.computeIfAbsent(geometryHash, h -> {
            // Usa a primeira seção do grupo como template
            SectionDrawData first = group.get(0);
            var pMeshData = first.pMeshData;
            
            // Calcula indexCount e baseVertex do primeiro slice ativo
            int indexCount = 0;
            int baseVertex = 0;
            boolean firstSlice = true;
            
            for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
                long eCount = SectionRenderDataUnsafe.getElementCount(pMeshData, facing);
                if (eCount > 0) {
                    if (firstSlice) {
                        baseVertex = (int) SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing);
                        firstSlice = false;
                    }
                    indexCount += (int) eCount;
                }
            }
            
            // Vertex buffer da região (compartilhado por todas as seções)
            var resources = renderRegion.getResources();
            GlBuffer vertexBuffer = resources.getVertexBuffer();
            int vertexStride = this.vertexFormat.getVertexFormat().getStride();
            
            GlTessellation instancedTess = createInstancedTessellation(commandList, vertexBuffer, vertexStride);
            
            return new InstancedTemplate(vertexBuffer, 0, vertexStride, indexCount, instancedTess);
        });

        // Prepara offsets das instancias (posição de cada chunk section em coordenadas de bloco)
        int instanceCount = group.size();
        float[] instanceOffsets = new float[instanceCount * 3];
        
        for (int i = 0; i < instanceCount; i++) {
            SectionDrawData data = group.get(i);
            instanceOffsets[i * 3 + 0] = data.chunkX * 16.0f;
            instanceOffsets[i * 3 + 1] = data.chunkY * 16.0f;
            instanceOffsets[i * 3 + 2] = data.chunkZ * 16.0f;
        }

        // Garante capacidade do instance buffer
        ensureInstanceBufferCapacity(commandList, instanceCount);
        
        // Upload dos offsets
        uploadInstanceOffsets(commandList, instanceOffsets);
        
        // Calcula indexCount e baseVertex do template (usa primeira seção do grupo)
        SectionDrawData first = group.get(0);
        var pMeshData = first.pMeshData;
        
        int totalIndexCount = 0;
        int baseVertex = 0;
        boolean firstSlice = true;
        
        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            long eCount = SectionRenderDataUnsafe.getElementCount(pMeshData, facing);
            if (eCount > 0) {
                if (firstSlice) {
                    baseVertex = (int) SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing);
                    firstSlice = false;
                }
                totalIndexCount += (int) eCount;
            }
        }
        
        if (totalIndexCount == 0) return;
        
        // Log detalhado no primeiro frame
        if (crossSectionFirstLog) {
            LOGGER.info("[Sodium] Cross-Section Instancing DRAW: hash=0x{} instances={} indexCount={} baseVertex={}",
                    Long.toHexString(geometryHash), instanceCount, totalIndexCount, baseVertex);
        }
        
        // Executa draw instanciado
        executeInstancedDraw(commandList, template.tessellation, totalIndexCount, baseVertex, instanceCount);
        
        FrameTrace.inc(FrameTrace.MULTIDRAW_CALLS);
        FrameTrace.add(FrameTrace.SUBDRAWS_FOLDED, instanceCount - 1); // economizamos instanceCount-1 draws
    }
    // Dados para draw de uma secao (usado no cross-section instancing)
    private static final class SectionDrawData {
        final int sectionIndex;
        final int chunkX, chunkY, chunkZ;
        final long pMeshData;
        final int slices;

        SectionDrawData(int sectionIndex, int chunkX, int chunkY, int chunkZ, long pMeshData, int slices) {
            this.sectionIndex = sectionIndex;
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.chunkZ = chunkZ;
            this.pMeshData = pMeshData;
            this.slices = slices;
        }
    }

    @SuppressWarnings("IntegerMultiplicationImplicitCastToLong")
    private static void addDrawCommands(MultiDrawBatch batch, long pMeshData, int mask) {
        final var pBaseVertex = batch.pBaseVertex;
        final var pElementCount = batch.pElementCount;

        int size = batch.size;

        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            // Uint32 -> Int32 cast is always safe and should be optimized away
            MemoryUtil.memPutInt(pBaseVertex + (size << 2), (int) SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing));
            MemoryUtil.memPutInt(pElementCount + (size << 2), (int) SectionRenderDataUnsafe.getElementCount(pMeshData, facing));

            size += (mask >> facing) & 1;
        }

        batch.size = size;
    }

    private static final int MODEL_UNASSIGNED = ModelQuadFacing.UNASSIGNED.ordinal();
    private static final int MODEL_POS_X      = ModelQuadFacing.POS_X.ordinal();
    private static final int MODEL_POS_Y      = ModelQuadFacing.POS_Y.ordinal();
    private static final int MODEL_POS_Z      = ModelQuadFacing.POS_Z.ordinal();

    private static final int MODEL_NEG_X      = ModelQuadFacing.NEG_X.ordinal();
    private static final int MODEL_NEG_Y      = ModelQuadFacing.NEG_Y.ordinal();
    private static final int MODEL_NEG_Z      = ModelQuadFacing.NEG_Z.ordinal();

    private static int getVisibleFaces(int originX, int originY, int originZ, int chunkX, int chunkY, int chunkZ) {
        // This is carefully written so that we can keep everything branch-less.
        //
        // Normally, this would be a ridiculous way to handle the problem. But the Hotspot VM's
        // heuristic for generating SETcc/CMOV instructions is broken, and it will always create a
        // branch even when a trivial ternary is encountered.
        //
        // For example, the following will never be transformed into a SETcc:
        //   (a > b) ? 1 : 0
        //
        // So we have to instead rely on sign-bit extension and masking (which generates a ton
        // of unnecessary instructions) to get this to be branch-less.
        //
        // To do this, we can transform the previous expression into the following.
        //   (b - a) >> 31
        //
        // This works because if (a > b) then (b - a) will always create a negative number. We then shift the sign bit
        // into the least significant bit's position (which also discards any bits following the sign bit) to get the
        // output we are looking for.
        //
        // If you look at the output which LLVM produces for a series of ternaries, you will instantly become distraught,
        // because it manages to a) correctly evaluate the cost of instructions, and b) go so far
        // as to actually produce vector code.  (https://godbolt.org/z/GaaEx39T9)

        int boundsMinX = (chunkX << 4), boundsMaxX = boundsMinX + 16;
        int boundsMinY = (chunkY << 4), boundsMaxY = boundsMinY + 16;
        int boundsMinZ = (chunkZ << 4), boundsMaxZ = boundsMinZ + 16;

        // the "unassigned" plane is always front-facing, since we can't check it
        int planes = (1 << MODEL_UNASSIGNED);

        planes |= BitwiseMath.greaterThan(originX, (boundsMinX - 3)) << MODEL_POS_X;
        planes |= BitwiseMath.greaterThan(originY, (boundsMinY - 3)) << MODEL_POS_Y;
        planes |= BitwiseMath.greaterThan(originZ, (boundsMinZ - 3)) << MODEL_POS_Z;

        planes |=    BitwiseMath.lessThan(originX, (boundsMaxX + 3)) << MODEL_NEG_X;
        planes |=    BitwiseMath.lessThan(originY, (boundsMaxY + 3)) << MODEL_NEG_Y;
        planes |=    BitwiseMath.lessThan(originZ, (boundsMaxZ + 3)) << MODEL_NEG_Z;

        return planes;
    }

    // Widens the camera-visible faces to every geometry face lying between the first and the last
    // of them, so that a section is submitted as one contiguous run of its vertex buffer.
    //
    // A section packs its faces back-to-back (SectionRenderDataStorage advances vertexOffset by
    // vertexCount, which is zero for a face with no geometry, so only faces that actually hold
    // geometry ever open a gap). Dropping a face *between* two kept ones therefore splits the
    // section's vertex range in two, and MultiDrawBatch.mergeContiguousCommands() can no longer
    // fold them: `inBaseVertex == outBaseVertex + outVertexSpan` fails across the gap, each run
    // stays its own command, and each command costs a separate glDrawElementsBaseVertex on the
    // driver -- on the PowerVR parts this targets, by far the most expensive thing the frame does.
    //
    // Filling the span submits only faces that sit between two visible ones. The triangles the
    // camera would see anyway are submitted exactly as before; the added ones are back-facing --
    // that is precisely why the plane test rejected them -- so GL's own cull discards them and the
    // pixels are unchanged. What it buys is that the emitted faces are contiguous, so the fold
    // above reduces the whole section to a single draw call.
    //
    // Only called when useBlockFaceCulling is on; with it off, slices is already ModelQuadFacing.ALL.
    private static int expandToGeometryRun(int visible, int geometryMask) {
        visible &= geometryMask;

        // Zero has no span to fill, and a lone face is contiguous by construction.
        if ((visible & (visible - 1)) == 0) {
            return visible;
        }

        final int lo = Integer.numberOfTrailingZeros(visible);
        final int hi = Integer.SIZE - 1 - Integer.numberOfLeadingZeros(visible);
        final int span = ((1 << (hi - lo + 1)) - 1) << lo;

        return visible | (geometryMask & span);
    }

    private static void setModelMatrixUniforms(ChunkShaderInterface shader, RenderRegion region, CameraTransform camera) {
        float x = getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX);
        float y = getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY);
        float z = getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ);

        shader.setRegionOffset(x, y, z);
    }

    private static float getCameraTranslation(int chunkBlockPos, int cameraBlockPos, float cameraPos) {
        return (chunkBlockPos - cameraBlockPos) - cameraPos;
    }

    private GlTessellation prepareTessellation(CommandList commandList, RenderRegion region) {
        var resources = region.getResources();
        var tessellation = resources.getTessellation();

        if (tessellation == null) {
            resources.updateTessellation(commandList, tessellation = this.createRegionTessellation(commandList, resources));
        }

        return tessellation;
    }

    private GlTessellation createRegionTessellation(CommandList commandList, RenderRegion.DeviceResources resources) {
        return commandList.createTessellation(GlPrimitiveType.TRIANGLES, new TessellationBinding[] {
                TessellationBinding.forVertexBuffer(resources.getVertexBuffer(), new GlVertexAttributeBinding[] {
                        new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_POSITION,
                                this.vertexFormat.getAttribute(ChunkMeshAttribute.POSITION)),
                        new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                                this.vertexFormat.getAttribute(ChunkMeshAttribute.COLOR)),
                        new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE,
                                this.vertexFormat.getAttribute(ChunkMeshAttribute.TEXTURE)),
                        new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX,
                                this.vertexFormat.getAttribute(ChunkMeshAttribute.LIGHT_MATERIAL_INDEX))
                }),
                TessellationBinding.forElementBuffer(this.sharedIndexBuffer.getBufferObject())
        });
    }

    // ── Cross-Section Instancing: Create instanced tessellation (VAO) ─────────────
    /**
     * Creates a VAO with the same vertex attributes as the region tessellation,
     * plus an instance attribute (location 10) for the instance offset.
     * The instance buffer is bound with divisor=1 so it advances once per instance.
     */
    private GlTessellation createInstancedTessellation(CommandList commandList, GlBuffer vertexBuffer, int vertexStride) {
        return commandList.createTessellation(GlPrimitiveType.TRIANGLES, new TessellationBinding[] {
                TessellationBinding.forVertexBuffer(vertexBuffer, new GlVertexAttributeBinding[] {
                        new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_POSITION,
                                this.vertexFormat.getAttribute(ChunkMeshAttribute.POSITION)),
                        new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                                this.vertexFormat.getAttribute(ChunkMeshAttribute.COLOR)),
                        new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE,
                                this.vertexFormat.getAttribute(ChunkMeshAttribute.TEXTURE)),
                        new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX,
                                this.vertexFormat.getAttribute(ChunkMeshAttribute.LIGHT_MATERIAL_INDEX))
                }),
                // Instance attribute: location 10 (a_InstanceOffset in shader)
                TessellationBinding.forVertexBuffer(instanceBuffer, new GlVertexAttributeBinding[] {
                        new GlVertexAttributeBinding(10, 3, 0, false, 12, 0) // vec3, offset 0, stride 12 (3*4)
                }).setInstanceDivisor(1),
                TessellationBinding.forElementBuffer(this.sharedIndexBuffer.getBufferObject())
        });
    }

    /**
     * Ensures the instance buffer has enough capacity for the given number of instances.
     * Grows exponentially to avoid frequent reallocations.
     */
    private void ensureInstanceBufferCapacity(CommandList commandList, int requiredInstances) {
        if (requiredInstances <= instanceBufferCapacity) return;

        int newCapacity = Math.max(instanceBufferCapacity * 2, MathUtil.ceilPowerOfTwo(requiredInstances));
        if (newCapacity < 256) newCapacity = 256; // minimum

        if (instanceBuffer != null) {
            instanceBuffer.delete(commandList);
        }

        int stride = 12; // vec3 = 3 floats = 12 bytes
        instanceStagingBuffer = commandList.createStagingBuffer(newCapacity * stride);
        instanceBuffer = commandList.createBuffer(GL31C.GL_ARRAY_BUFFER, newCapacity * stride, 
                GL31C.GL_DYNAMIC_DRAW, instanceStagingBuffer);
        instanceBufferCapacity = newCapacity;

        LOGGER.debug("[CrossSectionInstancing] Instance buffer grown to {} instances ({} KB)",
                newCapacity, (newCapacity * stride) / 1024);
    }

    /**
     * Uploads instance offsets to the instance buffer.
     * @param offsets flat array of float[instanceCount * 3] (x, y, z per instance)
     */
    private void uploadInstanceOffsets(CommandList commandList, float[] offsets) {
        if (instanceStagingBuffer == null) return;
        
        var mapped = instanceStagingBuffer.map();
        mapped.asFloatBuffer().put(offsets);
        instanceStagingBuffer.unmap();
        
        commandList.copyBuffer(instanceStagingBuffer, 0, instanceBuffer, 0, offsets.length * 4);
    }

    /**
     * Executes an instanced draw call for cross-section instancing.
     * Uses glDrawElementsInstanced with the shared index buffer.
     */
    private void executeInstancedDraw(CommandList commandList, GlTessellation tessellation, 
                                       int indexCount, int baseVertex, int instanceCount) {
        try (DrawCommandList drawCommandList = commandList.beginTessellating(tessellation)) {
            // glDrawElementsInstanced(mode, count, type, indices, instancecount)
            // We use baseVertex via the UBO or by binding the index buffer with offset
            // For instanced draw with baseVertex, we need to use the multi-draw path or 
            // ensure the index buffer covers the range
            drawCommandList.drawElementsInstancedBaseVertex(
                    GlPrimitiveType.TRIANGLES,
                    indexCount,
                    GlIndexType.UNSIGNED_INT,
                    0, // index offset (bytes)
                    instanceCount,
                    baseVertex
            );
        }
    }

    private static void executeDrawBatch(CommandList commandList, GlTessellation tessellation, MultiDrawBatch batch) {
        try (DrawCommandList drawCommandList = commandList.beginTessellating(tessellation)) {
            drawCommandList.multiDrawElementsBaseVertex(batch, GlIndexType.UNSIGNED_INT);
        }
    }

    @Override
    public void delete(CommandList commandList) {
        super.delete(commandList);

        this.sharedIndexBuffer.delete(commandList);
        this.batch.delete();

        // Free all pooled batches accumulated by the coalescing path.
        for (MultiDrawBatch b : batchPool) {
            b.delete();
        }
        batchPool.clear();
    }
}
