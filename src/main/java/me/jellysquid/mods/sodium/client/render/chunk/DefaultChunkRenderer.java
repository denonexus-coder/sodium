package me.jellysquid.mods.sodium.client.render.chunk;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.DrawCommandList;
import me.jellysquid.mods.sodium.client.gl.device.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlTessellation;
import me.jellysquid.mods.sodium.client.gl.tessellation.TessellationBinding;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
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
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.util.Iterator;

public class DefaultChunkRenderer extends ShaderChunkRenderer {
    private final MultiDrawBatch batch;

    private final SharedQuadIndexBuffer sharedIndexBuffer;

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

        boolean useBlockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;

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

        Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isReverseOrder());

        while (iterator.hasNext()) {
            ChunkRenderList renderList = iterator.next();

            FrameTrace.inc(FrameTrace.REGION_VISITED);

            var region = renderList.getRegion();
            var storage = region.getStorage(renderPass);

            if (storage == null) {
                FrameTrace.inc(FrameTrace.REGION_NO_STORAGE);
                continue;
            }

            final long tFill = FrameTrace.now();
            fillCommandBuffer(this.batch, region, storage, renderList, camera, renderPass, useBlockFaceCulling, cullFaceEnabled);
            FrameTrace.addTime(FrameTrace.T_FILL, tFill);

            if (this.batch.isEmpty()) {
                FrameTrace.inc(FrameTrace.REGION_EMPTY);
                continue;
            }

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

        super.end(renderPass);

        FrameTrace.addTime(FrameTrace.T_RENDER, tRender);
    }

    private static void fillCommandBuffer(MultiDrawBatch batch,
                                          RenderRegion renderRegion,
                                          SectionRenderDataStorage renderDataStorage,
                                          ChunkRenderList renderList,
                                          CameraTransform camera,
                                          TerrainRenderPass pass,
                                          boolean useBlockFaceCulling,
                                          boolean cullFaceEnabled) {
        batch.clear();

        // Interrupores A/B: lidos por frame, sem reinício. Ver SodiumGameOptions.performance.
        final var perf = SodiumClientMod.options().performance;

        var iterator = renderList.sectionsWithGeometryIterator(pass.isReverseOrder());

        // Sub-draws added before mergeContiguousCommands(); one per set face bit, which is exactly
        // what addDrawCommands() appends.
        int preMerge = 0;

        if (iterator != null) {
            int originX = renderRegion.getChunkX();
            int originY = renderRegion.getChunkY();
            int originZ = renderRegion.getChunkZ();

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

                    if (cullFaceEnabled && perf.widenVisibleFaces) {
                        // Merge-aware: one contiguous run per section, folded to one draw below.
                        slices = expandToGeometryRun(visible, geometryMask);

                        FrameTrace.add(FrameTrace.FACES_EXPANDED, Integer.bitCount(slices & ~visible));
                    } else {
                        // Without a cull in the driver the plane test is the only thing keeping back
                        // faces off screen, so widen nothing: exactly what was always submitted.
                        // (Or the A/B switch is off, which submits the same faces as vanilla.)
                        slices = visible;
                    }
                }

                slices &= geometryMask;

                if (slices != 0) {
                    final int commands = Integer.bitCount(slices);

                    preMerge += commands;
                    FrameTrace.add(FrameTrace.FACES_SUBMITTED, commands);

                    addDrawCommands(batch, pMeshData, slices);
                } else {
                    FrameTrace.inc(FrameTrace.SECTIONS_EMPTY);
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

        if (perf.mergeSubDraws) {
            // Optional A/B: order the commands by their vertex offset first so the merge finds
            // the longest possible contiguous runs. Never on a reverse-order (translucent) pass,
            // where the iteration order is the paint order and must survive.
            if (perf.sortSubDraws && !pass.isReverseOrder()) {
                batch.sortByBaseVertex();
            }

            batch.mergeContiguousCommands();
        }

        FrameTrace.addTime(FrameTrace.T_MERGE, tMerge);
        FrameTrace.add(FrameTrace.SUBDRAWS_FOLDED, preMerge - batch.size);
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
    }
}
