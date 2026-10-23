package me.jellysquid.mods.sodium.atlascache.cache;

import java.util.ArrayList;
import java.util.List;

/**
 * The metadata file of a single atlas.
 *
 * <p>It holds the geometry of the atlas and the full list of the sprites that were
 * stitched into it.
 */
public final class AtlasMetadata {
    /**
     * The identifier of the atlas.
     */
    public String atlas = "";

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
     * The sprites of the atlas.
     */
    public List<SpriteCacheEntry> sprites = new ArrayList<>();
}
