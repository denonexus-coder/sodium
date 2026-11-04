package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDOverlayRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ASMD Overlay — hook no RETURN de InGameHud.render.
 *
 * Nesse ponto o mundo e a HUD ja estao no buffer: desenhar aqui deixa as
 * linhas por cima do mundo, na fase 2D (mesmo instante do F3), com o
 * DrawContext/estado GL corretos — sem tocar em GL manualmente e sem
 * interferir no render do mundo. Custo quando desligado: um teste.
 */
@Mixin(InGameHud.class)
public abstract class ASMDOverlayMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;F)V", at = @At("RETURN"))
    private void asmd$drawOverlay(DrawContext context, float tickDelta, CallbackInfo ci) {
        ASMDOverlayRenderer.render(context, tickDelta, net.minecraft.client.MinecraftClient.getInstance());
    }
}
