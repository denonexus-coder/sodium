package me.jellysquid.mods.sodium.client.render.chunk.greedy;

import me.jellysquid.mods.sodium.memory.AIMemoryEngineV3;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.List;

/*
 * ULTRA GREEDY MESHER V4 — INTEGRADO AO SODIUM + AI MEMORY ENGINE V3
 *
 * Fusão do Greedy Meshing com o AI Memory Engine V3 dentro do Sodium.
 * Reduz vértices enviados para a GPU e mantém pressão de heap sob controle.
 *
 * Características:
 *   - Saída primitiva SoA (QuadBuffer) — nenhum GreedyQuad alocado durante a varredura
 *   - ThreadLocal QuadBuffer reusado entre seções
 *   - Multi-thread: múltiplas seções em paralelo (até 8 núcleos)
 *   - Índices lineares, pure bitwise, sem packing/unpacking no loop quente
 *   - Zero alocação de objetos no caminho quente de varredura
 *   - Registro de alocação via AIMemoryEngineV3.mixinRecordAlloc()
 *   - Sem System.gc(), compatível com Java 21 / Java 17
 */
public final class UltraGreedyMesher {

    public static final int SECTION_SIZE = 16;
    public static final int TOTAL_BLOCKS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final Direction[] ALL_FACES = Direction.values();
    private static final Logger LOGGER = LoggerFactory.getLogger("UltraGreedyMesher");

    private static final ThreadLocal<QuadBuffer> QUAD_TL = ThreadLocal.withInitial(QuadBuffer::new);

    private UltraGreedyMesher() {}

    /* =====================================================================
     *  QUAD BUFFER — saída primitiva SoA
     * ===================================================================== */
    public static final class QuadBuffer {
        public int[] face;
        public int[] x;
        public int[] y;
        public int[] z;
        public int[] w;
        public int[] h;
        public int[] stateIdx;
        public int size;
        public int capacity;

        public QuadBuffer() {
            this(256);
        }

        public QuadBuffer(int capacity) {
            this.capacity = capacity;
            this.face = new int[capacity];
            this.x = new int[capacity];
            this.y = new int[capacity];
            this.z = new int[capacity];
            this.w = new int[capacity];
            this.h = new int[capacity];
            this.stateIdx = new int[capacity];
            this.size = 0;
        }

        public void ensureCapacity(int need) {
            if (need > capacity) {
                int nc = capacity;
                while (nc < need) nc <<= 1;
                face = java.util.Arrays.copyOf(face, nc);
                x = java.util.Arrays.copyOf(x, nc);
                y = java.util.Arrays.copyOf(y, nc);
                z = java.util.Arrays.copyOf(z, nc);
                w = java.util.Arrays.copyOf(w, nc);
                h = java.util.Arrays.copyOf(h, nc);
                stateIdx = java.util.Arrays.copyOf(stateIdx, nc);
                capacity = nc;
            }
        }

        public void add(int face, int x, int y, int z, int w, int h, int stateIdx) {
            if (size >= capacity) ensureCapacity(size + 1);
            this.face[size] = face;
            this.x[size] = x;
            this.y[size] = y;
            this.z[size] = z;
            this.w[size] = w;
            this.h[size] = h;
            this.stateIdx[size] = stateIdx;
            size++;
        }

        public void reset() {
            size = 0;
        }
    }

