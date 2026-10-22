package me.jellysquid.mods.sodium.client.render.chunk.lighting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

/**
 * The analytic light engine: a short list of nearby light sources, uploaded as uniforms.
 *
 * <p>There is no texture and no upload in this path. A source is described by a world position,
 * the palette colour and the same {@link ColoredLighting#lightStrength(int)} the hand uses; the
 * shader sums every entry with the hand-light curve (12 blocks, squared falloff). A placed torch
 * therefore has exactly the peak intensity and the falloff of the same torch in the hand.
 *
 * <p>The list is rebuilt only when the camera crosses an eight-block boundary, when a block
 * changes, or when the world is replaced. When more than {@link #getMaxLights()} sources are in
 * range, the nearest ones win; the rest fall back to the vanilla warm ramp, which the shader
 * already blends in wherever no coloured light reaches.
 */
public final class AnalyticLighting {
    /** Absolute ceiling; the driver limit can lower this to 24, 16 or 8. */
    private static final int HARD_MAX = 32;

    /** How far around the camera sources are collected, in blocks. */
    private static final int SCAN_RADIUS_XZ = 32;
    private static final int SCAN_RADIUS_Y = 32;

    /** Rebuild when the camera moves further than this from the anchor. */
    private static final int ANCHOR_DISTANCE = 8;

    private static final int MAX_CANDIDATES = 512;

    private static final float[] WORLD = new float[HARD_MAX * 4];   // x, y, z, intensity
    private static final float[] COLORS = new float[HARD_MAX * 3];

    private static final float[] CAND_X = new float[MAX_CANDIDATES];
    private static final float[] CAND_Y = new float[MAX_CANDIDATES];
    private static final float[] CAND_Z = new float[MAX_CANDIDATES];
    private static final float[] CAND_STRENGTH = new float[MAX_CANDIDATES];
    private static final float[] CAND_R = new float[MAX_CANDIDATES];
    private static final float[] CAND_G = new float[MAX_CANDIDATES];
    private static final float[] CAND_B = new float[MAX_CANDIDATES];
    private static final float[] CAND_DIST = new float[MAX_CANDIDATES];
    private static final boolean[] USED = new boolean[MAX_CANDIDATES];

    private static int candidateCount;
    private static int count;

    private static int anchorX, anchorY, anchorZ;
    private static boolean hasAnchor;
    private static boolean dirty = true;
    private static ClientWorld lastWorld;

    private static int maxLights = -1;

    private AnalyticLighting() {
    }

    /**
     * The number of entries the shader was compiled for. The light uniforms are vertex-stage
     * arrays, where the ES minimum is only 256 components: 24 lights plus the existing matrices
     * fit, 32 only fit when the driver reports a larger budget.
     */
    public static int getMaxLights() {
        if (maxLights < 0) {
            // GLES 3 guarantees 256 vertex uniform components, and the desktop query below is
            // allowed to fail (or return a nonsense value) on a translation layer.
            int components = 256;

            try {
                // GL_MAX_VERTEX_UNIFORM_COMPONENTS. Kept as a literal so the code does not depend
                // on which LWJGL class happens to declare the constant.
                int queried = GL11.glGetInteger(0x8B4A);

                if (queried > 0) {
                    components = queried;
                }
            } catch (Throwable ignored) {
                // No context yet or a broken query; keep the conservative minimum.
            }

            int chosen = 8;

            // A vec3 array element may be padded to a full vec4 by the driver, so budget eight
            // components per light plus 72 for the matrices, fog, samplers and the hand light.
            // On a 256-component device this picks 16, which comfortably fits; 24 only appears
            // when the query reports at least 512.
            for (int candidate : new int[] { 32, 24, 16 }) {
                if (candidate * 8 + 72 <= components) {
                    chosen = candidate;
                    break;
                }
            }

            maxLights = chosen;
        }

        return maxLights;
    }

    public static int getCount() {
        return count;
    }

    public static void markDirty() {
        dirty = true;
    }

