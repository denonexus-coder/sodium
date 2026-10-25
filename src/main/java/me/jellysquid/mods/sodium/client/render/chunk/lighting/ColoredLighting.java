package me.jellysquid.mods.sodium.client.render.chunk.lighting;

import com.mojang.blaze3d.platform.GlStateManager;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.LightEngine;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.LightingPath;
import me.jellysquid.mods.sodium.client.render.MakeupProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL32C;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Colored block and hand light for the chunk shader.
 *
 * <p>The vanilla lightmap holds one warm ramp for every light source, so a soul lantern and a
 * lava lake end up the same color. This class owns the palette, the shared hand light and the
 * setting that chooses between the two engines:
 *
 * <ul>
 *     <li>{@link LightEngine#GRID}: a 32x16x32 grid around the player is rebuilt only when the
 *     player crosses an eight-block boundary or a block changes, then uploaded once as a 2D
 *     atlas. Emitters propagate with the exact hand-light distance curve; a max-heap finalises
 *     each cell with the source that dominates it, so a lava lake costs one pass over the grid
 *     instead of one pass per lava block.</li>
 *     <li>{@link LightEngine#ANALYTIC}: no texture at all. {@link AnalyticLighting} keeps a short
 *     list of nearby sources and the shader sums them with the same 12-block curve.</li>
 * </ul>
 *
 * <p>Everything is off when Iris has a shader pack active. Iris replaces the chunk shader in that
 * case, so there is nothing for this code to feed and the scan would be wasted work; the GUI says
 * so instead of letting the switches lie.
 */
public final class ColoredLighting {
    public static final int SIZE_X = 32;
    public static final int SIZE_Y = 16;
    public static final int SIZE_Z = 32;

    /**
     * Master switch for the normal colored-light engine (the "Sistema" control in the
     * Experimental page).
     *
     * <p>It stays off: the MakeUp profile has its own colored-ground path
     * ({@code MAKEUP_COLORED_LIGHTS}) with its own switch, so the profile does not need this
     * engine and the player does not need a second, unrelated light model in the menu. Turning
     * this on would expose the normal engine again, which is exactly what the profile path is
     * meant to replace.
     *
     * <p>The code stays in the tree, so turning the feature back on is a one-word change.
     */
    public static final boolean ENABLED = false;

    private static final int CELLS = SIZE_X * SIZE_Y * SIZE_Z;

    /** The hand and the placed lights share this radius; the shader hard-codes the same value. */
    public static final float LIGHT_RADIUS = 12.0F;
    private static final int LIGHT_RADIUS_SQ = (int) (LIGHT_RADIUS * LIGHT_RADIUS);

    /**
     * The grid is rebuilt when the player moves further than this from the anchor used for the
     * previous rebuild. Moving every tick is what made the old version flicker.
     */
    private static final int ANCHOR_DISTANCE = 8;

    /** Safety net only: a missed dirty event must not leave a stale grid forever. */
    private static final int GRID_REFRESH_TICKS = 200;

    /**
     * Floor between two dirty rebuilds. Block updates can arrive in bursts (redstone, fluids,
     * chunk lighting); batching them keeps the grid at the same 4 Hz the old periodic rebuild
     * used, without the per-frame origin drift that caused the flicker.
     */
    private static final int MIN_GRID_INTERVAL = 4;

    /** The fallback for a light source that is not in the table below. */
    private static final float[] NEUTRAL = rgb(0xCCCCCC);
    /** Candles and candle cakes share the warm flame; the wax colour is not the emitted light. */
    private static final float[] CANDLE = rgb(0xFFC46B);

    /**
     * Emission tints for the 1.20.1 light sources. The values are the visual colour of the light,
     * not the vanilla lightmap: the game itself has a single warm block-light ramp. Entries that
     * change with state (lit furnace, charged anchor, berries, pickles, candles) are keyed by block
     * and filtered by {@link BlockState#getLuminance()} during the scan.
     *
     * <p>Candle wax colours are not used: the flame is the actual emitter, and it is warm for every
     * wax variant. Stained glass is absent because it has no luminance; the beacon beam is drawn
     * by the vanilla block entity renderer and is not part of this chunk-light grid.
     */
    private static final Map<Block, float[]> COLORS = new HashMap<>();

    static {
        float[] fire = rgb(0xFF7722);
        float[] lava = rgb(0xFF8A1E);
        float[] torch = rgb(0xFFB55E);
        float[] campfire = rgb(0xFF9933);
        float[] jack = rgb(0xFFBB55);
        float[] magma = rgb(0xE0661F);
        float[] glowstone = rgb(0xFFD98C);
        float[] lantern = rgb(0xFFCB6B);
        float[] redstoneLamp = rgb(0xFFB266);
        float[] furnace = rgb(0xFF9D4D);
        float[] brewing = rgb(0xFFCC66);
        float[] seaLantern = rgb(0xCFE8F0);
        float[] beacon = rgb(0xFFFFFF);
        float[] endRod = rgb(0xF5EEE6);
        float[] conduit = rgb(0x55DDE0);
        float[] endPortal = rgb(0x2EE6C9);
        float[] endGateway = rgb(0x33E0D0);
        float[] endFrame = rgb(0x38C98A);
        float[] enderChest = rgb(0x9B5FD0);
        float[] seaPickle = rgb(0x8CE6B0);
        float[] netherPortal = rgb(0xA040E0);
        float[] cryingObsidian = rgb(0x7A3FD0);
        float[] respawnAnchor = rgb(0xC040C0);
        float[] dragonEgg = rgb(0x3A2050);
        float[] soulFire = rgb(0x36E0E8);
        float[] soulCampfire = rgb(0x3FE0E0);
        float[] soulTorch = rgb(0x5AE0E8);
        float[] shroomlight = rgb(0xFF9D5C);
        float[] glowLichen = rgb(0x8FE0A0);
        float[] caveVines = rgb(0xFFC45C);
        float[] brownMushroom = rgb(0xC9B896);
        float[] ochre = rgb(0xF7D94C);
        float[] verdant = rgb(0xA6E05A);
        float[] pearl = rgb(0xF0A6D0);
        float[] redstoneTorch = rgb(0xFF4D4D);
        float[] redstoneOre = rgb(0xFF5555);
        float[] sculkCatalyst = rgb(0x1FA0A8);
        float[] sculkSensor = rgb(0x2ABFCC);
        float[] amethyst = rgb(0xC9A6F0);
        float[] amethystMedium = rgb(0xBF9AEA);
        float[] amethystSmall = rgb(0xB590E5);

        COLORS.put(Blocks.LAVA, lava);
        COLORS.put(Blocks.LAVA_CAULDRON, lava);
        COLORS.put(Blocks.FIRE, fire);
        COLORS.put(Blocks.SOUL_FIRE, soulFire);
        COLORS.put(Blocks.MAGMA_BLOCK, magma);

        COLORS.put(Blocks.TORCH, torch);
        COLORS.put(Blocks.WALL_TORCH, torch);
        COLORS.put(Blocks.CAMPFIRE, campfire);
        COLORS.put(Blocks.JACK_O_LANTERN, jack);
        COLORS.put(Blocks.GLOWSTONE, glowstone);
        COLORS.put(Blocks.LANTERN, lantern);
        COLORS.put(Blocks.REDSTONE_LAMP, redstoneLamp);
        COLORS.put(Blocks.FURNACE, furnace);
        COLORS.put(Blocks.BLAST_FURNACE, furnace);
        COLORS.put(Blocks.SMOKER, furnace);
        COLORS.put(Blocks.BREWING_STAND, brewing);

        COLORS.put(Blocks.SEA_LANTERN, seaLantern);
        COLORS.put(Blocks.BEACON, beacon);
        COLORS.put(Blocks.END_ROD, endRod);
        COLORS.put(Blocks.LIGHT, beacon);

        COLORS.put(Blocks.CONDUIT, conduit);
        COLORS.put(Blocks.END_PORTAL, endPortal);
        COLORS.put(Blocks.END_GATEWAY, endGateway);
        COLORS.put(Blocks.END_PORTAL_FRAME, endFrame);
        COLORS.put(Blocks.ENDER_CHEST, enderChest);
        COLORS.put(Blocks.SEA_PICKLE, seaPickle);

        COLORS.put(Blocks.NETHER_PORTAL, netherPortal);
        COLORS.put(Blocks.CRYING_OBSIDIAN, cryingObsidian);
        COLORS.put(Blocks.RESPAWN_ANCHOR, respawnAnchor);
        COLORS.put(Blocks.DRAGON_EGG, dragonEgg);

        COLORS.put(Blocks.SOUL_CAMPFIRE, soulCampfire);
        COLORS.put(Blocks.SOUL_TORCH, soulTorch);
        COLORS.put(Blocks.SOUL_WALL_TORCH, soulTorch);
        COLORS.put(Blocks.SOUL_LANTERN, soulTorch);

        COLORS.put(Blocks.SHROOMLIGHT, shroomlight);
        COLORS.put(Blocks.GLOW_LICHEN, glowLichen);
        COLORS.put(Blocks.CAVE_VINES, caveVines);
        COLORS.put(Blocks.CAVE_VINES_PLANT, caveVines);
        COLORS.put(Blocks.BROWN_MUSHROOM, brownMushroom);

        COLORS.put(Blocks.OCHRE_FROGLIGHT, ochre);
        COLORS.put(Blocks.VERDANT_FROGLIGHT, verdant);
        COLORS.put(Blocks.PEARLESCENT_FROGLIGHT, pearl);

        COLORS.put(Blocks.REDSTONE_TORCH, redstoneTorch);
        COLORS.put(Blocks.REDSTONE_WALL_TORCH, redstoneTorch);
        COLORS.put(Blocks.REDSTONE_ORE, redstoneOre);
        COLORS.put(Blocks.DEEPSLATE_REDSTONE_ORE, redstoneOre);

        COLORS.put(Blocks.SCULK_CATALYST, sculkCatalyst);
        COLORS.put(Blocks.SCULK_SENSOR, sculkSensor);
        COLORS.put(Blocks.CALIBRATED_SCULK_SENSOR, sculkSensor);
        COLORS.put(Blocks.SCULK_SHRIEKER, sculkSensor);

        COLORS.put(Blocks.AMETHYST_CLUSTER, amethyst);
        COLORS.put(Blocks.LARGE_AMETHYST_BUD, amethyst);
        COLORS.put(Blocks.MEDIUM_AMETHYST_BUD, amethystMedium);
        COLORS.put(Blocks.SMALL_AMETHYST_BUD, amethystSmall);
    }

    // ---- Grid engine state ----------------------------------------------------------------

    private static final float[] GRID = new float[CELLS * 3];

    /**
     * Work buffers for the separable squared-Euclidean distance transform. For each luminance
     * group the transform gives every cell the nearest source of that group, so the grid costs
     * O(cells) per group instead of O(cells times sources): a lava lake with thousands of
     * emitting blocks is still one pass.
     */
    private static final float[] DIST_A = new float[CELLS];
    private static final float[] DIST_B = new float[CELLS];
    private static final int[] ARG_A = new int[CELLS];
    private static final int[] ARG_B = new int[CELLS];
    private static final float[] BEST_KEY = new float[CELLS];

    private static final int MAX_AXIS = Math.max(SIZE_X, Math.max(SIZE_Y, SIZE_Z));
    private static final int[] LINE_V = new int[MAX_AXIS];
    private static final float[] LINE_Z = new float[MAX_AXIS + 2];

    /** x, y, z (grid-local), the three premultiplied colour components, strength and luminance. */
    private static float[] sources = new float[256 * 8];
    private static int sourceCount;

    private static int textureId;
    private static ByteBuffer uploadBuffer;
    private static int originX, originY, originZ;

    private static int anchorX, anchorY, anchorZ;
    private static boolean hasAnchor;
    private static ClientWorld gridWorld;
    private static boolean gridDirty = true;
    private static int ticksSinceGridRebuild;

    // ---- Shared state ---------------------------------------------------------------------

    /** How long a positive/negative Iris answer is reused; the profile asks every frame. */
    private static final long IRIS_CHECK_INTERVAL_NANOS = 500_000_000L;

    private static long irisCheckNanos;
    private static boolean irisPack;
    private static boolean shaderFailed;
    private static boolean analyticFailed;

    private ColoredLighting() {
    }

    public static LightingPath getPath() {
        if (!ENABLED) {
            return LightingPath.OFF;
        }

        LightingPath path = SodiumClientMod.options().experimental.lighting;

        return path == null ? LightingPath.OFF : path;
    }

    /** Whether the chunk shader should be built with the colored-light code. */
    public static boolean isActive() {
        return getPath() != LightingPath.OFF && !shaderFailed && !isIrisShaderPackInUse();
    }

    public static boolean isEssl() {
        return isActive() && getPath() == LightingPath.ESSL;
    }

    /** The engine chosen in the options; the grid is the default. */
    public static LightEngine getEngine() {
        LightEngine engine = SodiumClientMod.options().experimental.lightEngine;

        return engine == null ? LightEngine.GRID : engine;
    }

    /**
     * Whether the analytic program is both selected and usable. When its shader failed to build
     * this returns false and the grid engine takes over for the rest of the session, instead of
     * leaving the world without colored light.
     */
    public static boolean useAnalytic() {
        return isActive() && !analyticFailed && getEngine() == LightEngine.ANALYTIC;
    }

    /**
     * Whether the MakeUp profile asks for the colour of the sources placed in the world.
     *
     * <p>This is not the normal colored-light engine: the profile keeps its own light model and
     * only borrows the colour of the sources, so a torch standing on the ground lights the
     * ground around it exactly like the same torch lights it while it is in the hand. The
     * profile's own switch is {@code experimental.makeupColoredLights}; the normal engine is a
     * separate option ("Sistema" in the Experimental page).
     */
    public static boolean isProfileActive() {
        SodiumGameOptions options = SodiumClientMod.options();

        if (!MakeupProfile.isEnabled() || options == null) {
            return false;
        }

        // Rethinking always carries its own light model: the propagation is the profile, so it
        // cannot be switched off without switching the profile off. No Effects keeps the switch
        // it always had.
        return MakeupProfile.isRethinking() || options.experimental.makeupColoredLights;
    }

    public static int getMaxLights() {
        return AnalyticLighting.getMaxLights();
    }

    /** Called by the shader compiler when the colored program fails to compile or link. */
    public static void markShaderFailed() {
        shaderFailed = true;
    }

    /** Called when the analytic program failed but the grid program is still usable. */
    public static void markAnalyticFailed() {
        analyticFailed = true;
        gridDirty = true;
    }

    /** Lets the user retry after changing the path, without restarting the game. */
    public static void resetShaderFailure() {
        shaderFailed = false;
        analyticFailed = false;
        gridDirty = true;
    }

    /**
     * Whether the Iris mod is present at all. The new shadow and reflection controls are Sodium
     * features that depend on Sodium's chunk shader; they are disabled when Iris is installed,
     * even if no shader pack is selected, because Iris owns the render path from that point on.
     * Existing colored lighting keeps the narrower pack-in-use check.
     */
    public static boolean isIrisLoaded() {
        return FabricLoader.getInstance().isModLoaded("iris");
    }

    /**
     * Iris replaces Sodium's chunk shader while a pack is on. The answer is cached for half a
     * second: the profile asks on every frame and every chunk pass, and a reflective call per
     * question would cost more than the effect it guards.
     *
     * <p>Installing Iris without selecting a pack leaves Sodium's render path untouched, so this
     * is the check the profile uses; {@link #isIrisLoaded()} would disable it for a mod that is
     * merely present.
     */
    public static boolean isIrisShaderPackInUse() {
        if (!isIrisLoaded()) {
            return false;
        }

        long now = System.nanoTime();

        if (now - irisCheckNanos > IRIS_CHECK_INTERVAL_NANOS) {
            irisCheckNanos = now;
            irisPack = queryIris();
        }

        return irisPack;
    }

    private static boolean queryIris() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstance = api.getMethod("getInstance");
            Object instance = getInstance.invoke(null);

            return (boolean) api.getMethod("isShaderPackInUse").invoke(instance);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static float getOriginX() {
        return originX;
    }

    public static float getOriginY() {
        return originY;
    }

    public static float getOriginZ() {
        return originZ;
    }

    public static float getScaleX() {
        return 1.0F / SIZE_X;
    }

    public static float getScaleY() {
        return 1.0F / SIZE_Y;
    }

    public static float getScaleZ() {
        return 1.0F / SIZE_Z;
    }

    public static int getTextureId() {
        if (textureId == 0) {
            createTexture();
        }

        return textureId;
    }

    /**
     * Writes the hand light as x, y, z (camera-relative), intensity, r, g and b. The hand light
     * is read on demand instead of every tick because it depends on the camera position, which
     * only the renderer knows.
     */
    public static void writeHandLight(Vec3d cameraPos, float[] light, float[] color) {
        light[0] = 0.0F;
        light[1] = 0.0F;
        light[2] = 0.0F;
        light[3] = 0.0F;
        color[0] = 1.0F;
        color[1] = 1.0F;
        color[2] = 1.0F;

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player == null || !SodiumClientMod.options().experimental.handLight) {
            return;
        }

        ItemStack stack = client.player.getMainHandStack();

        if (stack.isEmpty()) {
            stack = client.player.getOffHandStack();
        }

        BlockState state;

        if (stack.isOf(Items.LAVA_BUCKET)) {
            // The bucket is not a BlockItem, but the lava it carries is a level-15 source.
            state = Blocks.LAVA.getDefaultState();
        } else if (stack.getItem() instanceof BlockItem blockItem) {
            state = blockItem.getBlock().getDefaultState();
        } else {
            return;
        }

        if (state.getLuminance() <= 0) {
            return;
        }

        Vec3d eye = client.player.getEyePos();
        Vec3d look = client.player.getRotationVec(1.0F);

        light[0] = (float) ((eye.x + look.x * 0.6D) - cameraPos.x);
        light[1] = (float) ((eye.y + look.y * 0.6D) - cameraPos.y);
        light[2] = (float) ((eye.z + look.z * 0.6D) - cameraPos.z);
        light[3] = lightStrength(state.getLuminance());

        float[] blockColor = colorFor(state.getBlock());
        color[0] = blockColor[0];
        color[1] = blockColor[1];
        color[2] = blockColor[2];
    }

    /** Called from the block-update hooks so both engines refresh on the next tick. */
    public static void markDirty() {
        gridDirty = true;
        AnalyticLighting.markDirty();
    }

    /** Client tick hook; the scan runs on the render thread, so it can upload the texture. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();

        // The profile needs the grid even when the normal engine is off: it is the field that
        // carries the colour of the sources placed in the world.
        if ((!isActive() && !isProfileActive()) || client.world == null || client.player == null || client.isPaused()) {
            return;
        }

        if (isActive() && useAnalytic()) {
            AnalyticLighting.tick(client);
        } else {
            tickGrid(client);
        }
    }

    private static void tickGrid(MinecraftClient client) {
        BlockPos center = client.player.getBlockPos();

        ticksSinceGridRebuild++;

        boolean moved = !hasAnchor
                || client.world != gridWorld
                || Math.abs(center.getX() - anchorX) > ANCHOR_DISTANCE
                || Math.abs(center.getY() - anchorY) > ANCHOR_DISTANCE
                || Math.abs(center.getZ() - anchorZ) > ANCHOR_DISTANCE;

        boolean rebuild;

        if (moved) {
            rebuild = true;
        } else if (gridDirty) {
            rebuild = ticksSinceGridRebuild >= MIN_GRID_INTERVAL;
        } else {
            rebuild = ticksSinceGridRebuild >= GRID_REFRESH_TICKS;
        }

        if (!rebuild) {
            return;
        }

        gridDirty = false;
        ticksSinceGridRebuild = 0;

        // Keep the origin fixed between anchor crossings. Recentering on every block update
        // resampled the same field at a shifted phase, which was the remaining source of shimmer
        // while walking and placing torches.
        if (moved || !hasAnchor) {
            hasAnchor = true;
            gridWorld = client.world;
            anchorX = center.getX();
            anchorY = center.getY();
            anchorZ = center.getZ();
        }

        rebuildGrid(client.world, anchorX, anchorY, anchorZ);
    }

    // ---- Grid rebuild ---------------------------------------------------------------------

    private static void rebuildGrid(ClientWorld world, int centerX, int centerY, int centerZ) {
        if (MakeupProfile.isRethinking()) {
            rebuildRethinking(world, centerX, centerY, centerZ);
            return;
        }

        int ox = centerX - SIZE_X / 2;
        int oy = centerY - SIZE_Y / 2;
        int oz = centerZ - SIZE_Z / 2;

        Arrays.fill(GRID, 0.0F);
        sourceCount = 0;

        scanSources(world, ox, oy, oz, ox + SIZE_X - 1, oy + SIZE_Y - 1, oz + SIZE_Z - 1,
                (state, x, y, z) -> {
                    int luminance = state.getLuminance();

                    if (luminance <= 0) {
                        return;
                    }

                    int lx = x - ox;
                    int ly = y - oy;
                    int lz = z - oz;

                    if (lx < 0 || lx >= SIZE_X || ly < 0 || ly >= SIZE_Y || lz < 0 || lz >= SIZE_Z) {
                        return;
                    }

                    float[] color = colorFor(state.getBlock());
                    float strength = lightStrength(luminance);

                    addSource(lx, ly, lz, color, strength, luminance);
                });

        diffuseGrid();

        originX = ox;
        originY = oy;
        originZ = oz;

        upload();
    }

    private static void addSource(int x, int y, int z, float[] color, float strength, int luminance) {
        if ((sourceCount + 1) * 8 > sources.length) {
            sources = Arrays.copyOf(sources, sources.length * 2);
        }

        int base = sourceCount * 8;
        sources[base] = x;
        sources[base + 1] = y;
        sources[base + 2] = z;
        sources[base + 3] = color[0] * strength;
        sources[base + 4] = color[1] * strength;
        sources[base + 5] = color[2] * strength;
        sources[base + 6] = strength;
        sources[base + 7] = luminance;
        sourceCount++;
    }

    /**
     * Builds the colored field from the emitters.
     *
     * <p>Each emitter is a cone: the strength at its cell times the squared hand attenuation at
     * the exact Euclidean distance. A cell shows the source that dominates it, so the field is
     * the maximum over the cones. Because two sources of different strengths have a curved
     * dominance boundary, a flood fill that prunes dominated cells is not exact; the field is
     * instead computed with the separable squared-Euclidean distance transform: for every
     * luminance group (all its sources share one strength) the transform gives each cell the
     * nearest source of the group, and the groups are then compared cell by cell.
     *
     * <p>This is what keeps lava lakes cheap: the transform is O(cells) per group, not O(cells
     * times sources), and the attenuation is still the exact hand curve, so a placed torch has
     * the same peak and the same falloff as the same torch in the hand.
     */
    private static void diffuseGrid() {
        Arrays.fill(BEST_KEY, -1.0F);

        int groups = 0;

        for (int source = 0; source < sourceCount; source++) {
            groups |= 1 << (int) sources[source * 8 + 7];
        }

        for (int luminance = 1; luminance <= 15; luminance++) {
            if ((groups & (1 << luminance)) == 0) {
                continue;
            }

            float strength = lightStrength(luminance);
            boolean seeded = false;

            Arrays.fill(DIST_A, Float.POSITIVE_INFINITY);
            Arrays.fill(ARG_A, -1);

            for (int source = 0; source < sourceCount; source++) {
                int base = source * 8;

                if ((int) sources[base + 7] != luminance) {
                    continue;
                }

                int index = ((int) sources[base + 2] * SIZE_Y + (int) sources[base + 1]) * SIZE_X
                        + (int) sources[base];

                DIST_A[index] = 0.0F;
                ARG_A[index] = source;
                seeded = true;
            }

            if (!seeded) {
                continue;
            }

            distancePassX(DIST_A, ARG_A, DIST_B, ARG_B);
            distancePassY(DIST_B, ARG_B, DIST_A, ARG_A);
            distancePassZ(DIST_A, ARG_A, DIST_B, ARG_B);

            for (int index = 0; index < CELLS; index++) {
                float distSq = DIST_B[index];

                if (distSq >= LIGHT_RADIUS_SQ) {
                    continue;
                }

                float atten = 1.0F - distSq / (LIGHT_RADIUS * LIGHT_RADIUS);
                float attenuationSq = atten * atten;
                float key = strength * attenuationSq;

                if (key <= BEST_KEY[index]) {
                    continue;
                }

                BEST_KEY[index] = key;

                int base = ARG_B[index] * 8;
                int cell = index * 3;

                GRID[cell] = sources[base + 3] * attenuationSq;
                GRID[cell + 1] = sources[base + 4] * attenuationSq;
                GRID[cell + 2] = sources[base + 5] * attenuationSq;
            }
        }
    }

    private static void distancePassX(float[] srcDist, int[] srcArg, float[] dstDist, int[] dstArg) {
        for (int z = 0; z < SIZE_Z; z++) {
            for (int y = 0; y < SIZE_Y; y++) {
                transformLine(srcDist, srcArg, dstDist, dstArg, (z * SIZE_Y + y) * SIZE_X, 1, SIZE_X);
            }
        }
    }

    private static void distancePassY(float[] srcDist, int[] srcArg, float[] dstDist, int[] dstArg) {
        for (int z = 0; z < SIZE_Z; z++) {
            for (int x = 0; x < SIZE_X; x++) {
                transformLine(srcDist, srcArg, dstDist, dstArg, z * SIZE_Y * SIZE_X + x, SIZE_X, SIZE_Y);
            }
        }
    }

    private static void distancePassZ(float[] srcDist, int[] srcArg, float[] dstDist, int[] dstArg) {
        for (int y = 0; y < SIZE_Y; y++) {
            for (int x = 0; x < SIZE_X; x++) {
                transformLine(srcDist, srcArg, dstDist, dstArg, y * SIZE_X + x, SIZE_X * SIZE_Y, SIZE_Z);
            }
        }
    }

    /**
     * Felzenszwalb-Huttenlocher one-dimensional squared-distance transform. The lower envelope of
     * the parabolas is built in linear time, and every output cell carries the argument (source)
     * of the parabola that won, so the colour is recovered without a second pass.
     */
    private static void transformLine(float[] srcDist, int[] srcArg, float[] dstDist, int[] dstArg,
                                      int base, int stride, int length) {
        int envelopeSize = -1;

        for (int q = 0; q < length; q++) {
            float fq = srcDist[base + q * stride];

            if (fq == Float.POSITIVE_INFINITY) {
                continue;
            }

            float qSquared = (float) q * q;
            float intersection = Float.NEGATIVE_INFINITY;

            while (envelopeSize >= 0) {
                int vk = LINE_V[envelopeSize];
                float divisor = 2.0F * (q - vk);
                intersection = ((fq + qSquared) - (srcDist[base + vk * stride] + (float) vk * vk)) / divisor;

                if (intersection <= LINE_Z[envelopeSize]) {
                    envelopeSize--;
                } else {
                    break;
                }
            }

            envelopeSize++;
            LINE_V[envelopeSize] = q;

            if (envelopeSize == 0) {
                LINE_Z[0] = Float.NEGATIVE_INFINITY;
            } else {
                LINE_Z[envelopeSize] = intersection;
            }
        }

        if (envelopeSize < 0) {
            for (int q = 0; q < length; q++) {
                dstDist[base + q * stride] = Float.POSITIVE_INFINITY;
                dstArg[base + q * stride] = -1;
            }

            return;
        }

        int envelope = 0;

        for (int q = 0; q < length; q++) {
            while (envelope < envelopeSize && LINE_Z[envelope + 1] < q) {
                envelope++;
            }

            int vk = LINE_V[envelope];
            float distSq = (float) (q - vk) * (q - vk) + srcDist[base + vk * stride];

            dstDist[base + q * stride] = distSq;
            dstArg[base + q * stride] = srcArg[base + vk * stride];
        }
    }

    // ---- Section scan ---------------------------------------------------------------------

    public interface BlockConsumer {
        void accept(BlockState state, int x, int y, int z);
    }

    /**
     * Visits only sections whose palette contains a luminous block. The palette test is an idea
     * ported from Starlight's source collection: a section with no emitter never needs its 4096
     * cells read, which turns a full box scan into a handful of section checks.
     */
    static void scanSources(ClientWorld world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                            BlockConsumer consumer) {
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        int bottomSection = world.getBottomY() >> 4;
        int topSection = (world.getTopY() - 1) >> 4;

        int minSectionY = Math.max(minY >> 4, bottomSection);
        int maxSectionY = Math.min(maxY >> 4, topSection);

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                WorldChunk chunk = world.getChunk(chunkX, chunkZ);

                if (chunk == null) {
                    continue;
                }

                ChunkSection[] sections = chunk.getSectionArray();

                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int index = world.sectionCoordToIndex(sectionY);

                    if (index < 0 || index >= sections.length) {
                        continue;
                    }

                    ChunkSection section = sections[index];

                    if (section == null || section.isEmpty()) {
                        continue;
                    }

                    if (!section.hasAny(state -> state.getLuminance() > 0)) {
                        continue;
                    }

                    int baseX = chunkX << 4;
                    int baseY = sectionY << 4;
                    int baseZ = chunkZ << 4;

                    // ChunkSection index order: x in the low bits, then z, then y.
                    for (int i = 0; i < 4096; i++) {
                        int x = i & 15;
                        int z = (i >> 4) & 15;
                        int y = (i >> 8) & 15;

                        BlockState state = section.getBlockState(x, y, z);

                        if (state.getLuminance() <= 0) {
                            continue;
                        }

                        consumer.accept(state, baseX + x, baseY + y, baseZ + z);
                    }
                }
            }
        }
    }

    // ---- Atlas upload ---------------------------------------------------------------------

    private static void upload() {
        if (textureId == 0) {
            createTexture();
        }

        ByteBuffer buffer = uploadBuffer;
        buffer.clear();

        for (int i = 0; i < GRID.length; i++) {
            float value = GRID[i];

            if (value <= 0.0F) {
                buffer.put((byte) 0);
            } else if (value >= 1.0F) {
                buffer.put((byte) 255);
            } else {
                buffer.put((byte) (value * 255.0F));
            }
        }

        buffer.flip();

        // The grid texture lives on unit 2. Bind it explicitly and restore the active unit:
        // uploads happen from the client tick, not from the chunk shader setup, and leaking the
        // grid texture onto unit 0/1 made unrelated rendering flicker.
        int previousTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 2);
        GlStateManager._bindTexture(textureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE_X, SIZE_Y * SIZE_Z,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buffer);
        GlStateManager._activeTexture(previousTexture);
    }

    private static void createTexture() {
        int previousTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 2);

        textureId = GL11.glGenTextures();

        // A plain 2D atlas instead of a 3D texture. MobileGlues has a native crash in its
        // glTexImage3D path on some devices; every GLES 3 driver handles a 2D RGB8 texture.
        // Each Z slice is a block of SIZE_Y rows, so the row-major grid order
        // ((z * SIZE_Y + y) * SIZE_X + x) is already the upload order.
        GlStateManager._bindTexture(textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        uploadBuffer = BufferUtils.createByteBuffer(CELLS * 3);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, SIZE_X, SIZE_Y * SIZE_Z, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, uploadBuffer);

        GlStateManager._activeTexture(previousTexture);
    }


    // ---- Rethinking Voxels profile --------------------------------------------------------
    //
    // The No Effects grid above is a separable squared-distance transform: fast, but it has no
    // idea what stands between a source and a cell, so light goes through walls and a torch
    // behind a slab lights the room. The Rethinking profile replaces only the transport, never
    // the palette/fog/water of the base profile:
    //
    //  * every cell carries the six faces its outline shape leaves open (RV's `propagates()`);
    //    a full cube is closed, a bottom slab is open on the sides and on top, a plant is open
    //    everywhere. Light can only leave a face that is open and enter a face that is open.
    //  * translucent cells (water, glass, ice, slime, honey) keep the face open but multiply the
    //    colour that crosses them by RV's `colMult`, so a torch behind red glass paints the floor
    //    red instead of white.
    //  * emissive cells are always open, even when their shape is a full cube (glowstone, sea
    //    lantern), exactly like RV's early `emissive` return.
    //  * the wavefront carries the colour along the path and attenuates with the same 12-block
    //    curve as the hand, so hand and floor agree on distance as well as on hue.
    //
    // It stays a CPU grid: no ray tracing, no SSBO, no extra framebuffer. One rebuild costs a
    // scan of 16k cells plus one bounded flood per seed, and only runs when the player crosses an
    // eight-block boundary or a block changes.

    /** One bit per face: +X, -X, +Y, -Y, +Z, -Z. A full cube is 0, a plant is {@link #FULL_MASK}. */
    private static final byte FULL_MASK = 0x3F;
    private static final int[] FACE_DX = { 1, -1, 0, 0, 0, 0 };
    private static final int[] FACE_DY = { 0, 0, 1, -1, 0, 0 };
    private static final int[] FACE_DZ = { 0, 0, 0, 0, 1, -1 };
    /** A lava lake is one emitter, not one per block; this bounds the seeds of a pathological scene. */
    private static final int MAX_RETHINKING_SOURCES = 128;

    private static final byte[] FACE_MASK = new byte[CELLS];
    private static final float[] CELL_TINT = new float[CELLS * 3];
    private static final float[] FLOOD_COLOR = new float[CELLS * 3];
    private static final short[] FLOOD_DEPTH = new short[CELLS];
    private static final byte[] FLOOD_SEEN = new byte[CELLS];
    private static final int[] FLOOD_QUEUE = new int[CELLS];
    private static final Block[] SOURCE_BLOCK = new Block[CELLS];

    private static final Map<BlockState, Integer> SHAPE_CACHE = new HashMap<>();
    private static final Map<BlockState, float[]> TINT_CACHE = new HashMap<>();
    private static final Map<Block, float[]> TRANSLUCENT = new HashMap<>();

    private static final float[] RV_NEUTRAL = { 1.0F, 1.0F, 1.0F };
    private static final float[] RV_CANDLE = { 1.0F, 0.65F, 0.45F };

    /** The palette of Rethinking Voxels ({@code lib/materials/lightColorSettings.glsl}). */
    private static final Map<Block, float[]> RV_COLORS = new HashMap<>();

    static {
        float[] lava = col(1.0F, 0.35F, 0.1F);
        float[] fire = col(1.0F, 0.55F, 0.1F);
        float[] soulFire = col(0.15F, 0.85F, 0.85F);
        float[] torch = col(1.0F, 0.7F, 0.45F);
        float[] glowstone = col(1.0F, 0.75F, 0.55F);
        float[] seaLantern = col(0.85F, 0.95F, 0.95F);
        float[] magma = col(0.9F, 0.4F, 0.05F);
        float[] crying = col(0.55F, 0.15F, 1.0F);
        float[] endRod = col(1.0F, 0.9F, 0.85F);
        float[] furnace = col(0.95F, 0.5F, 0.2F);
        float[] soulTorch = col(0.15F, 0.85F, 0.85F);
        float[] lichen = col(0.8F, 1.0F, 0.85F);
        float[] table = col(0.8F, 0.4F, 0.1F);
        float[] end = col(0.7F, 1.0F, 0.8F);
        float[] dragon = col(0.5F, 0.05F, 0.6F);
        float[] portal = col(0.55F, 0.15F, 1.0F);
        float[] berry = col(0.75F, 0.4F, 0.1F);
        float[] redstoneLamp = col(1.0F, 0.7F, 0.4F);
        float[] shroomlight = col(1.0F, 0.5F, 0.2F);
        float[] ochre = col(1.0F, 0.6F, 0.1F);
        float[] verdant = col(0.65F, 1.0F, 0.15F);
        float[] pearl = col(0.9F, 0.25F, 1.0F);
        float[] sculk = col(0.05F, 1.0F, 1.0F);
        float[] brewing = col(0.9F, 0.4F, 0.1F);
        float[] beacon = col(0.35F, 1.0F, 0.95F);
        float[] conduit = col(0.6F, 0.95F, 1.0F);
        float[] pickle = col(0.65F, 1.0F, 1.0F);
        float[] amethyst = col(0.75F, 0.45F, 1.0F);
        float[] redstone = col(1.0F, 0.05F, 0.05F);
        float[] pumpkin = col(0.95F, 0.6F, 0.3F);

        RV_COLORS.put(Blocks.LAVA, lava);
        RV_COLORS.put(Blocks.LAVA_CAULDRON, lava);
        RV_COLORS.put(Blocks.FIRE, fire);
        RV_COLORS.put(Blocks.SOUL_FIRE, soulFire);
        RV_COLORS.put(Blocks.MAGMA_BLOCK, magma);
        RV_COLORS.put(Blocks.TORCH, torch);
        RV_COLORS.put(Blocks.WALL_TORCH, torch);
        RV_COLORS.put(Blocks.LANTERN, torch);
        RV_COLORS.put(Blocks.CAMPFIRE, fire);
        RV_COLORS.put(Blocks.JACK_O_LANTERN, pumpkin);
        RV_COLORS.put(Blocks.GLOWSTONE, glowstone);
        RV_COLORS.put(Blocks.REDSTONE_LAMP, redstoneLamp);
        RV_COLORS.put(Blocks.FURNACE, furnace);
        RV_COLORS.put(Blocks.BLAST_FURNACE, furnace);
        RV_COLORS.put(Blocks.SMOKER, furnace);
        RV_COLORS.put(Blocks.BREWING_STAND, brewing);
        RV_COLORS.put(Blocks.SEA_LANTERN, seaLantern);
        RV_COLORS.put(Blocks.BEACON, beacon);
        RV_COLORS.put(Blocks.END_ROD, endRod);
        RV_COLORS.put(Blocks.LIGHT, col(1.0F, 1.0F, 1.0F));
        RV_COLORS.put(Blocks.CONDUIT, conduit);
        RV_COLORS.put(Blocks.END_PORTAL, end);
        RV_COLORS.put(Blocks.END_GATEWAY, end);
        RV_COLORS.put(Blocks.END_PORTAL_FRAME, end);
        RV_COLORS.put(Blocks.ENDER_CHEST, dragon);
        RV_COLORS.put(Blocks.SEA_PICKLE, pickle);
        RV_COLORS.put(Blocks.NETHER_PORTAL, portal);
        RV_COLORS.put(Blocks.CRYING_OBSIDIAN, crying);
        RV_COLORS.put(Blocks.RESPAWN_ANCHOR, portal);
        RV_COLORS.put(Blocks.DRAGON_EGG, dragon);
        RV_COLORS.put(Blocks.SOUL_CAMPFIRE, soulFire);
        RV_COLORS.put(Blocks.SOUL_TORCH, soulTorch);
        RV_COLORS.put(Blocks.SOUL_WALL_TORCH, soulTorch);
        RV_COLORS.put(Blocks.SOUL_LANTERN, soulTorch);
        RV_COLORS.put(Blocks.SHROOMLIGHT, shroomlight);
        RV_COLORS.put(Blocks.GLOW_LICHEN, lichen);
        RV_COLORS.put(Blocks.CAVE_VINES, berry);
        RV_COLORS.put(Blocks.CAVE_VINES_PLANT, berry);
        RV_COLORS.put(Blocks.BROWN_MUSHROOM, col(1.0F, 1.0F, 1.0F));
        RV_COLORS.put(Blocks.OCHRE_FROGLIGHT, ochre);
        RV_COLORS.put(Blocks.VERDANT_FROGLIGHT, verdant);
        RV_COLORS.put(Blocks.PEARLESCENT_FROGLIGHT, pearl);
        RV_COLORS.put(Blocks.REDSTONE_TORCH, redstone);
        RV_COLORS.put(Blocks.REDSTONE_WALL_TORCH, redstone);
        RV_COLORS.put(Blocks.REDSTONE_ORE, redstone);
        RV_COLORS.put(Blocks.DEEPSLATE_REDSTONE_ORE, redstone);
        RV_COLORS.put(Blocks.SCULK_CATALYST, sculk);
        RV_COLORS.put(Blocks.SCULK_SENSOR, sculk);
        RV_COLORS.put(Blocks.CALIBRATED_SCULK_SENSOR, sculk);
        RV_COLORS.put(Blocks.SCULK_SHRIEKER, sculk);
        RV_COLORS.put(Blocks.AMETHYST_CLUSTER, amethyst);
        RV_COLORS.put(Blocks.LARGE_AMETHYST_BUD, amethyst);
        RV_COLORS.put(Blocks.MEDIUM_AMETHYST_BUD, amethyst);
        RV_COLORS.put(Blocks.SMALL_AMETHYST_BUD, amethyst);
        RV_COLORS.put(Blocks.ENCHANTING_TABLE, table);

        // RV's `TRANSLUCENT_LIGHT_TINT 0.5`, `TRANSLUCENT_LIGHT_CONDUCTION 1.0`, with the dye as
        // the texture colour. `colMult = clamp(1 - a + a * tint, 0, max(1, 1.02))`.
        TRANSLUCENT.put(Blocks.GLASS, translucent(0xFFFFFF, 0.5F));
        TRANSLUCENT.put(Blocks.GLASS_PANE, translucent(0xFFFFFF, 0.5F));
        TRANSLUCENT.put(Blocks.TINTED_GLASS, translucent(0x1A1A1A, 0.9F));
        TRANSLUCENT.put(Blocks.ICE, translucent(0x7DDAFF, 0.45F));
        TRANSLUCENT.put(Blocks.PACKED_ICE, translucent(0x9CCBFF, 0.55F));
        TRANSLUCENT.put(Blocks.BLUE_ICE, translucent(0x74A8FF, 0.65F));
        TRANSLUCENT.put(Blocks.FROSTED_ICE, translucent(0x9FD8FF, 0.5F));
        TRANSLUCENT.put(Blocks.SLIME_BLOCK, translucent(0x7EBF6B, 0.6F));
        TRANSLUCENT.put(Blocks.HONEY_BLOCK, translucent(0xF2B03B, 0.65F));

        Block[] stained = {
                Blocks.WHITE_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS,
                Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS, Blocks.LIME_STAINED_GLASS,
                Blocks.PINK_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS, Blocks.LIGHT_GRAY_STAINED_GLASS,
                Blocks.CYAN_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS,
                Blocks.BROWN_STAINED_GLASS, Blocks.GREEN_STAINED_GLASS, Blocks.RED_STAINED_GLASS,
                Blocks.BLACK_STAINED_GLASS
        };
        Block[] stainedPanes = {
                Blocks.WHITE_STAINED_GLASS_PANE, Blocks.ORANGE_STAINED_GLASS_PANE,
                Blocks.MAGENTA_STAINED_GLASS_PANE, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE,
                Blocks.YELLOW_STAINED_GLASS_PANE, Blocks.LIME_STAINED_GLASS_PANE,
                Blocks.PINK_STAINED_GLASS_PANE, Blocks.GRAY_STAINED_GLASS_PANE,
                Blocks.LIGHT_GRAY_STAINED_GLASS_PANE, Blocks.CYAN_STAINED_GLASS_PANE,
                Blocks.PURPLE_STAINED_GLASS_PANE, Blocks.BLUE_STAINED_GLASS_PANE,
                Blocks.BROWN_STAINED_GLASS_PANE, Blocks.GREEN_STAINED_GLASS_PANE,
                Blocks.RED_STAINED_GLASS_PANE, Blocks.BLACK_STAINED_GLASS_PANE
        };
        int[] dyes = {
                0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
                0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
        };

        for (int i = 0; i < stained.length; i++) {
            float[] tint = translucent(dyes[i], 0.55F);
            TRANSLUCENT.put(stained[i], tint);
            TRANSLUCENT.put(stainedPanes[i], tint);
        }
    }

    private static float[] col(float r, float g, float b) {
        return new float[] { r, g, b };
    }

    /**
     * RV's translucent conduction: the light that crosses a cell is tinted by the cell and loses
     * the energy its alpha absorbs. {@code alpha} is the texture alpha; the shape is 1:1 with the
     * pack, only the source colour comes from the block instead of from colortex15.
     */
    private static float[] translucent(int hex, float alpha) {
        float r = ((hex >> 16) & 0xFF) / 255.0F;
        float g = ((hex >> 8) & 0xFF) / 255.0F;
        float b = (hex & 0xFF) / 255.0F;

        float a = (float) Math.sqrt(alpha);
        float max = Math.max(r, Math.max(g, b));

        if (max <= 0.0F) {
            max = 1.0F;
        }

        r /= max;
        g /= max;
        b /= max;

        float sum = Math.max(0.0001F, r + g + b);
        float conduction = 0.5F + 1.0F / sum;

        r *= conduction;
        g *= conduction;
        b *= conduction;

        float cap = 1.02F;

        return new float[] {
                Math.min(cap, Math.max(0.0F, 1.0F - a + a * r)),
                Math.min(cap, Math.max(0.0F, 1.0F - a + a * g)),
                Math.min(cap, Math.max(0.0F, 1.0F - a + a * b))
        };
    }

    private static final float[] WATER_TINT = translucent(0x3F76E4, 0.35F);

    private static void rebuildRethinking(ClientWorld world, int centerX, int centerY, int centerZ) {
        int ox = centerX - SIZE_X / 2;
        int oy = centerY - SIZE_Y / 2;
        int oz = centerZ - SIZE_Z / 2;

        Arrays.fill(GRID, 0.0F);
        Arrays.fill(FACE_MASK, (byte) 0);
        Arrays.fill(CELL_TINT, 1.0F);
        Arrays.fill(SOURCE_BLOCK, null);
        sourceCount = 0;

        int bottom = world.getBottomY();
        int top = world.getTopY();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int z = 0; z < SIZE_Z; z++) {
            int worldZ = oz + z;

            for (int y = 0; y < SIZE_Y; y++) {
                int worldY = oy + y;

                for (int x = 0; x < SIZE_X; x++) {
                    int index = (z * SIZE_Y + y) * SIZE_X + x;

                    if (worldY < bottom || worldY >= top) {
                        FACE_MASK[index] = 0;
                        continue;
                    }

                    pos.set(ox + x, worldY, worldZ);
                    BlockState state = world.getBlockState(pos);
                    int luminance = state.getLuminance();

                    // RV checks `emissive` before `full`, so a glowstone or a sea lantern passes
                    // light even though its shape is a full cube.
                    if (luminance > 0) {
                        FACE_MASK[index] = FULL_MASK;
                        addRethinkingSource(world, pos, state, index, x, y, z, luminance);
                        continue;
                    }

                    if (state.isAir()) {
                        FACE_MASK[index] = FULL_MASK;
                        continue;
                    }

                    float[] tint = tintFor(state);

                    if (tint != null) {
                        FACE_MASK[index] = FULL_MASK;
                        CELL_TINT[index * 3] = tint[0];
                        CELL_TINT[index * 3 + 1] = tint[1];
                        CELL_TINT[index * 3 + 2] = tint[2];
                        continue;
                    }

                    if (state.isOpaque()) {
                        FACE_MASK[index] = 0;
                        continue;
                    }

                    FACE_MASK[index] = (byte) shapeMask(world, pos, state);
                }
            }
        }

        propagateRethinking();

        originX = ox;
        originY = oy;
        originZ = oz;

        upload();
    }

    private static void addRethinkingSource(ClientWorld world, BlockPos pos, BlockState state, int index,
                                            int x, int y, int z, int luminance) {
        Block block = state.getBlock();

        // A lava lake is thousands of emitting cells. Seeding only the first cell of each run of
        // the same block keeps the flood at a few dozen seeds instead of one per lava block; the
        // run is contiguous, so the field is unchanged.
        if (isSameSource(index, x, y, z, block) || sourceCount >= MAX_RETHINKING_SOURCES) {
            SOURCE_BLOCK[index] = block;
            return;
        }

        float[] color = colorFor(block);
        addSource(x, y, z, color, lightStrength(luminance), luminance);

        SOURCE_BLOCK[index] = block;
    }

    private static boolean isSameSource(int index, int x, int y, int z, Block block) {
        if (x > 0 && SOURCE_BLOCK[index - 1] == block) {
            return true;
        }

        if (y > 0 && SOURCE_BLOCK[index - SIZE_X] == block) {
            return true;
        }

        return z > 0 && SOURCE_BLOCK[index - SIZE_X * SIZE_Y] == block;
    }

    /**
     * RV's {@code propagates()}: one bit per face that the outline shape leaves open. The shape is
     * cached per state, because the scan asks the same few hundred states 16k times.
     */
    private static int shapeMask(ClientWorld world, BlockPos pos, BlockState state) {
        Integer cached = SHAPE_CACHE.get(state);

        if (cached != null) {
            return cached;
        }

        VoxelShape shape = state.getOutlineShape(world, pos);
        int mask;

        if (shape.isEmpty()) {
            mask = FULL_MASK;
        } else {
            mask = 0;

            if (shape.getMax(Direction.Axis.X) < 0.999) {
                mask |= 1 << 0;
            }

            if (shape.getMin(Direction.Axis.X) > 0.001) {
                mask |= 1 << 1;
            }

            if (shape.getMax(Direction.Axis.Y) < 0.999) {
                mask |= 1 << 2;
            }

            if (shape.getMin(Direction.Axis.Y) > 0.001) {
                mask |= 1 << 3;
            }

            if (shape.getMax(Direction.Axis.Z) < 0.999) {
                mask |= 1 << 4;
            }

            if (shape.getMin(Direction.Axis.Z) > 0.001) {
                mask |= 1 << 5;
            }
        }

        SHAPE_CACHE.put(state, mask);

        return mask;
    }

    private static float[] tintFor(BlockState state) {
        if (TINT_CACHE.containsKey(state)) {
            return TINT_CACHE.get(state);
        }

        float[] tint = null;

        if (!state.getFluidState().isEmpty()) {
            if (state.getFluidState().isIn(FluidTags.WATER)) {
                tint = WATER_TINT;
            }
        } else {
            tint = TRANSLUCENT.get(state.getBlock());
        }

        TINT_CACHE.put(state, tint);

        return tint;
    }

    /**
     * Bounded wavefront per seed. The colour travels with the path, so a cell only receives what
     * actually reaches it; the face mask on both sides of every step is what stops the light at a
     * wall, and the tint of every cell is what colours it on the way through glass or water.
     */
    private static void propagateRethinking() {
        for (int source = 0; source < sourceCount; source++) {
            int base = source * 8;
            int seed = ((int) sources[base + 2] * SIZE_Y + (int) sources[base + 1]) * SIZE_X
                    + (int) sources[base];

            Arrays.fill(FLOOD_SEEN, (byte) 0);

            int head = 0;
            int tail = 0;

            FLOOD_QUEUE[tail++] = seed;
            FLOOD_SEEN[seed] = 1;
            FLOOD_DEPTH[seed] = 0;
            FLOOD_COLOR[seed * 3] = sources[base + 3];
            FLOOD_COLOR[seed * 3 + 1] = sources[base + 4];
            FLOOD_COLOR[seed * 3 + 2] = sources[base + 5];

            while (head < tail) {
                int index = FLOOD_QUEUE[head++];
                int cell = index * 3;

                float red = FLOOD_COLOR[cell];
                float green = FLOOD_COLOR[cell + 1];
                float blue = FLOOD_COLOR[cell + 2];

                if (red > GRID[cell]) {
                    GRID[cell] = red;
                }

                if (green > GRID[cell + 1]) {
                    GRID[cell + 1] = green;
                }

                if (blue > GRID[cell + 2]) {
                    GRID[cell + 2] = blue;
                }

                int depth = FLOOD_DEPTH[index];

                if (depth + 1 >= (int) LIGHT_RADIUS) {
                    continue;
                }

                int mask = FACE_MASK[index] & 0xFF;

                if (mask == 0) {
                    continue;
                }

                int cellX = index % SIZE_X;
                int cellY = (index / SIZE_X) % SIZE_Y;
                int cellZ = index / (SIZE_X * SIZE_Y);
                float ratio = attenuationRatio(depth, depth + 1);

                for (int face = 0; face < 6; face++) {
                    if ((mask & (1 << face)) == 0) {
                        continue;
                    }

                    int nx = cellX + FACE_DX[face];
                    int ny = cellY + FACE_DY[face];
                    int nz = cellZ + FACE_DZ[face];

                    if (nx < 0 || nx >= SIZE_X || ny < 0 || ny >= SIZE_Y || nz < 0 || nz >= SIZE_Z) {
                        continue;
                    }

                    int next = (nz * SIZE_Y + ny) * SIZE_X + nx;

                    if (FLOOD_SEEN[next] != 0) {
                        continue;
                    }

                    int nextMask = FACE_MASK[next] & 0xFF;

                    // Light leaves an open face and enters the opposite open face: a slab only
                    // passes on the side it actually leaves free.
                    if (nextMask == 0 || (nextMask & (1 << (face ^ 1))) == 0) {
                        continue;
                    }

                    FLOOD_SEEN[next] = 1;
                    FLOOD_DEPTH[next] = (short) (depth + 1);
                    FLOOD_COLOR[next * 3] = red * CELL_TINT[next * 3] * ratio;
                    FLOOD_COLOR[next * 3 + 1] = green * CELL_TINT[next * 3 + 1] * ratio;
                    FLOOD_COLOR[next * 3 + 2] = blue * CELL_TINT[next * 3 + 2] * ratio;
                    FLOOD_QUEUE[tail++] = next;
                }
            }
        }
    }

    private static float attenuationRatio(int from, int to) {
        float fromCurve = 1.0F - (from * from) / (LIGHT_RADIUS * LIGHT_RADIUS);
        float toCurve = 1.0F - (to * to) / (LIGHT_RADIUS * LIGHT_RADIUS);

        if (fromCurve <= 0.0001F) {
            return 0.0F;
        }

        return (toCurve * toCurve) / (fromCurve * fromCurve);
    }

    // ---- Shared helpers -------------------------------------------------------------------

    /**
     * The same peak intensity for a block in the hand and for the same block placed in the world.
     * Luminance is the only property both paths can agree on: the hand has no block state, and the
     * grid has no item stack.
     */
    static float lightStrength(int luminance) {
        return 0.55F + (0.45F * luminance / 15.0F);
    }

    private static float[] rgb(int hex) {
        return new float[] {
                ((hex >> 16) & 0xFF) / 255.0F,
                ((hex >> 8) & 0xFF) / 255.0F,
                (hex & 0xFF) / 255.0F
        };
    }

    static float[] colorFor(Block block) {
        boolean rethinking = MakeupProfile.isRethinking();
        float[] color = (rethinking ? RV_COLORS : COLORS).get(block);

        if (color == null) {
            color = block instanceof AbstractCandleBlock
                    ? (rethinking ? RV_CANDLE : CANDLE)
                    : (rethinking ? RV_NEUTRAL : NEUTRAL);
        }

        return color;
    }
}
