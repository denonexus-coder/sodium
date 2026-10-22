package me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters;

public class MaterialParameters {
    public static final int OFFSET_USE_MIP = 0;
    public static final int OFFSET_ALPHA_CUTOFF = 1;

    /**
     * Bit 3 is free in the material byte. It marks water so the fragment shader can own the
     * tint, the waves and the sky reflection without a per-block lookup or a second vertex
     * format.
     */
    public static final int OFFSET_WATER = 3;

    /**
     * Bit 4 marks the whole translucent pass. Water uses it for the reflection, but so do glass,
     * ice and the other translucent blocks: the profile reflects the sky on all of them, while
     * the absorption and the waves remain water-only.
     */
    public static final int OFFSET_TRANSLUCENT = 4;

    public static int pack(AlphaCutoffParameter alphaCutoff, boolean useMipmaps) {
        return pack(alphaCutoff, useMipmaps, false, false);
    }

    public static int pack(AlphaCutoffParameter alphaCutoff, boolean useMipmaps, boolean water) {
        return pack(alphaCutoff, useMipmaps, water, false);
    }

    public static int pack(AlphaCutoffParameter alphaCutoff, boolean useMipmaps, boolean water, boolean translucent) {
        return (((useMipmaps ? 1 : 0) << OFFSET_USE_MIP) |
                ((alphaCutoff.ordinal()) << OFFSET_ALPHA_CUTOFF) |
                ((water ? 1 : 0) << OFFSET_WATER) |
                ((translucent ? 1 : 0) << OFFSET_TRANSLUCENT));
    }
}
