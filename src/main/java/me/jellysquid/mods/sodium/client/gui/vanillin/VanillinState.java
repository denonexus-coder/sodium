package me.jellysquid.mods.sodium.client.gui.vanillin;

/**
 * The three states Vanillin accepts for one visual.
 *
 * <p>The order is the order {@link VanillinCompat} resolves Vanillin's own enum constants in, so
 * {@code ordinal()} lines up with the reflected array. The names are Vanillin's, spelled out here
 * only so Sodium can talk about them without a compile-time dependency on the mod.
 */
public enum VanillinState {
	/** Let Vanillin decide. On for the visuals it ships as stable, off for the experimental ones. */
	DEFAULT,
	/** Never instance this. It falls back to vanilla rendering, exactly as if Vanillin were absent. */
	DISABLE,
	/** Instance this even when a mod has asked for it to be off. */
	FORCE_ENABLE;

	/** Whether this state results in the visual being instanced, before overrides are considered. */
	public boolean instances() {
		return this != DISABLE;
	}
}
