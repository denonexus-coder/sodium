package me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters;

public class MaterialParameters {
    public static final int OFFSET_USE_MIP = 0;
    public static final int OFFSET_ALPHA_CUTOFF = 1;
    // Bits 3..5 guardam a direcao da face (ModelQuadFacing + 1; 0 = sem face) para o
    // sombreamento N.L por vertice do INFINITIUM LIGHT ENGINE, sem mudar o stride do vertice.
    public static final int OFFSET_FACE = 3;
    public static final int FACE_MASK = 0x7;

    public static int pack(AlphaCutoffParameter alphaCutoff, boolean useMipmaps) {
        return (((useMipmaps ? 1 : 0) << OFFSET_USE_MIP) |
                ((alphaCutoff.ordinal()) << OFFSET_ALPHA_CUTOFF));
    }

    /** Devolve os bits do material com a direcao da face embutida (bits 3..5). */
    public static int packFace(int basePacked, int faceIndexPlusOne) {
        return (basePacked & ~(FACE_MASK << OFFSET_FACE)) |
                ((faceIndexPlusOne & FACE_MASK) << OFFSET_FACE);
    }
}
