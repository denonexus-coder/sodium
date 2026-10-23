package me.jellysquid.mods.sodium.atlascache.cache;

/**
 * The on-disk configuration of the atlas cache.
 *
 * <p>The file lives next to the manifest so that a user can turn the cache off without
 * having to remove the captured data.
 */
public final class CacheConfig {
    /**
     * Whether the atlases are captured at all.
     */
    public boolean enabled = true;

    /**
     * Whether a valid cache replaces the atlas construction.
     *
     * <p>When this is off the atlas is always built by the game, but the capture still
     * happens, which is useful to compare the two paths.
     */
    public boolean skipAtlasCreation = true;

    /**
     * Whether the source resources are hashed when the cache is validated.
     *
     * <p>This is the expensive part of the validation: every source texture is read and
     * hashed so a resource pack that changed in place is detected. Turning it off makes
     * the validation cheap, but a pack that was replaced without changing its name would
     * not invalidate the cache.
     */
    public boolean validateSources = true;
}
