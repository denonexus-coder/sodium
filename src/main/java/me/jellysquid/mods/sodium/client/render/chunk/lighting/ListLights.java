package me.jellysquid.mods.sodium.client.render.chunk.lighting;

import net.minecraft.client.world.ClientWorld;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The "many lights" transport: a short, clustered list of point lights uploaded to the GPU.
 *
 * <p>This is the Infinitium light-table design (256 lights, a 16x8x16 cluster grid over a
 * 64x32x64 local box, ranges plus a flat index array) implemented inside Sodium, because the
 * consumer of the data is Sodium's own chunk shader: the mod cannot reach into another mod's
 * render pass, so the table travels with the renderer that reads it.
 *
 * <p>It exists next to the {@link ColoredLighting} atlas for one reason: cost. The atlas has to
 * flood the 32x16x32 volume on every rebuild (about 1.4 million level checks) to get occlusion.
 * The list only scans the sources and writes a 172 KB UBO, so a world full of torches costs a
 * scan instead of a flood. The trade is that a point light has no occlusion of its own: the
 * shader gates the contribution with the vanilla block-light column, which is zero behind an
 * opaque wall, so the light still does not bleed through walls -- coarsely, but it does not.
 *
 * <p>Off by default. The switch is in the "Luz Avançada" tab and is mutually exclusive with the
 * Motor B atlas (both are the transport), last-wins by a single flag.
 */
public final class ListLights {
    public static final int MAX_LIGHTS = 256;
    public static final int CLUSTER_X = 16;
    public static final int CLUSTER_Y = 8;
    public static final int CLUSTER_Z = 16;
    public static final int CLUSTERS = CLUSTER_X * CLUSTER_Y * CLUSTER_Z;
    public static final int MAX_INDEX_SLOTS = 32768;

    /** The box the clusters cover, in blocks, centred on the anchor. */
    public static final float GRID_X = 64.0F;
    public static final float GRID_Y = 32.0F;
    public static final float GRID_Z = 64.0F;

    private static final int OFF_CFG = 0;
    private static final int OFF_LIGHTS_A = 16;
    private static final int OFF_LIGHTS_B = OFF_LIGHTS_A + MAX_LIGHTS * 16;
    private static final int OFF_RANGE = OFF_LIGHTS_B + MAX_LIGHTS * 16;
    private static final int OFF_INDEX = OFF_RANGE + CLUSTERS * 16;

    /** std140: 16 + 4096 + 4096 + 32768 + 131072. */
    public static final int UBO_SIZE = OFF_INDEX + (MAX_INDEX_SLOTS / 4) * 16;

    /** The UBO binding point. Sodium's ChunkData owns 3, so 1 is free (verified in the audit). */
    public static final int UBO_BINDING = 1;

    private static final float LIGHT_RADIUS = ColoredLighting.LIGHT_RADIUS;

    private static final float[] LIGHT_A = new float[MAX_LIGHTS * 4]; // x, y, z, radius
    private static final float[] LIGHT_B = new float[MAX_LIGHTS * 4]; // r, g, b, intensity
    private static final int[] CLUSTER_OFF = new int[CLUSTERS];
    private static final int[] CLUSTER_COUNT = new int[CLUSTERS];
    private static final int[] INDEX = new int[MAX_INDEX_SLOTS];
    private static final float[] ORDER = new float[MAX_LIGHTS]; // distance squared, for the sort

    private static ByteBuffer uboData;
    private static int uboBuffer;
    private static int lightCount;
    private static int indexUsed;
    private static int originX, originY, originZ;
    private static boolean built;

    private ListLights() {
    }

    public static void reset() {
        lightCount = 0;
        indexUsed = 0;
        built = false;
    }

    /**
     * Scans the emitters around the anchor and writes the clustered table.
     *
     * <p>The scan is the same one the atlas uses, so a soul lantern is the same colour in both
     * transports. Only the nearest {@link #MAX_LIGHTS} sources are kept: the radius is 12 blocks
     * and the box is 64x32x64, so the cap is never reached in normal play, and when it is the
     * far ones are the ones that would have contributed the least.
     */
    public static void rebuild(ClientWorld world, int centerX, int centerY, int centerZ) {
        int ox = centerX - (int) (GRID_X * 0.5F);
        int oy = centerY - (int) (GRID_Y * 0.5F);
        int oz = centerZ - (int) (GRID_Z * 0.5F);

        originX = ox;
        originY = oy;
        originZ = oz;

        lightCount = 0;

        final int baseX = ox;
        final int baseY = oy;
        final int baseZ = oz;

        ColoredLighting.scanSources(world, ox, oy, oz, ox + (int) GRID_X - 1, oy + (int) GRID_Y - 1,
                oz + (int) GRID_Z - 1, (state, x, y, z) -> {
                    if (lightCount >= MAX_LIGHTS) {
                        return;
                    }

                    int luminance = ColoredLighting.effectiveLuminance(state);

                    if (luminance <= 0) {
                        return;
                    }

                    float[] color = ColoredLighting.colorFor(state.getBlock());
                    float strength = ColoredLighting.lightStrength(luminance);

                    int slot = lightCount++;
                    int a = slot * 4;

                    LIGHT_A[a] = x - baseX + 0.5F;
                    LIGHT_A[a + 1] = y - baseY + 0.5F;
                    LIGHT_A[a + 2] = z - baseZ + 0.5F;
                    LIGHT_A[a + 3] = LIGHT_RADIUS;

                    LIGHT_B[a] = color[0];
                    LIGHT_B[a + 1] = color[1];
                    LIGHT_B[a + 2] = color[2];
                    LIGHT_B[a + 3] = strength;
                });

        buildClusters();
        writeUbo();
        built = true;
    }

