package me.jellysquid.mods.sodium.atlascache.cache;

/**
 * The metadata of a single sprite inside an atlas.
 *
 * <p>This is the part that makes the capture useful for the texture conversion: it holds
 * the exact rectangle the sprite occupies in the atlas, the matching texture coordinates
 * and the source file the sprite was loaded from.
 */
public final class SpriteCacheEntry {
    /**
     * The sprite identifier, for example {@code minecraft:block/stone}.
     */
    public String id = "";

    /**
     * The left edge of the sprite inside the atlas, in pixels.
     */
    public int x;

    /**
     * The top edge of the sprite inside the atlas, in pixels.
     */
    public int y;

    /**
     * The width of the sprite, in pixels.
     */
    public int width;

    /**
     * The height of the sprite, in pixels.
     */
    public int height;

    /**
     * The left texture coordinate of the sprite.
     */
    public float u0;

    /**
     * The right texture coordinate of the sprite.
     */
    public float u1;

    /**
     * The top texture coordinate of the sprite.
     */
    public float v0;

    /**
     * The bottom texture coordinate of the sprite.
     */
    public float v1;

    /**
     * Whether the sprite is animated.
     */
    public boolean animated;

    /**
     * The number of frames of an animated sprite.
     */
    public int frameCount;

    /**
     * The resource the sprite was loaded from, for example
     * {@code minecraft:textures/block/stone.png}. Empty when the source could not be
     * resolved.
     */
    public String source = "";

    /**
     * The SHA-256 of the source resource. Empty when the source could not be resolved.
     */
    public String sourceSha256 = "";
}
