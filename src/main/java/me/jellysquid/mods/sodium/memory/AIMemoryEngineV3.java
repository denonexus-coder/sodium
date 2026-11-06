/*
 * AI MEMORY ENGINE V3 — INTELLIGENT RAM MANAGEMENT
 * Integrated into Sodium (Minecraft Fabric) via Mixin hooks.
 *
 * Package adapted for Sodium: me.jellysquid.mods.sodium.memory
 */
package me.jellysquid.mods.sodium.memory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import me.jellysquid.mods.sodium.client.render.asmd.GCMonitorThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI Memory Engine V3 — JVM-level intelligent memory management.
 * Designed to run alongside Sodium without JNI, zero System.gc(),
 * non-blocking monitor thread, object pooling, TTL/LRU cache,
 * EWMA allocation-rate prediction, and Mixin-ready hooks.
 */
public final class AIMemoryEngineV3 {

    private static final Logger LOGGER = LoggerFactory.getLogger("AI-Memory-V3");

    /* =====================================================================
     *  CONFIGURAÇÃO (ajustável — também lida por Mixins via setters)
     * ===================================================================== */
    public static volatile double NORMAL_THRESHOLD   = 65.0;
    public static volatile double ELEVATED_THRESHOLD = 75.0;
    public static volatile double CRITICAL_THRESHOLD = 85.0;

    public static volatile long   MONITOR_INTERVAL_MS  = 2_000L;
    public static volatile long   HEARTBEAT_INTERVAL_MS= 250L;

    public static volatile int    MAX_CACHE_ENTRIES    = 2_000;
    public static volatile long   CACHE_TTL_MS         = 300_000L; // 5 min
    public static volatile int    MAX_POOL_SIZE        = 512;
    public static volatile double ALLOC_WEIGHT_ALPHA   = 0.3;      // EWMA fator

    public static volatile boolean AUTO_CLEAN_ENABLED  = true;
    public static volatile boolean POOLING_ENABLED     = true;

    // --- Greedy Meshing (fusão com Sodium) ---
    public static volatile boolean GREEDY_MESHING_ENABLED   = true;
    public static volatile boolean GREEDY_MESHING_MT        = true;
    public static volatile int     GREEDY_MESHING_MAX_THREADS = 8;

    public static final long MAX_EXECUTION_MS = 30_000L;

    /* =====================================================================
     *  ESTADO DE RUNTIME
     * ===================================================================== */
    private static final Runtime RT = Runtime.getRuntime();

    private static final LongAdder TOTAL_ALLOCATED   = new LongAdder();
    private static final LongAdder TOTAL_POOL_HITS   = new LongAdder();
    private static final LongAdder TOTAL_POOL_MISSES = new LongAdder();
    private static final LongAdder CACHE_REMOVED     = new LongAdder();
    private static final LongAdder CACHE_EVICTED_TTL = new LongAdder();
    private static final LongAdder TEMP_REFS_RELEASED= new LongAdder();

    private static final AtomicDouble ALLOC_RATE_EWMA = new AtomicDouble(0.0);
    private static volatile long lastSampledAllocated = 0L;
    private static volatile long lastSampleTimeNs     = System.nanoTime();

    private static final DoubleAdder MAX_PRESSURE = new DoubleAdder();

    private static final AtomicLong GC_PAUSE_MAX_NS = new AtomicLong(0L);

    private static final AtomicLong HEARTBEAT_MAX_DELAY_NS = new AtomicLong(0L);

    private static final AtomicInteger CYCLE_COUNT = new AtomicInteger(0);
    private static final AtomicLong CYCLE_START_NS = new AtomicLong(System.nanoTime());

