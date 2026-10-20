package me.jellysquid.mods.sodium.mixin.features.render.visual;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BlockBreakingInfo;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedSet;

/**
 * Applies the "Visual" page's block entity switches inside Sodium's own block entity loop.
 *
 * <p>Sodium renders every block entity of a visible section from {@code renderBlockEntity}, so a
 * single hook covers the vanilla renderers and keeps the same behaviour for sections and for the
 * global list (beacons and other entities that outlive their section).
 *
 * <p>Flywheel's instanced visuals never reach this method: Vanillin draws beds, campfires,
 * pistons and experimental item frames through its own renderer, so those stay visible here and
 * are switched off on the Vanillin page instead.
 */
@Mixin(SodiumWorldRenderer.class)
public class BlockEntityLimitsMixin {
    @Inject(method = "renderBlockEntity", at = @At("HEAD"), cancellable = true)
    private static void sodium$filterBlockEntity(MatrixStack matrices,
                                                 BufferBuilderStorage bufferBuilders,
                                                 Long2ObjectMap<SortedSet<BlockBreakingInfo>> blockBreakingProgressions,
                                                 float tickDelta,
                                                 VertexConsumerProvider.Immediate immediate,
                                                 double x, double y, double z,
                                                 BlockEntityRenderDispatcher dispatcher,
                                                 BlockEntity entity,
                                                 CallbackInfo ci) {
        var options = SodiumClientMod.options().visual;

        if (!options.beaconBeams && entity.getType() == BlockEntityType.BEACON) {
            ci.cancel();
            return;
        }

        if (!options.enchantingTableBook && entity.getType() == BlockEntityType.ENCHANTING_TABLE) {
            ci.cancel();
            return;
        }

        if (options.blockEntityDistance < 100) {
            int viewDistance = MinecraftClient.getInstance().options.getClampedViewDistance();
            double limit = viewDistance * 16.0 * (options.blockEntityDistance / 100.0);

            double dx = (entity.getPos().getX() + 0.5) - x;
            double dy = (entity.getPos().getY() + 0.5) - y;
            double dz = (entity.getPos().getZ() + 0.5) - z;

            if (dx * dx + dy * dy + dz * dz > limit * limit) {
                ci.cancel();
            }
        }
    }
}
