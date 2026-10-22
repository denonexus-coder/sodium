package me.jellysquid.mods.sodium.client.render.chunk.terrain.material;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.MaterialParameters;

public class Material {
    public final TerrainRenderPass pass;
    public final int packed;

    public final AlphaCutoffParameter alphaCutoff;
    public final boolean mipped;
    public final boolean water;
    public final boolean translucent;

    public Material(TerrainRenderPass pass, AlphaCutoffParameter alphaCutoff, boolean mipped) {
        this(pass, alphaCutoff, mipped, false, false);
    }

    public Material(TerrainRenderPass pass, AlphaCutoffParameter alphaCutoff, boolean mipped, boolean water) {
        this(pass, alphaCutoff, mipped, water, false);
    }

    public Material(TerrainRenderPass pass, AlphaCutoffParameter alphaCutoff, boolean mipped,
                    boolean water, boolean translucent) {
        this.pass = pass;
        this.packed = MaterialParameters.pack(alphaCutoff, mipped, water, translucent);

        this.alphaCutoff = alphaCutoff;
        this.mipped = mipped;
        this.water = water;
        this.translucent = translucent;
    }

    public int bits() {
        return this.packed;
    }
}
