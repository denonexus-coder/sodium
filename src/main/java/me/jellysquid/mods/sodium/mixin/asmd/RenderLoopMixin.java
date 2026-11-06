package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.afk.AFKDetector;
import me.jellysquid.mods.sodium.client.render.asmd.ASDMJavaBridge;
import me.jellysquid.mods.sodium.client.render.asmd.ASDMNativeBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ASMD — origem do frame clock (offset 84) e aviso ao co-processador C++.
 *
 * <p>{@code GameRenderer.render(float, long, boolean)} (method_3192) é o
 * único ponto por frame em que a imagem começa a ser composta. No HEAD dele
 * gravamos o timestamp que o motor ASMD usa para medir o tempo de frame, e —
 * se a ponte JNI estiver viva — chamamos {@code notifyFrameStart()} para o
 * {@code CognitiveBrain} dar o {@code on_frame_start()} dele.
 *
 * <p>Detecção de AFK: se o jogador estiver parado por {@code AFKDetector},
 * dispara o bench ultrarrápido (<10s) para recalibrar a tabela de decisão
 * de desenho (Merge / UBO_Meu / UBO_MG / Unroll / Instanced).
 *
 * <p>Uma gravação + no máximo uma chamada JNI por frame, zero alocação.
 */
@Mixin(GameRenderer.class)
public abstract class RenderLoopMixin {
    @Inject(method = "render(FJZ)V", at = @At("HEAD"))
    private void asmd$onRenderHead(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        ASDMJavaBridge.setFrameClockOriginNs(System.nanoTime());

        // --- Detecção de AFK + bench ultrarrápido (<10s) ---
        // Se o jogador está parado (câmera sem movimento), o C++ precisa
        // recalibrar a tabela de decisão de desenho para manter 60 FPS.
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getCamera() != null) {
            var pos = client.getCamera().getPos();
            float yaw = client.getCamera().getYaw();
            float pitch = client.getCamera().getPitch();
            if (AFKDetector.update((float) pos.x, (float) pos.y, (float) pos.z, yaw, pitch)) {
                // Jogador entrou em AFK — disparar bench em background
                AFKBenchRunner.runBench();
            }
        }

        if (ASDMNativeBridge.isSupported()) {
            ASDMNativeBridge.notifyFrameStart();
        }
    }
}
