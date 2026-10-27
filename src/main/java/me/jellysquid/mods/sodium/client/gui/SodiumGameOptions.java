package me.jellysquid.mods.sodium.client.gui;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import me.jellysquid.mods.sodium.client.gui.options.TextProvider;
import me.jellysquid.mods.sodium.client.util.FileUtil;
import me.jellysquid.mods.sodium.client.util.FrameTrace;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.text.Text;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

// TODO: Rename in Sodium 0.6
public class SodiumGameOptions {
    private static final String DEFAULT_FILE_NAME = "sodium-options.json";

    public final QualitySettings quality = new QualitySettings();
    public final AdvancedSettings advanced = new AdvancedSettings();
    public final PerformanceSettings performance = new PerformanceSettings();
    public final NotificationSettings notifications = new NotificationSettings();
    public final InfinitiumSettings infinitium = new InfinitiumSettings();

    private boolean readOnly;

    private SodiumGameOptions() {
        // NO-OP
    }

    public static SodiumGameOptions defaults() {
        return new SodiumGameOptions();
    }

    public static class PerformanceSettings {
        public int chunkBuilderThreads = 0;
        @SerializedName("always_defer_chunk_updates_v2") // this will reset the option in older configs
        public boolean alwaysDeferChunkUpdates = true;

        public boolean animateOnlyVisibleTextures = true;
        public boolean useEntityCulling = true;
        public boolean useFogOcclusion = true;
        public boolean useBlockFaceCulling = true;
        public boolean useNoErrorGLContext = true;

        // Otimizações A/B -- cada uma tem o seu interruptor na página Desempenho e é lida no
        // próprio caminho quente, por isso ligar/desligar faz efeito no frame seguinte, sem
        // reiniciar o jogo. As marcas "medido" vêm dos números do commit de cada uma.
        //
        // Medidas (padrão ligado):
        /**0940dac1 A1: dobra sub-draws contíguas em uma (medido: ~2.2 us por sub-draw no PowerVR). */
        public boolean mergeSubDraws = true;
        /** c43b6b32: intervalo de faces contíguo por secção, pré-requisito da fusão acima. */
        public boolean widenVisibleFaces = true;
        /** 0940dac1 A2: staging PERSISTENT+COHERENT (medido: 0.255 ms vs 0.310 ms no GE8320). */
        public boolean coherentStaging = true;
        /** 24b0e95e: nao re-desempacota vêinhos que nao mudaram (~32 KB menos por job). */
        public boolean skipNeighborUnpacks = true;
        /** 24b0e95e: carimbo de geração O(1) em vez de Arrays.fill de 8000 ints por rebuild. */
        public boolean lightCacheGenStamp = true;
        /** Novo: nao reenvia uniformes cujo valor nao mudou (estado visivel identico). */
        public boolean uniformValueCache = true;
        //
        // Novas / por medir (padrão desligado):
        /** Novo: ordena os comandos por offset de vértice antes da fusão (passes opacas). */
        public boolean sortSubDraws = false;

        // Multi-Region Coalescing (09b606df + 48fb039d do dev)
        /** Duas fases (fill CPU -> dispatch GPU) para não forçar flushes prematuros em TBDR. */
        public boolean useMultiRegionCoalescing = true;
    }

    public static class AdvancedSettings {
        public boolean enableMemoryTracing = false;
        public boolean useAdvancedStagingBuffers = true;

        public int cpuRenderAheadLimit = 3;

        // Frame Trace: the chunk-draw instrumentation (see util/FrameTrace). Off by default and
        // costless when off; it exists to be turned on deliberately, from the Advanced page.
        public boolean enableFrameTrace = false;
        public int frameTraceWindow = 300;
        public boolean frameTraceGlFinish = false;
    }

    public static class QualitySettings {
        public GraphicsQuality weatherQuality = GraphicsQuality.DEFAULT;
        public GraphicsQuality leavesQuality = GraphicsQuality.DEFAULT;

        public boolean enableVignette = true;
    }

    public static class NotificationSettings {
        public boolean hasClearedDonationButton = false;
        public boolean hasSeenDonationPrompt = false;
    }