    private static final Map<Long, CacheEntry> DATA_CACHE =
        new LinkedHashMap<Long, CacheEntry>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long,CacheEntry> e) {
                return size() > MAX_CACHE_ENTRIES;
            }
        };
    private static final Object CACHE_LOCK = new Object();

    private static final Map<Long, TempRef> TEMP_REFS = new ConcurrentHashMap<>();
    private static final Object TEMP_LOCK = new Object();

    private static final Map<Class<?>, ObjectPool<?>> POOLS = new ConcurrentHashMap<>();

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile long DEADLINE_MS = System.currentTimeMillis() + MAX_EXECUTION_MS;

    /* =====================================================================
     *  TIPOS INTERNOS
     * ===================================================================== */
    private static final class CacheEntry {
        final byte[] data;
        final long putTimeNs;
        CacheEntry(byte[] data) { this.data = data; this.putTimeNs = System.nanoTime(); }
        boolean expired() { return (System.nanoTime() - putTimeNs) / 1_000_000L > CACHE_TTL_MS; }
    }

    private static final class TempRef {
        final byte[] data;
        final long createdNs;
        TempRef(byte[] data) { this.data = data; this.createdNs = System.nanoTime(); }
    }

    // ===================== OBJECT POOL =====================
    public static final class ObjectPool<T> {
        private final ArrayDeque<T> pool = new ArrayDeque<>();
        private final Supplier<T> factory;
        private final int maxSize;

        ObjectPool(Supplier<T> factory, int maxSize) {
            this.factory = factory;
            this.maxSize = maxSize;
        }

        T acquire() {
            T item;
            synchronized (pool) {
                item = pool.poll();
            }
            if (item == null) {
                TOTAL_POOL_MISSES.increment();
                item = factory.get();
            } else {
                TOTAL_POOL_HITS.increment();
            }
            return item;
        }

        void release(T item) {
            if (item == null) return;
            synchronized (pool) {
                if (pool.size() < maxSize) {
                    pool.offer(item);
                }
            }
        }

        public int size() { synchronized (pool) { return pool.size(); } }
    }

    @SuppressWarnings("unchecked")
    public static <T> ObjectPool<T> poolFor(Class<T> type, Supplier<T> factory) {
        return (ObjectPool<T>) POOLS.computeIfAbsent(type,
            k -> new ObjectPool<>(factory, MAX_POOL_SIZE));
    }

    // ===================== PRIMITIVE MAP (sem boxing) =====================
    public static final class LongLongMap {
        private static final int DEFAULT_CAPACITY = 16;
        private static final float LOAD_FACTOR = 0.75f;

        private long[] keys;
        private long[] values;
        private int size;
        private int mask;
        private int threshold;

        public LongLongMap() {
            int cap = DEFAULT_CAPACITY;
            this.mask = cap - 1;
            this.threshold = (int)(cap * LOAD_FACTOR);
            this.keys = new long[cap];
            this.values = new long[cap];
            Arrays.fill(keys, Long.MIN_VALUE);
        }

        private int hash(long k) { return (int)(k ^ (k >>> 32)) & mask; }

        public long get(long key, long defaultValue) {
            int i = hash(key);
            while (keys[i] != Long.MIN_VALUE) {
                if (keys[i] == key) return values[i];
                i = (i + 1) & mask;
            }
            return defaultValue;
        }

        public void put(long key, long value) {
            if (size >= threshold) resize();
            int i = hash(key);
            while (keys[i] != Long.MIN_VALUE) {
                if (keys[i] == key) { values[i] = value; return; }
                i = (i + 1) & mask;
            }
            keys[i] = key; values[i] = value; size++;
        }

        public void remove(long key) {
            int i = hash(key);
            while (keys[i] != Long.MIN_VALUE) {
                if (keys[i] == key) {
                    keys[i] = Long.MIN_VALUE; values[i] = 0; size--;
                    rehash(i);
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        private void rehash(int slot) {
            int i = (slot + 1) & mask;
            while (keys[i] != Long.MIN_VALUE) {
                long k = keys[i]; long v = values[i];
                keys[i] = Long.MIN_VALUE; values[i] = 0; size--;
                put(k, v);
                i = (i + 1) & mask;
            }
        }

        private void resize() {
            int oldCap = keys.length;
            int newCap = oldCap * 2;
            long[] oldKeys = keys; long[] oldVals = values;
            keys = new long[newCap]; values = new long[newCap];
            Arrays.fill(keys, Long.MIN_VALUE);
            mask = newCap - 1; threshold = (int)(newCap * LOAD_FACTOR); size = 0;
            for (int j = 0; j < oldCap; j++) {
                if (oldKeys[j] != Long.MIN_VALUE) put(oldKeys[j], oldVals[j]);
            }
        }

        public int size() { return size; }
    }

    /* =====================================================================
     *  MÉTRICAS DE HEAP
     * ===================================================================== */
    public static long usedHeap()    { return RT.totalMemory() - RT.freeMemory(); }
    public static long maxHeap()     { return RT.maxMemory(); }
    public static long availHeap()   { return maxHeap() - usedHeap(); }

    public static double pressurePct() {
        long m = maxHeap(); return m > 0 ? usedHeap() * 100.0 / m : 0.0;
    }

    /* =====================================================================
     *  ALOCAÇÃO E TAXA (EWMA)
     * ===================================================================== */
    public static void recordAllocation(long bytes) {
        TOTAL_ALLOCATED.add(bytes);
        sampleRate();
    }

    private static void sampleRate() {
        long now = TOTAL_ALLOCATED.sum();
        long nowNs = System.nanoTime();
        long dtNs = nowNs - lastSampleTimeNs;
        if (dtNs > 0) {
            double bytesPerNs = (double)(now - lastSampledAllocated) / dtNs;
            double bytesPerSec = bytesPerNs * 1_000_000_000.0;
            double prev = ALLOC_RATE_EWMA.get();
            double next = ALLOC_WEIGHT_ALPHA * bytesPerSec + (1.0 - ALLOC_WEIGHT_ALPHA) * prev;
            ALLOC_RATE_EWMA.set(next);
        }
        lastSampledAllocated = now;
        lastSampleTimeNs = nowNs;
    }

    public static double allocRateBytesPerSec() { return ALLOC_RATE_EWMA.get(); }
    public static double allocRateKBPerSec()    { return ALLOC_RATE_EWMA.get() / 1024.0; }

    /* =====================================================================
     *  PREVISÃO DE PRESSÃO
     * ===================================================================== */
    public static String predictPressure() {
        double p = pressurePct();
        double rateKB = allocRateKBPerSec();
        long avail = availHeap();

        if (rateKB <= 0 || avail <= 0) {
            return "[PRED] Sem alocação ativa ou heap esgotado";
        }
        long kbToCritical = (long)((CRITICAL_THRESHOLD - p) / 100.0 * maxHeap() / 1024.0);
        if (kbToCritical <= 0) return "[PRED] JÁ EM NÍVEL CRÍTICO";
        double seconds = kbToCritical / rateKB;

        if (seconds < 60) {
            return String.format("[PRED] Crítico em ~%ds (taxa: %.1f KB/s)", (long)seconds, rateKB);
        } else if (seconds < 3600) {
            return String.format("[PRED] Crítico em ~%.1f min", seconds / 60.0);
        } else {
            return String.format("[PRED] Crítico em >1h (taxa: %.1f KB/s)", rateKB);
        }
    }

    /* =====================================================================
     *  NÍVEL DE PRESSÃO
     * ===================================================================== */
    public enum Pressure { NORMAL, MODERADO, ELEVADO, CRITICO }

    public static Pressure classify(double p) {
        if (p >= CRITICAL_THRESHOLD) return Pressure.CRITICO;
        if (p >= ELEVATED_THRESHOLD) return Pressure.ELEVADO;
        if (p >= NORMAL_THRESHOLD)   return Pressure.MODERADO;
        return Pressure.NORMAL;
    }

    /* =====================================================================
     *  CACHE TTL/LRU (seguro — nunca remove em uso)
     * ===================================================================== */
    public static void cachePut(long key, byte[] data) {
        if (!AUTO_CLEAN_ENABLED) return;
        recordAllocation(data.length);
        synchronized (CACHE_LOCK) {
            evictExpired();
            DATA_CACHE.put(key, new CacheEntry(data));
        }
    }

    public static byte[] cacheGet(long key) {
        synchronized (CACHE_LOCK) {
            CacheEntry e = DATA_CACHE.get(key);
            if (e == null) return null;
            if (e.expired()) {
                DATA_CACHE.remove(key);
                CACHE_EVICTED_TTL.increment();
                return null;
            }
            return e.data;
        }
    }

    private static void evictExpired() {
        long now = System.nanoTime();
        Iterator<Map.Entry<Long,CacheEntry>> it = DATA_CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long,CacheEntry> ent = it.next();
            if ((now - ent.getValue().putTimeNs) / 1_000_000L > CACHE_TTL_MS) {
                it.remove();
                CACHE_EVICTED_TTL.increment();
            }
        }
    }

    public static int cleanCache() {
        if (!AUTO_CLEAN_ENABLED || DATA_CACHE.isEmpty()) return 0;
        synchronized (CACHE_LOCK) {
            int before = DATA_CACHE.size();
            int target = before / 3;
            Iterator<Map.Entry<Long,CacheEntry>> it = DATA_CACHE.entrySet().iterator();
            int removed = 0;
            while (removed < target && it.hasNext()) {
                it.next(); it.remove(); removed++;
            }
            CACHE_REMOVED.add(removed);
            return removed;
        }
    }

    public static int cacheSize() { synchronized (CACHE_LOCK) { return DATA_CACHE.size(); } }

    /* =====================================================================
     *  REFERÊNCIAS TEMPORÁRIAS (liberação segura)
     * ===================================================================== */
    public static void addTempRef(long id, byte[] data) {
        recordAllocation(data.length);
        synchronized (TEMP_LOCK) { TEMP_REFS.put(id, new TempRef(data)); }
    }

    public static int releaseTempRefs() {
        synchronized (TEMP_LOCK) {
            int n = TEMP_REFS.size();
            TEMP_REFS.clear();
            TEMP_REFS_RELEASED.add(n);
            return n;
        }
    }

    public static void releaseTempRef(long id) {
        synchronized (TEMP_LOCK) {
            if (TEMP_REFS.remove(id) != null) TEMP_REFS_RELEASED.increment();
        }
    }

    /* =====================================================================
     *  MONITORAMENTO (thread independente — não bloqueia a principal)
     * ===================================================================== */
    public static void heartbeat() {
        long t0 = System.nanoTime();
        LockSupport.parkNanos(HEARTBEAT_INTERVAL_MS * 1_000_000L);
        long elapsed = System.nanoTime() - t0;
        long delay = Math.max(0L, elapsed - HEARTBEAT_INTERVAL_MS * 1_000_000L);
        long cur = HEARTBEAT_MAX_DELAY_NS.get();
        while (delay > cur && !HEARTBEAT_MAX_DELAY_NS.compareAndSet(cur, delay)) {
            cur = HEARTBEAT_MAX_DELAY_NS.get();
        }
    }

    public static void monitorCycle() {
        sampleRate();
        double p = pressurePct();
        Pressure lvl = classify(p);

        double curMax = MAX_PRESSURE.sum();
        if (p > curMax) { MAX_PRESSURE.reset(); MAX_PRESSURE.add(p); }

        CYCLE_COUNT.incrementAndGet();

        switch (lvl) {
            case CRITICO -> {
                cleanCache();
                releaseTempRefs();
            }
            case ELEVADO -> {
                if (CYCLE_COUNT.get() % 2 == 0) cleanCache();
            }
            case MODERADO -> {
                if (CYCLE_COUNT.get() % 3 == 0) cleanCache();
            }
            case NORMAL -> { /* sem ação agressiva */ }
        }

        // GC: sem poll próprio — lê o snapshot que o GCMonitorThread já
        // coleta a cada 250ms (fonte única de GC de todo o mod).
    }

    public static void runMonitorIteration() {
        monitorCycle();
        if (CYCLE_COUNT.get() % 5 == 0) {
            synchronized (CACHE_LOCK) { evictExpired(); }
        }
    }

    /* =====================================================================
     *  ESTATÍSTICAS GC (leitura apenas — nunca altera parâmetros automaticamente)
     * ===================================================================== */
    public static long gcCollections() { return GCMonitorThread.gcCollections(); }
    public static long gcTimeMs()      { return GCMonitorThread.gcTimeMs(); }
    public static long gcPauseMaxMs()  { return GC_PAUSE_MAX_NS.get() / 1_000_000L; }

    public static void sampleGCPause(long pauseNs) {
        long cur = GC_PAUSE_MAX_NS.get();
        while (pauseNs > cur && !GC_PAUSE_MAX_NS.compareAndSet(cur, pauseNs)) {
            cur = GC_PAUSE_MAX_NS.get();
        }
    }

    /* =====================================================================
     *  HOOKS PARA MIXIN (pontos de injeção de bytecode)
     * ===================================================================== */
    public static <T> T mixinAcquire(Class<T> type, Supplier<T> factory) {
        ObjectPool<T> pool = poolFor(type, factory);
        return pool.acquire();
    }

    public static <T> void mixinRelease(Class<T> type, T instance) {
        @SuppressWarnings("unchecked")
        ObjectPool<T> pool = (ObjectPool<T>) POOLS.get(type);
        if (pool != null) pool.release(instance);
    }

    public static void mixinRecordAlloc(long bytes) { recordAllocation(bytes); }

    public static boolean mixinIsCritical() { return classify(pressurePct()) == Pressure.CRITICO; }

    public static double mixinPressurePct() { return pressurePct(); }

    /* =====================================================================
     *  INICIALIZAÇÃO / DESLIGAMENTO
     * ===================================================================== */
    public static void start() {
        if (!RUNNING.compareAndSet(false, true)) return;
        LOGGER.info("[AI-Memory-V3] Motor iniciado. Heap máximo: {}",
            fmtMB(maxHeap()));
        // Thread de monitorização própria
        Thread monitorThread = new Thread(() -> {
            while (RUNNING.get()) {
                try {
                    Thread.sleep(MONITOR_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!RUNNING.get()) break;
                runMonitorIteration();
                heartbeat();
            }
        }, "AI-Memory-V3-Monitor");
        monitorThread.setDaemon(true);
        monitorThread.setPriority(Thread.MIN_PRIORITY);
        monitorThread.start();
    }

    public static void stop() {
        RUNNING.set(false);
        synchronized (CACHE_LOCK) { DATA_CACHE.clear(); }
        synchronized (TEMP_LOCK) { TEMP_REFS.clear(); }
        POOLS.clear();
        LOGGER.info("[AI-Memory-V3] Motor parado.");
    }

    public static boolean isRunning() { return RUNNING.get(); }

    /* =====================================================================
     *  RELATÓRIO
     * ===================================================================== */
    public static void printReport() {
        LOGGER.info("═══════════════════════════════════════════════════");
        LOGGER.info("      RELATÓRIO — AI MEMORY ENGINE V3 (Sodium)");
        LOGGER.info("═══════════════════════════════════════════════════");
        LOGGER.info("[1] Heap usada:       {} / {}", fmtMB(usedHeap()), fmtMB(maxHeap()));
        LOGGER.info("[2] Pressão máxima:   %.1f%%", MAX_PRESSURE.sum());
        LOGGER.info("[3] Heap disponível:  {}", fmtMB(availHeap()));
        LOGGER.info("[4] Total alocado:    {}", fmtMB(TOTAL_ALLOCATED.sum()));
        LOGGER.info("[5] Taxa de alocação: {:.1f} KB/s (EWMA)", allocRateKBPerSec());
        LOGGER.info("[6] Previsão:         {}", predictPressure());
        LOGGER.info("[7] Pool hits:        {}  | misses: {}", TOTAL_POOL_HITS.sum(), TOTAL_POOL_MISSES.sum());
        LOGGER.info("[8] Cache entradas:   {}  | removidas: {} | TTL: {}", cacheSize(), CACHE_REMOVED.sum(), CACHE_EVICTED_TTL.sum());
        LOGGER.info("[9] Temp refs liber.: {}", TEMP_REFS_RELEASED.sum());
        LOGGER.info("[10] Ciclos GC:       {}", gcCollections());
        LOGGER.info("[11] Tempo GC:        {} ms", gcTimeMs());
        LOGGER.info("[12] Pausa GC máx:    {} ms", GC_PAUSE_MAX_NS.get() / 1_000_000L);
        LOGGER.info("[13] Heartbeat máx:   {} ms", HEARTBEAT_MAX_DELAY_NS.get() / 1_000_000.0);
        LOGGER.info("[14] Tempo execução:  {}", formatDuration(CYCLE_START_NS.get(), System.nanoTime()));
        LOGGER.info("═══════════════════════════════════════════════════");
    }

    public static String fmtMB(long v) { return String.format("%.1f MB", v / 1_048_576.0); }

    private static String formatDuration(long start, long end) {
        long ms = (end - start) / 1_000_000L;
        if (ms < 1000) return String.format("%d ms", ms);
        return String.format("%d.%03d s", ms / 1000, ms % 1000);
    }

    /* =====================================================================
     *  HELPER: AtomicDouble
     * ===================================================================== */
    public static final class AtomicDouble {
        private volatile long bits;
        public AtomicDouble(double initial) { bits = Double.doubleToRawLongBits(initial); }
        public double get() { return Double.longBitsToDouble(bits); }
        public void set(double v) { bits = Double.doubleToRawLongBits(v); }
    }
}
