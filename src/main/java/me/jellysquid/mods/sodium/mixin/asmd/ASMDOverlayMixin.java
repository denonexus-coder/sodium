package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDOverlayRenderer;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ASMD Overlay Toggle — F8 liga/desliga o overlay do motor adaptativo.
 *
 * Custo quando F8 não é pressionado: um teste de tecla por evento
 * (não por frame — GLFW só entrega eventos quando há input).
 */
@Mixin(Keyboard.class)
public abstract class ASMDOverlayMixin {
    @Inject(method = "onKey", at = @At("HEAD"))
    private void asmd$onKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        // Só no jogo (tela nula), apenas no pressionar (não repetir)
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null) return;
        if (action != GLFW.GLFW_PRESS) return;

        if (key == GLFW.GLFW_KEY_F8) {
            ASMDOverlayRenderer.toggle();
        }
    }
}