    /**
     * INFINITIUM LIGHT ENGINE -- um unico motor de iluminacao com interruptor mestre e
     * sub-opcoes. Tudo aqui e lido ao vivo no caminho quente (tick + setupState + chave do
     * programa), por isso ligar/desligar qualquer um destes campos faz efeito sem reiniciar o
     * jogo: e assim que se faz A/B de verdade.
     */
    public static class InfinitiumSettings {
        /** Interruptor mestre. Desligado: programa de shader stock, motor nao corre. */
        public boolean enabled = true;

        /** S2/S3: a cor RGB so entra em celulas com luz de bloco vanilla > 0 (paredes mandam). */
        public boolean respectOcclusion = true;

        /** S5.2: atenuacao 1/(1+k d²) suave; desligado usa a curva classica (1 - d²/R²)². */
        public boolean smoothAttenuation = true;

        /** S5.4/S6.3: media ponderada estavel; desligado acumula em bruto (o "efeito discoteca"). */
        public boolean stableColorMix = true;

        /** S6.2: filtragem temporal asimetrica -- aumentos imediatos, quedas amortecidas. */
        public boolean temporalFilter = true;

        /** S8: luz da mao (item luminoso) como fonte dinamica por frame, sem reconstruir a grelha. */
        public boolean handLight = true;

        /** S3/S10: prioridade as classes proximas e raio reduzido para as distantes. */
        public boolean nearPriority = true;

        /** S1: brilho sintetico opcional de minérios/metais/cristais (luz nivel 0 no vanilla). */
        public boolean emissiveBlocks = false;

        /** S10: regista o custo real de cada rebuild no log (uma linha a cada 2 s). */
        public boolean debugStats = false;

        /** S7: forca da tintura por vertice, 0..100%. */
        public int tintStrength = 100;

        /** Tamanho da grelha a volta do jogador (custo e alcance). */
        public InfinitiumGridSize gridSize = InfinitiumGridSize.MEDIUM;
    }

    public enum InfinitiumGridSize implements TextProvider {
        SMALL("Pequena", 24, 12, 24),
        MEDIUM("Media", 32, 16, 32),
        LARGE("Grande", 48, 24, 48);

        private final Text name;
        private final int sizeX, sizeY, sizeZ;

        InfinitiumGridSize(String name, int sizeX, int sizeY, int sizeZ) {
            this.name = Text.literal("Grelha " + name);
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        @Override
        public Text getLocalizedName() {
            return this.name;
        }

        public int getSizeX() {
            return this.sizeX;
        }

        public int getSizeY() {
            return this.sizeY;
        }

        public int getSizeZ() {
            return this.sizeZ;
        }
    }

    public enum GraphicsQuality implements TextProvider {
        DEFAULT("options.gamma.default"),
        FANCY("options.clouds.fancy"),
        FAST("options.clouds.fast");

        private final Text name;

        GraphicsQuality(String name) {
            this.name = Text.translatable(name);
        }

        @Override
        public Text getLocalizedName() {
            return this.name;
        }

        public boolean isFancy(GraphicsMode graphicsMode) {
            return (this == FANCY) || (this == DEFAULT && (graphicsMode == GraphicsMode.FANCY || graphicsMode == GraphicsMode.FABULOUS));
        }
    }

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static SodiumGameOptions loadFromDisk() {
        Path path = getConfigPath();
        SodiumGameOptions config;

        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                config = GSON.fromJson(reader, SodiumGameOptions.class);
            } catch (IOException e) {
                throw new RuntimeException("Could not parse config", e);
            }
        } else {
            config = new SodiumGameOptions();
        }

        // Hand the trace switches over as soon as the options are known, so the instrument is
        // already in the right state before the first frame is drawn.
        FrameTrace.configure(config.advanced.enableFrameTrace, config.advanced.frameTraceWindow,
                config.advanced.frameTraceGlFinish);

        try {
            writeToDisk(config);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't update config file", e);
        }

        return config;
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve(DEFAULT_FILE_NAME);
    }

    public static void writeToDisk(SodiumGameOptions config) throws IOException {
        if (config.isReadOnly()) {
            throw new IllegalStateException("Config file is read-only");
        }

        Path path = getConfigPath();
        Path dir = path.getParent();

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        } else if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }

        FileUtil.writeTextRobustly(GSON.toJson(config), path);
    }

    public boolean isReadOnly() {
        return this.readOnly;
    }

    public void setReadOnly() {
        this.readOnly = true;
    }
}
