package me.jellysquid.mods.sodium.mixin.features.telemetry;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import me.jellysquid.mods.sodium.client.telemetry.InfinitiumTelemetry;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Infinitium Telemetry - MSPT do servidor integrado (tecnica do spark).
 *
 * O spark faz este mesmo par com ServerTickEvents.START/END_SERVER_TICK do
 * fabric-lifecycle-events -- dependencia que o fork nao tem e cuja presenca no
 * instance nao da para garantir. Este mixin mede exatamente o mesmo intervalo
 * (HEAD -> RETURN de MinecraftServer.tick, o tick completo uma vez por loop),
 * com o mesmo custo: dois System.nanoTime() por tick (20/s), zero misturado no
 * caminho de render.
 *
 * Em multiplayer a Server thread nao existe no cliente: o SRV fica — (sem
 * servidor local) e as outras linhas continuam valendo.
 *
 * Alem da HUD de telemetria, o mesmo intervalo alimenta a ASMD (offsets 0 e 4
 * do buffer de 128 bytes): o co-processador corta o merge de sub-draws quando o
 * server tick passa de 35 ms, entao precisa do MSPT mesmo com a HUD desligada.
 * Por isso a medição roda sempre -- a sobrecarga continua sendo os mesmos dois
 * nanoTime por tick, e o {@link InfinitiumTelemetry} mantem o proprio gate de
 * {@code enabled} la dentro.
 */
@Mixin(MinecraftServer.class)
public class ServerTickMixin {
    @Unique
    private long telemetry$tickStart;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void telemetry$onTickStart(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        this.telemetry$tickStart = System.nanoTime();
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void telemetry$onTickEnd(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        long start = this.telemetry$tickStart;
        this.telemetry$tickStart = 0L;

        if (start == 0L) {
            return;
        }

        float ms = (float) ((System.nanoTime() - start) / 1.0e6);

        ASMDJavaBridge.setServerTickTimeMs(ms);
        // 1000 ms = 20 TPS; acima de 20 o servidor nao consegue, entao trava no
        // maximo. ms <= 0.05 nao divide por zero (guarda contra tick degenerado).
        ASMDJavaBridge.setServerTps(ms > 0.05f ? Math.min(20.0f, 1000.0f / ms) : 20.0f);

        InfinitiumTelemetry.recordServerTickMs(ms);
    }
}