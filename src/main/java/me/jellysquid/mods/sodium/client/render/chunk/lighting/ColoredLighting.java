package me.jellysquid.mods.sodium.client.render.chunk.lighting;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.LightingPath;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
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

    /** One block in the grid, emitted at full strength before diffusion. */
    private static final float[] WARM = {1.00F, 0.68F, 0.22F};
    private static final float[] NEUTRAL = {0.80F, 0.80F, 0.80F};
    private static final float[] CANDLE = {1.00F, 0.60F, 0.20F};

    private static final Map<Block, float[]> COLORS = new HashMap<>();

    static {
        float[] fire = {1.00F, 0.45F, 0.08F};
        float[] torch = WARM;
        float[] ochre = {1.00F, 0.85F, 0.35F};
        float[] soul = {0.15F, 0.82F, 1.00F};
        float[] aqua = {0.75F, 0.95F, 1.00F};
        float[] verdant = {0.55F, 0.95F, 0.65F};
        float[] pearl = {0.90F, 0.70F, 0.95F};
        float[] redstone = {1.00F, 0.05F, 0.02F};
        float[] magma = {0.90F, 0.25F, 0.05F};
        float[] arcane = {0.70F, 0.20F, 0.95F};
        float[] sculk = {0.00F, 0.70F, 0.75F};
        float[] pickle = {0.30F, 0.85F, 0.55F};
        float[] endRod = {0.95F, 0.95F, 1.00F};

        COLORS.put(Blocks.LAVA, fire);
        COLORS.put(Blocks.FIRE, fire);
        COLORS.put(Blocks.SOUL_FIRE, soul);
        COLORS.put(Blocks.MAGMA_BLOCK, magma);

        COLORS.put(Blocks.TORCH, torch);
        COLORS.put(Blocks.WALL_TORCH, torch);
        COLORS.put(Blocks.LANTERN, torch);
        COLORS.put(Blocks.GLOWSTONE, torch);
        COLORS.put(Blocks.SHROOMLIGHT, torch);
        COLORS.put(Blocks.JACK_O_LANTERN, torch);
        COLORS.put(Blocks.CAMPFIRE, torch);

        COLORS.put(Blocks.SOUL_TORCH, soul);
        COLORS.put(Blocks.SOUL_WALL_TORCH, soul);
        COLORS.put(Blocks.SOUL_LANTERN, soul);
        COLORS.put(Blocks.SOUL_CAMPFIRE, soul);

        COLORS.put(Blocks.SEA_LANTERN, aqua);
        COLORS.put(Blocks.BEACON, aqua);
        COLORS.put(Blocks.END_ROD, endRod);

        COLORS.put(Blocks.OCHRE_FROGLIGHT, ochre);
        COLORS.put(Blocks.VERDANT_FROGLIGHT, verdant);
        COLORS.put(Blocks.PEARLESCENT_FROGLIGHT, pearl);

        COLORS.put(Blocks.REDSTONE_TORCH, redstone);
        COLORS.put(Blocks.REDSTONE_WALL_TORCH, redstone);

        COLORS.put(Blocks.AMETHYST_CLUSTER, arcane);
        COLORS.put(Blocks.LARGE_AMETHYST_BUD, arcane);
        COLORS.put(Blocks.MEDIUM_AMETHYST_BUD, arcane);
        COLORS.put(Blocks.SMALL_AMETHYST_BUD, arcane);
        COLORS.put(Blocks.CRYING_OBSIDIAN, arcane);
        COLORS.put(Blocks.RESPAWN_ANCHOR, arcane);

        COLORS.put(Blocks.SCULK_SENSOR, sculk);
        COLORS.put(Blocks.SCULK_CATALYST, sculk);
        COLORS.put(Blocks.SCULK_SHRIEKER, sculk);
        COLORS.put(Blocks.GLOW_LICHEN, sculk);

        COLORS.put(Blocks.SEA_PICKLE, pickle);
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
        light[3] = 1.0F;

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
                    float strength = 0.55F + (0.45F * luminance / 15.0F);

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

    private static float[] colorFor(Block block) {
        float[] color = COLORS.get(block);

        if (color == null) {
            color = block instanceof CandleBlock ? CANDLE : NEUTRAL;
        }

        return color;
    }
}
