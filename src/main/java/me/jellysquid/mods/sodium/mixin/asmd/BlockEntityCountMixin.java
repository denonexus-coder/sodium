package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ASMD — conta uma tile entity por frame real (alimenta o offset 80).
 *
 * <p>{@code BlockEntityRenderDispatcher.render(BlockEntity, float, MatrixStack,
 * VertexConsumerProvider)} (method_3555) é o funil único do desenho de block
 * entities: tanto os loops do {@code WorldRenderer.render} quanto o
 * {@code SodiumWorldRenderer.renderBlockEntity} passam por ele. Um incremento
 * por chamada = uma block entity de fato despachada naquele frame.
 *
 * <p>O contador é zerado no HEAD de {@code WorldRenderer.render} (ver
 * {@link EntityRendererMixin}) e lido no RETURN dele, então só conta o que
 * aconteceu dentro do frame atual — nada vaza de frames anteriores nem de
 * renderização fora da cena.
 *
 * <p>Descritor completo porque {@code render} é sobrecarregado: a outra
 * sobrecarga (method_23079) tem 5 argumentos e levaria a contagem errada.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCountMixin {
    @Inject(method = "render(Lnet/minecraft/block/entity/BlockEntity;F" +
                    "Lnet/minecraft/client/util/math/MatrixStack;" +
                    "Lnet/minecraft/client/render/VertexConsumerProvider;)V", at = @At("HEAD"))
    private void asmd$onRenderBlockEntity(BlockEntity entity, float tickDelta, MatrixStack matrices,
                                          VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
        ASMDJavaBridge.countTileEntity();
    }
}