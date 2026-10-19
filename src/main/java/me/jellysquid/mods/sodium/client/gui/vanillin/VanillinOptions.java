package me.jellysquid.mods.sodium.client.gui.vanillin;

/**
 * The state object Sodium's option machinery passes to every Vanillin binding.
 *
 * <p>The bindings read and write {@link VanillinCompat} directly, because Vanillin's config maps
 * are the live state the visualizer registry was built from and there is nothing to mirror. The
 * object exists because Sodium's storage contract needs a data holder, not because anything is
 * parked in it.
 */
public final class VanillinOptions {
	public static final VanillinOptions INSTANCE = new VanillinOptions();

	private VanillinOptions() {
	}
}
