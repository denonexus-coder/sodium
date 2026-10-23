package me.jellysquid.mods.sodium.atlascache.restore;

import me.jellysquid.mods.sodium.atlascache.AtlasCacheManager;
import me.jellysquid.mods.sodium.atlascache.AtlasCacheMod;
import me.jellysquid.mods.sodium.atlascache.cache.AtlasCacheEntry;
import me.jellysquid.mods.sodium.atlascache.cache.AtlasMetadata;
import me.jellysquid.mods.sodium.atlascache.cache.CacheManifest;
import me.jellysquid.mods.sodium.atlascache.cache.CacheStore;
import me.jellysquid.mods.sodium.atlascache.cache.SpriteCacheEntry;
import me.jellysquid.mods.sodium.atlascache.capture.SourceIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Rebuilds an atlas from the cache.
 *
 * <p>The result is the same object the atlas loader would have produced on its own: the
 * same size, the same sprites at the same places and the same texture coordinates. The
 * only difference is that the images come from the cache instead of from the resource
 * packs, so the atlas does not have to be stitched again.
 */
public final class AtlasRestore {
    private AtlasRestore() {
    }

    /**
     * Tries to restore the given atlas from the cache.
     *
     * @return the restored atlas, or an empty optional when the cache cannot be used
     */
    public static Optional<SpriteLoader.StitchResult> tryRestore(ResourceManager manager, Identifier atlasId) {
        if (!AtlasCacheManager.shouldSkipAtlasCreation()) {
            return Optional.empty();
        }

        CacheManifest manifest = AtlasCacheManager.getManifest();

        if (manifest.formatVersion != CacheStore.FORMAT_VERSION) {
            return Optional.empty();
        }

        if (!CacheStore.isCaptureComplete()) {
            return Optional.empty();
        }

        if (!manifest.minecraftVersion.equals(MinecraftClient.getInstance().getGameVersion())) {
            return Optional.empty();
        }

        if (!manifest.resourcePackFingerprint.equals(AtlasCacheManager.getResourcePackFingerprint())) {
            return Optional.empty();
        }

        AtlasCacheEntry entry = manifest.atlases.get(atlasId.toString());

        if (entry == null) {
            return Optional.empty();
        }

        Path png = CacheStore.getDirectory().resolve(entry.png);
        Path metadataPath = CacheStore.getDirectory().resolve(entry.metadata);

        if (!Files.isRegularFile(png) || !Files.isRegularFile(metadataPath)) {
            return Optional.empty();
        }

        try {
            if (!CacheStore.sha256(png).equals(entry.pngSha256)) {
                return Optional.empty();
            }

            if (!CacheStore.sha256(metadataPath).equals(entry.metadataSha256)) {
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }

        AtlasMetadata metadata = CacheStore.readJson(metadataPath, AtlasMetadata.class);

        if (AtlasCacheManager.shouldValidateSources()
                && !entry.sourcesFingerprint.equals(computeSourcesFingerprint(manager, metadata))) {
            AtlasCacheMod.logger().info("The resources of {} changed, the cache is not used", atlasId);
            return Optional.empty();
        }

        try {
            return Optional.of(restore(manager, atlasId, metadata, png));
        } catch (Throwable t) {
            AtlasCacheMod.logger().error("Could not restore the atlas {} from the cache", atlasId, t);
            return Optional.empty();
        }
    }

    /**
     * Builds the atlas from the cached image and the cached sprite list.
     */
    private static SpriteLoader.StitchResult restore(ResourceManager manager, Identifier atlasId,
                                                     AtlasMetadata metadata, Path png) throws IOException {
        NativeImage atlasImage;

        try (InputStream input = Files.newInputStream(png)) {
            atlasImage = NativeImage.read(input);
        }

        Map<Identifier, Sprite> regions = new LinkedHashMap<>(metadata.sprites.size());

        try {
            for (SpriteCacheEntry record : metadata.sprites) {
                Identifier spriteId = new Identifier(record.id);

                SpriteContents contents = null;

                // An animated sprite has to be loaded from the resource pack again, because the
                // cache only holds the first frame of it. There are only a handful of those.
                if (record.animated) {
                    contents = loadAnimated(manager, spriteId, record);
                }

                if (contents == null) {
                    contents = slice(atlasImage, record);
                }

                Sprite sprite = new Sprite(atlasId, contents, metadata.width, metadata.height,
                        record.x, record.y);

                regions.put(spriteId, sprite);
            }
        } finally {
            atlasImage.close();
        }

        if (metadata.mipLevel > 0) {
            for (Sprite sprite : regions.values()) {
                sprite.getContents().generateMipmaps(metadata.mipLevel);
            }
        }

        Sprite missing = regions.get(MissingSprite.getMissingSpriteId());

        AtlasCacheMod.logger().info("Restored the atlas {} from the cache ({}x{}, {} sprites)",
                atlasId, metadata.width, metadata.height, regions.size());

        return new SpriteLoader.StitchResult(metadata.width, metadata.height, metadata.mipLevel, missing,
                regions, CompletableFuture.completedFuture(null));
    }

    /**
     * Loads an animated sprite from the resource pack so that it keeps animating.
     */
    private static SpriteContents loadAnimated(ResourceManager manager, Identifier spriteId,
                                               SpriteCacheEntry record) {
        if (record.source.isEmpty()) {
            return null;
        }

        List<Resource> resources = manager.getAllResources(new Identifier(record.source));

        if (resources.isEmpty()) {
            return null;
        }

        try {
            return SpriteLoader.load(spriteId, resources.get(0));
        } catch (Throwable t) {
            AtlasCacheMod.logger().warn("Could not load the animated sprite {} from the resource pack",
                    spriteId, t);
            return null;
        }
    }

    /**
     * Cuts the pixels of a sprite out of the cached atlas image.
     */
    private static SpriteContents slice(NativeImage atlasImage, SpriteCacheEntry record) {
        NativeImage image = new NativeImage(record.width, record.height, false);

        // copyRect reads from the image it is called on and writes into the image that is
        // handed to it.
        atlasImage.copyRect(image, record.x, record.y, 0, 0, record.width, record.height, false, false);

        return new SpriteContents(new Identifier(record.id), new SpriteDimensions(record.width, record.height),
                image, AnimationResourceMetadata.EMPTY);
    }

    /**
     * Hashes the resources the atlas was built from.
     *
     * <p>Only the bytes of the source files are read, they are never decoded. That is much
     * cheaper than building the atlas, but it still detects a resource pack that changed.
     */
    private static String computeSourcesFingerprint(ResourceManager manager, AtlasMetadata metadata) {
        List<String> parts = new ArrayList<>(metadata.sprites.size());

        for (SpriteCacheEntry record : metadata.sprites) {
            String hash = "";

            if (!record.source.isEmpty()) {
                hash = SourceIndex.hashSource(manager, new Identifier(record.source));
            }

            parts.add(record.id + "|" + record.source + "|" + hash);
        }

        return CacheStore.fingerprint(parts);
    }
}
