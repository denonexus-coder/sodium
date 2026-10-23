package me.jellysquid.mods.sodium.atlascache.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The manifest of the cache.
 *
 * <p>It is the single file that says whether the cache can be used: it pins the format
 * version, the game version, the resource packs that were active and the hash of every
 * file that belongs to the cache.
 */
public final class CacheManifest {
    /**
     * The version of the on-disk format. A change here invalidates every older cache.
     */
    public int formatVersion = CacheStore.FORMAT_VERSION;

    /**
     * The Minecraft version the cache was captured with.
     */
    public String minecraftVersion = "";

    /**
     * The version of the mod that captured the cache.
     */
    public String modVersion = "";

    /**
     * The moment the cache was captured, in ISO-8601.
     */
    public String createdAt = "";

    /**
     * A hash over the names of the resource packs that were active.
     */
    public String resourcePackFingerprint = "";

    /**
     * The captured atlases, keyed by their identifier.
     */
    public Map<String, AtlasCacheEntry> atlases = new LinkedHashMap<>();
}
