package me.jellysquid.mods.sodium.mixin.memory;

import me.jellysquid.mods.sodium.memory.AIMemoryEngineV3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.render.BufferBuilder;

/**
 * Registra alocações de buffers de vértice no BufferBuilder do Sodium.
 * Esses buffers são alocados frequentemente durante a renderização de chunks.
 */
@SuppressWarnings("unused")
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderAllocMixin {

    @Inject(method = "begin", at = @At("HEAD"))
    private void onBufferBegin(int vertexFormat, int drawMode, CallbackInfo ci) {
        // Estimativa: um BufferBuilder típico aloca arrays de vértice
        // registramos um valor conservador por chamada de begin()
        AIMemoryEngineV3.mixinRecordAlloc(8192L); // 8 KB por begin
    }
}
