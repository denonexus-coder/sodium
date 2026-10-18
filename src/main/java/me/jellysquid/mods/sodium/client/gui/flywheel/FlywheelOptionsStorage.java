package me.jellysquid.mods.sodium.client.gui.flywheel;

import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

/**
 * Lets Sodium's apply cycle reach Flywheel's config file.
 *
 * <p>Sodium collects every storage an option touched during an apply and calls {@link #save()} on
 * it once, at the very end of the cycle. Flywheel's bindings have already put their values in
 * memory by then, so all that is left for this to do is the file write, which is exactly the split
 * the ordering wants: memory before the renderer reloads, disk after.
 */
public final class FlywheelOptionsStorage implements OptionStorage<FlywheelOptions> {
	public static final FlywheelOptionsStorage INSTANCE = new FlywheelOptionsStorage();

	private FlywheelOptionsStorage() {
	}

	@Override
	public FlywheelOptions getData() {
		return FlywheelOptions.INSTANCE;
	}

	@Override
	public void save() {
		FlywheelCompat.save();
	}
}
