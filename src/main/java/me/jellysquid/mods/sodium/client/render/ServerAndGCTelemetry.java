package me.jellysquid.mods.sodium.client.render;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import net.minecraft.client.MinecraftClient;

public class ServerAndGCTelemetry {
    private static boolean gcImminent = false;
    private static long lastGcCheck = 0;
    private static long prevGCTime = 0;

    public static float getServerTickTimeMs() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isIntegratedServerRunning() && mc.getServer() != null) {
            return mc.getServer().getTickTime();
        }
        return 15.0f;
    }

    public static boolean isGCActive() {
        long now = System.currentTimeMillis();
        if (now - lastGcCheck > 500) {
            lastGcCheck = now;
            long totalGcTime = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                totalGcTime += gc.getCollectionTime();
            }
            gcImminent = (totalGcTime > prevGCTime);
            prevGCTime = totalGcTime;
        }
        return gcImminent;
    }
}
