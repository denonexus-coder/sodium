package me.jellysquid.mods.sodium.client.render;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.ColoredLighting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DimensionEffects;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;

/**
 * The palette and the toggles of the MakeUp "No Effects" profile.
 *
 * <p>MakeUp normally computes this in its shader pack. The values below are the exact values of
 * {@code profile.no_effects} for {@code COLOR_SCHEME=1}, {@code NIGHT_BRIGHT=0.60},
 * {@code AVOID_DARK_LEVEL=0.030}, {@code BLOCKLIGHT_TEMP=1}, {@code WATER_ABSORPTION=0.10} and
 * {@code WATER_TURBULENCE=0.9}; the profil is reproduced inside Sodium's chunk shader so it costs
 * the same one pass as the normal terrain, instead of a full pack with shadow maps and composite
 * passes.
 *
 * <p>The class only produces plain floats. The chunk interface uploads them once per pass and the
 * sky renderer uploads the same palette once per frame, so the terrain and the sky cannot drift
 * apart at sunset or during rain.
 */
public final class MakeupProfile {
    // COLOR_SCHEME 1 ("New shoka") from MakeUp's color_utils.glsl.
    private static final float[] LIGHT_SUNSET = { 1.0F, 0.588F, 0.3555F };
    private static final float[] LIGHT_DAY = { 0.90F, 0.84F, 0.79F };
    private static final float[] LIGHT_NIGHT = { 0.04786874F, 0.05175001F, 0.06112969F };

    private static final float[] ZENITH_SUNSET = { 0.143F, 0.24394118F, 0.36450981F };
    private static final float[] ZENITH_DAY = { 0.143F, 0.24394118F, 0.36450981F };
    private static final float[] ZENITH_NIGHT = { 0.014F, 0.019F, 0.025F };

    private static final float[] HORIZON_SUNSET = { 1.0F, 0.648F, 0.37824F };
    private static final float[] HORIZON_DAY = { 0.65F, 0.91F, 1.3F };
    private static final float[] HORIZON_NIGHT = { 0.0213F, 0.0306F, 0.0387F };

    private static final float[] WATER = { 0.05F, 0.1F, 0.11F };

    private static final float[] ZENITH_SKY_RAIN = { 0.7F, 0.85F, 1.0F };
    private static final float[] HORIZON_SKY_RAIN = { 0.35F, 0.425F, 0.5F };

    // BLOCKLIGHT_TEMP 0..4 from MakeUp's color_utils.glsl.
    private static final float[][] CANDLE = {
            { 0.29975F, 0.15392353F, 0.0799F },
            { 0.27475F, 0.17392353F, 0.0899F },
            { 0.24975F, 0.19392353F, 0.0999F },
            { 0.22F, 0.19F, 0.14F },
            { 0.19F, 0.19F, 0.19F }
    };

    private MakeupProfile() {
    }

    /**
     * Whether the profile should own the chunk shader. Iris wins even if the saved config still
     * has the profile enabled: the pack replaces the chunk shader, and the Sodium controls have
     * to stay out of its way.
     */
    public static boolean isEnabled() {
        SodiumGameOptions options = SodiumClientMod.options();

        return options != null
                && options.experimental.makeupNoEffects
                && !ColoredLighting.isIrisLoaded()
                && isOverworld();
    }

    /** The sky and its fog are overworld features; the Nether and the End keep the vanilla sky. */
    public static boolean isOverworld() {
        ClientWorld world = MinecraftClient.getInstance().world;

        return world != null
                && world.getDimensionEffects().getSkyType() == DimensionEffects.SkyType.NORMAL;
    }

    public static boolean isSkyActive() {
        return isEnabled()
                && SodiumClientMod.options().visual.sky
                && SodiumClientMod.options().experimental.makeupSky
                && isOverworld();
    }

    public static boolean isFogActive() {
        return isEnabled()
                && SodiumClientMod.options().experimental.makeupFog
                && isOverworld();
    }

    /** The values the chunk shader and the sky pass need for the current frame. */
    public static Frame frame() {
        return frame(MinecraftClient.getInstance().world, MinecraftClient.getInstance().getTickDelta());
    }