    /* =====================================================================
     *  MESH ULTRA — seção única, zero alloc na varredura
     * ===================================================================== */
    public static void meshUltra(
            BlockState[] blocks,
            BitSet[] faceMasks,
            long[][] mergeKeys,
            QuadBuffer out) {

        out.reset();
        BinarySweepState bw = BinarySweepState.TL.get();
        int[] rows = bw.rows;
        long[] keys = bw.keys;
        int[] origins = bw.origins;
        int[] depthCells = bw.depthCells;
        int[] depthCounts = bw.depthCounts;

        for (Direction dir : ALL_FACES) {
            int fi = dir.ordinal();
            BitSet visible = faceMasks[fi];
            if (visible.isEmpty()) continue;

            long[] faceKeys = mergeKeys[fi];
            int[][] table = SWEEP_TO_BLOCK[fi];
            int[] inverse = BLOCK_TO_SWEEP[fi];

            java.util.Arrays.fill(depthCounts, 0);
            for (int bi = visible.nextSetBit(0); bi >= 0; bi = visible.nextSetBit(bi + 1)) {
                if (blocks[bi] == null) continue;
                int depth = inverse[bi] >>> 8;
                depthCells[(depth << 8) + depthCounts[depth]++] = bi;
            }

            for (int depth = 0; depth < SECTION_SIZE; depth++) {
                int cellCount = depthCounts[depth];
                if (cellCount == 0) continue;

                int[] lookup = table[depth];
                int cellBase = depth << 8;
                int nGroups = 0;
                int lastGid = -1;

                for (int c = 0; c < cellCount; c++) {
                    int bi = depthCells[cellBase + c];
                    BlockState block = blocks[bi];
                    int packed = inverse[bi];
                    int row = (packed >>> 4) & 0xF;
                    int col = packed & 0xF;

                    long ck = ((long) System.identityHashCode(block) << 32) | (faceKeys[bi] & 0xFFFFFFFFL);
                    int gid = -1;
                    if (lastGid >= 0 && keys[lastGid] == ck) {
                        gid = lastGid;
                    } else {
                        for (int g = 0; g < nGroups; g++) {
                            if (keys[g] == ck) { gid = g; break; }
                        }
                    }
                    if (gid == -1) {
                        if (nGroups >= BinarySweepState.CAP) {
                            continue;
                        }
                        gid = nGroups++;
                        keys[gid] = ck;
                        origins[gid] = bi;
                        for (int r = 0; r < SECTION_SIZE; r++) rows[gid * SECTION_SIZE + r] = 0;
                    }
                    lastGid = gid;
                    rows[gid * SECTION_SIZE + row] |= 1 << col;
                }

                for (int g = 0; g < nGroups; g++) {
                    int base = g * SECTION_SIZE;
                    BlockState block = blocks[origins[g]];
                    int stateIdx = origins[g];

                    for (int row = 0; row < SECTION_SIZE; row++) {
                        int bits = rows[base + row];
                        while (bits != 0) {
                            int col = Integer.numberOfTrailingZeros(bits);
                            int w = Integer.numberOfTrailingZeros(~(bits >>> col));
                            int span = ((1 << w) - 1) << col;

                            int hh = 1;
                            while (row + hh < SECTION_SIZE
                                    && (rows[base + row + hh] & span) == span) hh++;

                            for (int dr = 0; dr < hh; dr++) rows[base + row + dr] &= ~span;

                            int qi = lookup[row * SECTION_SIZE + col];
                            out.add(fi,
                                    qi & 0xF, (qi >> 8) & 0xF, (qi >> 4) & 0xF,
                                    w, hh, stateIdx);

                            bits = rows[base + row];
                        }
                    }
                }
            }
        }

        // Registro de alocação para o AI Memory Engine V3
        AIMemoryEngineV3.mixinRecordAlloc(out.size * 28); // ~28 bytes por quad
    }

