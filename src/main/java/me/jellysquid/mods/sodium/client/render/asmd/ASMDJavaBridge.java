package me.jellysquid.mods.sodium.client.render.asmd;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * ASMD Java Bridge (128-byte telemetry).
 * Direct ByteBuffer persistent; written by mixins via static offsets.
 * Zero allocation in the frame loop.
 */
public final class ASMDJavaBridge {
    private static final ByteBuffer BUFFER = ByteBuffer.allocateDirect(192).order(ByteOrder.nativeOrder());

    // Offset constants (see task spec)
    private static final int OFF_SERVER_TICK_TIME_MS = 0;
    private static final int OFF_SERVER_TPS = 4;
    private static final int OFF_GC_IMMINENT = 8;
    private static final int OFF_GC_CHURN_MB_S = 12;
    private static final int OFF_PLAYER_POS_X = 16;
    private static final int OFF_PLAYER_POS_Y = 20;
    private static final int OFF_PLAYER_POS_Z = 24;
    private static final int OFF_PLAYER_VEL_X = 28;
    private static final int OFF_PLAYER_VEL_Y = 32;
    private static final int OFF_PLAYER_VEL_Z = 36;
    private static final int OFF_PLAYER_SPEED = 40;
    private static final int OFF_ELYTRA = 44;
    private static final int OFF_CAMERA_YAW = 48;
    private static final int OFF_CAMERA_PITCH = 52;
    private static final int OFF_YAW_VEL = 56;
    private static final int OFF_PITCH_VEL = 60;
    private static final int OFF_ANGULAR_ACCEL = 64;
    private static final int OFF_VIEW_DIST = 68;
    private static final int OFF_CHUNK_QUEUE = 72;
    private static final int OFF_VISIBLE_ENTITIES = 76;
    private static final int OFF_VISIBLE_TILE = 80;
    private static final int OFF_FRAME_CLOCK = 84;
    private static final int OFF_STANDSTILL = 92;
    private static final int OFF_SPRINTING = 96;
    private static final int OFF_DRS_SCALE = 100;
    // Padding 104..127 (24 bytes) — engine state exposto pelo C++
    private static final int OFF_TECHNIQUE = 104;    // i32: 1=UNROLL 2=MERGED 3=UBO 4=PANIC
    private static final int OFF_DYNAMIC_K = 108;    // i32: crossover dinâmico (4-60)
    private static final int OFF_TREND = 112;        // i32: -1=subindo 0=estável +1=descendo
    private static final int OFF_SGD = 116;          // f32: acumulador SGD
    private static final int OFF_DRAW_COUNT = 120;   // i32: sub-draws do frame anterior
    private static final int OFF_FRAME_MS = 124;     // f32: duração real do frame anterior (ms)
    // 128..191 (64 bytes) — métricas expostas pelo C++
    private static final int OFF_FPS_MIN = 128;      // f32: FPS mínimo (120 frames)
    private static final int OFF_FPS_MED = 132;      // f32: FPS mediano
    private static final int OFF_FPS_MAX = 136;      // f32: FPS máximo
    private static final int OFF_MS_MIN = 140;       // f32: frame ms mínimo
    private static final int OFF_MS_MED = 144;       // f32: frame ms mediano
    private static final int OFF_MS_MAX = 148;       // f32: frame ms máximo
    private static final int OFF_DRAWS_MIN = 152;    // i32: draws mínimo
    private static final int OFF_DRAWS_MED = 156;    // i32: draws mediano
    private static final int OFF_DRAWS_MAX = 160;    // i32: draws máximo
    private static final int OFF_GPU_MS = 164;       // f32: tempo GPU do último draw (ms)
    private static final int OFF_CPU_DRAW_MS = 168;  // f32: tempo CPU do último draw (ms)
    private static final int OFF_MERGE_RATIO = 172;  // f32: % de draws fundidos (0-1)
    private static final int OFF_TECH_UNROLL_PCT = 176; // f32: % frames UNROLL (0-100)
    private static final int OFF_TECH_MERGED_PCT = 180; // f32: % frames MERGED
    private static final int OFF_TECH_UBO_PCT = 184;    // f32: % frames UBO
    private static final int OFF_GC_CHURN = 188;        // f32: GC churn MB/s (duplicado p/ acesso rápido)

    private ASMDJavaBridge() {
    }

    public static ByteBuffer buffer() {
        return BUFFER;
    }

    public static long nativeAddress() {
        return MemoryUtil.memAddress(BUFFER);
    }

    // ---- Write helpers (primitive, zero-alloc) ----
    public static void setServerTickTimeMs(float ms) {
        BUFFER.putFloat(OFF_SERVER_TICK_TIME_MS, ms);
    }

    public static void setServerTps(float tps) {
        BUFFER.putFloat(OFF_SERVER_TPS, tps);
    }

    public static void setGcImminent(int flag) {
        BUFFER.putInt(OFF_GC_IMMINENT, flag);
    }

    public static void setGcChurnMbS(float mbS) {
        BUFFER.putFloat(OFF_GC_CHURN_MB_S, mbS);
    }

