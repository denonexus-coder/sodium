package me.jellysquid.mods.sodium.client.gui;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.MotorSettings;

/**
 * Last-wins conflict resolver for the "Motor" tab.
 *
 * <p>A handful of visual slots (ambient shadow, metallic sheen, distance fog, ground light,
 * tonemap) can be driven either by an older option (the Experimental tab or a MakeUp profile)
 * or by the new Motor engine. When both sides of a slot are enabled they would fight, so instead
 * of forbidding the combination we let the <em>most recently enabled</em> side win: every time a
 * contender is switched on we stamp it with a monotonic clock, and the resolver simply keeps the
 * side with the higher stamp. The loser is suppressed for rendering but its checkbox state is
 * preserved, so flipping the other one back off hands the slot back without losing the setting.
 *
 * <p>The clock lives in the config so the ordering is stable across a save/load. The methods are
 * static and null-safe: before the options are loaded every slot resolves to the legacy side,
 * which is exactly today's behaviour.
 */
public final class MotorConflicts {
    private MotorConflicts() {
    }

    /** The visual slots that can be claimed by either the legacy options or the Motor engine. */
    public enum Slot {
        SHADOW,
        SHEEN,
        FOG,
        GROUND,
        TONEMAP
    }

    private static MotorSettings motor() {
        SodiumGameOptions opts = SodiumClientMod.options();
        return opts == null ? null : opts.motor;
    }

    /** Hands out the next monotonic stamp, persisted in the config. */
    private static long nextClock(MotorSettings m) {
        return ++m.conflictClock;
    }

    /**
     * Records that the Motor side of {@code slot} was just switched on. Call this from the Motor
     * toggle bindings. Switching off does not re-stamp: a disabled contender never wins anyway.
     */
    public static void touchMotor(Slot slot, boolean enabledNow) {
        MotorSettings m = motor();
        if (m == null || !enabledNow) {
            return;
        }
        long t = nextClock(m);
        switch (slot) {
            case SHADOW -> m.stampMotorShadow = t;
            case SHEEN -> m.stampMotorSheen = t;
            case FOG -> m.stampMotorFog = t;
            case GROUND -> m.stampMotorGround = t;
            case TONEMAP -> m.stampMotorTonemap = t;
        }
    }

    /**
     * Records that the legacy (Experimental / profile) side of {@code slot} was just switched on.
     * Call this from the matching legacy toggle bindings so the two sides share one clock.
     */
    public static void touchLegacy(Slot slot, boolean enabledNow) {
        MotorSettings m = motor();
        if (m == null || !enabledNow) {
            return;
        }
        long t = nextClock(m);
        switch (slot) {
            case SHADOW -> m.stampLegacyShadow = t;
            case SHEEN -> m.stampLegacySheen = t;
            case FOG -> m.stampLegacyFog = t;
            case GROUND -> m.stampLegacyGround = t;
            case TONEMAP -> m.stampLegacyTonemap = t;
        }
    }

    private static long motorStamp(MotorSettings m, Slot slot) {
        return switch (slot) {
            case SHADOW -> m.stampMotorShadow;
            case SHEEN -> m.stampMotorSheen;
            case FOG -> m.stampMotorFog;
            case GROUND -> m.stampMotorGround;
            case TONEMAP -> m.stampMotorTonemap;
        };
    }

    private static long legacyStamp(MotorSettings m, Slot slot) {
        return switch (slot) {
            case SHADOW -> m.stampLegacyShadow;
            case SHEEN -> m.stampLegacySheen;
            case FOG -> m.stampLegacyFog;
            case GROUND -> m.stampLegacyGround;
            case TONEMAP -> m.stampLegacyTonemap;
        };
    }

    /** True if the Motor engine is enabled and its feature for {@code slot} is switched on. */
    private static boolean motorWants(MotorSettings m, Slot slot) {
        if (!m.enabled) {
            return false;
        }
        return switch (slot) {
            case SHADOW -> m.analyticShadow;
            case SHEEN -> m.metallicSheen;
            case FOG -> m.inlineFog;
            case GROUND -> m.clusteredGrid;
            case TONEMAP -> m.inlineTonemap;
        };
    }

    /**
     * Decides who owns {@code slot} right now, given whether the legacy side is also asking for it.
     *
     * @param slot          the contested visual slot
     * @param legacyWants    whether the Experimental/profile side is currently enabled for it
     * @return {@code true} if the Motor engine should drive the slot, {@code false} for the legacy
     *         side. When only one side wants the slot that side wins; when both want it the higher
     *         (more recent) stamp wins, with ties going to the legacy side so enabling the Motor
     *         tab never silently changes a slot the player did not just touch.
     */
    public static boolean motorOwns(Slot slot, boolean legacyWants) {
        MotorSettings m = motor();
        if (m == null) {
            return false;
        }
        boolean motorWants = motorWants(m, slot);
        if (motorWants && !legacyWants) {
            return true;
        }
        if (!motorWants) {
            return false;
        }
        // Both sides want the slot: last-wins, tie -> legacy.
        return motorStamp(m, slot) > legacyStamp(m, slot);
    }

    /** Convenience: the legacy side owns the slot exactly when the Motor side does not. */
    public static boolean legacyOwns(Slot slot, boolean legacyWants) {
        return legacyWants && !motorOwns(slot, legacyWants);
    }
}
