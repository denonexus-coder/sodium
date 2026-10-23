package me.jellysquid.mods.sodium.atlascache.cache;

/**
 * The manifest entry of a single atlas.
 */
public final class AtlasCacheEntry {
    /**
     * The identifier of the atlas, for example {@code minecraft:textures/atlas/blocks.png}.
     */
    public String atlas = "";

    /**
     * The atlas image, relative to the cache directory.
     */
    public String png = "";

    /**
     * The sprite metadata, relative to the cache directory.
     */
    public String metadata = "";

    /**
     * The width of the atlas in pixels.
     */
    public int width;

    /**
     * The height of the atlas in pixels.
     */
    public int height;

    /**
     * The number of mipmap levels of the atlas.
     */
    public int mipLevel;

    /**
     * The number of sprites that were stitched into the atlas.
     */
    public int spriteCount;

    /**
     * The SHA-256 of the atlas image.
     */
    public String pngSha256 = "";

    /**
     * The SHA-256 of the sprite metadata.
     */
    public String metadataSha256 = "";

    /**
     * A hash over the source resources the atlas was built from.
     *
     * <p>This is what detects a resource pack that changed: every sprite knows which
     * source file it came from and what that file hashed to.
     */
    public String sourcesFingerprint = "";
}
