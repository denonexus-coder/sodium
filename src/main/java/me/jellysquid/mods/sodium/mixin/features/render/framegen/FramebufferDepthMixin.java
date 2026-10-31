package me.jellysquid.mods.sodium.mixin.features.render.framegen;

import me.jellysquid.mods.sodium.client.render.framegen.FrameGenBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin C da especificacao Infinitium FrameGen - profundidade (recriacao).
 *
 * Quando a superficie muda (resize/rotacao), o Minecraft destroi e recria os
 * attachments do framebuffer principal. Este mixin entrega o novo id de
 * profundidade ao MobileGlues no RETURN do resize, ja com o FBO reconstruido.
 *
 * Instancias que nao sao o framebuffer principal (mapa, sombras, etc.) sao
 * ignoradas: quem decide e a identidade do objeto, nao o tamanho.
 */
@Mixin(Framebuffer.class)
public class FramebufferDepthMixin {
    @Inject(method = "resize(IIZ)V", at = @At("RETURN"))
    private void framegen$onMainFramebufferResize(int width, int height, boolean getError, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.getFramebuffer() != (Framebuffer) (Object) this) {
            return;
        }

        FrameGenBridge.onDepthAttachment(((Framebuffer) (Object) this).getDepthAttachment());
    }
}
