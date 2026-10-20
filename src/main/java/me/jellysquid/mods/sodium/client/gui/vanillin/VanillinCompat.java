package me.jellysquid.mods.sodium.client.gui.vanillin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft bridge to Vanillin's runtime configuration.
 *
 * <p>Vanillin is a companion mod to Flywheel which registers visualizers for the handful of vanilla
 * objects the Flywheel engine can instance: chests, bells, shulker boxes, block displays and
 * minecarts. Its configuration lives in {@code vanillin.json} as a pair of maps from a registry
 * name to one of {@code default}, {@code disable} or {@code force_enable}, and it is read, applied
 * and written back once, during mod startup.
 *
 * <p>Nothing here mentions a Vanillin class at compile time, for the same reason as
 * {@code FlywheelCompat}: Sodium has to keep building, shipping and running on its own. Every class
 * and member is resolved once by name and {@link #PRESENT} stays false when the mod is absent, which
 * keeps the page out of the menu rather than showing rows that do nothing.
 *
 * <p>The option list is enumerated from Vanillin's own configurator rather than hardcoded. A second
 * mod registering more visuals, or an upstream addition, then shows up on the page with no change
 * here, and the registry names stay the single source of truth for both the key and the label.
 */
public final class VanillinCompat {
	private static final Logger LOGGER = LoggerFactory.getLogger("Sodium/Vanillin");

	public static final boolean PRESENT;

	/**
	 * The three states, in the order the control shows them. The names are Vanillin's own enum
	 * constants, resolved below rather than compiled against.
	 */
	private static final String[] STATE_NAMES = { "DEFAULT", "DISABLE", "FORCE_ENABLE" };

	private static final Object[] STATES;

	// FabricVanillinConfig.INSTANCE and the state it holds.
	@Nullable
	private static final Object CONFIG;
	@Nullable
	private static final Field CONFIG_DATA;
	@Nullable
	private static final Field DATA_BLOCK_ENTITIES;
	@Nullable
	private static final Field DATA_ENTITIES;
	@Nullable
	private static final Method CONFIG_SAVE;
	@Nullable
	private static final Method CONFIG_APPLY;

	// VanillaVisuals.CONFIGURATOR, whose entries know whether they are on by default.
	@Nullable
	private static final Object CONFIGURATOR;
	@Nullable
	private static final Field CONFIGURATOR_BLOCK_ENTITIES;
	@Nullable
	private static final Field CONFIGURATOR_ENTITIES;
	@Nullable
	private static final Field ENABLED_BY_DEFAULT;
	@Nullable
	private static final Method CONFIG_KEY;

	/** Every configurable visual, block entities first. */
	private static final List<Entry> ENTRIES;

	static {
		Object[] states = null;
		Object config = null;
		Field configData = null;
		Field dataBlockEntities = null;
		Field dataEntities = null;
		Method configSave = null;
		Method configApply = null;
		Object configurator = null;
		Field configuratorBlockEntities = null;
		Field configuratorEntities = null;
		Field enabledByDefault = null;
		Method configKey = null;
		List<Entry> entries = List.of();
		boolean present = false;

		try {
			Class<?> fabricConfig = Class.forName("dev.engine_room.vanillin.FabricVanillinConfig");
			Class<?> vanillaVisuals = Class.forName("dev.engine_room.vanillin.VanillaVisuals");
			Class<?> visualConfigValue = Class.forName("dev.engine_room.vanillin.config.VisualConfigValue");
			Class<?> configuratorType = Class.forName("dev.engine_room.vanillin.config.Configurator");

			// The enum, walked rather than resolved with valueOf: as an unknown Class<?> it cannot
			// be proven to satisfy the bound valueOf insists on, and matching names is the same
			// result with no unchecked cast between a typo and a config write.
			Object[] constants = visualConfigValue.getEnumConstants();
			if (constants != null && constants.length == STATE_NAMES.length) {
				states = new Object[STATE_NAMES.length];

				for (int i = 0; i < STATE_NAMES.length; i++) {
					for (Object constant : constants) {
						if (constant instanceof Enum<?> e && e.name().equals(STATE_NAMES[i])) {
							states[i] = constant;
							break;
						}
					}
				}
			}

			Field instance = memberField(fabricConfig, "INSTANCE");
			configData = memberField(fabricConfig, "config");

			if (instance != null) {
				config = instance.get(null);
			}

			if (configData != null) {
				Class<?> dataType = configData.getType();
				dataBlockEntities = memberField(dataType, "blockEntities");
				dataEntities = memberField(dataType, "entities");
			}

			configSave = memberMethod(fabricConfig, "save");
			configApply = memberMethod(fabricConfig, "apply", configuratorType);

			Field configuratorField = memberField(vanillaVisuals, "CONFIGURATOR");
			if (configuratorField != null) {
				configurator = configuratorField.get(null);
			}

			if (configurator != null) {
				Class<?> configuratorTypeOf = configurator.getClass();
				configuratorBlockEntities = memberField(configuratorTypeOf, "blockEntities");
				configuratorEntities = memberField(configuratorTypeOf, "entities");

				// Both concrete subclasses share one superclass, so one lookup covers chests,
				// minecarts and everything else alike. Found by name rather than by position:
				// the order of getDeclaredClasses is unspecified and could change under us.
				Class<?> configured = null;
				for (Class<?> inner : configuratorType.getDeclaredClasses()) {
					if (inner.getSimpleName().equals("ConfiguredVisual")) {
						configured = inner;
						break;
					}
				}

				if (configured != null) {
					enabledByDefault = memberField(configured, "enabledByDefault");
					configKey = memberMethod(configured, "configKey");
				}
			}

			present = states != null
					&& allNonNull(states)
					&& config != null
					&& configData != null
					&& dataBlockEntities != null
					&& dataEntities != null
					&& configSave != null
					&& configApply != null
					&& configurator != null
					&& configuratorBlockEntities != null
					&& configuratorEntities != null
					&& enabledByDefault != null
					&& configKey != null;

			if (present) {
				entries = enumerate(configuratorBlockEntities, configuratorEntities, configKey, enabledByDefault);
				present = !entries.isEmpty();
			}
		} catch (Throwable t) {
			LOGGER.debug("Vanillin is not installed, so its options page will not be shown", t);
			present = false;
		}

		STATES = states != null ? states : new Object[0];
		CONFIG = config;
		CONFIG_DATA = configData;
		DATA_BLOCK_ENTITIES = dataBlockEntities;
		DATA_ENTITIES = dataEntities;
		CONFIG_SAVE = configSave;
		CONFIG_APPLY = configApply;
		CONFIGURATOR = configurator;
		CONFIGURATOR_BLOCK_ENTITIES = configuratorBlockEntities;
		CONFIGURATOR_ENTITIES = configuratorEntities;
		ENABLED_BY_DEFAULT = enabledByDefault;
		CONFIG_KEY = configKey;
		ENTRIES = entries;
		PRESENT = present;

		if (present) {
			LOGGER.debug("Vanillin detected, {} visuals will appear on their own Sodium page", ENTRIES.size());
		}
	}

	private VanillinCompat() {
	}

	/**
	 * One configurable visual: the registry name Vanillin writes to {@code vanillin.json}, whether
	 * it belongs to the block entity or the entity map, and whether Vanillin turns it on when the
	 * file has no say.
	 */
	public record Entry(String key, boolean blockEntity, boolean enabledByDefault) {
		/**
		 * The label for the row, built from the registry path: {@code trapped_chest} becomes
		 * {@code Trapped Chest}. Stable, readable and free of translation files that would have to
		 * name every visual upstream might ever add.
		 */
		public String label() {
			String path = this.key.contains(":") ? this.key.substring(this.key.indexOf(':') + 1) : this.key;
			StringBuilder out = new StringBuilder(path.length() + 4);
			boolean capitalise = true;

			for (int i = 0; i < path.length(); i++) {
				char c = path.charAt(i);
				if (c == '_' || c == ' ') {
					capitalise = true;
					out.append(' ');
					continue;
				}
				out.append(capitalise ? Character.toUpperCase(c) : c);
				capitalise = false;
			}

			return out.toString();
		}
	}

	/** Every visual Vanillin can configure, block entities first, in registration order. */
	public static List<Entry> entries() {
		return ENTRIES;
	}

	public static Collection<Entry> blockEntityEntries() {
		List<Entry> out = new ArrayList<>();
		for (Entry entry : ENTRIES) {
			if (entry.blockEntity()) {
				out.add(entry);
			}
		}
		return Collections.unmodifiableCollection(out);
	}

	public static Collection<Entry> entityEntries() {
		List<Entry> out = new ArrayList<>();
		for (Entry entry : ENTRIES) {
			if (!entry.blockEntity()) {
				out.add(entry);
			}
		}
		return Collections.unmodifiableCollection(out);
	}

	/**
	 * The state of one visual, as one of {@link VanillinState}. An entry the file does not mention
	 * reads as {@code DEFAULT}, which is also what Vanillin writes on its first save.
	 */
	public static VanillinState get(Entry entry) {
		Object value = readMap(entry).get(entry.key());

		for (int i = 0; i < STATES.length; i++) {
			if (STATES[i].equals(value)) {
				return VanillinState.values()[i];
			}
		}

		return VanillinState.DEFAULT;
	}

	public static void set(Entry entry, VanillinState state) {
		if (state == null) {
			return;
		}

		int index = state.ordinal();
		if (index < 0 || index >= STATES.length) {
			return;
		}

		readMap(entry).put(entry.key(), STATES[index]);
	}

	/**
	 * Push the in-memory config into Vanillin's visualizer registry.
	 *
	 * <p>Vanillin applies this once at startup, so the page calls it again on Apply. On its own it
	 * only changes which visualizer a <em>future</em> visual will be built with: an object already
	 * visualised keeps the one it has, while {@code skipVanillaRender} reads the registry live, so
	 * the two would disagree until the visuals are rebuilt. The caller owns that rebuild --
	 * {@code VanillinOptionsStorage} follows this with a renderer reload.
	 */
	public static void apply() {
		if (CONFIG == null || CONFIG_APPLY == null || CONFIGURATOR == null) {
			return;
		}

		try {
			CONFIG_APPLY.invoke(CONFIG, CONFIGURATOR);
		} catch (Throwable t) {
			LOGGER.warn("Could not apply vanillin.json", t);
		}
	}

	/** Write {@code vanillin.json} so the choices survive a restart. */
	public static void save() {
		if (CONFIG == null || CONFIG_SAVE == null) {
			return;
		}

		try {
			CONFIG_SAVE.invoke(CONFIG);
		} catch (Throwable t) {
			LOGGER.warn("Could not write vanillin.json", t);
		}
	}

	// ---------------------------------------------------------------- helpers

	@SuppressWarnings("unchecked")
	private static Map<String, Object> readMap(Entry entry) {
		Field field = entry.blockEntity() ? DATA_BLOCK_ENTITIES : DATA_ENTITIES;

		if (field == null || CONFIG == null || CONFIG_DATA == null) {
			return Collections.emptyMap();
		}

		try {
			Object data = CONFIG_DATA.get(CONFIG);
			if (data == null) {
				return Collections.emptyMap();
			}

			Map<String, Object> map = (Map<String, Object>) field.get(data);

			// A hand-edited file with no block_entities or entities section would otherwise leave
			// this null and every write would be lost without a word.
			if (map == null) {
				map = new java.util.HashMap<>();
				field.set(data, map);
			}

			return map;
		} catch (Throwable t) {
			LOGGER.warn("Could not reach Vanillin's config map", t);
			return Collections.emptyMap();
		}
	}

	/**
	 * Reads Vanillin's own registrations so the page can list what exists and whether each entry is
	 * on by default, instead of guessing from a hardcoded table that would go stale.
	 */
	private static List<Entry> enumerate(Field blockField, Field entityField, Method key, Field defaultField) {
		List<Entry> out = new ArrayList<>();

		if (CONFIGURATOR == null) {
			return out;
		}

		try {
			Object configurator = CONFIGURATOR;
			collect(out, blockField.get(configurator), key, defaultField, true);
			collect(out, entityField.get(configurator), key, defaultField, false);
		} catch (Throwable t) {
			LOGGER.warn("Could not enumerate Vanillin's visuals", t);
			return List.of();
		}

		return Collections.unmodifiableList(out);
	}

	@SuppressWarnings("unchecked")
	private static void collect(List<Entry> out, Object mapObject, Method key, Field defaultField, boolean blockEntity) {
		if (!(mapObject instanceof Map<?, ?> raw)) {
			return;
		}

		for (Object value : raw.values()) {
			if (value == null) {
				continue;
			}

			try {
				String name = (String) key.invoke(value);
				if (name == null) {
					continue;
				}

				boolean enabledByDefault = defaultField.getBoolean(value);
				out.add(new Entry(name, blockEntity, enabledByDefault));
			} catch (Throwable t) {
				// One unreadable registration should not blank the rest of the page.
				LOGGER.debug("Skipping a Vanillin visual Sodium could not read", t);
			}
		}
	}

	private static boolean allNonNull(Object[] values) {
		for (Object value : values) {
			if (value == null) {
				return false;
			}
		}
		return true;
	}

	@Nullable
	private static Field memberField(Class<?> owner, String name) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (Throwable t) {
			return null;
		}
	}

	@Nullable
	private static Method memberMethod(Class<?> owner, String name, Class<?>... parameters) {
		try {
			Method method = owner.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method;
		} catch (Throwable t) {
			return null;
		}
	}
}
