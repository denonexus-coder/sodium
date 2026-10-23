package me.jellysquid.mods.sodium.atlascache.capture;

import com.mojang.blaze3d.platform.TextureUtil;
import me.jellysquid.mods.sodium.atlascache.AtlasCacheManager;
import me.jellysquid.mods.sodium.atlascache.AtlasCacheMod;
import me.jellysquid.mods.sodium.atlascache.cache.AtlasCacheEntry;
import me.jellysquid.mods.sodium.atlascache.cache.AtlasMetadata;
import me.jellysquid.mods.sodium.atlascache.cache.CacheStore;
import me.jellysquid.mods.sodium.atlascache.cache.SpriteCacheEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Captures a finished atlas.
 *
 * <p>Two things are written for every atlas:
 *
 * <ul>
 *     <li>the image of the atlas, exactly as the renderer sees it</li>
 *     <li>the metadata of every sprite in it: where it sits, how large it is, its texture
 *     coordinates and the file it was loaded from</li>
 * </ul>
 *
 * <p>Together they are everything a texture converter needs to rebuild the atlas and to
 * map every texture back to the file it came from.
 */
public final class AtlasCapture {
    private AtlasCapture() {
    }

    /**
     * Captures the given atlas and updates the manifest.
     */
    public static void capture(SpriteAtlasTexture atlas, SpriteLoader.StitchResult result) {
        Identifier atlasId = atlas.getId();
        int width = result.width();
        int height = result.height();
        int mipLevel = result.mipLevel();
        Map<Identifier, Sprite> regions = result.regions();

        Path atlasDirectory = CacheStore.getAtlasDirectory();

        try {
            Files.createDirectories(atlasDirectory);
        } catch (IOException e) {
            AtlasCacheMod.logger().error("Could not create the atlas cache directory", e);
            return;
        }

        String baseName = CacheStore.sanitize(atlasId);

        Path png = writeAtlasImage(atlas, baseName, regions, width, height, mipLevel);

        if (png == null) {
            return;
        }

        AtlasMetadata metadata = new AtlasMetadata();
        metadata.atlas = atlasId.toString();
        metadata.width = width;
        metadata.height = height;
        metadata.mipLevel = mipLevel;

        ResourceManager manager = MinecraftClient.getInstance().getResourceManager();
        List<String> fingerprint = new ArrayList<>(regions.size());

        for (Map.Entry<Identifier, Sprite> entry : regions.entrySet()) {
            Sprite sprite = entry.getValue();
            SpriteContents contents = sprite.getContents();

            SpriteCacheEntry record = new SpriteCacheEntry();
            record.id = entry.getKey().toString();
            record.x = sprite.getX();
            record.y = sprite.getY();
            record.width = contents.width;
            record.height = contents.height;
            record.u0 = sprite.getMinU();
            record.u1 = sprite.getMaxU();
            record.v0 = sprite.getMinV();
            record.v1 = sprite.getMaxV();
            record.frameCount = contents.getFrameCount();
            record.animated = record.frameCount > 0;

            Identifier source = SourceIndex.deriveSource(entry.getKey());
            record.source = source.toString();
            record.sourceSha256 = SourceIndex.hashSource(manager, source);

            fingerprint.add(record.id + "|" + record.source + "|" + record.sourceSha256);

            metadata.sprites.add(record);
        }

        Path metadataPath = atlasDirectory.resolve(baseName + ".json");
        CacheStore.writeJson(metadataPath, metadata);

        AtlasCacheEntry cacheEntry = new AtlasCacheEntry();
        cacheEntry.atlas = atlasId.toString();
        cacheEntry.png = atlasDirectory.relativize(png).toString();
        cacheEntry.metadata = atlasDirectory.relativize(metadataPath).toString();
        cacheEntry.width = width;
        cacheEntry.height = height;
        cacheEntry.mipLevel = mipLevel;
        cacheEntry.spriteCount = metadata.sprites.size();
        cacheEntry.sourcesFingerprint = CacheStore.fingerprint(fingerprint);

        try {
            cacheEntry.pngSha256 = CacheStore.sha256(png);
            cacheEntry.metadataSha256 = CacheStore.sha256(metadataPath);
        } catch (IOException e) {
            AtlasCacheMod.logger().error("Could not hash the captured atlas", e);
            return;
        }

        AtlasCacheManager.getManifest().atlases.put(atlasId.toString(), cacheEntry);
        AtlasCacheManager.getManifest().createdAt = Instant.now().toString();
        AtlasCacheManager.getManifest().minecraftVersion = MinecraftClient.getInstance().getGameVersion();
        AtlasCacheManager.getManifest().modVersion = AtlasCacheMod.version();
        AtlasCacheManager.getManifest().resourcePackFingerprint =
                AtlasCacheManager.getResourcePackFingerprint();

        AtlasCacheManager.saveManifest();

        CacheStore.markCaptureComplete(Instant.now().toString());

        AtlasCacheMod.logger().info("Captured atlas {} ({}x{}, {} sprites)",
                atlasId, width, height, cacheEntry.spriteCount);
    }

    /**
     * Writes the image of the atlas.
     *
     * <p>The image is read back from the GPU, which is the same thing the game does when
     * it dumps an atlas. That guarantees the captured image is the one the renderer
     * actually uses, including the first frame of the animated sprites.
     *
     * <p>If the GPU cannot be read the atlas is rebuilt from the sprite images instead.
     */
    private static Path writeAtlasImage(SpriteAtlasTexture atlas, String baseName,
                                         Map<Identifier, Sprite> regions, int width, int height,
                                         int mipLevel) {
        Path atlasDirectory = CacheStore.getAtlasDirectory();
        Path png = atlasDirectory.resolve(baseName + "_0.png");

        try {
            // Remove the image of the previous run, so a failed read back can never be
            // mistaken for a fresh capture.
            Files.deleteIfExists(png);

            TextureUtil.writeAsPNG(atlasDirectory, baseName, atlas.getGlId(), mipLevel, width, height);

            if (Files.isRegularFile(png)) {
                return png;
            }
        } catch (Throwable t) {
            AtlasCacheMod.logger().warn("Could not read the atlas back from the GPU, "
                    + "falling back to the sprite images", t);
        }

        try {
            NativeImage canvas = new NativeImage(width, height, false);

            for (Sprite sprite : regions.values()) {
                SpriteContents contents = sprite.getContents();
                NativeImage image = contents.image;

                if (image == null) {
                    continue;
                }

                // The first frame of an animated sprite sits in the top left corner of the
                // image, which is also what ends up in the atlas.
                //
                // copyRect reads from the image it is called on and writes into the image
                // that is handed to it.
                image.copyRect(canvas, 0, 0, sprite.getX(), sprite.getY(),
                        contents.width, contents.height, false, false);
            }

            canvas.writeTo(png);
            canvas.close();

            return png;
        } catch (Throwable t) {
            AtlasCacheMod.logger().error("Could not write the atlas image", t);
            return null;
        }
    }
}
