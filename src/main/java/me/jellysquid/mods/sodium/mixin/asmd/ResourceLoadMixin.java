package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourceReload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * ASMD — tempo de carregamento de recursos (texturas, models, sons, packs).
 *
 * Alvo verificado no yarn-1.20.1+build.10 (mappings locais):
 *   ReloadableResourceManagerImpl.reload
 *   (Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;
 *    Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)
 *    Lnet/minecraft/resource/ResourceReload;
 *   params: prepareExecutor, applyExecutor, initialStage, packs
 *
 * Chamado no boot do jogo, na troca de resource pack e no F3+T. O metodo roda
 * na thread chamadora (retorna um ResourceReload futuro), entao HEAD e RETURN
 * estao na mesma thread — um field comum basta.
 *
 * O Ultra-leitor nao toca em ResourceManager, entao nao ha conflito. O Sodium
 * tambem nao injeta neste metodo.
 *
 * Custo: 2 System.nanoTime() por reload (zero no frame quente).
 */
@Mixin(ReloadableResourceManagerImpl.class)
public abstract class ResourceLoadMixin {

    @Unique
    private long asmd$reloadStartNs;

    @Inject(method = "reload", at = @At("HEAD"))
    private void asmd$reloadStart(Executor prepareExecutor, Executor applyExecutor,
                                  CompletableFuture<?> initialStage, List<?> packs,
                                  CallbackInfoReturnable<ResourceReload> cir) {
        this.asmd$reloadStartNs = System.nanoTime();
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void asmd$reloadEnd(Executor prepareExecutor, Executor applyExecutor,
                                CompletableFuture<?> initialStage, List<?> packs,
                                CallbackInfoReturnable<ResourceReload> cir) {
        long start = this.asmd$reloadStartNs;
        if (start == 0L) {
            return;
        }
        this.asmd$reloadStartNs = 0L;

        long elapsed = System.nanoTime() - start;
        // Filtro: < 120 s e reload plausivel (packs grandes demoram, mas nao minutos a mais).
        if (elapsed < 120_000_000_000L) {
            ASMDJavaBridge.setResourceLoadMs(elapsed / 1_000_000.0f);
        }
    }
}
