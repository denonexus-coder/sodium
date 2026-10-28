package me.jellysquid.mods.sodium.client.render.chunk.lighting.infinitium;

import com.mojang.blaze3d.platform.GlStateManager;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.InfinitiumGridSize;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.InfinitiumSettings;
import net.fabricmc.loader.api.FabricLoader;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL32C;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * INFINITIUM LIGHT ENGINE -- motor de iluminacao voxel fiel ao rethinking-voxels (RV).
 *
 * <p>Reescrito por completo a partir do ILE antigo. Em vez de um BFS por classe de cor com
 * gate de luz vanilla (que dava luz fraca, arco-iris e vazava paredes), este motor faz
 * <b>avaliacao direta fonte-&gt;ponto</b> com a matematica LITERAL do RV:
 *
 * <ul>
 *   <li>Paleta: 47 cores e 39 brilhos copiados de {@code lightColorSettings.glsl} do RV.</li>
 *   <li>Atenuacao: {@code 0.0625 * nivel * pow(max(0, 1 - d/nivel), 2)} (ignora se < 0.01).</li>
 *   <li>Mistura: soma additiva das fontes + normalizacao {@code cor/max * log(max*0.4 + 1)}
 *       (matiz estavel -- sem arco-iris).</li>
 *   <li>Luz da mao: mesma paleta, avaliada por vertice no shader (sem reconstruir a grelha).</li>
 * </ul>
 *
 * <p>A soma e a normalizacao correm na CPU (Java) e sobem como atlas 2D; o resultado e
 * identico ao do RV (mesmas formulas). Sombra/raytrace fica desativado por ora -- so sombreamento
 * N.L, escolhivel nos 3 modos ao vivo. Iris check + fallback inquebravel preservados.
 */
public final class InfinitiumLightEngine {
    private static final Logger LOGGER = LogManager.getLogger("InfinitiumLightEngine");

    // ---- Estado da grelha -----------------------------------------------------------------
    private static int SIZE_X, SIZE_Y, SIZE_Z;
    private static int CELLS;
    private static InfinitiumGridSize gridSize;

    private static int originX, originY, originZ;

    /** Luz RV acumulada por celula (RGB, 0..1 ja normalizada e clampada). */
    private static float[] gridR = new float[0];
    private static float[] gridG = new float[0];
    private static float[] gridB = new float[0];

    // ---- Atlas 2D (upload) ---------------------------------------------------------------
    private static int textureId;
    private static int textureSizeX, textureSizeY;
    private static ByteBuffer uploadBuffer;

    // ---- Fontes temporarias (rebuild) ----------------------------------------------------
    private static float[] sourceX = new float[0];
    private static float[] sourceY = new float[0];
    private static float[] sourceZ = new float[0];
    private static float[] sourceR = new float[0];
    private static float[] sourceG = new float[0];
    private static float[] sourceB = new float[0];
    private static float[] sourceLvl = new float[0];
    private static int sourceCount;

    private static final int MAX_SOURCES = 4096;

    // ---- Controlo -------------------------------------------------------------------------
    private static boolean shaderFailed;
    private static boolean gridDirty = true;
    private static int lastFingerprint = -1;
    private static int ticksSinceRebuild;
    private static final int REBUILD_INTERVAL = 4; // ticks entre reconstrucoes completas

    private static boolean statsEnabled;
    private static long lastRebuildNanos;
    private static int lastSources;
    private static long lastStatsNanos;
    private static boolean firstLog = true;

    // ---- Iris -----------------------------------------------------------------------------
    private static boolean irisPack;
    private static long irisCheckNanos;
    private static final long IRIS_CHECK_INTERVAL_NANOS = 500_000_000L;

    // ---- Modos lidos ao vivo --------------------------------------------------------------
    private static int colorModeOrdinal;
    private static int shadingModeOrdinal;

    // ---- Paleta LITERAL do RV -------------------------------------------------------------
    // Copiada de lib/materials/lightColorSettings.glsl + o mapeamento bloco->cor de
    // lib/materials/shadowchecks_precise.glsl. Cada entrada: {r, g, b, nivel}.
    private static final Map<Block, float[]> PALETTE = new HashMap<>();