    private static void buildClusters() {
        for (int i = 0; i < CLUSTERS; i++) {
            CLUSTER_OFF[i] = 0;
            CLUSTER_COUNT[i] = 0;
        }

        indexUsed = 0;

        float cellW = GRID_X / CLUSTER_X;
        float cellH = GRID_Y / CLUSTER_Y;
        float cellD = GRID_Z / CLUSTER_Z;

        for (int slot = 0; slot < lightCount; slot++) {
            int a = slot * 4;

            float px = LIGHT_A[a];
            float py = LIGHT_A[a + 1];
            float pz = LIGHT_A[a + 2];
            float radius = LIGHT_A[a + 3];

            int x0 = Math.max(0, (int) ((px - radius) / cellW));
            int x1 = Math.min(CLUSTER_X - 1, (int) ((px + radius) / cellW));
            int y0 = Math.max(0, (int) ((py - radius) / cellH));
            int y1 = Math.min(CLUSTER_Y - 1, (int) ((py + radius) / cellH));
            int z0 = Math.max(0, (int) ((pz - radius) / cellD));
            int z1 = Math.min(CLUSTER_Z - 1, (int) ((pz + radius) / cellD));

            float r2 = radius * radius;

            for (int y = y0; y <= y1; y++) {
                float cy0 = y * cellH;
                float dy = Math.max(0.0F, Math.max(cy0 - py, py - (cy0 + cellH)));

                for (int z = z0; z <= z1; z++) {
                    float cz0 = z * cellD;
                    float dz = Math.max(0.0F, Math.max(cz0 - pz, pz - (cz0 + cellD)));

                    for (int x = x0; x <= x1; x++) {
                        float cx0 = x * cellW;
                        float dx = Math.max(0.0F, Math.max(cx0 - px, px - (cx0 + cellW)));

                        if (dx * dx + dy * dy + dz * dz >= r2) {
                            continue;
                        }

                        // Saturated index list: stop indexing instead of overflowing the array.
                        // The cluster keeps the indices already written, so the shader never reads
                        // a slot that was not filled. Degradation, not corruption.
                        if (indexUsed >= MAX_INDEX_SLOTS) {
                            continue;
                        }

                        int cluster = (y * CLUSTER_Z + z) * CLUSTER_X + x;

                        if (CLUSTER_COUNT[cluster] == 0) {
                            CLUSTER_OFF[cluster] = indexUsed;
                        }

                        INDEX[indexUsed++] = slot;
                        CLUSTER_COUNT[cluster]++;
                    }
                }
            }
        }
    }

    private static void writeUbo() {
        if (uboData == null) {
            uboData = BufferUtils.createByteBuffer(UBO_SIZE).order(ByteOrder.nativeOrder());
        }

        ByteBuffer buffer = uboData;
        buffer.clear();

        buffer.putInt(lightCount);
        buffer.putInt(CLUSTER_X);
        buffer.putInt(CLUSTER_Y);
        buffer.putInt(CLUSTER_Z);

        buffer.position(OFF_LIGHTS_A);
        for (int i = 0; i < lightCount * 4; i++) {
            buffer.putFloat(LIGHT_A[i]);
        }
        buffer.position(OFF_LIGHTS_B);
        for (int i = 0; i < lightCount * 4; i++) {
            buffer.putFloat(LIGHT_B[i]);
        }

        buffer.position(OFF_RANGE);
        for (int c = 0; c < CLUSTERS; c++) {
            buffer.putInt(CLUSTER_OFF[c]);
            buffer.putInt(CLUSTER_COUNT[c]);
            buffer.putInt(0);
            buffer.putInt(0);
        }

        buffer.position(OFF_INDEX);
        int padded = (indexUsed + 3) & ~3;

        for (int i = 0; i < padded; i++) {
            buffer.putInt(i < indexUsed ? INDEX[i] : 0);
        }

        buffer.position(0);
        buffer.limit(OFF_INDEX + padded * 4);

        bindBuffer();
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, buffer, GL15.GL_DYNAMIC_DRAW);
    }

    private static void bindBuffer() {
        if (uboBuffer == 0) {
            uboBuffer = GL15.glGenBuffers();
        }

        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, UBO_BINDING, uboBuffer);
    }

    /** Called once per draw, before the program is bound: the UBO is global state, not a uniform. */
    public static void bind() {
        if (built) {
            bindBuffer();
        }
    }

    public static boolean isBuilt() {
        return built;
    }

    public static int getLightCount() {
        return lightCount;
    }

    public static float getOriginX() {
        return (float) originX;
    }

    public static float getOriginY() {
        return (float) originY;
    }

    public static float getOriginZ() {
        return (float) originZ;
    }

    public static float getScaleX() {
        return 1.0F / GRID_X;
    }

    public static float getScaleY() {
        return 1.0F / GRID_Y;
    }

    public static float getScaleZ() {
        return 1.0F / GRID_Z;
    }
}
