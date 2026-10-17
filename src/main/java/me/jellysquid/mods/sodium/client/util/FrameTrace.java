package me.jellysquid.mods.sodium.client.util;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Black-box frame diagnostic for the chunk render path.
 *
 * <p>This is a measuring instrument, not an optimisation. It exists to answer, with numbers taken
 * from the real device, the questions the frame budget has been argued about without evidence:
 *
 * <ul>
 *   <li>how many regions the render walk actually visits, and how many of those reach the driver;</li>
 *   <li>how many sub-draws Sodium hands over <em>before</em> its own contiguous merge and how many
 *       survive it -- the difference is what the merge is worth;</li>
 *   <li>the distribution of sub-draws per region after the merge, which is what decides whether a
 *       cross-section fold (by vertex offset) could still win anything;</li>
 *   <li>how long the CPU spends building the command list versus handing it to the driver.</li>
 * </ul>
 *
 * <p>Off unless {@code -Dsodium.trace} is set. When it is off every entry point below is guarded by
 * a {@code static final boolean} the JIT folds away, counters are never touched and nothing is
 * allocated, so a game build carries the class but not the cost.
 *
 * <p>Switches:
 * <ul>
 *   <li>{@code -Dsodium.trace} -- enable (writes {@code sodium-trace.csv} in the game directory and
 *       logs a summary every window);</li>
 *   <li>{@code -Dsodium.trace.window=N} -- frames per summary/flush, default 300;</li>
 *   <li>{@code -Dsodium.trace.finish} -- bracket every region draw with {@code glFinish} so the
 *       measured draw time is GPU completion and not just submission. This serialises the pipeline
 *       and changes the frame; use it deliberately, never to judge FPS.</li>
 * </ul>
 */
public final class FrameTrace {
    public static final boolean ENABLED = Boolean.getBoolean("sodium.trace");

    private static final boolean GL_FINISH = Boolean.getBoolean("sodium.trace.finish");
    private static final int WINDOW = Math.max(1, Integer.getInteger("sodium.trace.window", 300));

    // ---- counters ---------------------------------------------------------
    public static final int REGION_VISITED = 0;
    public static final int REGION_NO_STORAGE = 1;
    public static final int REGION_EMPTY = 2;
    public static final int REGION_DRAWN = 3;
    /** Sub-draws handed to {@code mergeContiguousCommands()}, i.e. one per set face bit. */
    public static final int SUBDRAWS_PRE = 4;
    /** Sub-draws that survived the merge and reached the driver. */
    public static final int SUBDRAWS_POST = 5;
    /** {@code SUBDRAWS_PRE - SUBDRAWS_POST}: what the contiguous merge eliminated. */
    public static final int SUBDRAWS_FOLDED = 6;
    public static final int SECTIONS_ITER = 7;
    public static final int SECTIONS_EMPTY = 8;
    /** Face bits submitted (popcount of {@code slices}). */
    public static final int FACES_SUBMITTED = 9;
    /** Face bits the plane test considered visible (popcount of {@code visible}). */
    public static final int FACES_VISIBLE = 10;
    /** Face bits the widen step added, i.e. back-facing triangles handed to the driver's cull. */
    public static final int FACES_EXPANDED = 11;
    public static final int INDEX_ENSURE = 12;
    public static final int INDEX_GROW = 13;
    public static final int CULL_QUERIES = 14;
    public static final int PASSES = 15;
    /** One per region that reached the driver: the actual {@code glMultiDraw*} call count. */
    public static final int MULTIDRAW_CALLS = 16;
    public static final int COUNT = 17;

    // ---- timers -----------------------------------------------------------
    public static final int T_RENDER = 0;
    public static final int T_FILL = 1;
    public static final int T_MERGE = 2;
    public static final int T_ENSURE = 3;
    public static final int T_DRAW = 4;
    public static final int TIMER_COUNT = 5;

    // ---- histogram --------------------------------------------------------
    // Busy, not linear: the interesting masses sit at 0-4, and whether the tail reaches 17+ is
    // exactly the question that decides if a further fold is worth writing.
    public static final int HIST_BUCKETS = 10;

