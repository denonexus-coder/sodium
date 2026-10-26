package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.MotorConflicts;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.ColoredLighting;

/**
 * The compile-time footprint of the "Motor" single-pass lighting engine.
 *
 * <p>Only the flags that change which code the shader compiles live here, so that two different
 * Motor configurations produce two different {@link ChunkShaderOptions} and therefore two different
 * cached programs. The sliders (shadow / sheen strength and softness) are <em>not</em> here: they
 * are uniforms uploaded every frame and must not fragment the program cache.
 *
 * <p>The flags are already resolved against the legacy options through {@link MotorConflicts}: a
 * slot that a MakeUp profile or an Experimental toggle owns under last-wins is reported {@code
 * false} here, so the Motor shader body for that slot is simply not compiled and the legacy path
 * keeps the slot. When the master switch is off, or Iris controls the pipeline, every flag is
 * false and this is {@link #OFF} -- i.e. today's shader, bit for bit.
 *
 * <p>This is a plain record so it participates in the {@link ChunkShaderOptions} record equality
 * that keys the program cache.
 */
public record MotorShaderState(boolean shadow, boolean sheen, boolean fog, boolean tonemap,
                               boolean grid, boolean gridLod, boolean shafts, boolean motorB) {

    /** Nothing lit: identical to the stock Sodium shader. */
    public static final MotorShaderState OFF = new MotorShaderState(false, false, false, false, false, false, false, false);

    /** True when at least one Motor body is compiled in; gates the shared varyings. */
    public boolean any() {
        return this.shadow || this.sheen || this.fog || this.tonemap || this.grid || this.gridLod || this.shafts;
    }

    /** A copy with the Motor B transport switched off, used by the shader fallback ladder. */
    public MotorShaderState withMotorB(boolean value) {
        return new MotorShaderState(this.shadow, this.sheen, this.fog, this.tonemap,
                this.grid, this.gridLod, this.shafts, value);
    }

    /**
     * Resolves the live Motor configuration into compile flags for one draw.
     *
     * <p>Null-safe and Iris-aware: before the options load, or while Iris owns the world shader,
     * the result is {@link #OFF}. Each slot that overlaps a legacy option is gated by
     * {@link MotorConflicts#motorOwns}, so the Motor side only compiles when it actually won the
     * slot under last-wins. The GROUND slot carries the clustered grid; {@code gridLod} and
     * {@code shafts} are Motor-only and have no legacy counterpart.
     */
    public static MotorShaderState resolve() {
        SodiumGameOptions options = SodiumClientMod.options();
        if (options == null) {
            return OFF;
        }

        SodiumGameOptions.MotorSettings m = options.motor;

        // Motor B is not one of the single-pass "slots": it is its own transport under the
        // "Luz Avançada" switch, so it is resolved before the Motor master switch and survives
        // when the rest of Motor is off.
        boolean motorB = ColoredLighting.useMotorB();

        if (!m.enabled || ColoredLighting.isIrisLoaded()) {
            return OFF.withMotorB(motorB);
        }

        boolean shadow = m.analyticShadow && MotorConflicts.motorOwns(MotorConflicts.Slot.SHADOW, legacyShadow(options));
        boolean sheen = m.metallicSheen && MotorConflicts.motorOwns(MotorConflicts.Slot.SHEEN, legacySheen(options));
        boolean fog = m.inlineFog && MotorConflicts.motorOwns(MotorConflicts.Slot.FOG, legacyFog(options));
        boolean tonemap = m.inlineTonemap && MotorConflicts.motorOwns(MotorConflicts.Slot.TONEMAP, legacyTonemap(options));
        boolean grid = m.clusteredGrid && MotorConflicts.motorOwns(MotorConflicts.Slot.GROUND, legacyGround(options));
        boolean gridLod = grid && m.gridLod;
        boolean shafts = m.lightShafts;

        return new MotorShaderState(shadow, sheen, fog, tonemap, grid, gridLod, shafts, motorB);
    }

    // ---- what the legacy (Experimental / MakeUp profile) side currently wants, per slot ----

    private static boolean legacyShadow(SodiumGameOptions o) {
        return o.experimental.coloredShadows && !ColoredLighting.isIrisLoaded();
    }

    private static boolean legacySheen(SodiumGameOptions o) {
        return o.experimental.surfaceReflection && !ColoredLighting.isIrisLoaded();
    }

    private static boolean legacyFog(SodiumGameOptions o) {
        return o.experimental.makeupFog && o.experimental.makeupMode != SodiumGameOptions.MakeupMode.OFF;
    }

    private static boolean legacyTonemap(SodiumGameOptions o) {
        return o.experimental.makeupMode != SodiumGameOptions.MakeupMode.OFF;
    }

    private static boolean legacyGround(SodiumGameOptions o) {
        return o.experimental.makeupMode != SodiumGameOptions.MakeupMode.OFF;
    }
}
