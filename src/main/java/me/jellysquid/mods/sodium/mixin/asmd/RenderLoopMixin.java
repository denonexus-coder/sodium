package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import me.jellysquid.mods.sodium.client.render.asmd.ASMDNativeBridge;
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
 * <p>Uma gravação + no máximo uma chamada JNI por frame, zero alocação.
 */
@Mixin(GameRenderer.class)
public abstract class RenderLoopMixin {
    @Inject(method = "render(FJZ)V", at = @At("HEAD"))
    private void asmd$onRenderHead(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        ASMDJavaBridge.setFrameClockOriginNs(System.nanoTime());

        if (ASMDNativeBridge.isSupported()) {
            ASMDNativeBridge.notifyFrameStart();
        }
    }
}