    private static final int[] HIST_EDGES = { 1, 2, 3, 4, 5, 9, 17, 33, 65, Integer.MAX_VALUE };

    private static final long[] counters = new long[COUNT];
    private static final long[] timers = new long[TIMER_COUNT];
    private static final int[] histPre = new int[HIST_BUCKETS];
    private static final int[] histPost = new int[HIST_BUCKETS];

    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium/Trace");

    private static final char[] SCRATCH = new char[16384];
    private static int spos;

    private static Writer writer;
    private static boolean writerFailed;
    private static long frames;
    private static long lastFrameNs;
    private static long lastPeriodNs;

    private FrameTrace() {
    }

    // ---- emit API ---------------------------------------------------------

    public static long now() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void inc(int id) {
        if (ENABLED) {
            counters[id]++;
        }
    }

    public static void add(int id, long value) {
        if (ENABLED) {
            counters[id] += value;
        }
    }

    public static void addTime(int id, long startNs) {
        if (ENABLED) {
            timers[id] += System.nanoTime() - startNs;
        }
    }

    public static void histPreMerge(int size) {
        if (ENABLED) {
            histPre[bucket(size)]++;
        }
    }

    public static void histPostMerge(int size) {
        if (ENABLED) {
            histPost[bucket(size)]++;
        }
    }

    public static void beforeRegionDraw() {
        if (ENABLED && GL_FINISH) {
            GL11C.glFinish();
        }
    }

    public static void afterRegionDraw() {
        if (ENABLED && GL_FINISH) {
            GL11C.glFinish();
        }
    }

    private static int bucket(int size) {
        for (int i = 0; i < HIST_BUCKETS; i++) {
            if (size < HIST_EDGES[i]) {
                return i;
            }
        }

        return HIST_BUCKETS - 1;
    }

    // ---- frame boundary ---------------------------------------------------

    /**
     * Called once per frame, before the frame's own work. It closes the previous frame: the
     * counters accumulated since the last call are exactly that frame's and are written as one row.
     */
    public static void frameTick() {
        if (!ENABLED) {
            return;
        }

        final long now = System.nanoTime();
        final long period = lastFrameNs != 0 ? now - lastFrameNs : 0;
        lastFrameNs = now;
        lastPeriodNs = period;

        frames++;

        writeRow(period);
        reset();

        if (frames % WINDOW == 0) {
            flush();
            logSummary();
        }
    }

    private static void reset() {
        java.util.Arrays.fill(counters, 0L);
        java.util.Arrays.fill(timers, 0L);
        java.util.Arrays.fill(histPre, 0);
        java.util.Arrays.fill(histPost, 0);
    }

    // ---- output -----------------------------------------------------------

    private static void writeRow(long periodNs) {
        if (!ensureOpen() || writer == null) {
            return;
        }

        spos = 0;

        putLong(frames);
        putCSVMs(periodNs);
        put(',');
        if (periodNs > 0) {
            putF(1.0e9 / (double) periodNs, 1);
        } else {
            putStr("0.0");
        }

        for (int i = 0; i < COUNT; i++) {
            put(',');
            putLong(counters[i]);
        }

        for (int i = 0; i < TIMER_COUNT; i++) {
            putCSVMs(timers[i]);
        }

        for (int i = 0; i < HIST_BUCKETS; i++) {
            put(',');
            putLong(histPre[i]);
        }

        for (int i = 0; i < HIST_BUCKETS; i++) {
            put(',');
            putLong(histPost[i]);
        }

        put('\n');

        try {
            writer.write(SCRATCH, 0, spos);
        } catch (IOException e) {
            writerFailed = true;
            writer = null;
            LOGGER.warn("[trace] write failed, trace disabled: {}", e.toString());
        }
    }

    private static boolean ensureOpen() {
        if (writer != null) {
            return true;
        }

        if (writerFailed) {
            return false;
        }

        try {
            final Path file = FabricLoader.getInstance().getGameDir().resolve("sodium-trace.csv");
            writer = Files.newBufferedWriter(file);
            writer.write(HEADER);
            writer.write('\n');
            LOGGER.info("[trace] sodium.trace on, writing {}", file.toAbsolutePath());
            return true;
        } catch (Throwable t) {
            writerFailed = true;
            writer = null;
            LOGGER.warn("[trace] cannot open trace file, trace disabled: {}", t.toString());
            return false;
        }
    }

