package me.jellysquid.mods.sodium.client.render.chunk.lighting;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Colored block and hand light for the chunk shader.
 *
 * <p>The vanilla lightmap holds one warm ramp for every light source, so a soul lantern and a
 * lava lake end up the same color. This class builds a small 3D grid around the player where each
 * cell carries the color of the light that reaches it, and the chunk shader samples it with the
 * world position it already computes. No chunk is rebuilt when the grid changes: the light lives
 * in a texture, not in the vertex data.
 *
 * <p>The grid is re-scanned every {@link #REBUILD_INTERVAL} client ticks. Sources are seeded with
 * their palette color and then diffused with a max-blur, which is what gives the halo around a
 * torch without a flood fill that would have to know about walls. It is an approximation: light
 * crosses a wall here, because the grid has no occlusion and the cost of adding it is not worth
 * it for a light radius of a few blocks.
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
    private static final int REBUILD_INTERVAL = 20;
    private static final int DIFFUSE_PASSES = 6;
    private static final float DIFFUSE_DECAY = 0.84F;

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

    private static final float[] GRID = new float[CELLS * 3];
    private static final float[] SCRATCH = new float[CELLS * 3];

    private static int textureId;
    private static ByteBuffer uploadBuffer;
    private static int originX, originY, originZ;

    private static int tickCounter;
    private static int irisTimer;
    private static boolean irisPack;
    private static boolean shaderFailed;

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

    /** Called by the shader compiler when the colored program fails to compile or link. */
    public static void markShaderFailed() {
        shaderFailed = true;
    }

    /** Lets the user retry after changing the path, without restarting the game. */
    public static void resetShaderFailure() {
        shaderFailed = false;
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

        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }

        BlockState state = blockItem.getBlock().getDefaultState();

        if (state.getLuminance() <= 0) {
            return;
        }

        Vec3d eye = client.player.getEyePos();
        Vec3d look = client.player.getRotationVec(1.0F);

        light[0] = (float) ((eye.x + look.x * 0.6D) - cameraPos.x);
        light[1] = (float) ((eye.y + look.y * 0.6D) - cameraPos.y);
        light[2] = (float) ((eye.z + look.z * 0.6D) - cameraPos.z);
        light[3] = lightStrength(state.getLuminance());

        float[] blockColor = colorFor(blockItem.getBlock());
        color[0] = blockColor[0];
        color[1] = blockColor[1];
        color[2] = blockColor[2];
    }

    /** Client tick hook; the scan runs on the render thread, so it can upload the texture. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (!isActive() || client.world == null || client.player == null || client.isPaused()) {
            tickCounter = 0;
            return;
        }

        if (++tickCounter < REBUILD_INTERVAL) {
            return;
        }

        tickCounter = 0;
        rebuild(client.world, client.player.getBlockPos());
    }

    private static void rebuild(ClientWorld world, BlockPos center) {
        int ox = center.getX() - SIZE_X / 2;
        int oy = center.getY() - SIZE_Y / 2;
        int oz = center.getZ() - SIZE_Z / 2;

        java.util.Arrays.fill(GRID, 0.0F);

        for (int z = 0; z < SIZE_Z; z++) {
            for (int y = 0; y < SIZE_Y; y++) {
                int worldY = oy + y;

                if (world.isOutOfHeightLimit(worldY)) {
                    continue;
                }

                for (int x = 0; x < SIZE_X; x++) {
                    BlockPos pos = new BlockPos(ox + x, worldY, oz + z);
                    BlockState state = world.getBlockState(pos);

                    if (state.isAir()) {
                        continue;
                    }

                    int luminance = state.getLuminance();

                    if (luminance <= 0) {
                        continue;
                    }

                    float[] color = colorFor(state.getBlock());
                    int index = ((y * SIZE_Z + z) * SIZE_X + x) * 3;
                    float strength = lightStrength(luminance);

                    GRID[index] = Math.max(GRID[index], color[0] * strength);
                    GRID[index + 1] = Math.max(GRID[index + 1], color[1] * strength);
                    GRID[index + 2] = Math.max(GRID[index + 2], color[2] * strength);
                }
            }
        }

        diffuse();

        originX = ox;
        originY = oy;
        originZ = oz;

        upload();
    }

    /**
     * Six max-blur passes. A cell takes the strongest neighbor it has, decayed by one step; the
     * result is a soft falloff in every direction instead of a hard sphere around each source.
     */
    private static void diffuse() {
        for (int pass = 0; pass < DIFFUSE_PASSES; pass++) {
            System.arraycopy(GRID, 0, SCRATCH, 0, GRID.length);

            for (int y = 0; y < SIZE_Y; y++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    for (int x = 0; x < SIZE_X; x++) {
                        int index = ((y * SIZE_Z + z) * SIZE_X + x) * 3;

                        if (x > 0) {
                            spread(index, index - 3);
                        }
                        if (x + 1 < SIZE_X) {
                            spread(index, index + 3);
                        }
                        if (z > 0) {
                            spread(index, index - SIZE_X * 3);
                        }
                        if (z + 1 < SIZE_Z) {
                            spread(index, index + SIZE_X * 3);
                        }
                        if (y > 0) {
                            spread(index, index - SIZE_X * SIZE_Z * 3);
                        }
                        if (y + 1 < SIZE_Y) {
                            spread(index, index + SIZE_X * SIZE_Z * 3);
                        }
                    }
                }
            }

            System.arraycopy(SCRATCH, 0, GRID, 0, GRID.length);
        }
    }

    private static void spread(int target, int neighbor) {
        SCRATCH[target] = Math.max(SCRATCH[target], GRID[neighbor] * DIFFUSE_DECAY);
        SCRATCH[target + 1] = Math.max(SCRATCH[target + 1], GRID[neighbor + 1] * DIFFUSE_DECAY);
        SCRATCH[target + 2] = Math.max(SCRATCH[target + 2], GRID[neighbor + 2] * DIFFUSE_DECAY);
    }

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

        GL11.glBindTexture(GL12.GL_TEXTURE_3D, textureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, SIZE_X, SIZE_Y, SIZE_Z,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buffer);
    }

    private static void createTexture() {
        textureId = GL11.glGenTextures();

        GL11.glBindTexture(GL12.GL_TEXTURE_3D, textureId);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGB8, SIZE_X, SIZE_Y, SIZE_Z, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        uploadBuffer = BufferUtils.createByteBuffer(CELLS * 3);
    }

    /**
     * The same peak intensity for a block in the hand and for the same block placed in the world.
     * Luminance is the only property both paths can agree on: the hand has no block state, and the
     * grid has no item stack.
     */
    private static float lightStrength(int luminance) {
        return 0.55F + (0.45F * luminance / 15.0F);
    }

    private static float[] rgb(int hex) {
        return new float[] {
                ((hex >> 16) & 0xFF) / 255.0F,
                ((hex >> 8) & 0xFF) / 255.0F,
                (hex & 0xFF) / 255.0F
        };
    }

    private static float[] colorFor(Block block) {
        float[] color = COLORS.get(block);

        if (color == null) {
            color = block instanceof AbstractCandleBlock ? CANDLE : NEUTRAL;
        }

        return color;
    }
}
