package me.jellysquid.mods.sodium.client.gl.device;

import me.jellysquid.mods.sodium.client.gl.array.GlVertexArray;
import me.jellysquid.mods.sodium.client.gl.buffer.*;
import me.jellysquid.mods.sodium.client.gl.functions.DeviceFunctions;
import me.jellysquid.mods.sodium.client.gl.state.GlStateTracker;
import me.jellysquid.mods.sodium.client.gl.sync.GlFence;
import me.jellysquid.mods.sodium.client.gl.tessellation.*;
import me.jellysquid.mods.sodium.client.gl.util.EnumBitField;
import me.jellysquid.mods.sodium.client.render.MGNativeDrawer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkType;
import net.caffeinemc.mods.sodium.client.compatibility.environment.OsUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.RenderLayer;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;

public class GLRenderDevice implements RenderDevice {
    public static final GLRenderDevice INSTANCE = new GLRenderDevice();

    private final GlStateTracker stateTracker = new GlStateTracker();
    private final CommandList commandList = new ImmediateCommandList(this.stateTracker);
    private final DrawCommandList drawCommandList = new ImmediateDrawCommandList();

    private final DeviceFunctions functions = new DeviceFunctions(this);

    private boolean isActive;
    private GlTessellation activeTessellation;

    // Camera tracking for AFK detection and delta rotation
    private float lastCameraYaw = 0.0f;
    private float lastCameraPitch = 0.0f;
    private boolean cameraInitialized = false;

    private float lastServerTickMs = 0.0f;

    @Override
    public CommandList createCommandList() {
        GLRenderDevice.this.checkDeviceActive();

        return this.commandList;
    }

    @Override
    public void makeActive() {
        if (this.isActive) {
            return;
        }

        BufferRenderer.reset();

        this.stateTracker.clear();
        this.isActive = true;
    }

    @Override
    public void makeInactive() {
        if (!this.isActive) {
            return;
        }

        this.stateTracker.clear();
        this.isActive = false;
    }

    @Override
    public GLCapabilities getCapabilities() {
        return GL.getCapabilities();
    }

    @Override
    public int getSubTexelPrecisionBits() {
        // OpenGL only specifies "at least" 4 bits of sub-texel precision for texture fetches. Thankfully, nearly every
        // graphics card is Direct3D-compatible and capable of providing 8 bits of precision. The only exception to this
        // rule seems to be when using OpenGL on macOS, where it appears to arbitrarily limit the precision to 4 bits
        // *even if* the hardware is capable of better.
        if (OsUtils.getOs() == OsUtils.OperatingSystem.MAC) {
            return 4;
        }

        return 8;
    }

    @Override
    public DeviceFunctions getDeviceFunctions() {
        return this.functions;
    }

    private void checkDeviceActive() {
        if (!this.isActive) {
            throw new IllegalStateException("Tried to access device from unmanaged context");
        }
    }

    private class ImmediateCommandList implements CommandList {
        private final GlStateTracker stateTracker;

        private ImmediateCommandList(GlStateTracker stateTracker) {
            this.stateTracker = stateTracker;
        }

        @Override
        public void bindVertexArray(GlVertexArray array) {
            if (this.stateTracker.makeVertexArrayActive(array)) {
                GL30C.glBindVertexArray(array.handle());
            }
        }

        @Override
        public void uploadData(GlMutableBuffer glBuffer, ByteBuffer byteBuffer, GlBufferUsage usage) {
            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, glBuffer);

            GL20C.glBufferData(GlBufferTarget.ARRAY_BUFFER.getTargetParameter(), byteBuffer, usage.getId());
            glBuffer.setSize(byteBuffer.remaining());
        }

