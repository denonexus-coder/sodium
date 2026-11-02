package me.jellysquid.mods.sodium.client.render;

public class MGNativeDrawer {
    private static boolean supported = false;
    private static boolean checked = false;

    private static native boolean isNativeReady();
    public static native void drawBatchZeroCopy(
            int vao, int vertexBuffer, int indexBuffer, int indexType,
            long pCounts, long pIndices, long pBaseVertices,
            int drawCount,
            float serverTickMs, boolean gcImminent, float cameraDeltaRot);

    static void load() {
        try {
            isNativeReady();
            supported = true;
        } catch (UnsatisfiedLinkError ignored) {
            supported = false;
        }
        checked = true;
    }

    public static boolean isSupported() {
        if (!checked) load();
        return supported;
    }

    static {
        load();
    }
}
