package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * ASMD — tempo de carregamento de recursos (textures, models, sounds).
 *
 * Hook em ReloadableResourceManagerImpl.reload (Yarn 1.20.1): o ponto onde
 * todos os resource packs são recarregados. Mede o tempo total do reload.
 *
 * Ultra-leitor NÃO toca neste método (só RegionFileStorage/RegionFile),
 * então não há conflito.
 *
 * Chamado: boot do jogo, troca de resource pack, F3+T.
 * Custo: 2 System.nanoTime() por reload (~0 no frame quente).
 */
@Mixin(ReloadableResourceManagerImpl.class)
public abstract class ResourceLoadMixin {
    @Unique
    private long asmd$reloadStartNs;

    @Inject(
        method = "reload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Iterable;Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD")
    )
    private void asmd$reloadStart(Executor prepareExecutor, Executor applyExecutor,
                                  Iterable<ResourcePack> packs,
                                  CompletableFuture<Void> initialFuture,
                                  CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        this.asmd$reloadStartNs = System.nanoTime();
    }

    @Inject(
        method = "reload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Iterable;Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN")
    )
    private void asmd$reloadEnd(Executor prepareExecutor, Executor applyExecutor,
                                Iterable<ResourcePack> packs,
                                CompletableFuture<Void> initialFuture,
                                CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        long elapsed = System.nanoTime() - this.asmd$reloadStartNs;
        if (this.asmd$reloadStartNs > 0 && elapsed < 120_000_000_000L) { // < 120s = válido
            float ms = elapsed / 1_000_000.0f;
            ASMDJavaBridge.setResourceLoadMs(ms);
        }
    }
}
