package me.jellysquid.mods.sodium.mixin.features.telemetry;

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
 */
@Mixin(MinecraftServer.class)
public class ServerTickMixin {
    @Unique
    private long telemetry$tickStart;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void telemetry$onTickStart(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (InfinitiumTelemetry.isEnabled()) {
            this.telemetry$tickStart = System.nanoTime();
        }
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void telemetry$onTickEnd(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        long start = this.telemetry$tickStart;
        this.telemetry$tickStart = 0L;

        if (start != 0L) {
            InfinitiumTelemetry.recordServerTickMs((System.nanoTime() - start) / 1.0e6);
        }
    }
}
