package me.jellysquid.mods.sodium.mixin.features.render.visual;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the whole sky pass when the player switched it off.
 *
 * <p>The sky is one geometry pass with very few quads, so the saving is small; it matters when
 * the camera looks at the horizon for a long time and the device is already at the fill-rate
 * limit. Cancelling here also removes the sun, the moon and the stars, because all of them are
 * emitted from inside this method.
 *
 * <p>This runs alongside Sodium's own sky mixin, which cancels the same method when the camera
 * is underwater. Either cancellation is enough to skip the pass.
 */
@Mixin(WorldRenderer.class)
public class SkyRendererMixin {
    @Inject(method = "renderSky(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V",
            at = @At("HEAD"), cancellable = true)
    private void sodium$skipDisabledSky(MatrixStack matrices, Matrix4f projectionMatrix, float tickDelta,
                                        Camera camera, boolean thickFog, Runnable fogCallback, CallbackInfo ci) {
        if (!SodiumClientMod.options().visual.sky) {
            ci.cancel();
        }
    }
}