    /* =====================================================================
     *  MESH ULTRA MT — múltiplas seções em paralelo
     * ===================================================================== */
    public static void meshUltraMT(
            BlockState[][] blocksSections,
            BitSet[][] faceMasksSections,
            long[][][] mergeKeysSections,
            QuadBuffer[] outBuffers,
            int sectionCount) {

        if (sectionCount <= 0) return;

        int cores = Runtime.getRuntime().availableProcessors();
        int numThreads = Math.max(1, Math.min(cores, 8));
        int perThread = (sectionCount + numThreads - 1) / numThreads;

        Thread[] threads = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                int start = threadId * perThread;
                int end = Math.min(start + perThread, sectionCount);
                for (int s = start; s < end; s++) {
                    meshUltra(
                            blocksSections[s],
                            faceMasksSections[s],
                            mergeKeysSections[s],
                            outBuffers[s]);
                }
            }, "UGM-" + t);
            threads[t].start();
        }

        for (Thread th : threads) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /* =====================================================================
     *  THREAD-LOCAL QUAD BUFFER
     * ===================================================================== */
    public static QuadBuffer acquireQuadBuffer() {
        return QUAD_TL.get();
    }

    /* =====================================================================
     *  TABELAS DE LOOKUP (estáticas, idênticas ao Greedy Meshing original)
     * ===================================================================== */
    private static final int[][][] SWEEP_TO_BLOCK = buildSweepTable();
    private static final int[][] BLOCK_TO_SWEEP = buildInverseSweepTable();

    private static int[][][] buildSweepTable() {
        int[][][] t = new int[ALL_FACES.length][SECTION_SIZE][SECTION_SIZE * SECTION_SIZE];
        for (Direction dir : ALL_FACES) {
            for (int depth = 0; depth < SECTION_SIZE; depth++) {
                for (int row = 0; row < SECTION_SIZE; row++) {
                    for (int col = 0; col < SECTION_SIZE; col++) {
                        int x, y, z;
                        switch (dir) {
                            case DOWN, UP -> { x = col; y = depth; z = row; }
                            case NORTH, SOUTH -> { x = col; y = row; z = depth; }
                            default -> { x = depth; y = row; z = col; }
                        }
                        t[dir.ordinal()][depth][row * SECTION_SIZE + col] = index(x, y, z);
                    }
                }
            }
        }
        return t;
    }

    private static int[][] buildInverseSweepTable() {
        int[][] inv = new int[ALL_FACES.length][TOTAL_BLOCKS];
        for (int[] face : inv) {
            java.util.Arrays.fill(face, -1);
        }
        for (Direction dir : ALL_FACES) {
            int fi = dir.ordinal();
            for (int depth = 0; depth < SECTION_SIZE; depth++) {
                for (int row = 0; row < SECTION_SIZE; row++) {
                    for (int col = 0; col < SECTION_SIZE; col++) {
                        int bi = SWEEP_TO_BLOCK[fi][depth][row * SECTION_SIZE + col];
                        inv[fi][bi] = (depth << 8) | (row << 4) | col;
                    }
                }
            }
        }
        for (int fi = 0; fi < inv.length; fi++) {
            for (int bi = 0; bi < TOTAL_BLOCKS; bi++) {
                if (inv[fi][bi] < 0) {
                    throw new IllegalStateException(
                            "UltraGreedyMesher: sweep table is not a bijection (face=" + fi + ", block=" + bi + ")");
                }
            }
        }
        return inv;
    }

    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /* =====================================================================
     *  FACE MASKS — BitSet[6] reutilizável (zero alloc entre seções)
     * ===================================================================== */
    private static final ThreadLocal<BitSet[]> FACE_MASKS_TL = ThreadLocal.withInitial(() -> {
        BitSet[] masks = new BitSet[6];
        for (int f = 0; f < 6; f++) {
            masks[f] = new BitSet(TOTAL_BLOCKS);
        }
        return masks;
    });

    /**
     * Retorna 6 {@link BitSet} (um por face, índice = {@link Direction#ordinal()})
     * zerados e prontos para uso. Reutiliza alocação via ThreadLocal.
     */
    public static BitSet[] createFaceMaskArray() {
        BitSet[] masks = FACE_MASKS_TL.get();
        for (BitSet m : masks) {
            m.clear();
        }
        return masks;
    }

    /* =====================================================================
     *  ESTADO TEMPORÁRIO (ThreadLocal)
     * ===================================================================== */
    private static final class BinarySweepState {
        static final int CAP = 256;
        final int[] rows = new int[CAP * SECTION_SIZE];
        final long[] keys = new long[CAP];
        final int[] origins = new int[CAP];
        final int[] depthCells = new int[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE];
        final int[] depthCounts = new int[SECTION_SIZE];

        private static final ThreadLocal<BinarySweepState> TL =
            ThreadLocal.withInitial(BinarySweepState::new);
    }
}
