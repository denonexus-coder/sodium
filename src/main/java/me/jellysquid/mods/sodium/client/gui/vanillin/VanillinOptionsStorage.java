package me.jellysquid.mods.sodium.client.gui.vanillin;

import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.client.MinecraftClient;

/**
 * Lets Sodium's apply cycle reach Vanillin's config file and its live registry.
 *
 * <p>Sodium collects every storage an option touched during an apply and calls {@link #save()} on
 * it once, at the very end. By then every binding has already written its value into Vanillin's
 * config maps, so what is left is to install that state in the visualizer registry, write the
 * file, and rebuild the visuals that are already on screen.
 *
 * <p>The rebuild is the part that matters. {@code skipVanillaRender} reads the registry on every
 * frame, so pushing a change into a live registry without rebuilding does the wrong thing in both
 * directions: disabling a visual hands the object back to the vanilla renderer while the Flywheel
 * visual that already exists keeps drawing it (two of everything), and enabling one makes the
 * vanilla renderer skip an object that has no visual yet (nothing at all).
 *
 * <p>A renderer reload is enough to rebuild everything, and it is the same mechanism the rest of
 * the options screen already uses. It disposes Flywheel's visualization manager and marks every
 * chunk section dirty; the section rebuilds re-add the block entities, and the fresh manager
 * re-adds the entities from the level. Both therefore create their visuals from the registry that
 * was just installed. The price is the chunk rebuild itself, which is visible for a moment.
 *
 * <p>Out of a world there is nothing to rebuild; the registry and the file are still updated, and
 * the first world load builds its visuals from them.
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
		// Registry first, file second: the rebuild below reads the registry, and the file only
		// exists to make the same choice survive a restart.
		VanillinCompat.apply();
		VanillinCompat.save();

		MinecraftClient client = MinecraftClient.getInstance();

		if (client.world == null) {
			return;
		}

		client.worldRenderer.reload();
	}
}
