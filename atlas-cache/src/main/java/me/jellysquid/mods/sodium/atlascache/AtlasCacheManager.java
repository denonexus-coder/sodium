package me.jellysquid.mods.sodium.atlascache;

import me.jellysquid.mods.sodium.atlascache.cache.CacheConfig;
import me.jellysquid.mods.sodium.atlascache.cache.CacheManifest;
import me.jellysquid.mods.sodium.atlascache.cache.CacheStore;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Holds the state of the atlas cache: the configuration, the manifest and the few
 * helpers that both the capture and the restore path need.
 */
public final class AtlasCacheManager {
    private static CacheConfig config = new CacheConfig();
    private static CacheManifest manifest = new CacheManifest();
    private static final Set<String> RESTORED = new HashSet<>();

    private AtlasCacheManager() {
    }

    /**
     * Loads the configuration and the manifest from disk.
     */
    public static void initialize() {
        config = CacheStore.readConfig();
        CacheStore.writeConfig(config);

        manifest = CacheStore.readManifest();
    }

    public static CacheConfig getConfig() {
        return config;
    }

    public static CacheManifest getManifest() {
        return manifest;
    }

    /**
     * Writes the manifest back to disk.
     */
    public static void saveManifest() {
        CacheStore.writeManifest(manifest);
    }

    /**
     * Whether the atlases are captured at all.
     */
    public static boolean isEnabled() {
        return config.enabled;
    }

    /**
     * Whether a valid cache is allowed to replace the atlas construction.
     */
    public static boolean shouldSkipAtlasCreation() {
        return config.enabled && config.skipAtlasCreation;
    }

    /**
     * Whether the source resources are hashed when the cache is validated.
     */
    public static boolean shouldValidateSources() {
        return config.enabled && config.validateSources;
    }

    /**
     * Remembers that an atlas was rebuilt from the cache.
     *
     * <p>Such an atlas is not captured again: the cache already holds exactly what the
     * game ended up with.
     */
    public static void markRestored(String atlasId) {
        RESTORED.add(atlasId);
    }

    /**
     * Whether the given atlas was rebuilt from the cache.
     */
    public static boolean wasRestored(String atlasId) {
        return RESTORED.contains(atlasId);
    }

    /**
     * A hash over the resource packs that are currently active.
     *
     * <p>When the active packs change the cache cannot be trusted any more, even if all
     * the files themselves are still in place.
     */
    public static String getResourcePackFingerprint() {
        Collection<String> names;

        try {
            names = MinecraftClient.getInstance().getResourcePackManager().getEnabledNames();
        } catch (Throwable t) {
            return "";
        }

        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);

        return CacheStore.fingerprint(sorted);
    }
}