        @Override
        public void copyBufferSubData(GlBuffer src, GlBuffer dst, long readOffset, long writeOffset, long bytes) {
            this.bindBuffer(GlBufferTarget.COPY_READ_BUFFER, src);
            this.bindBuffer(GlBufferTarget.COPY_WRITE_BUFFER, dst);

            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, readOffset, writeOffset, bytes);
        }

        @Override
        public void bindBuffer(GlBufferTarget target, GlBuffer buffer) {
            if (this.stateTracker.makeBufferActive(target, buffer)) {
                GL20C.glBindBuffer(target.getTargetParameter(), buffer.handle());
            }
        }

        @Override
        public void unbindVertexArray() {
            if (this.stateTracker.makeVertexArrayActive(null)) {
                GL30C.glBindVertexArray(GlVertexArray.NULL_ARRAY_ID);
            }
        }

        @Override
        public void allocateStorage(GlMutableBuffer buffer, long bufferSize, GlBufferUsage usage) {
            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, buffer);

            GL20C.glBufferData(GlBufferTarget.ARRAY_BUFFER.getTargetParameter(), bufferSize, usage.getId());
            buffer.setSize(bufferSize);
        }

        @Override
        public void deleteBuffer(GlBuffer buffer) {
            if (buffer.getActiveMapping() != null) {
                this.unmap(buffer.getActiveMapping());
            }

            this.stateTracker.notifyBufferDeleted(buffer);

            int handle = buffer.handle();
            buffer.invalidateHandle();

            GL20C.glDeleteBuffers(handle);
        }

        @Override
        public void deleteVertexArray(GlVertexArray vertexArray) {
            this.stateTracker.notifyVertexArrayDeleted(vertexArray);

            int handle = vertexArray.handle();
            vertexArray.invalidateHandle();

            GL30C.glDeleteVertexArrays(handle);
        }

        @Override
        public void flush() {
            // NO-OP
        }

        @Override
        public DrawCommandList beginTessellating(GlTessellation tessellation) {
            GLRenderDevice.this.activeTessellation = tessellation;
            GLRenderDevice.this.activeTessellation.bind(GLRenderDevice.this.commandList);

            return GLRenderDevice.this.drawCommandList;
        }

        @Override
        public void deleteTessellation(GlTessellation tessellation) {
            tessellation.delete(this);
        }

        @Override
        public GlBufferMapping mapBuffer(GlBuffer buffer, long offset, long length, EnumBitField<GlBufferMapFlags> flags) {
            if (buffer.getActiveMapping() != null) {
                throw new IllegalStateException("Buffer is already mapped");
            }

            if (flags.contains(GlBufferMapFlags.PERSISTENT) && !(buffer instanceof GlImmutableBuffer)) {
                throw new IllegalStateException("Tried to map mutable buffer as persistent");
            }

            // TODO: speed this up?
            if (buffer instanceof GlImmutableBuffer) {
                EnumBitField<GlBufferStorageFlags> bufferFlags = ((GlImmutableBuffer) buffer).getFlags();

                if (flags.contains(GlBufferMapFlags.PERSISTENT) && !bufferFlags.contains(GlBufferStorageFlags.PERSISTENT)) {
                    throw new IllegalArgumentException("Tried to map non-persistent buffer as persistent");
                }

                if (flags.contains(GlBufferMapFlags.WRITE) && !bufferFlags.contains(GlBufferStorageFlags.MAP_WRITE)) {
                    throw new IllegalStateException("Tried to map non-writable buffer as writable");
                }

                if (flags.contains(GlBufferMapFlags.READ) && !bufferFlags.contains(GlBufferStorageFlags.MAP_READ)) {
                    throw new IllegalStateException("Tried to map non-readable buffer as readable");
                }
            }

            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, buffer);

            ByteBuffer buf = GL32C.glMapBufferRange(GlBufferTarget.ARRAY_BUFFER.getTargetParameter(), offset, length, flags.getBitField());

            if (buf == null) {
                throw new RuntimeException("Failed to map buffer");
            }

            GlBufferMapping mapping = new GlBufferMapping(buffer, buf);

            buffer.setActiveMapping(mapping);

            return mapping;
        }

        @Override
        public void unmap(GlBufferMapping map) {
            checkMapDisposed(map);

            GlBuffer buffer = map.getBufferObject();

            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, buffer);
            GL32C.glUnmapBuffer(GlBufferTarget.ARRAY_BUFFER.getTargetParameter());

            buffer.setActiveMapping(null);
            map.dispose();
        }

        @Override
        public void flushMappedRange(GlBufferMapping map, int offset, int length) {
            checkMapDisposed(map);

            GlBuffer buffer = map.getBufferObject();

            this.bindBuffer(GlBufferTarget.COPY_READ_BUFFER, buffer);
            GL32C.glFlushMappedBufferRange(GlBufferTarget.COPY_READ_BUFFER.getTargetParameter(), offset, length);
        }

        @Override
        public GlFence createFence() {
            return new GlFence(GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
        }

        private void checkMapDisposed(GlBufferMapping map) {
            if (map.isDisposed()) {
                throw new IllegalStateException("Buffer mapping is already disposed");
            }
        }

        @Override
        public GlMutableBuffer createMutableBuffer() {
            return new GlMutableBuffer();
        }

        @Override
        public GlImmutableBuffer createImmutableBuffer(long bufferSize, EnumBitField<GlBufferStorageFlags> flags) {
            GlImmutableBuffer buffer = new GlImmutableBuffer(flags);

            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, buffer);
            GLRenderDevice.this.functions.getBufferStorageFunctions()
                    .createBufferStorage(GlBufferTarget.ARRAY_BUFFER, bufferSize, flags);

            return buffer;
        }

        @Override
        public GlTessellation createTessellation(GlPrimitiveType primitiveType, TessellationBinding[] bindings) {
            GlVertexArrayTessellation tessellation = new GlVertexArrayTessellation(new GlVertexArray(), primitiveType, bindings);
            tessellation.init(this);

            return tessellation;
        }
    }

    private class ImmediateDrawCommandList implements DrawCommandList {
        public ImmediateDrawCommandList() {

        }

        @Override
        public void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlIndexType indexType) {
            GlPrimitiveType primitiveType = GLRenderDevice.this.activeTessellation.getPrimitiveType();

            if (MGNativeDrawer.isSupported()) {
                // Extrai os handles reais do VAO, VBO e EBO da tesselação ativa.
                // batch.pElementCount / pElementPointer / pBaseVertex são ponteiros
                // de dados do multi-draw, NÃO handles OpenGL — não os usamos como IDs.
                GlVertexArrayTessellation tess = (GlVertexArrayTessellation) GLRenderDevice.this.activeTessellation;

                // VAO: GlVertexArrayTessellation.array (private, exposto pelo getter)
                int vao = tess.getArray().handle();

                // VBO (ARRAY_BUFFER) e EBO (ELEMENT_BUFFER) — localizados pelo target,
                // sem assumir posição fixa no array de bindings.
                int vertexBuffer = tess.getBufferForTarget(GlBufferTarget.ARRAY_BUFFER).handle();
                int indexBuffer  = tess.getBufferForTarget(GlBufferTarget.ELEMENT_BUFFER).handle();

                // Telemetria real para o seletor de método de desenho do MobileGlues
                float serverTickMs = getServerTickMs();
                boolean gcImminent = isGCImminent();
                float cameraDeltaRot = computeCameraDeltaRot();
                int chunkTypeId = ChunkType.fromRenderLayer(getRenderLayerOrdinal()).id;

                MGNativeDrawer.drawBatchZeroCopy(
                        vao,
                        vertexBuffer,
                        indexBuffer,
                        indexType.getFormatId(),
                        batch.pElementCount,
                        batch.pElementPointer,
                        batch.pBaseVertex,
                        batch.size(),
                        serverTickMs, gcImminent, cameraDeltaRot,
                        chunkTypeId);
                return;
            }

            GL32C.nglMultiDrawElementsBaseVertex(primitiveType.getId(),
                    batch.pElementCount,
                    indexType.getFormatId(),
                    batch.pElementPointer,
                    batch.size(),
                    batch.pBaseVertex);
        }

        @Override
        public void endTessellating() {
            GLRenderDevice.this.activeTessellation.unbind(GLRenderDevice.this.commandList);
            GLRenderDevice.this.activeTessellation = null;
        }

        @Override
        public void flush() {
            if (GLRenderDevice.this.activeTessellation != null) {
                this.endTessellating();
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Telemetria para o seletor de método de desenho do MobileGlues
    // ────────────────────────────────────────────────────────────────

    private float getServerTickMs() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getServer() != null) {
            return (float) client.getServer().getTicks();
        }
        return 0.0f;
    }

    private boolean isGCImminent() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long freeMemory = runtime.freeMemory();
        if (maxMemory <= 0) return false;
        return (double) freeMemory / (double) maxMemory < 0.20;
    }

    private float computeCameraDeltaRot() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.gameRenderer == null || client.gameRenderer.getCamera() == null) {
            return 0.0f;
        }

        var camera = client.gameRenderer.getCamera();
        float currentYaw = camera.getYaw();
        float currentPitch = camera.getPitch();

        float deltaRot = 0.0f;
        if (cameraInitialized) {
            float yawDelta = currentYaw - lastCameraYaw;
            float pitchDelta = currentPitch - lastCameraPitch;
            // Normalize yaw delta to [-180, 180]
            while (yawDelta > 180.0f) yawDelta -= 360.0f;
            while (yawDelta < -180.0f) yawDelta += 360.0f;
            deltaRot = (float) Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
        }

        lastCameraYaw = currentYaw;
        lastCameraPitch = currentPitch;
        cameraInitialized = true;

        return deltaRot;
    }

    private int getRenderLayerOrdinal() {
        return currentRenderLayerOrdinal;
    }

    /** Define o ordinal do RenderLayer atual (chamado pelo RenderSectionManager). */
    @Override
    public void setCurrentRenderLayer(int ordinal) {
        this.currentRenderLayerOrdinal = ordinal;
    }

    private int currentRenderLayerOrdinal = ChunkType.SOLID.id; // Default: SOLID
}
