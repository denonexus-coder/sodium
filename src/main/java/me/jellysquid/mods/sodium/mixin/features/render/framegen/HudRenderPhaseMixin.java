package me.jellysquid.mods.sodium.mixin.features.render.framegen;

import me.jellysquid.mods.sodium.client.render.framegen.FrameGenBridge;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin B da especificacao Infinitium FrameGen - separacao de fase do HUD.
 *
 * O InGameHud.render desenha por ultimo, por cima do mundo 3D. O MobileGlues
 * precisa saber EXATAMENTE quando isso comeca e termina para, no futuro,
 * interpolar apenas o mundo e compor o HUD estatico por cima -- acabando com
 * os "fantasmas" em texto, coracoes, hotbar e mira quando a camera gira.
 *
 * O custo e zero: duas chamadas JNI de uma flag por frame (a de escrita e um
 * inteiro no lado nativo, sem GL nenhum).
 */
@Mixin(InGameHud.class)
public class HudRenderPhaseMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;F)V", at = @At("HEAD"))
    private void framegen$onHudStart(DrawContext context, float tickDelta, CallbackInfo ci) {
        FrameGenBridge.onRenderHudPhase(true);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;F)V", at = @At("RETURN"))
    private void framegen$onHudEnd(DrawContext context, float tickDelta, CallbackInfo ci) {
        FrameGenBridge.onRenderHudPhase(false);
    }
}
