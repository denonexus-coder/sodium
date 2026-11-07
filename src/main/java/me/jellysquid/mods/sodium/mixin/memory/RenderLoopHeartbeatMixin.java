package me.jellysquid.mods.sodium.mixin.memory;

import me.jellysquid.mods.sodium.memory.AIMemoryEngineV3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.MinecraftClient;

/**
 * Injeta heartbeat e ciclo de monitorização no loop de renderização do Minecraft.
 * Executado a cada frame — impacto mínimo, apenas leitura de métricas.
 */
@SuppressWarnings("unused")
@Mixin(MinecraftClient.class)
public abstract class RenderLoopHeartbeatMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTick(CallbackInfo ci) {
        if (AIMemoryEngineV3.isRunning()) {
            AIMemoryEngineV3.heartbeat();
            // Roda um ciclo de monitorização a cada N frames (não a cada frame)
            // para não sobrecarregar a thread principal.
            if (ci != null) {
                // Throttling inline (a classe helper RenderLoopThrottle ficaria
                // dentro do pacote de mixins e é rejeitada pelo Mixin:
                // "in a defined mixin package ... cannot be referenced directly").
                counter++;
                if (counter >= 60) { // ~1 vez por segundo a 60 FPS
                    counter = 0;
                    AIMemoryEngineV3.monitorCycle();
                }
            }
        }
    }

    private static int counter = 0;
}
