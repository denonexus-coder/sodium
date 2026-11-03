package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ASMD — contagem de entidades visíveis (offset 76) e de tile entities (80).
 *
 * <p><b>Entidades (76):</b> {@code regularEntityCount} ({@code field_4089}) é o
 * contador que o próprio {@code WorldRenderer.render} incrementa uma vez por
 * entidade que passou na culling e foi desenhada. É dado real do vanilla, não
 * estimativa.
 *
 * <p><b>Tile entities (80):</b> o {@code blockEntityCount} do vanilla
 * ({@code field_4110}) é <b>sempre zero</b> — só é zerado no início do
 * {@code render} e nunca incrementado em lugar nenhum (3 referências em todo o
 * bytecode, nenhuma nas classes internas). Por isso contamos as block entities
 * de verdade no {@code BlockEntityRenderDispatcher.render}, que é o funil único
 * por onde passa todo desenho de block entity.
 *
 * <p>Ordem dentro do frame: HEAD zera o contador → as block entities são
 * despachadas e incrementam → no RETURN lemos as duas contagens.
 */
@Mixin(WorldRenderer.class)
public abstract class EntityRendererMixin {
    @Shadow
    private int regularEntityCount;

    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;FJZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;)V", at = @At("HEAD"))
    private void asmd$onRenderHead(MatrixStack matrices, float tickDelta, long limitTime,
                                   boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                   LightmapTextureManager lightmap, Matrix4f positionMatrix, CallbackInfo ci) {
        ASMDJavaBridge.beginTileEntityFrame();
    }

    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;FJZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;)V", at = @At("RETURN"))
    private void asmd$onRenderReturn(MatrixStack matrices, float tickDelta, long limitTime,
                                     boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                     LightmapTextureManager lightmap, Matrix4f positionMatrix, CallbackInfo ci) {
        ASMDJavaBridge.setVisibleEntities(this.regularEntityCount);
        ASMDJavaBridge.setVisibleTileEntities(ASMDJavaBridge.tileEntityFrameCount());
    }
}