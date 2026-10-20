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
    public final VisualSettings visual = new VisualSettings();
    public final ExperimentalSettings experimental = new ExperimentalSettings();
    public final NotificationSettings notifications = new NotificationSettings();

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

    /**
     * Rendering that Sodium cannot speed up but that the player may want to switch off.
     *
     * <p>Every field here is a pure client-side choice: the server still simulates the same
     * world, so hiding something never changes gameplay. The defaults keep vanilla behaviour,
     * which means a config file written before this section existed loads with everything on.
     */
    public static class VisualSettings {
        public boolean sky = true;
        public boolean beaconBeams = true;
        public boolean enchantingTableBook = true;

        /** Percentage of the render distance kept for block entities. 100 means "no limit of our own". */
        public int blockEntityDistance = 100;
    }

    /**
     * The colored-lighting instrument. It is separate from {@link VisualSettings} because it does
     * not change what the vanilla renderer draws: it replaces the light that the chunk shader
     * applies, and it only exists when the user turns one of the two paths on.
     *
     * <p>The path is an enum instead of two booleans so that the two systems can never run at the
     * same time; the GUI exposes it as one control with three states.
     */
    public static class ExperimentalSettings {
        public LightingPath lighting = LightingPath.OFF;

        /** Adds the light of the held block to the chunk shader; costs a few ALU per vertex. */
        public boolean handLight = true;
    }

    public enum LightingPath {
        /** The chunk shader is the normal Sodium one; no colored light is computed. */
        OFF,
        /** Desktop GLSL 330; MobileGlues translates it to ESSL before the driver sees it. */
        GLSL,
        /** ESSL 320; MobileGlues hands it to the driver without translating it. */
        ESSL
    }

    public static class NotificationSettings {
        public boolean hasClearedDonationButton = false;
        public boolean hasSeenDonationPrompt = false;
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