    public static Frame frame(ClientWorld world, float tickDelta) {
        SodiumGameOptions.ExperimentalSettings settings = SodiumClientMod.options().experimental;

        float dayMoment = (float) ((world.getTimeOfDay() % 24000L) + (double) tickDelta) / 24000.0F;
        float dayMixer = MathHelper.clamp(-((dayMoment - 0.25F) * (dayMoment - 0.25F)) * 20.0F + 1.25F, 0.0F, 1.0F);
        float nightMixer = MathHelper.clamp(-((dayMoment - 0.75F) * (dayMoment - 0.75F)) * 50.0F + 3.125F, 0.0F, 1.0F);

        // NIGHT_BRIGHT is stored in percent in the config; MakeUp also brightens with the moon
        // phase. In 1.20.1 phase 0 is the full moon and phase 4 is the new moon.
        float nightBright = MathHelper.clamp(settings.makeupNightBrightness / 100.0F, 0.05F, 0.70F);
        float nightPhase = nightBright + nightBright * (Math.abs(4.0F - world.getMoonPhase()) * 0.25F);

        float rain = MathHelper.clamp(world.getRainGradient(tickDelta), 0.0F, 1.0F);

        float[] lightNight = scale(LIGHT_NIGHT, nightPhase);
        float[] zenithNight = scale(ZENITH_NIGHT, nightPhase);
        float[] horizonNight = scale(HORIZON_NIGHT, nightPhase);

        float[] directColor = dayBlend(LIGHT_SUNSET, LIGHT_DAY, lightNight, dayMoment, dayMixer, nightMixer);
        directColor = mix(directColor, scale(ZENITH_SKY_RAIN, luma(directColor) * 0.4F), rain);

        float[] zenith = dayBlend(ZENITH_SUNSET, ZENITH_DAY, zenithNight, dayMoment, dayMixer, nightMixer);
        zenith = mix(zenith, scale(ZENITH_SKY_RAIN, luma(zenith)), rain);

        float[] horizon = dayBlend(HORIZON_SUNSET, HORIZON_DAY, horizonNight, dayMoment, dayMixer, nightMixer);
        horizon = mix(horizon, scale(HORIZON_SKY_RAIN, luma(horizon)), rain);

        // OMNI_TINT is 0.25 for COLOR_SCHEME 1.
        float[] omni = mix(zenith, scale(directColor, 0.45F), 0.25F);
        float minCave = MathHelper.clamp(settings.makeupMinCaveLight / 1000.0F, 0.0F, 0.060F);
        float[] omniMin = scale(omni, minCave / Math.max(luma(omni), 1.0E-4F));
        omni = max(omni, omniMin);

        float[] candle = CANDLE[MathHelper.clamp(settings.makeupBlockLightTemp, 0, CANDLE.length - 1)];

        // A slight zenith component keeps the fog from becoming a flat wall of horizon colour.
        float[] fog = mix(horizon, zenith, 0.15F);

        float fogDensity = dayBlendFloat(2.0F, 3.0F, 3.0F, dayMoment, dayMixer, nightMixer) * 2.0F;
        float skyBrightness = MathHelper.clamp(
                dayBlendFloat(0.25F, 1.0F, nightBright, dayMoment, dayMixer, nightMixer), 0.0F, 1.0F);

        // The sun rises at time 0 on +X, is overhead at noon and sets on -X. The moon is opposite.
        double angle = ((world.getTimeOfDay() % 24000L) + (double) tickDelta) / 24000.0D * Math.PI * 2.0D;
        float sunX = (float) Math.cos(angle);
        float sunY = (float) Math.sin(angle);
        float sunZ = 0.0F;

        // Stars appear after sunset and disappear before sunrise. The narrow ramps keep the
        // transition from looking like a light switch.
        float night = smooth(dayMoment, 0.50F, 0.54F) * (1.0F - smooth(dayMoment, 0.96F, 1.0F));

        float fogFar = Math.max(32.0F,
                MinecraftClient.getInstance().options.getViewDistance().getValue() * 16.0F);

        return new Frame(
                omni, omniMin, directColor, candle, zenith, horizon, WATER, fog,
                fogDensity, rain, skyBrightness, night,
                sunX, sunY, sunZ,
                settings.makeupSunLight,
                settings.makeupWater,
                settings.makeupWaterAbsorption / 100.0F,
                settings.makeupWaterSkyReflection,
                settings.makeupWaterWaves,
                settings.makeupWaterWaveStrength / 100.0F,
                settings.makeupFog,
                fogFar
        );
    }

    /**
     * The values are immutable for one frame even though they are arrays: nothing outside this
     * class writes to them after the frame is built.
     */
    public record Frame(
            float[] omniColor,
            float[] omniMin,
            float[] directColor,
            float[] candleColor,
            float[] zenith,
            float[] horizon,
            float[] waterColor,
            float[] fogColor,
            float fogDensity,
            float rain,
            float eyeSky,
            float night,
            float sunX,
            float sunY,
            float sunZ,
            boolean sunLight,
            boolean water,
            float waterAbsorption,
            boolean waterSkyReflection,
            boolean waterWaves,
            float waveStrength,
            boolean fog,
            float fogFar
    ) {
    }

    private static float[] dayBlend(float[] sunset, float[] day, float[] night, float dayMoment,
                                    float dayMixer, float nightMixer) {
        float[] dayColor = mix(sunset, day, dayMixer);
        float[] nightColor = mix(sunset, night, nightMixer);

        return dayMoment < 0.5F ? dayColor : nightColor;
    }

    private static float dayBlendFloat(float sunset, float day, float night, float dayMoment,
                                       float dayMixer, float nightMixer) {
        float dayValue = sunset + (day - sunset) * dayMixer;
        float nightValue = sunset + (night - sunset) * nightMixer;

        return dayMoment < 0.5F ? dayValue : nightValue;
    }

    private static float[] mix(float[] a, float[] b, float t) {
        return new float[] {
                a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t
        };
    }

    private static float[] scale(float[] value, float scalar) {
        return new float[] { value[0] * scalar, value[1] * scalar, value[2] * scalar };
    }

    private static float[] max(float[] a, float[] b) {
        return new float[] {
                Math.max(a[0], b[0]),
                Math.max(a[1], b[1]),
                Math.max(a[2], b[2])
        };
    }

    private static float luma(float[] color) {
        return color[0] * 0.299F + color[1] * 0.587F + color[2] * 0.114F;
    }

    private static float smooth(float value, float min, float max) {
        float t = MathHelper.clamp((value - min) / (max - min), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
