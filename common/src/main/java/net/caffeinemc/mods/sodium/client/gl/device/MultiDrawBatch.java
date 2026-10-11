package net.caffeinemc.mods.sodium.client.gl.device;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import java.nio.IntBuffer;

/**
 * Provides a fixed-size queue for building a draw-command list usable with
 * {@link org.lwjgl.opengl.GL33C#glMultiDrawElementsBaseVertex(int, IntBuffer, int, PointerBuffer, IntBuffer)}.
 */
public final class MultiDrawBatch {
    private static final int ELEMENTS_PER_PRIMITIVE = 6;
    private static final int VERTICES_PER_PRIMITIVE = 4;

    public final long pElementPointer;
    public final long pElementCount;
    public final long pBaseVertex;

    public int size;
    public boolean isFilled;

    public MultiDrawBatch(int capacity) {
        this.pElementPointer = MemoryUtil.nmemAlignedAlloc(32, (long) capacity * Pointer.POINTER_SIZE);
        MemoryUtil.memSet(this.pElementPointer, 0x0, (long) capacity * Pointer.POINTER_SIZE);

        this.pElementCount = MemoryUtil.nmemAlignedAlloc(32, (long) capacity * Integer.BYTES);
        this.pBaseVertex = MemoryUtil.nmemAlignedAlloc(32, (long) capacity * Integer.BYTES);
    }

    public void clear() {
        this.size = 0;
        this.isFilled = false;
    }

    public void delete() {
        MemoryUtil.nmemAlignedFree(this.pElementPointer);
        MemoryUtil.nmemAlignedFree(this.pElementCount);
        MemoryUtil.nmemAlignedFree(this.pBaseVertex);
    }

    public boolean isEmpty() {
        return this.size <= 0;
    }

    public int getIndexBufferSize() {
        int elements = 0;

        for (var index = 0; index < this.size; index++) {
            elements = Math.max(elements, MemoryUtil.memGetInt(this.pElementCount + ((long) index * Integer.BYTES)));
        }

        return elements;
    }

    /**
     * Combines consecutive draw commands which read from the same index buffer and whose vertex
     * ranges are exactly contiguous, cutting the number of sub-draws the driver must replay.
     *
     * <p>This is only valid against the shared quad index buffer (the non-translucent path), which
     * holds a monotonic quad pattern. Because the pattern is monotonic, sliding a command's element
     * window forward by {@code C} elements raises every index value by {@code (C/6)*4} vertices, and
     * that shift is exactly compensated by advancing the base vertex. So {@code (E,C1,V1)} followed
     * by {@code (E,C2,V2)} is equivalent to a single {@code (E,C1+C2,V1)} precisely when
     * {@code V2 == V1 + (C1/6)*4}. Any gap (culled faces between groups, or a different index
     * buffer) breaks that equality, so those commands are left untouched.
     *
     * <p>Callers must ensure the shared index buffer is grown via
     * {@link #getIndexBufferSize()} after this runs, since a merged command reports a larger
     * element count.
     */
    public void mergeContiguousCommands() {
        if (this.size <= 1) {
            return;
        }

        int out = 0;

        for (int in = 1; in < this.size; in++) {
            final long outElementPointer = MemoryUtil.memGetAddress(this.pElementPointer + ((long) out << Pointer.POINTER_SHIFT));
            final long inElementPointer = MemoryUtil.memGetAddress(this.pElementPointer + ((long) in << Pointer.POINTER_SHIFT));

            final int outCount = MemoryUtil.memGetInt(this.pElementCount + ((long) out << 2));
            final int inCount = MemoryUtil.memGetInt(this.pElementCount + ((long) in << 2));

            final long outBaseVertex = Integer.toUnsignedLong(MemoryUtil.memGetInt(this.pBaseVertex + ((long) out << 2)));
            final long inBaseVertex = Integer.toUnsignedLong(MemoryUtil.memGetInt(this.pBaseVertex + ((long) in << 2)));

            final long outVertexSpan = (Integer.toUnsignedLong(outCount) / ELEMENTS_PER_PRIMITIVE) * VERTICES_PER_PRIMITIVE;

            if (outElementPointer == inElementPointer && inBaseVertex == (outBaseVertex + outVertexSpan)) {
                // Extend the surviving command's element count to cover both; the base vertex and
                // element pointer are unchanged since the index window just grows forward.
                MemoryUtil.memPutInt(this.pElementCount + ((long) out << 2), outCount + inCount);
            } else {
                out++;

                if (out != in) {
                    MemoryUtil.memPutAddress(this.pElementPointer + ((long) out << Pointer.POINTER_SHIFT), inElementPointer);
                    MemoryUtil.memPutInt(this.pElementCount + ((long) out << 2), inCount);
                    MemoryUtil.memPutInt(this.pBaseVertex + ((long) out << 2), MemoryUtil.memGetInt(this.pBaseVertex + ((long) in << 2)));
                }
            }
        }

        this.size = out + 1;
    }
}
