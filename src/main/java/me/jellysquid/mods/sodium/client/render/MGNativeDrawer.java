package me.jellysquid.mods.sodium.client.render;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MGNativeDrawer {
    private static final Logger LOGGER = LogManager.getLogger("Sodium-MGNativeDrawer");
    private static boolean supported = false;
    private static boolean checked = false;

    private static native boolean isNativeReady();
    public static native void drawBatchZeroCopy(
            int vao, int vertexBuffer, int indexBuffer, int indexType,
            long pCounts, long pIndices, long pBaseVertices,
            int drawCount,
            float serverTickMs, boolean gcImminent, float cameraDeltaRot,
            int chunkTypeId); // Tipo do chunk para seleção adaptativa de desenho

    static synchronized void load() {
        if (checked) return;
        try {
            supported = isNativeReady();
            if (supported) {
                LOGGER.info("[Sodium] MGNativeDrawer CONECTADO com sucesso ao MobileGlues! Ultra Drawer Zero-Copy ativado.");
            }
        } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {
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
