package me.jellysquid.mods.sodium.atlascache;

import me.jellysquid.mods.sodium.atlascache.cache.CacheConfig;
import me.jellysquid.mods.sodium.atlascache.cache.CacheManifest;
import me.jellysquid.mods.sodium.atlascache.cache.CacheStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the atlas cache.
 *
 * <p>The mod does not change how the game renders anything. It only observes the atlas
 * construction, writes the result to disk and, on the next run, feeds the cached result
 * back into the atlas loader so the atlas does not have to be built again.
 */
public class AtlasCacheMod implements ClientModInitializer {
    public static final String MOD_ID = "sodium_atlas_cache";

    private static final Logger LOGGER = LoggerFactory.getLogger("SodiumAtlasCache");

    @Override
    public void onInitializeClient() {
        AtlasCacheManager.initialize();

        CacheConfig config = AtlasCacheManager.getConfig();
        CacheManifest manifest = AtlasCacheManager.getManifest();

        LOGGER.info("Atlas cache directory: {}", CacheStore.getDirectory());
        LOGGER.info("Cached atlases: {}", manifest.atlases.size());
        LOGGER.info("Atlas creation is {}",
                config.skipAtlasCreation ? "skipped when the cache is valid" : "always performed");
    }

    public static Logger logger() {
        return LOGGER;
    }

    public static String version() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
