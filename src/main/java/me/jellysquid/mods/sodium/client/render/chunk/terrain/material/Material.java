package me.jellysquid.mods.sodium.client.render.chunk.terrain.material;

import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.MaterialParameters;

public class Material {
    public final TerrainRenderPass pass;
    public final int packed;

    public final AlphaCutoffParameter alphaCutoff;
    public final boolean mipped;

    // Variantes cacheadas por direcao da face (usadas pelo INFINITIUM LIGHT ENGINE para o
    // sombreamento N.L por vertice). Alocadas uma vez, reaproveitadas no caminho quente.
    private Material[] faceVariants;

    public Material(TerrainRenderPass pass, AlphaCutoffParameter alphaCutoff, boolean mipped) {
        this.pass = pass;
        this.packed = MaterialParameters.pack(alphaCutoff, mipped);

        this.alphaCutoff = alphaCutoff;
        this.mipped = mipped;
    }

    private Material(TerrainRenderPass pass, AlphaCutoffParameter alphaCutoff, boolean mipped, int packed) {
        this.pass = pass;
        this.packed = packed;

        this.alphaCutoff = alphaCutoff;
        this.mipped = mipped;
    }

    public int bits() {
        return this.packed;
    }

    /**
     * Variante deste material com a direcao da face embutida nos bits 3..5 (para o N.L por
     * vertice). UNASSIGNED nao tem face -> devolve este mesmo. Cacheado por face.
     */
    public Material withFace(ModelQuadFacing face) {
        int ordinal = face.ordinal();

        // ModelQuadFacing: POS_X..NEG_Z = 0..5, UNASSIGNED = 6 (sem face util).
        if (ordinal >= 6) {
            return this;
        }

        if (this.faceVariants == null) {
            this.faceVariants = new Material[6];
        }

        Material cached = this.faceVariants[ordinal];

        if (cached == null) {
            cached = new Material(this.pass, this.alphaCutoff, this.mipped,
                    MaterialParameters.packFace(this.packed, ordinal + 1));
            this.faceVariants[ordinal] = cached;
        }

        return cached;
    }
}