    /** Called from the client tick while the analytic engine is selected. */
    public static void tick(MinecraftClient client) {
        ClientWorld world = client.world;

        if (world == null || client.player == null) {
            return;
        }

        Vec3d camera = client.gameRenderer.getCamera().getPos();

        int centerX = (int) Math.floor(camera.x);
        int centerY = (int) Math.floor(camera.y);
        int centerZ = (int) Math.floor(camera.z);

        boolean moved = !hasAnchor
                || world != lastWorld
                || Math.abs(centerX - anchorX) > ANCHOR_DISTANCE
                || Math.abs(centerY - anchorY) > ANCHOR_DISTANCE
                || Math.abs(centerZ - anchorZ) > ANCHOR_DISTANCE;

        if (!dirty && !moved) {
            return;
        }

        dirty = false;
        hasAnchor = true;
        lastWorld = world;
        anchorX = centerX;
        anchorY = centerY;
        anchorZ = centerZ;

        rebuild(world, centerX, centerY, centerZ, camera);
    }

    private static void rebuild(ClientWorld world, int centerX, int centerY, int centerZ, Vec3d camera) {
        int minX = centerX - SCAN_RADIUS_XZ;
        int maxX = centerX + SCAN_RADIUS_XZ;
        int minZ = centerZ - SCAN_RADIUS_XZ;
        int maxZ = centerZ + SCAN_RADIUS_XZ;
        int minY = Math.max(world.getBottomY(), centerY - SCAN_RADIUS_Y);
        int maxY = Math.min(world.getTopY() - 1, centerY + SCAN_RADIUS_Y);

        candidateCount = 0;

        ColoredLighting.scanSources(world, minX, minY, minZ, maxX, maxY, maxZ, (state, x, y, z) -> {
            int luminance = state.getLuminance();

            if (luminance <= 0 || candidateCount >= MAX_CANDIDATES) {
                return;
            }

            float px = x + 0.5F;
            float py = y + 0.5F;
            float pz = z + 0.5F;

            float dx = px - (float) camera.x;
            float dy = py - (float) camera.y;
            float dz = pz - (float) camera.z;

            float[] color = ColoredLighting.colorFor(state.getBlock());

            int index = candidateCount++;
            CAND_X[index] = px;
            CAND_Y[index] = py;
            CAND_Z[index] = pz;
            CAND_STRENGTH[index] = ColoredLighting.lightStrength(luminance);
            CAND_R[index] = color[0];
            CAND_G[index] = color[1];
            CAND_B[index] = color[2];
            CAND_DIST[index] = dx * dx + dy * dy + dz * dz;
        });

        selectNearest();

        // A zero count must not read stale entries; the loop in the shader is bounded anyway,
        // but clearing the colour keeps the arrays deterministic between rebuilds.
        if (count == 0) {
            Arrays.fill(COLORS, 0.0F);
        }
    }

    /**
     * Selection sort over the candidates. The list is short (a few hundred at most), and this
     * avoids boxing or sorting the source positions themselves.
     */
    private static void selectNearest() {
        Arrays.fill(USED, 0, candidateCount, false);

        int limit = Math.min(getMaxLights(), candidateCount);

        count = 0;

        for (int slot = 0; slot < limit; slot++) {
            int best = -1;
            float bestDistance = Float.MAX_VALUE;

            for (int i = 0; i < candidateCount; i++) {
                if (!USED[i] && CAND_DIST[i] < bestDistance) {
                    bestDistance = CAND_DIST[i];
                    best = i;
                }
            }

            if (best < 0) {
                break;
            }

            USED[best] = true;

            int base = count * 4;
            WORLD[base] = CAND_X[best];
            WORLD[base + 1] = CAND_Y[best];
            WORLD[base + 2] = CAND_Z[best];
            WORLD[base + 3] = CAND_STRENGTH[best];

            int colorBase = count * 3;
            COLORS[colorBase] = CAND_R[best];
            COLORS[colorBase + 1] = CAND_G[best];
            COLORS[colorBase + 2] = CAND_B[best];

            count++;
        }
    }

    /**
     * Fills the camera-relative uniform arrays. Positions are recomputed every frame because the
     * shader works in camera space; the colours only change on a rebuild.
     */
    public static void writeUniforms(Vec3d cameraPos, float[] positions, float[] colors) {
        int n = count;

        for (int i = 0; i < n; i++) {
            int base = i * 4;
            positions[base] = WORLD[base] - (float) cameraPos.x;
            positions[base + 1] = WORLD[base + 1] - (float) cameraPos.y;
            positions[base + 2] = WORLD[base + 2] - (float) cameraPos.z;
            positions[base + 3] = WORLD[base + 3];
        }

        System.arraycopy(COLORS, 0, colors, 0, n * 3);
    }
}
