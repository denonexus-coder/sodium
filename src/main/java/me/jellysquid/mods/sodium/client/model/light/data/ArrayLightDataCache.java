package me.jellysquid.mods.sodium.client.model.light.data;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.BlockRenderView;

import java.util.Arrays;

/**
 * A light data cache which uses a flat-array to store the light data for the blocks in a given chunk and its direct
 * neighbors. This is considerably faster than using a hash table to lookup values for a given block position and
 * can be re-used by {@link WorldSlice} to avoid allocations.
 */
public class ArrayLightDataCache extends LightDataAccess {
    private static final int NEIGHBOR_BLOCK_RADIUS = 2;
    private static final int BLOCK_LENGTH = 16 + (NEIGHBOR_BLOCK_RADIUS * 2);

    private final int[] light;

    // Per-slot generation stamp: a slot is valid when stamp[i] == generation. This replaces the
    // old "0 means not computed" sentinel, which forced an 8000-int Arrays.fill (32 KB) on every
    // single rebuild just to re-arm the sentinel -- see reset().
    private final int[] stamp;

    // Starts at 1 so that the zero-initialised stamps all miss on the first lookup.
    private int generation = 1;

    // Which validity scheme the current job uses. Decided once, in reset(), so that flipping the
    // A/B switch (performance.lightCacheGenStamp) can never leave a job reading slots that were
    // armed by the other scheme -- every job runs entirely on one side of the switch.
    private boolean useStamp = true;

    private int xOffset, yOffset, zOffset;

    public ArrayLightDataCache(BlockRenderView world) {
        this.world = world;
        this.light = new int[BLOCK_LENGTH * BLOCK_LENGTH * BLOCK_LENGTH];
        this.stamp = new int[BLOCK_LENGTH * BLOCK_LENGTH * BLOCK_LENGTH];
    }

    public void reset(ChunkSectionPos origin) {
        this.xOffset = origin.getMinX() - NEIGHBOR_BLOCK_RADIUS;
        this.yOffset = origin.getMinY() - NEIGHBOR_BLOCK_RADIUS;
        this.zOffset = origin.getMinZ() - NEIGHBOR_BLOCK_RADIUS;

        this.useStamp = SodiumClientMod.options().performance.lightCacheGenStamp;

        if (this.useStamp) {
            // O(1) instead of the 8000-element clear this used to do: the index is derived from the
            // origin, so a new origin invalidates every slot at once and a single counter carries that.
            // The stamp array costs 32 KB per chunk-build worker and is paid back on the first rebuild.
            if (++this.generation == 0) {
                // Generation counter wrapped after 2^31 rebuilds. Clear rather than let a slot's stale
                // stamp coincide with the new generation, then park it at 1 (all miss) again.
                Arrays.fill(this.stamp, 0);
                this.generation = 1;
            }
        } else {
            // Baseline: re-arm the "0 means not computed" sentinel with the 8000-int fill (32 KB),
            // and park the stamps at 0 so that turning the switch back on starts from all-miss.
            Arrays.fill(this.light, 0);
            Arrays.fill(this.stamp, 0);
            this.generation = 0;
        }
    }

    private int index(int x, int y, int z) {
        int x2 = x - this.xOffset;
        int y2 = y - this.yOffset;
        int z2 = z - this.zOffset;

        return (z2 * BLOCK_LENGTH * BLOCK_LENGTH) + (y2 * BLOCK_LENGTH) + x2;
    }

    @Override
    public int get(int x, int y, int z) {
        int l = this.index(x, y, z);

        if (this.useStamp) {
            if (this.stamp[l] == this.generation) {
                return this.light[l];
            }

            int word = this.compute(x, y, z);

            this.light[l] = word;
            this.stamp[l] = this.generation;

            return word;
        }

        // Baseline: the 0 sentinel. Identical results -- a slot that reads 0 is recomputed exactly
        // as it was before the generation stamps existed.
        int word = this.light[l];

        if (word != 0) {
            return word;
        }

        return this.light[l] = this.compute(x, y, z);
    }
}