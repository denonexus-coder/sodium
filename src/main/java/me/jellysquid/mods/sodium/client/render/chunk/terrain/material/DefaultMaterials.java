package me.jellysquid.mods.sodium.client.render.chunk.terrain.material;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;

public class DefaultMaterials {
    public static final Material SOLID = new Material(DefaultTerrainRenderPasses.SOLID, AlphaCutoffParameter.ZERO, true);
    public static final Material CUTOUT = new Material(DefaultTerrainRenderPasses.CUTOUT, AlphaCutoffParameter.ONE_TENTH, false);
    public static final Material CUTOUT_MIPPED = new Material(DefaultTerrainRenderPasses.CUTOUT, AlphaCutoffParameter.ONE_TENTH, true);
    public static final Material TRANSLUCENT = new Material(DefaultTerrainRenderPasses.TRANSLUCENT,
            AlphaCutoffParameter.ZERO, true, false, true);

    /**
     * Water lives in the translucent pass and carries two extra material bits: one for water
     * (absorption and waves) and the shared translucent bit (sky reflection on water and glass).
     * The bits are read only by the profile shader; with the profile off they compile to nothing.
     */
    public static final Material WATER = new Material(DefaultTerrainRenderPasses.TRANSLUCENT,
            AlphaCutoffParameter.ZERO, true, true, true);

    public static Material forBlockState(BlockState state) {
        return forRenderLayer(RenderLayers.getBlockLayer(state));
    }

    public static Material forFluidState(FluidState state) {
        if (state.isIn(FluidTags.WATER)) {
            return WATER;
        }

        return forRenderLayer(RenderLayers.getFluidLayer(state));
    }

    public static Material forRenderLayer(RenderLayer layer) {
        if (layer == RenderLayer.getSolid()) {
            return SOLID;
        } else if (layer == RenderLayer.getCutout()) {
            return CUTOUT;
        } else if (layer == RenderLayer.getCutoutMipped() || layer == RenderLayer.getTripwire()) {
            return CUTOUT_MIPPED;
        } else if (layer == RenderLayer.getTranslucent()) {
            return TRANSLUCENT;
        }

        throw new IllegalArgumentException("No material mapping exists for " + layer);
    }
}
