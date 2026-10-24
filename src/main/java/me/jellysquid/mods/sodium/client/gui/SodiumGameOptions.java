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

        // MobileGlues Ultra Drawer & Instancing Settings
        public boolean useInstancedFaceCompaction = true;
        public boolean useMultiRegionCoalescing = true;
        public boolean useCrossSectionInstancing = true;
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

        /**
         * Which engine feeds the colored light. The grid is the default because it is the older,
         * cheaper path; the analytic list is opt-in.
         */
        public LightEngine lightEngine = LightEngine.GRID;

        /** Adds the light of the held block to the chunk shader; costs a few ALU per vertex. */
        public boolean handLight = true;

        /**
         * Tints and darkens the ambient part of the light where the surface is occluded from the
         * sky. It is applied in the chunk vertex shader, so it adds no pass and no texture. The
         * defaults are conservative: low light gets a cool tint, full sky light is unchanged.
         */
        public boolean coloredShadows = true;

        /** 0..100. Scales the amount by which occluded ambient light is darkened. */
        public int shadowStrength = 50;

        /** 0..100. How much of the shadow comes from depth (low Y) rather than from occlusion. */
        public int shadowDepth = 60;

        /** 0..100. How much the shadow hue follows the local colored light. */
        public int shadowTint = 55;

        /**
         * Adds a cheap Fresnel/sky sheen to opaque chunk surfaces. It is a fragment effect derived
         * from the view-space position, so it needs no normals in the chunk format and no shadow
         * map. Off by default: grass and other matte surfaces should look matte unless the player
         * asks for the effect.
         */
        public boolean surfaceReflection = false;

        /** 0..100. How strongly the sheen is mixed over the block texture. */
        public int reflectionStrength = 35;

        /** 0..100. Fresnel exponent; higher values keep the sheen at grazing angles only. */
        public int reflectionSoftness = 50;

        /**
         * The MakeUp "No Effects" profile, rebuilt inside the chunk shader instead of running the
         * shader pack. It is deliberately independent from the colored shadows and the surface
         * reflection: those keep their own switches and can be combined with this profile.
         */
        public boolean makeupNoEffects = false;

        /** The profile's own sky gradient, sun, moon and stars (Overworld only). */
        public boolean makeupSky = true;

        /** MakeUp-style distance fog using the horizon colour of the profile sky. */
        public boolean makeupFog = true;

        /** Water tint and the sky reflection on water and glass. */
        public boolean makeupWater = true;

        /** 0..80, in percent. The exact profile value is 10 (0.10). */
        public int makeupWaterAbsorption = 10;

        /** The profile keeps the sky reflection on water/glass, but not the sun glint. */
        public boolean makeupWaterSkyReflection = true;

        /** Analytic normal waves on water; the exact profile value is WATER_TURBULENCE 0.9. */
        public boolean makeupWaterWaves = true;

        /** 0..100. Scales the analytic wave normal; 90 matches the profile's 0.9. */
        public int makeupWaterWaveStrength = 90;

        /** 5..70. The exact profile value is NIGHT_BRIGHT 0.60. */
        public int makeupNightBrightness = 60;

        /** 0..60, shown as 0.000..0.060. The exact profile value is AVOID_DARK_LEVEL 0.030. */
        public int makeupMinCaveLight = 30;

        /** 0..4. The exact profile value is BLOCKLIGHT_TEMP 1. */
        public int makeupBlockLightTemp = 1;

        /** The profile's directional sun without a shadow map; the grass is lit by N·L. */
        public boolean makeupSunLight = true;
    }

    public enum LightEngine {
        /** A 2D atlas rebuilt around the player and sampled by the chunk shader. */
        GRID,
        /** A short list of nearby lights uploaded as uniforms and summed in the shader. */
        ANALYTIC
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
