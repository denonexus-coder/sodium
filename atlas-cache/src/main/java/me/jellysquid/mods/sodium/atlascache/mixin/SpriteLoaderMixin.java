package me.jellysquid.mods.sodium.atlascache.mixin;

import me.jellysquid.mods.sodium.atlascache.AtlasCacheManager;
import me.jellysquid.mods.sodium.atlascache.AtlasCacheMod;
import me.jellysquid.mods.sodium.atlascache.restore.AtlasRestore;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Feeds the cached atlas back into the atlas loader.
 *
 * <p>This is the hook that skips the atlas creation. When the cache is valid the loader
 * never reads a texture, never decodes a PNG and never runs the stitcher: the atlas is
 * rebuilt from the cached image and the cached sprite list instead.
 */
@Mixin(SpriteLoader.class)
public class SpriteLoaderMixin {
    @Inject(
            method = "load(Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/util/Identifier;ILjava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void atlasCache$restore(ResourceManager resourceManager, Identifier atlasId, int maxSize,
                                    Executor executor,
                                    CallbackInfoReturnable<CompletableFuture<SpriteLoader.StitchResult>> cir) {
        if (!AtlasCacheManager.shouldSkipAtlasCreation()) {
            return;
        }

        Optional<SpriteLoader.StitchResult> restored;

        try {
            restored = AtlasRestore.tryRestore(resourceManager, atlasId);
        } catch (Throwable t) {
            AtlasCacheMod.logger().error("Could not restore the atlas {} from the cache", atlasId, t);
            return;
        }

        if (restored.isPresent()) {
            AtlasCacheManager.markRestored(atlasId.toString());
            cir.setReturnValue(CompletableFuture.completedFuture(restored.get()));
        }
    }
}