    public static void setPlayerPos(float x, float y, float z) {
        BUFFER.putFloat(OFF_PLAYER_POS_X, x);
        BUFFER.putFloat(OFF_PLAYER_POS_Y, y);
        BUFFER.putFloat(OFF_PLAYER_POS_Z, z);
    }

    public static void setPlayerVel(float x, float y, float z) {
        BUFFER.putFloat(OFF_PLAYER_VEL_X, x);
        BUFFER.putFloat(OFF_PLAYER_VEL_Y, y);
        BUFFER.putFloat(OFF_PLAYER_VEL_Z, z);
    }

    public static void setPlayerSpeed(float mps) {
        BUFFER.putFloat(OFF_PLAYER_SPEED, mps);
    }

    public static void setElytraFlying(int flag) {
        BUFFER.putInt(OFF_ELYTRA, flag);
    }

    public static void setCameraYawPitch(float yaw, float pitch) {
        BUFFER.putFloat(OFF_CAMERA_YAW, yaw);
        BUFFER.putFloat(OFF_CAMERA_PITCH, pitch);
    }

    public static void setYawVelocity(float degPerSec) {
        BUFFER.putFloat(OFF_YAW_VEL, degPerSec);
    }

    public static void setPitchVelocity(float degPerSec) {
        BUFFER.putFloat(OFF_PITCH_VEL, degPerSec);
    }

    public static void setAngularAccel(float accel) {
        BUFFER.putFloat(OFF_ANGULAR_ACCEL, accel);
    }

    public static void setViewDistanceChunks(int chunks) {
        BUFFER.putInt(OFF_VIEW_DIST, chunks);
    }

    public static void setChunkQueueSize(int size) {
        BUFFER.putInt(OFF_CHUNK_QUEUE, size);
    }

    public static void setVisibleEntities(int count) {
        BUFFER.putInt(OFF_VISIBLE_ENTITIES, count);
    }

    public static void setVisibleTileEntities(int count) {
        BUFFER.putInt(OFF_VISIBLE_TILE, count);
    }

    public static void setFrameClockOriginNs(long ns) {
        BUFFER.putLong(OFF_FRAME_CLOCK, ns);
    }

    public static void setStandstill(int flag) {
        BUFFER.putInt(OFF_STANDSTILL, flag);
    }

    public static void setSprinting(int flag) {
        BUFFER.putInt(OFF_SPRINTING, flag);
    }

    public static void setDrsViewportScale(float scale) {
        BUFFER.putFloat(OFF_DRS_SCALE, scale);
    }

    // ---- Engine state (escrito pelo C++, lido pelo overlay) ----
    public static void setEngineState(int technique, int dynamicK, int trend,
                                      float sgd, int drawCount, float frameMs) {
        BUFFER.putInt(OFF_TECHNIQUE, technique);
        BUFFER.putInt(OFF_DYNAMIC_K, dynamicK);
        BUFFER.putInt(OFF_TREND, trend);
        BUFFER.putFloat(OFF_SGD, sgd);
        BUFFER.putInt(OFF_DRAW_COUNT, drawCount);
        BUFFER.putFloat(OFF_FRAME_MS, frameMs);
    }

    public static void setFpsStats(float min, float med, float max) {
        BUFFER.putFloat(OFF_FPS_MIN, min);
        BUFFER.putFloat(OFF_FPS_MED, med);
        BUFFER.putFloat(OFF_FPS_MAX, max);
    }

    public static void setMsStats(float min, float med, float max) {
        BUFFER.putFloat(OFF_MS_MIN, min);
        BUFFER.putFloat(OFF_MS_MED, med);
        BUFFER.putFloat(OFF_MS_MAX, max);
    }

    public static void setDrawStats(int min, int med, int max) {
        BUFFER.putInt(OFF_DRAWS_MIN, min);
        BUFFER.putInt(OFF_DRAWS_MED, med);
        BUFFER.putInt(OFF_DRAWS_MAX, max);
    }

    public static void setDrawTiming(float gpuMs, float cpuMs, float mergeRatio) {
        BUFFER.putFloat(OFF_GPU_MS, gpuMs);
        BUFFER.putFloat(OFF_CPU_DRAW_MS, cpuMs);
        BUFFER.putFloat(OFF_MERGE_RATIO, mergeRatio);
    }

    public static void setTechniqueDistribution(float unrollPct, float mergedPct, float uboPct) {
        BUFFER.putFloat(OFF_TECH_UNROLL_PCT, unrollPct);
        BUFFER.putFloat(OFF_TECH_MERGED_PCT, mergedPct);
        BUFFER.putFloat(OFF_TECH_UBO_PCT, uboPct);
    }

    // ---- Contagem de block entities do frame ----
    // O blockEntityCount do vanilla (field_4110) nunca é incrementado, então o
    // offset 80 é preenchido por contagem própria: zerada no HEAD de
    // WorldRenderer.render, incrementada por BlockEntityCountMixin a cada
    // block entity realmente despachada, lida no RETURN do mesmo render.
    private static int tileEntityFrameCount;

    public static void beginTileEntityFrame() {
        tileEntityFrameCount = 0;
    }

    public static void countTileEntity() {
        tileEntityFrameCount++;
    }

    public static int tileEntityFrameCount() {
        return tileEntityFrameCount;
    }
}
