package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ASMD — tempo de geração de terreno (worldgen).
 *
 * Alvo verificado no yarn-1.20.1+build.10 (mappings locais):
 *   NoiseChunkGenerator.populateNoise
 *   (Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/StructureAccessor;
 *    Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;II)
 *    Lnet/minecraft/world/chunk/Chunk;
 *
 * Esta e a versao SINCRONA que preenche o chunk com ruido — o trabalho real de
 * geracao de terreno (Mojang: NoiseBasedChunkGenerator.doFill, ~90 linhas de
 * loop). O populateNoise da classe base ChunkGenerator e abstrato, por isso o
 * mixin alvo na subclasse concreta.
 *
 * Nao conflita com o Ultra-leitor: ele sobrescreve apenas RegionFileStorage e
 * RegionFile (leitura/escrita em disco), nunca worldgen.
 *
 * Roda nas threads de worldgen (vem varias em paralelo), por isso o inicio e
 * guardado em ThreadLocal — um field comum causaria medições cruzadas.
 *
 * Custo: 2 System.nanoTime() por chunk gerado; escrita de 1 float no buffer.
 */
@Mixin(NoiseChunkGenerator.class)
public abstract class ChunkGenMixin {

    /** Inicio da geracao atual, por thread (worldgen usa pool de threads). */
    @Unique
    private ThreadLocal<Long> asmd$genStartNs;

    @Inject(method = "populateNoise", at = @At("HEAD"))
    private void asmd$genStart(Blender blender, StructureAccessor structureAccessor,
                               NoiseConfig noiseConfig, Chunk chunk, int x, int z,
                               CallbackInfoReturnable<Chunk> cir) {
        ThreadLocal<Long> tl = this.asmd$genStartNs;
        if (tl == null) {
            tl = new ThreadLocal<>();
            this.asmd$genStartNs = tl;
        }
        tl.set(System.nanoTime());
    }

    @Inject(method = "populateNoise", at = @At("RETURN"))
    private void asmd$genEnd(Blender blender, StructureAccessor structureAccessor,
                             NoiseConfig noiseConfig, Chunk chunk, int x, int z,
                             CallbackInfoReturnable<Chunk> cir) {
        ThreadLocal<Long> tl = this.asmd$genStartNs;
        if (tl == null) {
            return;
        }
        Long start = tl.get();
        if (start == null) {
            return;
        }
        tl.remove();

        long elapsed = System.nanoTime() - start;
        // Filtro: < 10 s e geracao plausivel; valores gigantes = relogio/estado estranho.
        if (elapsed < 10_000_000_000L) {
            ASMDJavaBridge.setChunkGenMs(elapsed / 1_000_000.0f);
        }
    }
}
