package me.jellysquid.mods.sodium.client.render.asmd;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;

/**
 * Low-latency GC / heap monitor running on its own daemon thread.
 *
 * Samples every {@link #SAMPLE_INTERVAL_MS} and writes two fields of the ASMD
 * 128-byte telemetry buffer:
 *   - offset 8  (int)   gc_imminent_flag: 1 when the heap is >=85% saturated
 *                       and either growing fast or a collection just happened.
 *   - offset 12 (float) gc_heap_churn_rate_mb_s: megabytes of garbage allocated
 *                       per second over the last ~500ms window.
 *
 * The render/frame loop never touches this thread's state: it only reads the
 * two buffer fields already written here, so the hot path stays allocation-free.
 */
public final class GCMonitorThread extends Thread {
    private static final long SAMPLE_INTERVAL_MS = 250L;
    private static final long CHURN_WINDOW_NS = 500_000_000L; // 500ms
    private static final float SATURATION_THRESHOLD = 0.85f;
    private static final float FAST_GROWTH_MB_S = 8.0f;

    private static volatile boolean running = true;

    private GCMonitorThread() {
        super("ASMD-GC-Monitor");
        setDaemon(true);
        setPriority(Thread.MIN_PRIORITY);
    }

    public static GCMonitorThread startMonitor() {
        GCMonitorThread t = new GCMonitorThread();
        t.start();
        return t;
    }

    public static void stopMonitor() {
        running = false;
    }

    @Override
    public void run() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryPoolMXBean youngGen = findYoungGenPool();

        long prevHeapUsed = 0L;
        long prevYoungUsed = 0L;
        long prevYoungCap = 0L;
        long prevGcCount = 0L;
        long prevGcTime = 0L;
        long prevSampleNs = System.nanoTime();
        long churnWindowStartNs = prevSampleNs;
        double churnWindowBytes = 0.0;
        int imminent = 0;

        while (running) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (!running) {
                break;
            }

            long nowNs = System.nanoTime();
            long nowMs = System.currentTimeMillis();

            MemoryUsage heap = memory.getHeapMemoryUsage();
            long heapUsed = heap.getUsed();
            long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();

            long gcCount = 0L;
            long gcTime = 0L;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                long c = gc.getCollectionCount();
                long t = gc.getCollectionTime();
                if (c > 0) {
                    gcCount += c;
                }
                if (t > 0) {
                    gcTime += t;
                }
            }

            // --- churn (bytes allocated per second, ~500ms window) ---
            // Primary signal: young-gen (Eden) growth. Allocations land there;
            // a young GC resets it, so a reset means the freed amount was also
            // allocated inside the window.
            long allocated;
            if (youngGen != null) {
                MemoryUsage y = youngGen.getUsage();
                long yUsed = y.getUsed();
                long yCap = Math.max(y.getMax() > 0 ? y.getMax() : y.getCommitted(), 1L);
                if (gcCount > prevGcCount && yUsed < prevYoungUsed) {
                    // Young GC happened between samples: everything that filled
                    // the Eden region plus what remains now was allocated.
                    allocated = (prevYoungCap - prevYoungUsed) + yUsed;
                } else {
                    allocated = yUsed - prevYoungUsed;
                }
                if (allocated < 0) {
                    allocated = 0;
                }
                prevYoungUsed = yUsed;
                prevYoungCap = yCap;
            } else {
                allocated = heapUsed - prevHeapUsed;
                if (allocated < 0) {
                    allocated = 0;
                }
            }
            churnWindowBytes += allocated;

            long windowNs = nowNs - churnWindowStartNs;
            if (windowNs >= CHURN_WINDOW_NS) {
                float mbS = (float) ((churnWindowBytes / (1024.0 * 1024.0)) / (windowNs / 1.0e9));
                ASMDJavaBridge.setGcChurnMbS(mbS);
                churnWindowStartNs = nowNs;
                churnWindowBytes = 0.0;
            }

            // --- imminent flag ---
            long heapDelta = heapUsed - prevHeapUsed;
            float dtSec = (nowNs - prevSampleNs) / 1.0e9f;
            float growthMbS = dtSec > 0f ? (heapDelta / (1024.0f * 1024.0f)) / dtSec : 0f;
            boolean saturated = heapMax > 0 && ((float) heapUsed / (float) heapMax) >= SATURATION_THRESHOLD;
            boolean fastGrowth = growthMbS >= FAST_GROWTH_MB_S;
            boolean gcJustRan = gcCount > prevGcCount;
            boolean gcBusy = gcTime > prevGcTime && dtSec > 0f
                    && ((gcTime - prevGcTime) / 1000.0f) / dtSec > 0.10f;

            int nextImminent = (saturated && (fastGrowth || gcJustRan || gcBusy)) || gcBusy ? 1 : 0;
            if (nextImminent != imminent) {
                imminent = nextImminent;
                ASMDJavaBridge.setGcImminent(imminent);
            } else if (imminent == 0) {
                // Keep the buffer explicit even before the first transition.
                ASMDJavaBridge.setGcImminent(0);
            }

            prevHeapUsed = heapUsed;
            prevGcCount = gcCount;
            prevGcTime = gcTime;
            prevSampleNs = nowNs;

            // Defensive no-op: keeps nowMs referenced without allocation.
            if (nowMs < 0) {
                ASMDJavaBridge.setGcImminent(0);
            }
        }
    }

    private static MemoryPoolMXBean findYoungGenPool() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = pool.getName();
            if (name.contains("Eden") || name.contains("Young") || name.contains("Nursery")) {
                return pool;
            }
        }
        return null;
    }
}
