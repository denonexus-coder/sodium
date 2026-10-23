package me.jellysquid.mods.sodium.atlascache.capture;

import me.jellysquid.mods.sodium.atlascache.cache.CacheStore;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Resolves the resource a sprite was loaded from.
 *
 * <p>Minecraft stitches the atlases from plain texture files. The name of a sprite
 * follows the path of that file, so the source of a sprite can be derived from its id:
 * {@code minecraft:block/stone} comes from {@code minecraft:textures/block/stone.png}.
 */
public final class SourceIndex {
    private SourceIndex() {
    }

    /**
     * Derives the resource a sprite was loaded from.
     */
    public static Identifier deriveSource(Identifier spriteId) {
        return new Identifier(spriteId.getNamespace(), "textures/" + spriteId.getPath() + ".png");
    }

    /**
     * Hashes the bytes of every resource that provides the given file.
     *
     * <p>Only the bytes are hashed, the file is never decoded. That keeps the validation
     * of the cache far cheaper than building the atlas itself.
     *
     * @return the hash of the source, or an empty string when the source does not exist
     */
    public static String hashSource(ResourceManager manager, Identifier source) {
        List<Resource> resources = manager.getAllResources(source);

        if (resources.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        for (Resource resource : resources) {
            try (InputStream input = resource.getInputStream()) {
                builder.append(CacheStore.sha256(input)).append(',');
            } catch (IOException e) {
                return "";
            }
        }

        return builder.toString();
    }
}
