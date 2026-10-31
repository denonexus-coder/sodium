package me.jellysquid.mods.sodium.mixin.features.telemetry;

import me.jellysquid.mods.sodium.client.telemetry.InfinitiumTelemetry;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Infinitium Telemetry - cadencia de entrega de chunks ao cliente.
 *
 * scheduleRenderChunk e o ponto nomeado (Yarn 1.20.1, descritor verificado no
 * yarn-1.20.1+build.10: {@code (Lnet/minecraft/class_2818;II)V} = WorldChunk,
 * int, int) onde um chunk que acabou de chegar pelo pacote entra no pipeline do
 * render: um chamado por chunk entregue. Contamos quantos passam por segundo e
 * o intervalo entre chegadas (o "gap" da coluna Server Chunk Delivery) -- puro
 * System.nanoTime, sem tocar no payload do pacote.
 *
 * Interpretacao no overlay: com a tela sem chao e o gap em vermelho (>=40 ms)
 * enquanto o SuperDrawer esta em verde, o servidor e que esta atrasando; com o
 * gap verde e o MSH/GPU vermelhos, a montagem ou a GPU e que travam.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ChunkDeliveryMixin {
    @Inject(method = "scheduleRenderChunk(Lnet/minecraft/world/chunk/WorldChunk;II)V", at = @At("HEAD"))
    private void telemetry$onChunkDelivered(WorldChunk chunk, int x, int z, CallbackInfo ci) {
        InfinitiumTelemetry.recordChunkDelivered();
    }
}
