package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import me.jellysquid.mods.sodium.client.render.asmd.ASMDOverlayRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ASMD Overlay — hook no RenderLoopMixin para desenhar o HUD a cada frame.
 * Não afeta performance (leitura direta do buffer, 1 draw pass).
 */
@Mixin(GameRenderer.class)
public abstract class ASMDOverlayMixin {
    @Inject(method = "render(FJZ)V", at = @At("TAIL"))
    private void asmd$onRenderTail(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null) {
            ASMDOverlayRenderer.render(new MatrixStack(), tickDelta, client);
        }
    }
}
