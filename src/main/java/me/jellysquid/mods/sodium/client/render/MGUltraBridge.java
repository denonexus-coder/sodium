package me.jellysquid.mods.sodium.client.render;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class MGUltraBridge {
    private static final Logger LOGGER = LogManager.getLogger("Sodium-MGUltraBridge");
    private static final boolean AVAILABLE;

    static {
        boolean available = false;
        try {
            available = isAvailable();
            if (available) {
                LOGGER.info("[Sodium] MobileGlues DETECTADO! Ativando Ultra Drawer nativo com UBO Persistente.");
            }
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            available = false;
        }

        if (!available) {
            LOGGER.info("[Sodium] MobileGlues nao detectado. Utilizando MultiDraw padrao via OpenGL.");
        }

        AVAILABLE = available;
    }

    public static boolean isSupported() {
        return AVAILABLE;
    }

    private static native boolean isAvailable();
}
