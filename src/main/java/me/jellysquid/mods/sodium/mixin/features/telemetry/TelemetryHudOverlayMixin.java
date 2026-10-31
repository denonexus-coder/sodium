package me.jellysquid.mods.sodium.mixin.features.telemetry;

import me.jellysquid.mods.sodium.client.telemetry.InfinitiumTelemetry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Infinitium Telemetry - desenho do overlay.
 *
 * No RETURN do InGameHud.render o mundo e todos os widgets ja estao no buffer:
 * desenhar aqui deixa as linhas por cima da HUD, na fase 2D (o mesmo instante
 * em que o HudReportPhase do framegen marca "HUD comecou"). Custo com o overlay
 * desligado: um teste de boolean; ligado: ~6 drawTextWithShadow por frame
 * (mesma ordem de custo do proprio F3).
 */
@Mixin(InGameHud.class)
public class TelemetryHudOverlayMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;F)V", at = @At("RETURN"))
    private void telemetry$drawOverlay(DrawContext context, float tickDelta, CallbackInfo ci) {
        InfinitiumTelemetry.renderOverlay(context);
    }
}
