package me.jellysquid.mods.sodium.client.render.chunk.data;

import me.jellysquid.mods.sodium.client.gl.util.VertexRange;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;

public class BuiltSectionMeshParts {
    private final VertexRange[] ranges;
    private final NativeBuffer buffer;
    private final long geometryHash;

    public BuiltSectionMeshParts(NativeBuffer buffer, VertexRange[] ranges, long geometryHash) {
        this.ranges = ranges;
        this.buffer = buffer;
        this.geometryHash = geometryHash;
    }

    public NativeBuffer getVertexData() {
        return this.buffer;
    }

    public VertexRange[] getVertexRanges() {
        return this.ranges;
    }

    public long getGeometryHash() {
        return this.geometryHash;
    }
}
