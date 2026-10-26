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
    public final MotorSettings motor = new MotorSettings();
    public final MotorBSettings motorB = new MotorBSettings();
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
         *
         * @deprecated only read once, to migrate a config written before the profile selector
         * ({@link #makeupMode}) existed. Never written back.
         */
        @Deprecated
        public boolean makeupNoEffects = false;

        /**
         * Which profile the chunk shader builds. A single value, so "only one at a time, or both
         * off" is guaranteed by construction instead of by two checkboxes that can disagree.
         */
        public MakeupMode makeupMode = MakeupMode.OFF;

        /** The profile's own sky gradient, sun, moon and stars (Overworld only). */
        public boolean makeupSky = true;

        /**
         * The profile's ground light takes the colour of the block that emits it (torch, soul
         * torch, lava, portal, glowstone...), sampled from the Sodium colored-light grid. With it
         * off the ground keeps the warm candle ramp of the profile.
         */
        public boolean makeupColoredLights = true;

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

        /**
         * Gives ores, raw/refined metal blocks and crystals a faint coloured glow of their own
         * mineral hue, even though vanilla leaves them completely dark. It is pure extra data for
         * the colored-light scan — no extra pass, texture or uniform — and it feeds both engines,
         * the Rethinking transport and the MakeUp profile grid. Off = the scan is byte-for-byte
         * the vanilla-emitter-only scan.
         */
        public boolean emissiveBlocks = true;
    }

    /**
     * The "Motor" engine: a new, fully optional single-pass lighting path that lives on its own
     * GUI tab. Every field here defaults to off/neutral, so with the whole tab disabled the game
     * renders byte-for-byte as it does today. The features are additive and independent; where a
     * Motor feature drives the same visual slot as an older option (ambient shadow, sheen, fog,
     * ground light, tonemap) the conflict is resolved last-wins via {@link MotorConflicts}: the
     * most recently enabled side wins and the other is suppressed for that frame, while both keep
     * their checkbox state. Non-conflicting features (grid LOD, light shafts) also benefit the
     * MakeUp profiles when a profile is active.
     */
    public static class MotorSettings {
        /** Master switch for the whole tab. Off = none of the fields below have any effect. */
        public boolean enabled = false;

        /** Tinted analytic shadow (sky occlusion + AO + N·sunDir), no shadow map. SHADOW slot. */
        public boolean analyticShadow = false;
        /** Voxel grid as the main light (clustered-forward equivalent). GROUND slot. */
        public boolean clusteredGrid = false;
        /** Cheap metallic/Fresnel sheen, no SSR. SHEEN slot. */
        public boolean metallicSheen = false;
        /** Analytic distance/height fog folded into the fragment. FOG slot. */
        public boolean inlineFog = false;
        /** Tonemap / colour grading folded into the fragment output. TONEMAP slot. */
        public boolean inlineTonemap = false;

        /** Non-conflicting optimisation: grid resolution drops with distance. Benefits profiles. */
        public boolean gridLod = false;
        /** Non-conflicting, off by default: cheap screen-space light shafts approximation. */
        public boolean lightShafts = false;

        /** 0..100. Strength of the analytic shadow darkening. Neutral when the feature is off. */
        public int shadowStrength = 50;
        /** 0..100. Strength of the metallic/Fresnel sheen. */
        public int sheenStrength = 35;
        /** 0..100. Fresnel exponent for the sheen; higher keeps it to grazing angles. */
        public int sheenSoftness = 50;

        /**
         * Monotonic clock and per-slot timestamps used by the last-wins resolver. They are plain
         * numbers so they survive a config round-trip; {@link MotorConflicts} compares the two
         * contenders of each slot and lets the higher (more recent) one win. 0 means "never
         * toggled on in this profile", which loses to any real timestamp.
         */
        public long conflictClock = 0;
        public long stampMotorShadow = 0;
        public long stampMotorSheen = 0;
        public long stampMotorFog = 0;
        public long stampMotorGround = 0;
        public long stampMotorTonemap = 0;
        public long stampLegacyShadow = 0;
        public long stampLegacySheen = 0;
        public long stampLegacyFog = 0;
        public long stampLegacyGround = 0;
        public long stampLegacyTonemap = 0;
    }

    /**
     * "Motor B": a second, fully optional colored-light transport that lives on its own
     * "Luz Avançada" tab. Where the GRID engine stores one omnidirectional colour per cell, Motor
     * B stores the colour arriving along each of the six axes (+X/-X, +Y/-Y, +Z/-Z) and fuses the
     * contributions of overlapping sources additively instead of keeping only the brightest. The
     * result is light that has a direction (a wall lit from one torch is darker on its far face)
     * and colour that adds up where two lamps meet, at the cost of six times the grid data.
     *
     * <p>It is mutually exclusive with the GRID / ANALYTIC engines of the Experimental tab, by
     * construction: {@link #enabled} is the single switch the renderer reads, and while it is on
     * Motor B owns the colored-light transport and the Experimental engine selector is left intact
     * but dormant. Turning it back off hands the transport straight back to GRID, with every GRID
     * setting exactly where it was. Nothing here is forced on: with {@link #enabled} false the
     * scan, the upload and the shader are byte-for-byte the GRID path.
     *
     * <p>Fase 0 wires only the GUI and the config; the six-axis store, the additive fusion and the
     * directional sampling in the chunk shader stay behind {@code #ifdef} until the later phases.
     * Nothing here has been compiled or run.
     */
    public static class MotorBSettings {
        /**
         * Master switch. Off = the colored light is the normal GRID path and this whole tab has
         * no effect. On = Motor B owns the transport, overriding the Experimental engine selector
         * for the session while leaving its value untouched.
         */
        public boolean enabled = false;

        /**
         * Store the colour per axis (six directions) instead of one omni value. This is what makes
         * the light directional; off, Motor B still fuses additively but lights every face the
         * same, which is cheaper but flatter.
         */
        public boolean directional = true;

        /**
         * Sum the colour of overlapping sources (additive fusion) instead of keeping the brightest
         * (the GRID rule). Two coloured lamps in the same cell mix their hues; off, the brighter
         * one simply wins, as in GRID.
         */
        public boolean additiveFusion = true;

        /** 0..100. How strongly the per-axis direction modulates a face. 0 = flat like GRID. */
        public int directionalStrength = 60;

        /**
         * 0..100. Ceiling on the additive sum, as a share of full white, so a cluster of lamps
         * saturates to a colour instead of blowing out to white. 100 = no clamp.
         */
        public int fusionCeiling = 80;

        /** Grid resolution drops with distance to pay for the six-axis store. Scan-only. */
        public boolean gridLod = true;

        /**
         * The "many lights" transport: a clustered list of point lights in a uniform buffer
         * instead of the six-axis atlas. It skips the flood (the expensive part) and costs one
         * scan plus a 172 KB upload per rebuild, at the price of a per-fragment light loop and a
         * coarser occlusion (gated by the vanilla block-light column). Mutually exclusive with
         * {@link #enabled}: last one on wins, resolved in {@code ColoredLighting.useMotorB()}.
         */
        public boolean listLights = false;

        /**
         * Monotonic clock and stamp used by the last-wins rule between the colored-light engines.
         * Plain numbers so the ordering survives a save/load. 0 means "never turned on".
         */
        public long conflictClock = 0;
        public long stampMotorB = 0;
        public long stampLegacyEngine = 0;
    }

    /** The two profiles the chunk shader can build; they are mutually exclusive. */
    public enum MakeupMode {        /** Plain Sodium. */
        OFF,
        /** The MakeUp No Effects palette, fog, water and sky. */
        NO_EFFECTS,
        /**
         * The same palette, but the block light is propagated with the Rethinking Voxels rules
         * (per-face occlusion, translucent tinting) and uses its palette.
         */
        RETHINKING
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

        // Migrate a config written before the profile selector existed. The old boolean is never
        // written back, so the mode stays the only source of truth.
        if (config.experimental.makeupMode == null) {
            config.experimental.makeupMode = MakeupMode.OFF;
        }

        if (config.experimental.makeupMode == MakeupMode.OFF && config.experimental.makeupNoEffects) {
            config.experimental.makeupMode = MakeupMode.NO_EFFECTS;
        }

        config.experimental.makeupNoEffects = false;

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
