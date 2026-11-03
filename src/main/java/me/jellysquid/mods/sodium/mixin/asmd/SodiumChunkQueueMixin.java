package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * ASMD — tamanho da fila de reconstrução de chunks (offset 72).
 *
 * <p>Não existe {@code getChunkQueueSize()} em {@link RenderSectionManager}: a
 * fila real é o {@code rebuildLists}, somando as filas de
 * {@code IMPORTANT_REBUILD}, {@code REBUILD} e {@code INITIAL_BUILD} — o mesmo
 * que o próprio Sodium imprime nas estatísticas.
 *
 * <p>{@code renderLayer} é chamado 3x por frame (SOLID, CUTOUT, TRANSLUCENT)
 * a partir de {@code SodiumWorldRenderer.drawChunkLayer}; a gravação é idêntica
 * nas três, então só reflete o estado da fila no início do desenho.
 */
@Mixin(RenderSectionManager.class)
public abstract class SodiumChunkQueueMixin {
    @Shadow
    private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists;

    @Inject(method = "renderLayer(Lme/jellysquid/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;DDD)V", at = @At("HEAD"))
    private void asmd$onRenderLayerHead(ChunkRenderMatrices matrices, TerrainRenderPass pass,
                                        double x, double y, double z, CallbackInfo ci) {
        int queue = 0;

        for (ArrayDeque<RenderSection> list : this.rebuildLists.values()) {
            queue += list.size();
        }

        ASMDJavaBridge.setChunkQueueSize(queue);
    }
}