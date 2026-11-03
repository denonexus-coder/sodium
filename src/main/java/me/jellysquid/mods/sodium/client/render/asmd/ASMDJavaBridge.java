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
    private static final ByteBuffer BUFFER = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());

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
    // Padding 104..127 (24 bytes) reserved.

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
