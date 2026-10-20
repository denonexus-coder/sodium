package me.jellysquid.mods.sodium.client.gui.vanillin;

import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

/**
 * Lets Sodium's apply cycle reach Vanillin's config file.
 *
 * <p>Sodium collects every storage an option touched during an apply and calls {@link #save()} on
 * it once, at the very end. By then every binding has already written its value into Vanillin's
 * config maps, so what is left is to write the file.
 *
 * <p>The registry is deliberately not pushed here. Vanillin reads its config and installs its
 * visualizers once, at startup, and {@code skipVanillaRender} consults that registry on every
 * frame. Pushing a change into a live registry would therefore do the wrong thing in both
 * directions: disabling a visual would hand the object back to the vanilla renderer while the
 * Flywheel visual that already exists keeps drawing it (two of everything), and enabling one would
 * make the vanilla renderer skip an object that has no visual yet (nothing at all). Recreating the
 * visuals is the only safe way to apply live, and that belongs to Flywheel's storage rather than to
 * this screen -- so the file is written now and the change lands on the next launch.
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
		VanillinCompat.save();
	}
}