    private static void flush() {
        if (writer == null) {
            return;
        }

        try {
            writer.flush();
        } catch (IOException e) {
            writerFailed = true;
            writer = null;
        }
    }

    private static final String HEADER = buildHeader();

    private static String buildHeader() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("frame,periodMs,fps");
        sb.append(",regionVisited,regionNoStorage,regionEmpty,regionDrawn");
        sb.append(",subDrawsPre,subDrawsPost,subDrawsFolded");
        sb.append(",sectionsIter,sectionsEmpty");
        sb.append(",facesSubmitted,facesVisible,facesExpanded");
        sb.append(",indexEnsure,indexGrow,cullQueries,passes,multiDrawCalls");
        sb.append(",renderMs,fillMs,mergeMs,ensureMs,drawMs");
        for (int i = 0; i < HIST_BUCKETS; i++) {
            sb.append(",pre<").append(HIST_EDGES[i]);
        }
        for (int i = 0; i < HIST_BUCKETS; i++) {
            sb.append(",post<").append(HIST_EDGES[i]);
        }
        return sb.toString();
    }

    private static void logSummary() {
        LOGGER.info("[trace] f={} fps={} | regions v={} drawn={} empty={} noStorage={} | subDraws pre={} post={} folded={} ({}%) "
                        + "| mdCalls={} passes={} | ms render={} fill={} merge={} ensure={} draw={}",
                frames,
                lastPeriodNs > 0 ? Long.toString(1000000000L / lastPeriodNs) : "n/a",
                counters[REGION_VISITED], counters[REGION_DRAWN], counters[REGION_EMPTY], counters[REGION_NO_STORAGE],
                counters[SUBDRAWS_PRE], counters[SUBDRAWS_POST], counters[SUBDRAWS_FOLDED],
                counters[SUBDRAWS_PRE] > 0 ? (100L * counters[SUBDRAWS_FOLDED] / counters[SUBDRAWS_PRE]) : 0L,
                counters[MULTIDRAW_CALLS], counters[PASSES],
                ms(timers[T_RENDER]), ms(timers[T_FILL]), ms(timers[T_MERGE]), ms(timers[T_ENSURE]), ms(timers[T_DRAW]));
    }

    private static long ms(long ns) {
        return ns / 1000000L;
    }

    // ---- allocation-free formatting ---------------------------------------

    private static void put(char c) {
        if (spos < SCRATCH.length) {
            SCRATCH[spos++] = c;
        }
    }

    private static void putStr(String s) {
        int n = s.length();
        if (spos + n > SCRATCH.length) {
            n = SCRATCH.length - spos;
        }
        if (n > 0) {
            s.getChars(0, n, SCRATCH, spos);
            spos += n;
        }
    }

    private static void putLong(long v) {
        if (v < 0) {
            put('-');
            v = -v;
        }

        if (v == 0) {
            put('0');
            return;
        }

        final int start = spos;

        while (v > 0 && spos < SCRATCH.length) {
            SCRATCH[spos++] = (char) ('0' + (int) (v % 10L));
            v /= 10L;
        }

        for (int i = start, j = spos - 1; i < j; i++, j--) {
            final char t = SCRATCH[i];
            SCRATCH[i] = SCRATCH[j];
            SCRATCH[j] = t;
        }
    }

    private static void putF(double v, int decimals) {
        if (v < 0) {
            put('-');
            v = -v;
        }

        final long scale = decimals == 0 ? 1L : (decimals == 1 ? 10L : (decimals == 2 ? 100L : 1000L));
        final long scaled = (long) (v * (double) scale + 0.5);

        putLong(scaled / scale);

        if (decimals > 0) {
            put('.');
            final long frac = scaled % scale;
            for (long d = scale / 10L; d > 0; d /= 10L) {
                put((char) ('0' + (int) ((frac / d) % 10L)));
            }
        }
    }

    private static void putCSVMs(long ns) {
        put(',');
        putF((double) ns / 1.0e6, 4);
    }
}
