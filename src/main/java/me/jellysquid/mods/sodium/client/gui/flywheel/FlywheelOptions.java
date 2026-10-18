package me.jellysquid.mods.sodium.client.gui.flywheel;

/**
 * The state object Sodium's option machinery passes to every Flywheel binding.
 *
 * <p>The bindings themselves read and write {@link FlywheelCompat} directly, because Sodium runs a
 * binding's setter before it reloads the renderer and only persists the storage afterwards, so a
 * value parked here would arrive too late for anything the backend reads while rebuilding. What
 * the object does carry is the one piece of information that is not switchable at all: how the
 * driver identified itself at startup, which the page shows as a status row.
 */
public final class FlywheelOptions {
	public static final FlywheelOptions INSTANCE = new FlywheelOptions();

	/**
	 * True when the GL strings named a mobile or translation-layer driver. Resolved once, here,
	 * because it does not change for the life of the process.
	 */
	public final boolean mobileDriver;

	private FlywheelOptions() {
		this.mobileDriver = FlywheelCompat.mobileDriver();
	}
}
