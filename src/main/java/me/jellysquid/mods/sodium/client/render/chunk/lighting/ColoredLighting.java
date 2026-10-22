package me.jellysquid.mods.sodium.client.render.chunk.lighting;

import com.mojang.blaze3d.platform.GlStateManager;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.LightEngine;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.LightingPath;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
 *     atlas. Sources are splatted with a Starlight-style flood fill that carries the source
 *     index, so every cell uses the same distance curve as the hand light.</li>
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
    private static final int[] QUEUE = new int[CELLS];
    private static final int[] STAMP = new int[CELLS];

    /** Flood-fill queue tail; the queue itself is a flat array with no per-entry objects. */
    private static int queueTail;
    private static int stampCounter;

    /** x, y, z (grid-local) then the three premultiplied colour components. */
    private static float[] sources = new float[256 * 6];
    private static int sourceCount;

    private static int textureId;
    private static ByteBuffer uploadBuffer;
    private static int originX, originY, originZ;

    private static int anchorX, anchorY, anchorZ;
    private static boolean hasAnchor;
    private static boolean gridDirty = true;
    private static int ticksSinceGridRebuild;

    // ---- Shared state ---------------------------------------------------------------------

    private static int irisTimer;
    private static boolean irisPack;
    private static boolean shaderFailed;
    private static boolean analyticFailed;

    private ColoredLighting() {
    }

    public static LightingPath getPath() {
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
     * Iris replaces Sodium's chunk shader while a pack is on. The reflection is done once per
     * second at most, and only when Iris is actually installed.
     */
    public static boolean isIrisShaderPackInUse() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return false;
        }

        if (--irisTimer <= 0) {
            irisTimer = 20;
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

        if (!isActive() || client.world == null || client.player == null || client.isPaused()) {
            return;
        }

        if (useAnalytic()) {
            AnalyticLighting.tick(client);
        } else {
            tickGrid(client);
        }
    }

    private static void tickGrid(MinecraftClient client) {
        BlockPos center = client.player.getBlockPos();

        ticksSinceGridRebuild++;

        boolean moved = !hasAnchor
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
        hasAnchor = true;
        anchorX = center.getX();
        anchorY = center.getY();
        anchorZ = center.getZ();

        rebuildGrid(client.world, center);
    }

    // ---- Grid rebuild ---------------------------------------------------------------------

    private static void rebuildGrid(ClientWorld world, BlockPos center) {
        int ox = center.getX() - SIZE_X / 2;
        int oy = center.getY() - SIZE_Y / 2;
        int oz = center.getZ() - SIZE_Z / 2;

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

                    // The 2D atlas is laid out x-fastest, then y for one Z slice, then the next
                    // Z slice. Keep that order in the grid as well.
                    int index = ((lz * SIZE_Y + ly) * SIZE_X + lx) * 3;

                    GRID[index] = Math.max(GRID[index], color[0] * strength);
                    GRID[index + 1] = Math.max(GRID[index + 1], color[1] * strength);
                    GRID[index + 2] = Math.max(GRID[index + 2], color[2] * strength);

                    addSource(lx, ly, lz, color, strength);
                });

        diffuseGrid();

        originX = ox;
        originY = oy;
        originZ = oz;

        upload();
    }

    private static void addSource(int x, int y, int z, float[] color, float strength) {
        if ((sourceCount + 1) * 6 > sources.length) {
            sources = Arrays.copyOf(sources, sources.length * 2);
        }

        int base = sourceCount * 6;
        sources[base] = x;
        sources[base + 1] = y;
        sources[base + 2] = z;
        sources[base + 3] = color[0] * strength;
        sources[base + 4] = color[1] * strength;
        sources[base + 5] = color[2] * strength;
        sourceCount++;
    }

    /**
     * Starlight-style flood fill. Each source owns one pass of a flat FIFO queue; every visited
     * cell evaluates the exact distance to that source with the hand-light curve, and the six
     * neighbours are queued only while they remain inside the 12-block sphere. The result is the
     * same attenuation the shader applies to the held block, so a placed torch and a held torch
     * have the same peak intensity and the same falloff on the floor.
     */
    private static void diffuseGrid() {
        for (int source = 0; source < sourceCount; source++) {
            int base = source * 6;

            int sx = (int) sources[base];
            int sy = (int) sources[base + 1];
            int sz = (int) sources[base + 2];

            float cr = sources[base + 3];
            float cg = sources[base + 4];
            float cb = sources[base + 5];

            int stamp = nextStamp();

            queueTail = 0;

            int start = (sz * SIZE_Y + sy) * SIZE_X + sx;
            STAMP[start] = stamp;
            QUEUE[queueTail++] = start;

            int head = 0;

            while (head < queueTail) {
                int index = QUEUE[head++];

                int x = index % SIZE_X;
                int y = (index / SIZE_X) % SIZE_Y;
                int z = index / (SIZE_X * SIZE_Y);

                int dx = x - sx;
                int dy = y - sy;
                int dz = z - sz;
                int distSq = dx * dx + dy * dy + dz * dz;

                float atten = 1.0F - (float) distSq / (LIGHT_RADIUS * LIGHT_RADIUS);

                if (atten > 0.0F) {
                    atten *= atten;

                    int cell = index * 3;
                    GRID[cell] = Math.max(GRID[cell], cr * atten);
                    GRID[cell + 1] = Math.max(GRID[cell + 1], cg * atten);
                    GRID[cell + 2] = Math.max(GRID[cell + 2], cb * atten);
                }

                enqueue(x + 1, y, z, sx, sy, sz, stamp);
                enqueue(x - 1, y, z, sx, sy, sz, stamp);
                enqueue(x, y + 1, z, sx, sy, sz, stamp);
                enqueue(x, y - 1, z, sx, sy, sz, stamp);
                enqueue(x, y, z + 1, sx, sy, sz, stamp);
                enqueue(x, y, z - 1, sx, sy, sz, stamp);
            }
        }
    }

    private static int nextStamp() {
        if (++stampCounter == 0) {
            Arrays.fill(STAMP, 0);
            stampCounter = 1;
        }

        return stampCounter;
    }

    private static void enqueue(int x, int y, int z, int sx, int sy, int sz, int stamp) {
        if (x < 0 || x >= SIZE_X || y < 0 || y >= SIZE_Y || z < 0 || z >= SIZE_Z) {
            return;
        }

        int dx = x - sx;
        int dy = y - sy;
        int dz = z - sz;

        if (dx * dx + dy * dy + dz * dz >= LIGHT_RADIUS_SQ) {
            return;
        }

        int index = (z * SIZE_Y + y) * SIZE_X + x;

        if (STAMP[index] == stamp) {
            return;
        }

        STAMP[index] = stamp;
        QUEUE[queueTail++] = index;
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

                    var container = section.getBlockStateContainer();

                    int baseX = chunkX << 4;
                    int baseY = sectionY << 4;
                    int baseZ = chunkZ << 4;

                    // PalettedContainer index order: x in the low bits, then z, then y.
                    for (int i = 0; i < 4096; i++) {
                        BlockState state = container.get(i);

                        if (state.getLuminance() <= 0) {
                            continue;
                        }

                        consumer.accept(state,
                                baseX + (i & 15),
                                baseY + ((i >> 8) & 15),
                                baseZ + ((i >> 4) & 15));
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
        float[] color = COLORS.get(block);

        if (color == null) {
            color = block instanceof AbstractCandleBlock ? CANDLE : NEUTRAL;
        }

        return color;
    }
}
