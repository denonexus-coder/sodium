package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseConfig;
import net.minecraft.world.gen.chunk.StructureAccessor;
import net.minecraft.world.gen.Blender;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletionStage;

/**
 * ASMD — tempo de geração de chunk (worldgen).
 *
 * Hook em ChunkGenerator.populateNoise (Yarn 1.20.1): o ponto onde o terreno
 * é gerado a partir de noise. Mede o tempo de CPU da geração.
 *
 * Ultra-leitor NÃO toca neste método (só RegionFileStorage/RegionFile),
 * então não há conflito de @Overwrite.
 *
 * Custo: 2 System.nanoTime() por chunk gerado (~0.05% do tempo total).
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGenMixin {
    @Unique
    private long asmd$genStartNs;

    @Inject(method = "populateNoise", at = @At("HEAD"))
    private void asmd$genStart(Blender blender, NoiseConfig noiseConfig,
                               Chunk chunk, StructureAccessor structureAccessor,
                               CallbackInfoReturnable<CompletionStage<Chunk>> cir) {
        this.asmd$genStartNs = System.nanoTime();
    }

    @Inject(method = "populateNoise", at = @At("RETURN"))
    private void asmd$genEnd(Blender blender, NoiseConfig noiseConfig,
                             Chunk chunk, StructureAccessor structureAccessor,
                             CallbackInfoReturnable<CompletionStage<Chunk>> cir) {
        long elapsed = System.nanoTime() - this.asmd$genStartNs;
        if (this.asmd$genStartNs > 0 && elapsed < 10_000_000_000L) { // < 10s = válido
            float ms = elapsed / 1_000_000.0f;
            ASMDJavaBridge.setChunkGenMs(ms);
        }
    }
}
