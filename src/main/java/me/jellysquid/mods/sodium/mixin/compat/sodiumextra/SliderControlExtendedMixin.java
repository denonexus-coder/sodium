package me.jellysquid.mods.sodium.mixin.compat.sodiumextra;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Sodium Extra 0.5.9 injeta na página General do Sodium o slider "Fullscreen
// Resolution" construido com
//
//     max = window.getMonitor() != null ? window.getMonitor().getVideoModeCount() : 0
//
// No Android nao ha monitor nem video modes: max chega a 0, igual ao min 0, e o
// proprio construtor de SliderControlExtended lanca
// "IllegalArgumentException: The maximum value must be greater than the minimum
// value". Como a página General e construida dentro do construtor do
// SodiumOptionsGUI, esse throw mata TODA a tela de opcoes do Sodium antes de
// abrir -- nenhum interruptor (FSR1, frame generation, coalescing...) fica
// acessivel.
//
// Este mixin sobe qualquer maximo nao positivo para 1: o slider continua
// interativo (0..1), o formatador de resolucao ja trata monitor == null com a
// string "fullscreen.unavailable", o binding de resolucao e no-op sem monitor e
// a divisao do proprio render do slider tem divisor 1. Se o Sodium Extra nao
// existir, @Pseudo salta o mixin em silencio.
@Pseudo
@Mixin(targets = "me.flashyreese.mods.sodiumextra.client.gui.options.control.SliderControlExtended", remap = false)
public class SliderControlExtendedMixin {
    // argsOnly: entre os argumentos int (min, max, interval), o ordinal 1 e o max.
    // require = 0: se uma versao futura mudar a assinatura, fica sem o fix em vez
    // de rebentar o arranque -- o efeito e entao o crash original do Sodium Extra.
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private int sodiumextra$clampDegenerateUpperBound(int max) {
        return Math.max(max, 1);
    }
}
