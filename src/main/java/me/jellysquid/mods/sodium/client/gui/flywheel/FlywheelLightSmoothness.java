package me.jellysquid.mods.sodium.client.gui.flywheel;

/**
 * Sodium's copy of Flywheel's light-smoothness choices.
 *
 * <p>Flywheel keeps this enum in its own jar, which Sodium does not compile against, so the values
 * are mirrored here and carried as their field names through {@link FlywheelCompat}. The names are
 * those of {@code LightSmoothness} itself, which is what {@code flywheel.json} stores, so a value
 * written here is written verbatim to the config.
 */
public enum FlywheelLightSmoothness {
	/** Cheapest: one light value per face, so shading visibly bands along edges. */
	FLAT,
	/** Light interpolated across the face on two axes, without the inner-face correction. */
	TRI_LINEAR,
	/** The default: smooth interpolation that follows the face's own geometry. */
	SMOOTH,
	/** Smooth, plus a correction that keeps light either side of a solid block consistent. */
	SMOOTH_INNER_FACE_CORRECTED;

	/** The value Flywheel's config already holds, or {@link #SMOOTH} when it cannot be read. */
	public static FlywheelLightSmoothness fromConfig() {
		try {
			return valueOf(FlywheelCompat.lightSmoothness());
		} catch (IllegalArgumentException e) {
			// A config written by a newer version of Flywheel may name a mode this build does
			// not know. Falling back to the default keeps the control usable instead of throwing
			// while the screen is being built.
			return SMOOTH;
		}
	}
}
