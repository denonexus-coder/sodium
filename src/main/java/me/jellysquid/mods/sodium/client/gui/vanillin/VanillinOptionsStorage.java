package me.jellysquid.mods.sodium.client.gui.vanillin;

import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

/**
 * Lets Sodium's apply cycle reach Vanillin's config file.
 *
 * <p>Sodium collects every storage an option touched during an apply and calls {@link #save()} on
 * it once, at the very end. By then every binding has already written its value into Vanillin's
 * config maps, so what is left is to push that state into the visualizer registry and then write
 * the file -- memory first, disk second, which is the order the apply cycle wants.
 *
 * <p>The registry push matters on its own: without it a change would only be visible after the
 * next launch, and the user would have no way of knowing whether the switch had been heard.
 */
public final class VanillinOptionsStorage implements OptionStorage<VanillinOptions> {
	public static final VanillinOptionsStorage INSTANCE = new VanillinOptionsStorage();

	private VanillinOptionsStorage() {
	}

	@Override
	public VanillinOptions getData() {
		return VanillinOptions.INSTANCE;
	}

	@Override
	public void save() {
		VanillinCompat.apply();
		VanillinCompat.save();
	}
}
