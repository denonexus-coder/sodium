package me.jellysquid.mods.sodium.client.render.chunk.lighting.infinitium;

import com.mojang.blaze3d.platform.GlStateManager;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.InfinitiumGridSize;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.InfinitiumSettings;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL32C;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * INFINITIUM LIGHT ENGINE (ILE) -- nucleo (Fase 1).
 *
 * <p>Um unico motor de iluminacao voxel, hibrido: a luz vanilla continua a dar niveis de luz e a
 * oclusao do mundo (e com ela o chunk build), e o ILE transporta apenas a COR RGB numa grelha
 * propria a volta do jogador, aplicada a malha por vertice. Nao ha aqui "N motores": um unico
 * caminho, com sub-opcoes que se ligam e desligam ao vivo pelo menu de configuracao.
 *
 * <p>Setores (S1..S10 do documento de engenharia) e onde vivem neste ficheiro:
 *
 * <ul>
 *     <li>S1 Fontes: {@link #COLORS}/{@link #EMISSIVE} (paleta 1.20.1) + {@link #scanSources},
 *         que varre apenas seccoes cuja paleta contem emissores (teste de paleta, estilo
 *         Starlight); o nivel sintetico dos minérios e opcional.</li>
 *     <li>S2 Oclusao: vem "gratis" da luz vanilla -- a propagação RGB so entra em celulas onde a
 *         luz de bloco vanilla e maior que zero ({@link #respectOcclusion}). Paredes opacas,
 *         vidro, agua e folhas comportam-se exatamente como o jogo.</li>
 *     <li>S3 Propagacao: flood-fill por onda (BFS) multi-fonte por classe de cor, incremental:
 *         so corre quando o jogador atravessa uma fronteira de 8 blocos, quando ha blocos em
 *         mudanca (agrupados) ou como rede de seguranca -- nunca por frame.</li>
 *     <li>S5 Transporte RGB: acumulacao linear por contribuicao (A(d)=1/(1+k d²)) com identidade
 *         de cor preservada; com {@link #stableMix} desligado a soma e crua (o "efeito
 *         discoteca" de antes), para A/B.</li>
 *     <li>S6 Estabilidade: curva de atenuacao suave opcional + filtragem temporal asimetrica
 *         (aumentos passam de imediato, quedas descem amortecidas).</li>
 *     <li>S7 Malha: a amostragem e feita no vertex shader ({@code block_layer_opaque.vsh}) e o
 *         valor interpolado; sem passe de fragmento extra.</li>
 *     <li>S8 Dinamica: a luz da mao e um uniform por frame (posicao camera-relativa), nao reconstrói
 *         a grelha a cada passo do jogador.</li>
 *     <li>S10 Adaptação: {@link #nearPriority} da prioridade as classes proximas e corta o raio
 *         das distantes; {@link #debugStats} regista o custo real de cada rebuild.</li>
 * </ul>
 *
 * <p>A textura e um atlas 2D RGB8 (uma fatia Z = um bloco de linhas): a amostragem 3D rebenta no
 * MobileGlues (glTexImage3D), a 2D e tratada por qualquer driver GLES3. Corre no client tick
 * (render thread, contexto GL corrente); o chunk shader apenas a amostra.
 */
public final class InfinitiumLightEngine {
    private static final Logger LOGGER = LogManager.getLogger("InfinitiumLightEngine");

    // ---- Estado da grelha (dimensoes dinamicas pela sub-opcao de tamanho) -----------------

    private static int SIZE_X, SIZE_Y, SIZE_Z;
    private static int CELLS;
    private static InfinitiumGridSize gridSize;

    /** Raio util da luz (blocos). A curva OFF e a curva provada da mao do dev. */
    private static int lightRadius = 12;

    /** Custo da atenuacao suave: A(R) ~ 0.05 no raio. */
    private static float smoothK = 19.0F / (12.0F * 12.0F);

    /** Cor acumulada (linear, so soma). */
    private static float[] accRgb = new float[0];
    /** Peso acumulado (soma de A(d) sobre todas as contribuicoes). */
    private static float[] accWeight = new float[0];
    /** Ultimos bytes enviados -- o campo combinado pronto a subir e a base da filtragem temporal. */
    private static byte[] prevBytes = new byte[0];
    private static boolean prevValid;

    /** Luz vanilla de bloco por celula, com carimbo por rebuild (so se consulta uma vez). */
    private static byte[] maskLight = new byte[0];
    private static int[] maskStamp = new int[0];
    private static int rebuildEpoch;

    /** Fila de propagacao: celula nos 24 bits baixos, distancia nos 8 altos. */
    private static int[] queue = new int[0];
    /** Carimbo de visita por classe (evita Arrays.fill por classe -- a licao do lightCacheGenStamp). */
    private static int[] visitStamp = new int[0];
    private static int visitEpoch;

    // ---- Fontes (S1) ----------------------------------------------------------------------

    /**
     * Tintes de emissao dos emissores 1.20.1. E a cor visual da luz, nao o lightmap vanilla (que
     * tem uma unica rampa quente para tudo). Entradas com estado (forno aceso, amethyst cluster)
     * sao filtradas por {@link BlockState#getLuminance()} no scan.
     */
    private static final Map<Block, float[]> COLORS = new HashMap<>();

    /** Brilho sintetico opcional (minérios, metais, cristais) -- luz nivel 0 no vanilla. */
    private static final Map<Block, float[]> EMISSIVE = new HashMap<>();
    private static final Map<Block, Integer> EMISSIVE_LEVEL = new HashMap<>();

    private static final float[] NEUTRAL = rgb(0xCCCCCC);
    private static final float[] CANDLE = rgb(0xFFC46B);

    /** Fontes do ultimo scan, agrupadas por identidade de cor (chave RGB 0..255). */
    private static final Map<Integer, int[]> groupCells = new HashMap<>();
    private static final List<Group> groups = new ArrayList<>();

    private static final class Group {
        float[] color;
        int[] cells;
        int count;
        int nearDist; // distancia de Chebyshev da fonte mais proxima ao centro da grelha
    }

    private static int sourceCount;

    // ---- Textura / origem -----------------------------------------------------------------

    private static int textureId;
    private static int textureSizeX, textureSizeY;
    private static ByteBuffer uploadBuffer;
    private static int originX, originY, originZ;
    private static float tintStrength = 1.0F;

    // ---- Âncora / politica de rebuild -----------------------------------------------------

    private static int anchorX, anchorY, anchorZ;
    private static boolean hasAnchor;
    private static ClientWorld gridWorld;
    private static boolean gridDirty = true;
    private static int ticksSinceRebuild;
    private static int lastFingerprint = Integer.MIN_VALUE;

    /** Rede de seguranca: uma ocorrencia perdida nunca pode deixar a grelha velha para sempre. */
    private static final int REFRESH_TICKS = 200;
    /** Piso entre rebuilds por bloco: chegadas em rajada (redstone, agua) ficam a 4 Hz. */
    private static final int MIN_DIRTY_INTERVAL = 4;
    private static final int ANCHOR_DISTANCE = 8;

    // ---- Falhas / Iris --------------------------------------------------------------------

    private static final long IRIS_CHECK_INTERVAL_NANOS = 500_000_000L;
    private static long irisCheckNanos;
    private static boolean irisPack;
    private static volatile boolean shaderFailed;

    private static final BlockPos.Mutable BLOCK_POS = new BlockPos.Mutable();

    private static final Comparator<Group> NEAR_FIRST = Comparator.comparingInt(g -> g.nearDist);

    // ---- Diagnostico (S10) ---------------------------------------------------------------

    private static boolean statsEnabled;
    private static long lastStatsNanos;
    private static long lastRebuildNanos;
    private static int lastGroups, lastSources, lastReached;
    private static boolean firstLog = true;

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

        emissive(Blocks.IRON_ORE, 0xD8C0A8, 3);
        emissive(Blocks.DEEPSLATE_IRON_ORE, 0xD8C0A8, 3);
        emissive(Blocks.COPPER_ORE, 0xE0804D, 3);
        emissive(Blocks.DEEPSLATE_COPPER_ORE, 0xE0804D, 3);
        emissive(Blocks.GOLD_ORE, 0xFFD94C, 4);
        emissive(Blocks.DEEPSLATE_GOLD_ORE, 0xFFD94C, 4);
        emissive(Blocks.NETHER_GOLD_ORE, 0xFFC94C, 4);
        emissive(Blocks.LAPIS_ORE, 0x2A5CE0, 4);
        emissive(Blocks.DEEPSLATE_LAPIS_ORE, 0x2A5CE0, 4);
        emissive(Blocks.DIAMOND_ORE, 0x4AEDD9, 5);
        emissive(Blocks.DEEPSLATE_DIAMOND_ORE, 0x4AEDD9, 5);
        emissive(Blocks.EMERALD_ORE, 0x3CE07A, 5);
        emissive(Blocks.DEEPSLATE_EMERALD_ORE, 0x3CE07A, 5);
        emissive(Blocks.NETHER_QUARTZ_ORE, 0xEDE6DE, 3);
        emissive(Blocks.ANCIENT_DEBRIS, 0x8A5A4A, 3);
        emissive(Blocks.RAW_IRON_BLOCK, 0xD8B48C, 3);
        emissive(Blocks.RAW_COPPER_BLOCK, 0xC9663D, 3);
        emissive(Blocks.RAW_GOLD_BLOCK, 0xF0C64A, 4);
        emissive(Blocks.IRON_BLOCK, 0xE8E8E8, 4);
        emissive(Blocks.GOLD_BLOCK, 0xFFE34D, 6);
        emissive(Blocks.DIAMOND_BLOCK, 0x5AF0DC, 7);
        emissive(Blocks.EMERALD_BLOCK, 0x3CE07A, 7);
        emissive(Blocks.LAPIS_BLOCK, 0x2A5CE0, 5);
        emissive(Blocks.REDSTONE_BLOCK, 0xFF3030, 5);
        emissive(Blocks.NETHERITE_BLOCK, 0x6A5A5A, 2);

        int[] copperHues = { 0xE0804D, 0xB88A5A, 0x7FA07A, 0x5AA587 };
        Block[][] copperTiers = {
                { Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.WAXED_COPPER_BLOCK, Blocks.WAXED_CUT_COPPER },
                { Blocks.EXPOSED_COPPER, Blocks.EXPOSED_CUT_COPPER, Blocks.WAXED_EXPOSED_COPPER, Blocks.WAXED_EXPOSED_CUT_COPPER },
                { Blocks.WEATHERED_COPPER, Blocks.WEATHERED_CUT_COPPER, Blocks.WAXED_WEATHERED_COPPER, Blocks.WAXED_WEATHERED_CUT_COPPER },
                { Blocks.OXIDIZED_COPPER, Blocks.OXIDIZED_CUT_COPPER, Blocks.WAXED_OXIDIZED_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER }
        };
        for (int tier = 0; tier < copperTiers.length; tier++) {
            for (Block block : copperTiers[tier]) {
                emissive(block, copperHues[tier], 4);
            }
        }

        emissive(Blocks.QUARTZ_BLOCK, 0xF2EDE4, 3);
        emissive(Blocks.SMOOTH_QUARTZ, 0xF2EDE4, 3);
        emissive(Blocks.CHISELED_QUARTZ_BLOCK, 0xF2EDE4, 3);
        emissive(Blocks.QUARTZ_PILLAR, 0xF2EDE4, 3);
        emissive(Blocks.QUARTZ_BRICKS, 0xF2EDE4, 3);
        emissive(Blocks.AMETHYST_BLOCK, 0xC9A6F0, 5);
        emissive(Blocks.BUDDING_AMETHYST, 0xC9A6F0, 5);
    }

    private InfinitiumLightEngine() {
    }

    // ---- Estado publico (GUI / shader) -----------------------------------------------------

    /** O interruptor mestre esta ligado e nada falhou. Lido ao vivo, por frame. */
    public static boolean isShaderEnabled() {
        if (shaderFailed) {
            return false;
        }

        SodiumGameOptions options = SodiumClientMod.options();

        return options != null && options.infinitium.enabled;
    }

    /** O motor deve correr no tick atual (mestre ligado, mundo carregado, sem Iris). */
    public static boolean isActive() {
        return isShaderEnabled() && !isIrisShaderPackInUse();
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

    /** Forca da tintura (0..100% lido da GUI, ao vivo). */
    public static float getTintStrength() {
        return tintStrength;
    }

    public static int getTextureId() {
        if (textureId == 0) {
            createTexture();
        }

        return textureId;
    }

    /** Chamado pelo compilador de shaders quando o programa do ILE nao compila neste driver. */
    public static void markShaderFailed() {
        shaderFailed = true;
    }

    /**
     * Escreve a luz da mao como x,y,z (camera-relativa), intensidade e r,g,b (S8). Lida por
     * frame no setupState porque depende da posicao da camera, que so o renderer conhece.
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

        BlockState state;

        if (stack.isOf(Items.LAVA_BUCKET)) {
            state = Blocks.LAVA.getDefaultState();
        } else if (stack.getItem() instanceof BlockItem blockItem) {
            state = blockItem.getBlock().getDefaultState();
        } else {
            return;
        }

        int luminance = effectiveLuminance(state);

        if (luminance <= 0) {
            return;
        }

        Vec3d eye = client.player.getEyePos();
        Vec3d look = client.player.getRotationVec(1.0F);

        light[0] = (float) ((eye.x + look.x * 0.6D) - cameraPos.x);
        light[1] = (float) ((eye.y + look.y * 0.6D) - cameraPos.y);
        light[2] = (float) ((eye.z + look.z * 0.6D) - cameraPos.z);
        light[3] = lightStrength(luminance);

        float[] blockColor = colorFor(state.getBlock());
        color[0] = blockColor[0];
        color[1] = blockColor[1];
        color[2] = blockColor[2];
    }

    /** Chamado pelos hooks de atualizacao de blocos: o proximo tick reconstrói de imediato. */
    public static void markDirty() {
        gridDirty = true;
    }

    // ---- Tick / politica de rebuild (S3/S10) ----------------------------------------------

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
        tintStrength = settings.tintStrength / 100.0F;

        // Qualquer sub-opcao que mude marca a grelha: o efeito aparece no rebuild seguinte
        // (no maximo 4 ticks), sem reiniciar o jogo.
        int fingerprint = fingerprint(settings);

        if (fingerprint != lastFingerprint) {
            lastFingerprint = fingerprint;
            gridDirty = true;
            prevValid = false;
        }

        BlockPos center = client.player.getBlockPos();

        ticksSinceRebuild++;

        boolean moved = !hasAnchor
                || client.world != gridWorld
                || Math.abs(center.getX() - anchorX) > ANCHOR_DISTANCE
                || Math.abs(center.getY() - anchorY) > ANCHOR_DISTANCE
                || Math.abs(center.getZ() - anchorZ) > ANCHOR_DISTANCE;

        boolean rebuild;

        if (moved) {
            rebuild = true;
        } else if (gridDirty) {
            rebuild = ticksSinceRebuild >= MIN_DIRTY_INTERVAL;
        } else {
            rebuild = ticksSinceRebuild >= REFRESH_TICKS;
        }

        if (!rebuild) {
            return;
        }

        gridDirty = false;
        ticksSinceRebuild = 0;

        // A origem so muda na fronteira do ancora: recentrar a cada atualizacao de bloco
        // reamostrava o mesmo campo numa fase deslocada -- era isso que cintilava a andar.
        if (moved || !hasAnchor) {
            hasAnchor = true;
            gridWorld = client.world;
            anchorX = center.getX();
            anchorY = center.getY();
            anchorZ = center.getZ();
        }

        rebuildGrid(client.world, anchorX, anchorY, anchorZ, settings);
    }

    private static int fingerprint(InfinitiumSettings s) {
        int hash = s.gridSize.ordinal();
        hash = hash * 31 + (s.respectOcclusion ? 1 : 0);
        hash = hash * 31 + (s.smoothAttenuation ? 1 : 0);
        hash = hash * 31 + (s.stableColorMix ? 1 : 0);
        hash = hash * 31 + (s.temporalFilter ? 1 : 0);
        hash = hash * 31 + (s.nearPriority ? 1 : 0);
        hash = hash * 31 + (s.emissiveBlocks ? 1 : 0);

        return hash;
    }

    // ---- Construcao da grelha (Fase 1) ----------------------------------------------------

    private static void rebuildGrid(ClientWorld world, int centerX, int centerY, int centerZ,
                                    InfinitiumSettings settings) {
        long start = System.nanoTime();

        ensureGridSize(settings.gridSize);

        int ox = centerX - SIZE_X / 2;
        int oy = centerY - SIZE_Y / 2;
        int oz = centerZ - SIZE_Z / 2;

        Arrays.fill(accRgb, 0.0F);
        Arrays.fill(accWeight, 0.0F);

        rebuildEpoch++;
        sourceCount = 0;
        groupCells.clear();

        scanSources(world, ox, oy, oz, ox + SIZE_X - 1, oy + SIZE_Y - 1, oz + SIZE_Z - 1,
                settings.emissiveBlocks, (state, x, y, z) -> {
                    int luminance = effectiveLuminance(state);

                    if (luminance <= 0) {
                        return;
                    }

                    int lx = x - ox;
                    int ly = y - oy;
                    int lz = z - oz;

                    if (lx < 0 || lx >= SIZE_X || ly < 0 || ly >= SIZE_Y || lz < 0 || lz >= SIZE_Z) {
                        return;
                    }

                    addSource(lx, ly, lz, colorFor(state.getBlock()));
                });

        // Propagar e combinar: uma onda multi-fonte por classe de cor.
        groups.clear();

        for (Map.Entry<Integer, int[]> entry : groupCells.entrySet()) {
            Group group = new Group();
            group.color = rgb(entry.getKey());
            group.cells = entry.getValue();
            group.count = group.cells.length;
            group.nearDist = Integer.MAX_VALUE;

            for (int i = 0; i < group.count; i++) {
                int cell = group.cells[i];
                int cx = cell % SIZE_X;
                int cy = (cell / SIZE_X) % SIZE_Y;
                int cz = cell / (SIZE_X * SIZE_Y);

                int dist = Math.max(Math.max(
                        Math.abs(cx - SIZE_X / 2), Math.abs(cy - SIZE_Y / 2)), Math.abs(cz - SIZE_Z / 2));

                if (dist < group.nearDist) {
                    group.nearDist = dist;
                }
            }

            groups.add(group);
        }

        if (settings.nearPriority) {
            // Prioridade A (proxima) antes de C (distante) e raio reduzido para as classes
        // cuja fonte mais proxima esta la fora: a luz la chega de qualquer forma.
            groups.sort(NEAR_FIRST);
        }

        for (int g = 0; g < groups.size(); g++) {
            Group group = groups.get(g);

            int radius = lightRadius;

            if (settings.nearPriority && group.nearDist > 24) {
                radius = Math.max(4, lightRadius / 2);
            }

            propagateGroup(world, ox, oy, oz, group, radius, settings.respectOcclusion,
                    settings.smoothAttenuation);
        }

        combine(settings.stableColorMix, settings.temporalFilter);

        originX = ox;
        originY = oy;
        originZ = oz;

        upload();

        lastRebuildNanos = System.nanoTime() - start;
        lastGroups = groups.size();
        lastSources = sourceCount;

        if (statsEnabled) {
            logStats(centerX, centerY, centerZ);
        }
    }

    private static void addSource(int x, int y, int z, float[] color) {
        int cell = ((z * SIZE_Y + y) * SIZE_X) + x;
        int key = ((int) (color[0] * 255.0F) << 16) | ((int) (color[1] * 255.0F) << 8)
                | (int) (color[2] * 255.0F);

        int[] list = groupCells.get(key);

        if (list == null) {
            groupCells.put(key, new int[] { cell });
        } else {
            int[] grown = Arrays.copyOf(list, list.length + 1);
            grown[list.length] = cell;
            groupCells.put(key, grown);
        }

        sourceCount++;
    }

    /**
     * Uma onda (BFS) por classe de cor: todas as fontes da classe sao sementes ao mesmo tempo,
     * por isso a distancia de cada celula e a distancia a fonte mais proxima da classe, e o
     * trabalho e O(celulas) por classe -- um lago de lava com milhares de blocos emissores ainda
     * e uma unica onda.
     *
     * <p>Cada celula recebe a contribuicao A(d) da classe (acumulacao linear, S5) e so e visitada
     * uma vez por classe. Com {@code respectOcclusion} a onda so entra em celulas com luz de
     * bloco vanilla maior que zero: paredes, portas e agua comportam-se como o jogo (S2/S3), e a
     * primeira classe a consultar cada celula paga a amostragem; as restantes usam a cache.
     */
    private static void propagateGroup(ClientWorld world, int ox, int oy, int oz, Group group,
                                       int radius, boolean respectOcclusion, boolean smooth) {
        visitEpoch++;

        int head = 0;
        int tail = 0;

        for (int i = 0; i < group.count; i++) {
            int cell = group.cells[i];

            if (visitStamp[cell] == visitEpoch) {
                continue;
            }

            visitStamp[cell] = visitEpoch;

            addContribution(cell, group.color, attenuation(0, radius, smooth));
            queue[tail++] = cell; // distancia 0
        }

        float reach = smooth
                ? 1.0F / (1.0F + smoothK * (radius + 1) * (radius + 1))
                : 0.0F;

        while (head < tail) {
            int packed = queue[head++];
            int cell = packed & 0xFFFFFF;
            int dist = (packed >>> 24) + 1;

            if (dist > radius) {
                continue;
            }

            int x = cell % SIZE_X;
            int y = (cell / SIZE_X) % SIZE_Y;
            int z = cell / (SIZE_X * SIZE_Y);

            float atten = attenuation(dist, radius, smooth);

            if (atten <= reach) {
                continue;
            }

            for (int axis = 0; axis < 3; axis++) {
                for (int step = -1; step <= 1; step += 2) {
                    int nx = x, ny = y, nz = z;

                    if (axis == 0) {
                        nx += step;
                    } else if (axis == 1) {
                        ny += step;
                    } else {
                        nz += step;
                    }

                    if (nx < 0 || nx >= SIZE_X || ny < 0 || ny >= SIZE_Y || nz < 0 || nz >= SIZE_Z) {
                        continue;
                    }

                    int neighbour = ((nz * SIZE_Y + ny) * SIZE_X) + nx;

                    if (visitStamp[neighbour] == visitEpoch) {
                        continue;
                    }

                    visitStamp[neighbour] = visitEpoch;

                    if (respectOcclusion && vanillaLightAt(world, ox, oy, oz, neighbour, nx, ny, nz) <= 0) {
                        // Parede: a luz nao entra. A celula fica marcada para esta classe, o que
                        // e correto: nenhuma outro caminho da mesma classe a alcança mais barato.
                        continue;
                    }

                    addContribution(neighbour, group.color, atten);

                    if (tail < queue.length) {
                        queue[tail++] = neighbour | (dist << 24);
                    }
                }
            }
        }
    }

    private static void addContribution(int cell, float[] color, float atten) {
        int base = cell * 3;

        accRgb[base] += color[0] * atten;
        accRgb[base + 1] += color[1] * atten;
        accRgb[base + 2] += color[2] * atten;
        accWeight[cell] += atten;
    }

    /**
     * A(d): 1/(1+k d²) com a opcao suave (nunca apaga a cor de forma abrupta, S5.2), ou a curva
     * provada da mao do dev, (1 - d²/R²)², com a opcao ligada no modo classico.
     */
    private static float attenuation(int dist, int radius, boolean smooth) {
        if (smooth) {
            return 1.0F / (1.0F + smoothK * dist * dist);
        }

        float norm = (float) (dist * dist) / (float) (radius * radius);

        if (norm >= 1.0F) {
            return 0.0F;
        }

        float atten = 1.0F - norm;

        return atten * atten;
    }

    private static int vanillaLightAt(ClientWorld world, int ox, int oy, int oz,
                                      int cell, int x, int y, int z) {
        if (maskStamp[cell] == rebuildEpoch) {
            return maskLight[cell];
        }

        BLOCK_POS.set(ox + x, oy + y, oz + z);
        int level = world.getLight(BLOCK_POS, LightType.BLOCK);

        maskLight[cell] = (byte) level;
        maskStamp[cell] = rebuildEpoch;

        return level;
    }

    /**
     * Combina as contribuicoes no campo final. Com {@code stableMix} a cor e a media ponderada
     * estavel das classes (identidade cromatica sem explosao de intensidade); sem ele, a soma
     * crua com clamp -- exatamente o comportamento "discoteca" que se quer poder comparar.
     */
    private static void combine(boolean stableMix, boolean temporalFilter) {
        boolean usePrev = temporalFilter && prevValid
                && prevBytes.length == CELLS * 3;

        int reached = 0;

        for (int cell = 0; cell < CELLS; cell++) {
            float weight = accWeight[cell];
            int base = cell * 3;

            float r, g, b;

            if (weight <= 0.0F) {
                r = 0.0F;
                g = 0.0F;
                b = 0.0F;
            } else if (stableMix) {
                reached++;

                float presence = Math.min(1.0F, weight);
                float inv = presence / weight;

                r = accRgb[base] * inv;
                g = accRgb[base + 1] * inv;
                b = accRgb[base + 2] * inv;
            } else {
                reached++;

                r = accRgb[base];
                g = accRgb[base + 1];
                b = accRgb[base + 2];
            }

            byte br = toByte(r);
            byte bg = toByte(g);
            byte bb = toByte(b);

            if (usePrev) {
                // Assimetrico (S6.2): aumentos passam de imediato (uma tocha na caverna escura
                // acende na hora), quedas amortecidas em ~4 rebuilds (sem flicker).
                br = filterStep(br, prevBytes[base]);
                bg = filterStep(bg, prevBytes[base + 1]);
                bb = filterStep(bb, prevBytes[base + 2]);
            }

            prevBytes[base] = br;
            prevBytes[base + 1] = bg;
            prevBytes[base + 2] = bb;
        }

        prevValid = true;
        lastReached = reached;
    }

    private static byte filterStep(byte value, byte previous) {
        if (value >= previous) {
            return value;
        }

        return (byte) (((value & 0xFF) * 3 + (previous & 0xFF) + 2) >> 2);
    }

    private static byte toByte(float value) {
        if (value <= 0.0F) {
            return 0;
        }

        if (value >= 1.0F) {
            return (byte) 255;
        }

        return (byte) (value * 255.0F);
    }

    // ---- Scan de fontes (S1) --------------------------------------------------------------

    public interface BlockConsumer {
        void accept(BlockState state, int x, int y, int z);
    }

    /**
     * Visita apenas seccoes cuja paleta contem algum emissores: uma secção sem emissores nunca
     * precisa de ler as suas 4096 celulas, o que transforma uma varredura de caixa inteira num
     * punhado de testes de secção.
     */
    private static void scanSources(ClientWorld world, int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ, boolean scanEmissive,
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

                    final boolean emissiveScan = scanEmissive;

                    if (!section.hasAny(state -> state.getLuminance() > 0
                            || (emissiveScan && EMISSIVE.containsKey(state.getBlock())))) {
                        continue;
                    }

                    int baseX = chunkX << 4;
                    int baseY = sectionY << 4;
                    int baseZ = chunkZ << 4;

                    for (int i = 0; i < 4096; i++) {
                        int x = i & 15;
                        int z = (i >> 4) & 15;
                        int y = (i >> 8) & 15;

                        BlockState state = section.getBlockState(x, y, z);

                        if (effectiveLuminance(state) <= 0) {
                            continue;
                        }

                        consumer.accept(state, baseX + x, baseY + y, baseZ + z);
                    }
                }
            }
        }
    }

    // ---- Atlas 2D (upload provado do dev) -------------------------------------------------

    private static void upload() {
        if (textureId == 0 || textureSizeX != SIZE_X || textureSizeY != SIZE_Y * SIZE_Z) {
            deleteTexture();
            createTexture();
        }

        ByteBuffer buffer = uploadBuffer;
        buffer.clear();

        for (int i = 0; i < CELLS; i++) {
            buffer.put(prevBytes[i * 3]);
            buffer.put(prevBytes[i * 3 + 1]);
            buffer.put(prevBytes[i * 3 + 2]);
        }

        buffer.flip();

        // A textura da grelha vive na unidade 2. Bind explicito e restore da unidade ativa: o
        // upload acontece no client tick, nao no setup do chunk shader, e deixar a grelha na
        // unidade 0/1 fazia renderizacao sem nada a ver piscar.
        int previousTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 2);
        GlStateManager._bindTexture(textureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE_X, SIZE_Y * SIZE_Z,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buffer);
        GlStateManager._activeTexture(previousTexture);
    }

    private static void createTexture() {
        // O programa pode pedir a textura no primeiro frame, ainda antes do primeiro rebuild:
        // aloca a grelha por omissao para o upload nunca ver dimensoes zero.
        if (CELLS == 0) {
            ensureGridSize(InfinitiumGridSize.MEDIUM);
        }

        int previousTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 2);

        textureId = GL11.glGenTextures();

        // Atlas 2D em vez de textura 3D: o glTexImage3D do MobileGlues tem um crash nativo em
        // alguns aparelhos; qualquer driver GLES3 trata 2D RGB8. Cada fatia Z e um bloco de
        // SIZE_Y linhas, por isso a ordem row-major da grelha ja e a ordem do upload.
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

    // ---- Alocacao dinamica ----------------------------------------------------------------

    private static void ensureGridSize(InfinitiumGridSize size) {
        if (gridSize == size && CELLS > 0) {
            return;
        }

        gridSize = size;
        SIZE_X = size.getSizeX();
        SIZE_Y = size.getSizeY();
        SIZE_Z = size.getSizeZ();
        CELLS = SIZE_X * SIZE_Y * SIZE_Z;

        lightRadius = size == InfinitiumGridSize.LARGE ? 14 : 12;
        smoothK = 19.0F / (float) (lightRadius * lightRadius);

        accRgb = new float[CELLS * 3];
        accWeight = new float[CELLS];
        prevBytes = new byte[CELLS * 3];
        maskLight = new byte[CELLS];
        maskStamp = new int[CELLS];
        queue = new int[CELLS];
        visitStamp = new int[CELLS];
        prevValid = false;
    }

    // ---- Utilidades de paleta -------------------------------------------------------------

    private static void emissive(Block block, int hex, int level) {
        EMISSIVE.put(block, rgb(hex));
        EMISSIVE_LEVEL.put(block, level);
    }

    private static float lightStrength(int luminance) {
        return 0.55F + (0.45F * luminance / 15.0F);
    }

    private static int effectiveLuminance(BlockState state) {
        int luminance = state.getLuminance();

        if (luminance > 0) {
            return luminance;
        }

        SodiumGameOptions options = SodiumClientMod.options();

        if (options == null || !options.infinitium.emissiveBlocks) {
            return 0;
        }

        Integer synthetic = EMISSIVE_LEVEL.get(state.getBlock());

        return synthetic == null ? 0 : synthetic;
    }

    private static float[] colorFor(Block block) {
        float[] color = COLORS.get(block);

        if (color == null) {
            float[] emissive = EMISSIVE.get(block);

            if (emissive != null) {
                color = emissive;
            } else {
                color = block instanceof AbstractCandleBlock ? CANDLE : NEUTRAL;
            }
        }

        return color;
    }

    private static float[] rgb(int hex) {
        return new float[] {
                ((hex >> 16) & 0xFF) / 255.0F,
                ((hex >> 8) & 0xFF) / 255.0F,
                (hex & 0xFF) / 255.0F
        };
    }

    // ---- Diagnostico (S10) ---------------------------------------------------------------

    private static void logStats(int centerX, int centerY, int centerZ) {
        long now = System.nanoTime();

        if (!firstLog && now - lastStatsNanos < 2_000_000_000L) {
            return;
        }

        firstLog = false;
        lastStatsNanos = now;

        LOGGER.info("ILE: rebuild=%.3f ms | classes=%d fontes=%d celulas=%d/%d | ancora=(%d,%d,%d) grelha=%dx%dx%d"
                        .formatted(lastRebuildNanos / 1_000_000.0, lastGroups, lastSources,
                                lastReached, CELLS, centerX, centerY, centerZ, SIZE_X, SIZE_Y, SIZE_Z));
    }

    // ---- Iris ------------------------------------------------------------------------------

    public static boolean isIrisLoaded() {
        return FabricLoader.getInstance().isModLoaded("iris");
    }

    /**
     * Um shaderpack do Iris substitui o chunk shader do Sodium por completo: nao ha nada para o
     * ILE alimentar, por isso desliga-se em vez de pagar o scan. Resposta cacheada meio segundo
     * -- e perguntada todos os frames.
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
