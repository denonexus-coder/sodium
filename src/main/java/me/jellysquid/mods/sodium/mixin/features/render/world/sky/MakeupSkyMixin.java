package me.jellysquid.mods.sodium.mixin.features.render.world.sky;

import me.jellysquid.mods.sodium.client.render.MakeupProfile;
import me.jellysquid.mods.sodium.client.render.sky.MakeupSkyRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.CameraSubmersionType;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's sky with the profile's single full-screen gradient. It runs at HEAD and
 * cancels only after the program actually drew, so a driver that rejects the shader falls back
 * to the vanilla sky instead of leaving a black screen.
 */
@Mixin(WorldRenderer.class)
public class MakeupSkyMixin {
    @Inject(method = "renderSky(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V",
            at = @At("HEAD"), cancellable = true)
    private void sodium$renderMakeupSky(MatrixStack matrices, Matrix4f projectionMatrix, float tickDelta,
                                        Camera camera, boolean thickFog, Runnable fogCallback, CallbackInfo ci) {
        if (thickFog || camera.getSubmersionType() == CameraSubmersionType.WATER) {
            return;
        }

        if (!MakeupProfile.isSkyActive()) {
            return;
        }

        if (MakeupSkyRenderer.render(matrices, projectionMatrix, tickDelta, camera)) {
            ci.cancel();
        }
    }
}
