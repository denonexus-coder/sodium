package me.jellysquid.mods.sodium.client.gui.flywheel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft bridge to Flywheel's runtime configuration.
 *
 * <p>Sodium must keep building, shipping and running on its own, so nothing in this package may
 * mention a Flywheel class at compile time. Every class and member below is resolved once, by name,
 * and {@link #PRESENT} stays false when the mod is absent, which keeps the page out of the menu
 * rather than showing switches that do nothing.
 *
 * <p>The state is held in two places and both have to be kept in step. {@code MobileOptions} is
 * what the backend reads every frame: plain static fields, reachable before the platform config has
 * loaded. {@code FabricFlwConfig} is the same state in a form that survives a restart, as
 * {@code flywheel.json}. Writing one and not the other either loses the change on exit or refuses
 * to apply it, so every setter below writes both.
 *
 * <p>One ordering detail drives the shape of this class: Sodium applies an option's binding first,
 * runs the renderer reload second, and only then calls {@link #save()}. Anything the backend reads
 * while rebuilding therefore has to be in memory by the time the binding runs, which is why
 * setters touch the live state and {@link #save()} is left with nothing but the file write.
 */
public final class FlywheelCompat {
	private static final Logger LOGGER = LoggerFactory.getLogger("Sodium/Flywheel");

	public static final boolean PRESENT;

	// MobileOptions: static, read every frame by the backend.
	private static final Field MOBILE_OIT;
	private static final Field MOBILE_DISABLE_PARTICLES;
	private static final Field MOBILE_FREEZE_GUI_ITEM_ANIMATION;
	private static final Field MOBILE_ORPHAN_UPLOADS;
	@Nullable
	private static final Field MOBILE_DRIVER;

	// FabricFlwConfig: the persisted form of the same state.
	@Nullable
	private static final Object CONFIG;
	private static final Field CONFIG_USE_DEFAULT_BACKEND;
	private static final Field CONFIG_BACKEND;
	private static final Field CONFIG_LIMIT_UPDATES;
	private static final Field CONFIG_WORKER_THREADS;
	private static final Field CONFIG_OIT;
	private static final Field CONFIG_DISABLE_PARTICLES;
	private static final Field CONFIG_FREEZE_GUI_ITEM_ANIMATION;
	private static final Field CONFIG_ORPHAN_UPLOADS;
	private static final Field CONFIG_BACKEND_CONFIG;
	private static final Field BACKEND_CONFIG_LIGHT_SMOOTHNESS;

	@Nullable
	private static final Method CONFIG_SAVE;
	@Nullable
	private static final Method OFF_BACKEND;

	private static final int WORKER_THREADS_MIN;
	private static final int WORKER_THREADS_MAX;

	static {
		Field mobileOit = null;
		Field mobileDisableParticles = null;
		Field mobileFreezeGuiItemAnimation = null;
		Field mobileOrphanUploads = null;
		Field mobileDriver = null;
		Object config = null;
		Field configUseDefaultBackend = null;
		Field configBackend = null;
		Field configLimitUpdates = null;
		Field configWorkerThreads = null;
		Field configOit = null;
		Field configDisableParticles = null;
		Field configFreezeGuiItemAnimation = null;
		Field configOrphanUploads = null;
		Field configBackendConfig = null;
		Field backendConfigLightSmoothness = null;
		Method configSave = null;
		Method offBackend = null;
		int workerThreadsMin = -1;
		int workerThreadsMax = -1;
		boolean present = false;

		try {
			Class<?> mobileOptions = Class.forName("dev.engine_room.flywheel.backend.MobileOptions");
			Class<?> fabricConfig = Class.forName("dev.engine_room.flywheel.impl.FabricFlwConfig");
			Class<?> backendManager = Class.forName("dev.engine_room.flywheel.api.backend.BackendManager");

			mobileOit = staticField(mobileOptions, "oit");
			mobileDisableParticles = staticField(mobileOptions, "disableParticles");
			mobileFreezeGuiItemAnimation = staticField(mobileOptions, "freezeGuiItemAnimation");
			mobileOrphanUploads = staticField(mobileOptions, "orphanInstanceUpload");
			mobileDriver = staticField(mobileOptions, "mobileDriver");

			Field instance = staticField(fabricConfig, "INSTANCE");
			configUseDefaultBackend = staticField(fabricConfig, "useDefaultBackend");
			configBackend = staticField(fabricConfig, "backend");
			configLimitUpdates = staticField(fabricConfig, "limitUpdates");
			configWorkerThreads = staticField(fabricConfig, "workerThreads");
			configOit = staticField(fabricConfig, "oit");
			configDisableParticles = staticField(fabricConfig, "disableParticles");
			configFreezeGuiItemAnimation = staticField(fabricConfig, "freezeGuiItemAnimation");
			configOrphanUploads = staticField(fabricConfig, "orphanInstanceUpload");
			configBackendConfig = staticField(fabricConfig, "backendConfig");

			if (configBackendConfig != null) {
				// "flw_backends" in the JSON, a nested object with its own fields.
				backendConfigLightSmoothness = staticField(configBackendConfig.getType(), "lightSmoothness");
			}

			configSave = fabricConfig.getDeclaredMethod("save");
			configSave.setAccessible(true);

			offBackend = backendManager.getDeclaredMethod("offBackend");
			offBackend.setAccessible(true);

			Field workerThreads = configWorkerThreads;
			if (workerThreads != null) {
				// The slider range has to be the one the config will clamp to on load, or an
				// out-of-range value would quietly change the next time the game starts. The
				// default is read from the constant because workerThreads itself is per-instance.
				Field min = staticField(fabricConfig, "WORKER_THREADS_DEFAULT");
				Field max = staticField(fabricConfig, "WORKER_THREADS_MAX");

				if (min != null) {
					workerThreadsMin = min.getInt(null);
				}

				if (max != null) {
					workerThreadsMax = max.getInt(null);
				} else {
					workerThreadsMax = Runtime.getRuntime()
							.availableProcessors();
				}
			}

			if (instance != null) {
				config = instance.get(null);
			}

			// Only claim the integration works when every piece resolved. Half a bridge would
			// offer options that silently do nothing, which is worse than offering none.
			present = mobileOit != null
					&& mobileDisableParticles != null
					&& mobileFreezeGuiItemAnimation != null
					&& mobileOrphanUploads != null
					&& config != null
					&& configUseDefaultBackend != null
					&& configBackend != null
					&& configLimitUpdates != null
					&& configWorkerThreads != null
					&& configOit != null
					&& configDisableParticles != null
					&& configFreezeGuiItemAnimation != null
					&& configOrphanUploads != null
					&& configBackendConfig != null
					&& backendConfigLightSmoothness != null
					&& configSave != null
					&& offBackend != null
					// Without a usable range the slider would be built with max <= min and throw.
					&& workerThreadsMax > workerThreadsMin;
		} catch (Throwable t) {
			LOGGER.debug("Flywheel is not installed, so its options page will not be shown", t);
			present = false;
		}

		MOBILE_OIT = mobileOit;
		MOBILE_DISABLE_PARTICLES = mobileDisableParticles;
		MOBILE_FREEZE_GUI_ITEM_ANIMATION = mobileFreezeGuiItemAnimation;
		MOBILE_ORPHAN_UPLOADS = mobileOrphanUploads;
		MOBILE_DRIVER = mobileDriver;
		CONFIG = config;
		CONFIG_USE_DEFAULT_BACKEND = configUseDefaultBackend;
		CONFIG_BACKEND = configBackend;
		CONFIG_LIMIT_UPDATES = configLimitUpdates;
		CONFIG_WORKER_THREADS = configWorkerThreads;
		CONFIG_OIT = configOit;
		CONFIG_DISABLE_PARTICLES = configDisableParticles;
		CONFIG_FREEZE_GUI_ITEM_ANIMATION = configFreezeGuiItemAnimation;
		CONFIG_ORPHAN_UPLOADS = configOrphanUploads;
		CONFIG_BACKEND_CONFIG = configBackendConfig;
		BACKEND_CONFIG_LIGHT_SMOOTHNESS = backendConfigLightSmoothness;
		CONFIG_SAVE = configSave;
		OFF_BACKEND = offBackend;
		WORKER_THREADS_MIN = workerThreadsMin;
		WORKER_THREADS_MAX = workerThreadsMax;

		PRESENT = present;

		if (present) {
			LOGGER.debug("Flywheel detected, its options will appear on their own Sodium page");
		}
	}

	private FlywheelCompat() {
	}

	// ---------------------------------------------------------------- engine

	/**
	 * True when the backend runs on a mobile or translation-layer driver, which on this device
	 * means MobileGlues over a PowerVR GPU. Derived from the GL strings at startup, so it is
	 * reported rather than switched.
	 */
	public static boolean mobileDriver() {
		return readStatic(MOBILE_DRIVER, false);
	}

	/**
	 * True when Flywheel is rendering the world. The config spells "automatic" out as its own
	 * mode, but the default backend is instancing, so automatic and on are one and the same here
	 * and the switch reads as a plain tick box.
	 */
	public static boolean enabled() {
		return readConfig(CONFIG_USE_DEFAULT_BACKEND, true);
	}

	/**
	 * Turn the backend on or off. Off also points the config at {@code flywheel:off}, the backend
	 * that draws nothing and so leaves the vanilla path in charge; without that the JSON would keep
	 * a backend id while claiming to be automatic, and the next launch would come back as it was.
	 */
	public static void enabled(boolean value) {
		writeConfig(CONFIG_USE_DEFAULT_BACKEND, value);

		if (!value && CONFIG != null && CONFIG_BACKEND != null && OFF_BACKEND != null) {
			try {
				writeInstance(CONFIG_BACKEND, OFF_BACKEND.invoke(null));
			} catch (Throwable t) {
				LOGGER.warn("Could not point Flywheel at its off backend", t);
			}
		}
	}

	/**
	 * Worker threads used to precompile Flywheel's shaders. {@code -1} lets Flywheel pick,
	 * {@code 0} serialises compilation onto the render thread.
	 */
	public static int workerThreads() {
		return readConfigInt(CONFIG_WORKER_THREADS, WORKER_THREADS_MIN);
	}

	public static void workerThreads(int value) {
		writeConfigInt(CONFIG_WORKER_THREADS, clamp(value, WORKER_THREADS_MIN, WORKER_THREADS_MAX));
	}

	public static int workerThreadsMin() {
		return WORKER_THREADS_MIN;
	}

	public static int workerThreadsMax() {
		return WORKER_THREADS_MAX;
	}

	/**
	 * Drop instance updates by distance. When on, objects far from the camera are updated on a
	 * banded schedule instead of every frame, which is most of the per-frame cost of a dense
	 * field of block entities.
	 */
	public static boolean limitUpdates() {
		return readConfig(CONFIG_LIMIT_UPDATES, true);
	}

	public static void limitUpdates(boolean value) {
		writeConfig(CONFIG_LIMIT_UPDATES, value);
	}

	/**
	 * How light is interpolated across a block face. Smoother values need a shader recompile and
	 * cost a little per fragment; {@code flat} is the cheapest and the most visibly banded.
	 */
	public static String lightSmoothness() {
		Object backendConfig = readInstance(CONFIG_BACKEND_CONFIG);
		if (backendConfig == null || BACKEND_CONFIG_LIGHT_SMOOTHNESS == null) {
			return "SMOOTH";
		}

		try {
			Object value = BACKEND_CONFIG_LIGHT_SMOOTHNESS.get(backendConfig);
			return value instanceof Enum<?> e ? e.name() : "SMOOTH";
		} catch (Throwable t) {
			return "SMOOTH";
		}
	}

	public static void lightSmoothness(String value) {
		Object backendConfig = readInstance(CONFIG_BACKEND_CONFIG);
		if (backendConfig == null || BACKEND_CONFIG_LIGHT_SMOOTHNESS == null) {
			return;
		}

		try {
			Class<?> type = BACKEND_CONFIG_LIGHT_SMOOTHNESS.getType();
			BACKEND_CONFIG_LIGHT_SMOOTHNESS.set(backendConfig, Enum.valueOf(type, value.toUpperCase(Locale.ROOT)));
		} catch (Throwable t) {
			LOGGER.warn("Could not set Flywheel light smoothness to '{}'", value, t);
		}
	}

	// -------------------------------------------------------------- mobile

	/**
	 * Order-independent transparency. On a tile-based GPU every full-screen pass is a tile
	 * load/store, so this is usually the single most expensive switch on the page.
	 */
	public static boolean oit() {
		return readStatic(MOBILE_OIT, true);
	}

	public static void oit(boolean value) {
		writeConfigAndMobile(CONFIG_OIT, MOBILE_OIT, value);
	}

	public static boolean disableParticles() {
		return readStatic(MOBILE_DISABLE_PARTICLES, false);
	}

	public static void disableParticles(boolean value) {
		writeConfigAndMobile(CONFIG_DISABLE_PARTICLES, MOBILE_DISABLE_PARTICLES, value);
	}

	public static boolean freezeGuiItemAnimation() {
		return readStatic(MOBILE_FREEZE_GUI_ITEM_ANIMATION, false);
	}

	public static void freezeGuiItemAnimation(boolean value) {
		writeConfigAndMobile(CONFIG_FREEZE_GUI_ITEM_ANIMATION, MOBILE_FREEZE_GUI_ITEM_ANIMATION, value);
	}

	/**
	 * Replace span-by-span instance uploads with one full re-upload of the instancer's array.
	 * The orphaned upload avoids the driver waiting on an in-flight read, which is what makes
	 * partial {@code glBufferSubData} expensive on mobile hardware.
	 */
	public static boolean orphanInstanceUpload() {
		return readStatic(MOBILE_ORPHAN_UPLOADS, true);
	}

	public static void orphanInstanceUpload(boolean value) {
		writeConfigAndMobile(CONFIG_ORPHAN_UPLOADS, MOBILE_ORPHAN_UPLOADS, value);
	}

	// ---------------------------------------------------------------- persist

	/**
	 * Write {@code flywheel.json} so the choices survive a restart. Called once per apply by the
	 * option storage, after every binding has already put its value in memory.
	 */
	public static void save() {
		if (CONFIG == null || CONFIG_SAVE == null) {
			return;
		}

		try {
			CONFIG_SAVE.invoke(CONFIG);
		} catch (Throwable t) {
			LOGGER.warn("Could not write flywheel.json", t);
		}
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * The four mobile options exist twice, as a config field for persistence and as a static field
	 * for the backend. They are written together here so a change takes effect this frame and is
	 * still there after a restart.
	 */
	private static void writeConfigAndMobile(@Nullable Field configField, @Nullable Field mobileField, boolean value) {
		writeConfig(configField, value);
		writeStatic(mobileField, value);
	}

	private static boolean readStatic(@Nullable Field field, boolean fallback) {
		if (field == null) {
			return fallback;
		}

		try {
			return field.getBoolean(null);
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static void writeStatic(@Nullable Field field, boolean value) {
		if (field == null) {
			return;
		}

		try {
			field.setBoolean(null, value);
		} catch (Throwable t) {
			LOGGER.warn("Could not write a Flywheel option", t);
		}
	}

	private static boolean readConfig(@Nullable Field field, boolean fallback) {
		if (field == null || CONFIG == null) {
			return fallback;
		}

		try {
			return field.getBoolean(CONFIG);
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static void writeConfig(@Nullable Field field, boolean value) {
		if (field == null || CONFIG == null) {
			return;
		}

		try {
			field.setBoolean(CONFIG, value);
		} catch (Throwable t) {
			LOGGER.warn("Could not write a Flywheel option", t);
		}
	}

	private static int readConfigInt(@Nullable Field field, int fallback) {
		if (field == null || CONFIG == null) {
			return fallback;
		}

		try {
			return field.getInt(CONFIG);
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static void writeConfigInt(@Nullable Field field, int value) {
		if (field == null || CONFIG == null) {
			return;
		}

		try {
			field.setInt(CONFIG, value);
		} catch (Throwable t) {
			LOGGER.warn("Could not write a Flywheel option", t);
		}
	}

	@Nullable
	private static Object readInstance(@Nullable Field field) {
		if (field == null || CONFIG == null) {
			return null;
		}

		try {
			return field.get(CONFIG);
		} catch (Throwable t) {
			return null;
		}
	}

	private static void writeInstance(@Nullable Field field, @Nullable Object value) {
		if (field == null || CONFIG == null) {
			return;
		}

		try {
			field.set(CONFIG, value);
		} catch (Throwable t) {
			LOGGER.warn("Could not write the Flywheel backend", t);
		}
	}

	@Nullable
	private static Field staticField(Class<?> owner, String name) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (Throwable t) {
			return null;
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
