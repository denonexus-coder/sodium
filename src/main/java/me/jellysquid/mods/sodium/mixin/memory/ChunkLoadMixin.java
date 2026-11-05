package me.jellysquid.mods.sodium.mixin.memory;

import me.jellysquid.mods.sodium.memory.AIMemoryEngineV3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.chunk.Chunk;

/**
 * Registra alocações de chunk quando um chunk é carregado/construído.
 * Injetado no construtor ou método de carregamento de Chunk.
 */
@SuppressWarnings("unused")
@Mixin(Chunk.class)
public abstract class ChunkLoadMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onChunkConstruct(CallbackInfo ci) {
        // Cada chunk consome aproximadamente 16*16*256 bytes de dados de bloco
        // mais sobrecarga de objetos Java. Registramos uma estimativa conservadora.
        AIMemoryEngineV3.mixinRecordAlloc(65536L); // 64 KB por chunk carregado
    }
}
