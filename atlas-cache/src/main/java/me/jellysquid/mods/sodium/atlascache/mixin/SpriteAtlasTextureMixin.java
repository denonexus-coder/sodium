package me.jellysquid.mods.sodium.atlascache.mixin;

import me.jellysquid.mods.sodium.atlascache.AtlasCacheManager;
import me.jellysquid.mods.sodium.atlascache.AtlasCacheMod;
import me.jellysquid.mods.sodium.atlascache.capture.AtlasCapture;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the atlas once it has been uploaded.
 *
 * <p>At this point the atlas is complete: every sprite is in the texture and the map of
 * the sprites is filled, so both the image and the metadata can be read.
 */
@Mixin(SpriteAtlasTexture.class)
public class SpriteAtlasTextureMixin {
    @Inject(method = "upload", at = @At("RETURN"))
    private void atlasCache$capture(SpriteLoader.StitchResult result, CallbackInfo ci) {
        if (!AtlasCacheManager.isEnabled()) {
            return;
        }

        SpriteAtlasTexture atlas = (SpriteAtlasTexture) (Object) this;

        if (AtlasCacheManager.wasRestored(atlas.getId().toString())) {
            // The atlas was rebuilt from the cache, so there is nothing new to capture.
            return;
        }

        try {
            AtlasCapture.capture(atlas, result);
        } catch (Throwable t) {
            AtlasCacheMod.logger().error("Could not capture the atlas {}", atlas.getId(), t);
        }
    }
}