    static {
        // tocha / torch / wall_torch
        reg(Blocks.TORCH, 1.00f, 0.70f, 0.45f, 20);
        reg(Blocks.WALL_TORCH, 1.00f, 0.70f, 0.45f, 20);
        // soul torch (ciano)
        reg(Blocks.SOUL_TORCH, 0.15f, 0.85f, 0.85f, 20);
        reg(Blocks.SOUL_WALL_TORCH, 0.15f, 0.85f, 0.85f, 20);
        // lantern / soul lantern (brilho 22 do RV)
        reg(Blocks.LANTERN, 1.00f, 0.70f, 0.45f, 22);
        reg(Blocks.SOUL_LANTERN, 0.15f, 0.85f, 0.85f, 22);
        // lava
        reg(Blocks.LAVA, 1.00f, 0.35f, 0.10f, 24);
        // glowstone
        reg(Blocks.GLOWSTONE, 1.00f, 0.75f, 0.55f, 22);
        // sea lantern
        reg(Blocks.SEA_LANTERN, 0.85f, 0.95f, 0.95f, 22);
        // magma block
        reg(Blocks.MAGMA_BLOCK, 0.90f, 0.40f, 0.05f, 17);
        // crying obsidian
        reg(Blocks.CRYING_OBSIDIAN, 0.55f, 0.15f, 1.00f, 12);
        // end rod
        reg(Blocks.END_ROD, 1.00f, 0.90f, 0.85f, 22);
        // jack o lantern (pumpkin)
        reg(Blocks.JACK_O_LANTERN, 0.95f, 0.60f, 0.30f, 20);
        // campfire / soul campfire
        reg(Blocks.CAMPFIRE, 1.00f, 0.55f, 0.10f, 20);
        reg(Blocks.SOUL_CAMPFIRE, 0.15f, 0.85f, 0.85f, 20);
        // shroomlight
        reg(Blocks.SHROOMLIGHT, 1.00f, 0.50f, 0.20f, 22);
        // redstone torch / lamp (acesos)
        reg(Blocks.REDSTONE_TORCH, 1.00f, 0.05f, 0.05f, 16);
        reg(Blocks.REDSTONE_LAMP, 1.00f, 0.70f, 0.40f, 22);
        // froglights
        reg(Blocks.OCHRE_FROGLIGHT, 1.00f, 0.60f, 0.10f, 23);
        reg(Blocks.VERDANT_FROGLIGHT, 0.65f, 1.00f, 0.15f, 23);
        reg(Blocks.PEARLESCENT_FROGLIGHT, 0.90f, 0.25f, 1.00f, 23);
        // beacon / conduit
        reg(Blocks.BEACON, 0.35f, 1.00f, 0.95f, 24);
        reg(Blocks.CONDUIT, 0.60f, 0.95f, 1.00f, 16);
        // candles (acesos)
        reg(Blocks.CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.WHITE_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.ORANGE_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.YELLOW_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.RED_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.BLUE_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.GREEN_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.PURPLE_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.CYAN_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.LIGHT_BLUE_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.LIME_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.PINK_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.MAGENTA_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.GRAY_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.LIGHT_GRAY_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.BROWN_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        reg(Blocks.BLACK_CANDLE, 1.00f, 0.65f, 0.45f, 10);
        // glow lichen (baixo, ciano-esverdeado)
        reg(Blocks.GLOW_LICHEN, 0.80f, 1.00f, 0.85f, 12);
        // sculk catalyst / shrieker (brilho fraco do RV via SENSOR_BRIGHTNESS_SCULK)
        reg(Blocks.SCULK_CATALYST, 0.30f, 0.65f, 0.70f, 11);
        // amethyst (emissivo sintetico, BRIGHTNESS_AMETHYST=10)
        reg(Blocks.AMETHYST_CLUSTER, 0.75f, 0.45f, 1.00f, 10);
        reg(Blocks.BUDDING_AMETHYST, 0.75f, 0.45f, 1.00f, 10);
        reg(Blocks.SMALL_AMETHYST_BUD, 0.75f, 0.45f, 1.00f, 10);
        reg(Blocks.MEDIUM_AMETHYST_BUD, 0.75f, 0.45f, 1.00f, 10);
        reg(Blocks.LARGE_AMETHYST_BUD, 0.75f, 0.45f, 1.00f, 10);
    }

    private static void reg(Block block, float r, float g, float b, int level) {
        PALETTE.put(block, new float[] { r, g, b, level });
    }

    /**
     * Blocos que o RV faz brilhar mas que o vanilla deixa a luminancia 0 (magma, crying
     * obsidian, ametista, sculk, redstone torch). So brilham quando o toggle de emissivos
     * esta ligado. Redstone torch depende do estado "aceso".
     */
    private static final java.util.Set<Block> SYNTHETIC = java.util.Set.of(
            Blocks.MAGMA_BLOCK, Blocks.CRYING_OBSIDIAN,
            Blocks.AMETHYST_CLUSTER, Blocks.BUDDING_AMETHYST,
            Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
            Blocks.SCULK_CATALYST, Blocks.REDSTONE_TORCH);

    /** Os blocos sinteticos "sempre acesos" brillam por si; o redstone torch so se aceso. */
    private static boolean syntheticActive(BlockState state) {
        if (state.getBlock() == Blocks.REDSTONE_TORCH) {
            return state.get(net.minecraft.block.RedstoneTorchBlock.LIT);
        }
        return true;
    }

    /** Cor de reserva para blocos emissivos fora da paleta (neutro quente). */
    private static final float[] FALLBACK = { 1.00f, 0.90f, 0.78f, 0 };

    private InfinitiumLightEngine() {
    }

    // ---- API publica (chamada pelo renderer / mixins) --------------------------------------

    public static boolean isShaderEnabled() {
        SodiumGameOptions options = SodiumClientMod.options();
        return options != null && options.infinitium.enabled && !shaderFailed && !isIrisShaderPackInUse();
    }

    public static void markShaderFailed() {
        shaderFailed = true;
    }

    public static void markDirty() {
        gridDirty = true;
    }

    public static int getTextureId() {
        if (textureId == 0) {
            createTexture();
        }
        return textureId;
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
        return SIZE_X > 0 ? 1.0F / SIZE_X : 1.0F;
    }

    public static float getScaleY() {
        return SIZE_Y > 0 ? 1.0F / SIZE_Y : 1.0F;
    }

    public static float getScaleZ() {
        return SIZE_Z > 0 ? 1.0F / SIZE_Z : 1.0F;
    }

    /** Modo de cor (0 = so blocklight, 1 = tudo). Lido ao vivo pelo shader via uniform. */
    public static int getColorMode() {
        return colorModeOrdinal;
    }

    /** Modo de sombreamento (0 = vertice N.L, 1 = solido, 2 = por pixel). */
    public static int getShadingMode() {
        return shadingModeOrdinal;
    }

    /**
     * Escreve a luz da mao (posicao camera-relativa, intensidade, cor RV) para o shader.
     * Lida por frame no setupState porque depende da posicao da camera.
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

        if (client.player == null) {
            return;
        }

        SodiumGameOptions options = SodiumClientMod.options();

        if (options == null || !options.infinitium.handLight) {
            return;
        }

        ItemStack stack = client.player.getMainHandStack();

        if (stack.isEmpty()) {
            stack = client.player.getOffHandStack();
        }

        Block block;

        if (stack.isOf(Items.LAVA_BUCKET)) {
            block = Blocks.LAVA;
        } else if (stack.getItem() instanceof BlockItem blockItem) {
            block = blockItem.getBlock();
        } else {
            return;
        }

        float[] entry = PALETTE.get(block);

        if (entry == null) {
            // Sem cor RV especifica: usa a luminancia vanilla com cor neutra quente.
            int lum = block.getDefaultState().getLuminance();

            if (lum <= 0) {
                return;
            }

            entry = new float[] { FALLBACK[0], FALLBACK[1], FALLBACK[2], lum };
        }

        float level = entry[3];

        if (level <= 0.0F) {
            return;
        }

        Vec3d eye = client.player.getEyePos();
        Vec3d look = client.player.getRotationVec(1.0F);

        light[0] = (float) ((eye.x + look.x * 0.6D) - cameraPos.x);
        light[1] = (float) ((eye.y + look.y * 0.6D) - cameraPos.y);
        light[2] = (float) ((eye.z + look.z * 0.6D) - cameraPos.z);
        // Intensidade da mao: o RV usa um nivel fixo da paleta para o item na mao.
        light[3] = level;

        color[0] = entry[0];
        color[1] = entry[1];
        color[2] = entry[2];
    }

    // ---- Tick / rebuild --------------------------------------------------------------------

    /** Client tick; corre na render thread com o contexto GL corrente, por isso pode subir textura. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world == null || client.player == null || client.isPaused()) {
            return;
        }

        SodiumGameOptions options = SodiumClientMod.options();

        if (options == null || !options.infinitium.enabled || shaderFailed) {
            return;
        }

        if (isIrisShaderPackInUse()) {
            return;
        }

        InfinitiumSettings settings = options.infinitium;
        statsEnabled = settings.debugStats;
        colorModeOrdinal = settings.colorMode.ordinal();
        shadingModeOrdinal = settings.shadingMode.ordinal();

        int fingerprint = fingerprint(settings);

        if (fingerprint != lastFingerprint) {
            lastFingerprint = fingerprint;
            gridDirty = true;
        }

        ensureGridSize(settings.gridSize);

        // Origem da grelha: centrada no jogador (bloco inteiro).
        int px = client.player.getBlockPos().getX();
        int py = client.player.getBlockPos().getY();
        int pz = client.player.getBlockPos().getZ();
        originX = px - (SIZE_X >> 1);
        originY = py - (SIZE_Y >> 2);
        originZ = pz - (SIZE_Z >> 1);

        ticksSinceRebuild++;

        if (!gridDirty && ticksSinceRebuild < REBUILD_INTERVAL) {
            return;
        }

        long start = System.nanoTime();
        rebuild(client.world, settings);
        upload();
        lastRebuildNanos = System.nanoTime() - start;

        gridDirty = false;
        ticksSinceRebuild = 0;

        if (statsEnabled) {
            logStats();
        }
    }

    private static int fingerprint(InfinitiumSettings s) {
        int h = 17;
        h = h * 31 + s.colorMode.ordinal();
        h = h * 31 + s.shadingMode.ordinal();
        h = h * 31 + (s.attenQuadratic ? 1 : 0);
        h = h * 31 + (s.logNormalize ? 1 : 0);
        h = h * 31 + (s.threshold ? 1 : 0);
        h = h * 31 + (s.emissiveBlocks ? 1 : 0);
        h = h * 31 + s.gridSize.ordinal();
        return h;
    }

    /**
     * Reconstrui a grelha por avaliacao direta fonte-&gt;ponto (RV). Primeiro recolhe as fontes
     * dentro da regiao; depois cada fonte "espalha" a sua contribuicao nas celulas dentro do
     * alcance, com a atenuacao literal do RV. Sem BFS, sem gate de luz vanilla.
     */
    private static void rebuild(ClientWorld world, InfinitiumSettings settings) {
        // Limpa a acumulacao.
        java.util.Arrays.fill(gridR, 0.0F);
        java.util.Arrays.fill(gridG, 0.0F);
        java.util.Arrays.fill(gridB, 0.0F);

        boolean quad = settings.attenQuadratic;
        boolean threshold = settings.threshold;
        boolean emissive = settings.emissiveBlocks;

        // ---- 1. Recolha de fontes na regiao da grelha (com margem para o alcance) ----------
        sourceCount = 0;
        int margin = 24; // nivel maximo do RV: alcance ate 24 blocos
        int minX = originX - margin, maxX = originX + SIZE_X + margin;
        int minY = originY - margin, maxY = originY + SIZE_Y + margin;
        int minZ = originZ - margin, maxZ = originZ + SIZE_Z + margin;

        BlockPos.Mutable mutable = new BlockPos.Mutable();

        scan:
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }

                    mutable.set(x, y, z);
                    BlockState state = world.getBlockState(mutable);

                    if (state.isAir()) {
                        continue;
                    }

                    float[] entry = PALETTE.get(state.getBlock());
                    int lum = state.getLuminance();

                    float r, g, b, level;

                    if (lum > 0) {
                        // Fonte real: o vanilla ja reflete o estado (vela/campfire/lamp so contam
                        // se acesos). A nivel e a cor vem da paleta RV; sem entrada, fallback.
                        level = entry != null ? entry[3] : lum;
                        r = entry != null ? entry[0] : FALLBACK[0];
                        g = entry != null ? entry[1] : FALLBACK[1];
                        b = entry != null ? entry[2] : FALLBACK[2];
                    } else if (emissive && entry != null && SYNTHETIC.contains(state.getBlock())
                            && syntheticActive(state)) {
                        // Bloco sintetico do RV (brilha onde o vanilla fica a zero).
                        level = entry[3];
                        r = entry[0];
                        g = entry[1];
                        b = entry[2];
                    } else {
                        continue;
                    }

                    if (level <= 0.0F) {
                        continue;
                    }

                    if (sourceCount >= MAX_SOURCES) {
                        break scan;
                    }

                    addSource(x + 0.5F, y + 0.5F, z + 0.5F, r, g, b, level);
                }
            }
        }

        lastSources = sourceCount;

        // ---- 2. Espalhar cada fonte nas celulas (atenuacao literal do RV) ------------------
        for (int s = 0; s < sourceCount; s++) {
            float sx = sourceX[s], sy = sourceY[s], sz = sourceZ[s];
            float sr = sourceR[s], sg = sourceG[s], sb = sourceB[s];
            float level = sourceLvl[s];

            int radius = (int) Math.ceil(level);
            int cx0 = Math.max(0, (int) Math.floor(sx - radius) - originX);
            int cx1 = Math.min(SIZE_X - 1, (int) Math.ceil(sx + radius) - originX);
            int cy0 = Math.max(0, (int) Math.floor(sy - radius) - originY);
            int cy1 = Math.min(SIZE_Y - 1, (int) Math.ceil(sy + radius) - originY);
            int cz0 = Math.max(0, (int) Math.floor(sz - radius) - originZ);
            int cz1 = Math.min(SIZE_Z - 1, (int) Math.ceil(sz + radius) - originZ);

            for (int cy = cy0; cy <= cy1; cy++) {
                float wy = originY + cy + 0.5F;
                float dy = wy - sy;
                float dy2 = dy * dy;

                for (int cx = cx0; cx <= cx1; cx++) {
                    float wx = originX + cx + 0.5F;
                    float dx = wx - sx;
                    float dx2 = dx * dx;

                    for (int cz = cz0; cz <= cz1; cz++) {
                        float wz = originZ + cz + 0.5F;
                        float dz = wz - sz;
                        float dist = (float) Math.sqrt(dx2 + dy2 + dz * dz);

                        float atten = attenuation(level, dist, quad);

                        if (threshold && atten < 0.01F) {
                            continue;
                        }

                        int idx = (cz * SIZE_Y + cy) * SIZE_X + cx;
                        gridR[idx] += sr * atten;
                        gridG[idx] += sg * atten;
                        gridB[idx] += sb * atten;
                    }
                }
            }
        }

        // ---- 3. Normalizacao final (mistura RV: cor/max * log(max*0.4 + 1)) ----------------
        boolean logNorm = settings.logNormalize;

        for (int i = 0; i < CELLS; i++) {
            float r = gridR[i], g = gridG[i], b = gridB[i];
            float m = Math.max(Math.max(r, g), Math.max(b, 1.0e-9F));

            if (logNorm) {
                float s = (float) (Math.log(m * 0.4 + 1.0) / m);
                r *= s;
                g *= s;
                b *= s;
            }

            gridR[i] = Math.min(1.0F, Math.max(0.0F, r));
            gridG[i] = Math.min(1.0F, Math.max(0.0F, g));
            gridB[i] = Math.min(1.0F, Math.max(0.0F, b));
        }
    }

    /** Atenuacao LITERAL do RV: 0.0625 * nivel * pow(max(0, 1 - d/nivel), 2). */
    private static float attenuation(float level, float dist, boolean quadratic) {
        float f = Math.max(0.0F, 1.0F - dist / level);

        if (quadratic) {
            f = f * f;
        }

        return 0.0625F * level * f;
    }

    private static void addSource(float x, float y, float z, float r, float g, float b, float level) {
        int i = sourceCount++;
        ensureSourceCapacity(sourceCount);

        sourceX[i] = x;
        sourceY[i] = y;
        sourceZ[i] = z;
        sourceR[i] = r;
        sourceG[i] = g;
        sourceB[i] = b;
        sourceLvl[i] = level;
    }

    private static void ensureSourceCapacity(int want) {
        if (sourceX.length >= want) {
            return;
        }

        int n = Math.max(MAX_SOURCES, want);
        sourceX = new float[n];
        sourceY = new float[n];
        sourceZ = new float[n];
        sourceR = new float[n];
        sourceG = new float[n];
        sourceB = new float[n];
        sourceLvl = new float[n];
    }

    // ---- Atlas 2D (upload) -----------------------------------------------------------------

    private static void upload() {
        if (textureId == 0 || textureSizeX != SIZE_X || textureSizeY != SIZE_Y * SIZE_Z) {
            deleteTexture();
            createTexture();
        }

        ByteBuffer buffer = uploadBuffer;
        buffer.clear();

        for (int i = 0; i < CELLS; i++) {
            buffer.put((byte) (gridR[i] * 255.0F));
            buffer.put((byte) (gridG[i] * 255.0F));
            buffer.put((byte) (gridB[i] * 255.0F));
        }

        buffer.flip();

        // Textura da grelha vive na unidade 2. Bind explicito e restore da unidade ativa.
        int previousTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 2);
        GlStateManager._bindTexture(textureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE_X, SIZE_Y * SIZE_Z,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buffer);
        GlStateManager._activeTexture(previousTexture);
    }

    private static void createTexture() {
        if (CELLS == 0) {
            ensureGridSize(InfinitiumGridSize.MEDIUM);
        }

        int previousTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 2);

        textureId = GL11.glGenTextures();

        // Atlas 2D (X por Y*Z): qualquer driver GLES3 trata RGB8; a ordem row-major da grelha
        // e a ordem do upload.
        GlStateManager._bindTexture(textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        uploadBuffer = BufferUtils.createByteBuffer(CELLS * 3);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, SIZE_X, SIZE_Y * SIZE_Z, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, uploadBuffer);

        textureSizeX = SIZE_X;
        textureSizeY = SIZE_Y * SIZE_Z;

        GlStateManager._activeTexture(previousTexture);
    }

    private static void deleteTexture() {
        if (textureId != 0) {
            int previousTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 2);
            GL11.glDeleteTextures(textureId);
            GlStateManager._activeTexture(previousTexture);

            textureId = 0;
            textureSizeX = 0;
            textureSizeY = 0;
        }
    }

    private static void ensureGridSize(InfinitiumGridSize size) {
        if (gridSize == size && CELLS > 0) {
            return;
        }

        gridSize = size;
        SIZE_X = size.getSizeX();
        SIZE_Y = size.getSizeY();
        SIZE_Z = size.getSizeZ();
        CELLS = SIZE_X * SIZE_Y * SIZE_Z;

        gridR = new float[CELLS];
        gridG = new float[CELLS];
        gridB = new float[CELLS];
        gridDirty = true;
    }

    // ---- Diagnostico -----------------------------------------------------------------------

    private static void logStats() {
        long now = System.nanoTime();

        if (!firstLog && now - lastStatsNanos < 2_000_000_000L) {
            return;
        }

        firstLog = false;
        lastStatsNanos = now;

        LOGGER.info("ILE(RV): rebuild=%.3f ms | fontes=%d | grelha=%dx%dx%d | cor=%d sombra=%d"
                .formatted(lastRebuildNanos / 1_000_000.0, lastSources,
                        SIZE_X, SIZE_Y, SIZE_Z, colorModeOrdinal, shadingModeOrdinal));
    }

    // ---- Iris ------------------------------------------------------------------------------

    public static boolean isIrisLoaded() {
        return FabricLoader.getInstance().isModLoaded("iris");
    }

    /**
     * Um shaderpack do Iris substitui o chunk shader do Sodium por completo: nao ha nada para o
     * motor alimentar, por isso desliga-se. Resposta cacheada meio segundo.
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
}
