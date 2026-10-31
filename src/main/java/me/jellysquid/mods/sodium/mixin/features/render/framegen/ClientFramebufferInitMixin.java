package me.jellysquid.mods.sodium.mixin.features.render.framegen;

import me.jellysquid.mods.sodium.client.render.framegen.FrameGenBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin C da especificacao Infinitium FrameGen - profundidade (criacao).
 *
 * O framebuffer principal nasce dentro do construtor do MinecraftClient, um
 * instante ANTES de existir qualquer mundo (e portanto antes de todo o resto).
 * O RETURN do construtor e o primeiro ponto em que getInstance().getFramebuffer()
 * e confiavel, entao e aqui que o id de profundidade inicial e entregue; os
 * recriados depois disso sao cobertos pelo FramebufferDepthMixin.
 *
 * Nao ha toque de GL neste ponto (o contexto ainda nao garantidamente existe):
 * so guardamos o inteiro; a validacao do tipo (textura vs renderbuffer) acontece
 * la dentro do fg_jni_bridge.cpp na primeira captura de matrizes, com o
 * contexto do render corrente.
 */
@Mixin(MinecraftClient.class)
public class ClientFramebufferInitMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void framegen$sendInitialDepthAttachment(CallbackInfo ci) {
        MinecraftClient self = (MinecraftClient) (Object) this;
        Framebuffer framebuffer = self.getFramebuffer();

        if (framebuffer != null) {
            FrameGenBridge.onDepthAttachment(framebuffer.getDepthAttachment());
        }
    }
}
