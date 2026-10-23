package me.jellysquid.mods.sodium.atlascache.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Reads and writes everything that lives in the cache directory.
 *
 * <p>The layout is:
 *
 * <pre>
 * .minecraft/mobileglues-atlas-cache/
 *   config.json
 *   manifest.json
 *   atlases/
 *     minecraft_textures_atlas_blocks_0.png
 *     minecraft_textures_atlas_blocks.json
 *     ...
 * </pre>
 */
public final class CacheStore {
    /**
     * The version of the on-disk format.
     */
    public static final int FORMAT_VERSION = 1;

    private static final String DIRECTORY_NAME = "mobileglues-atlas-cache";
    private static final String CONFIG_NAME = "config.json";
    private static final String MANIFEST_NAME = "manifest.json";
    private static final String COMPLETION_NAME = "capture_complete";
    private static final String ATLAS_DIRECTORY = "atlases";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private CacheStore() {
    }

    /**
     * The root of the cache directory.
     */
    public static Path getDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve(DIRECTORY_NAME);
    }

    /**
     * The directory that holds the captured atlases.
     */
    public static Path getAtlasDirectory() {
        return getDirectory().resolve(ATLAS_DIRECTORY);
    }

    /**
     * The path of the manifest.
     */
    public static Path getManifestPath() {
        return getDirectory().resolve(MANIFEST_NAME);
    }

    /**
     * The path of the configuration file.
     */
    public static Path getConfigPath() {
        return getDirectory().resolve(CONFIG_NAME);
    }

    /**
     * The path of the marker that says the capture ran to the end.
     *
     * <p>Without it the cache is never used, even if the manifest happens to be there.
     */
    public static Path getCompletionMarkerPath() {
        return getDirectory().resolve(COMPLETION_NAME);
    }

    /**
     * Whether the capture ran to the end at least once.
     */
    public static boolean isCaptureComplete() {
        return Files.isRegularFile(getCompletionMarkerPath());
    }

    /**
     * Marks the capture as complete.
     */
    public static void markCaptureComplete(String note) {
        try {
            Path path = getCompletionMarkerPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, note == null ? "" : note, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + getCompletionMarkerPath(), e);
        }
    }

    /**
     * Turns an atlas identifier into a file name that is safe on every file system.
     */
    public static String sanitize(Identifier id) {
        StringBuilder builder = new StringBuilder(id.toString().length());

        for (int i = 0; i < id.toString().length(); i++) {
            char c = id.toString().charAt(i);
            builder.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }

        return builder.toString();
    }

    /**
     * Reads the configuration, falling back to the defaults when it is missing or broken.
     */
    public static CacheConfig readConfig() {
        Path path = getConfigPath();

        if (!Files.isRegularFile(path)) {
            return new CacheConfig();
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            CacheConfig config = GSON.fromJson(reader, CacheConfig.class);
            return config != null ? config : new CacheConfig();
        } catch (IOException | JsonSyntaxException e) {
            return new CacheConfig();
        }
    }

    /**
     * Writes the configuration.
     */
    public static void writeConfig(CacheConfig config) {
        writeJson(getConfigPath(), config);
    }

    /**
     * Reads the manifest, falling back to an empty one when it is missing or broken.
     */
    public static CacheManifest readManifest() {
        Path path = getManifestPath();

        if (!Files.isRegularFile(path)) {
            return new CacheManifest();
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            CacheManifest manifest = GSON.fromJson(reader, CacheManifest.class);
            return manifest != null ? manifest : new CacheManifest();
        } catch (IOException | JsonSyntaxException e) {
            return new CacheManifest();
        }
    }

    /**
     * Writes the manifest.
     */
    public static void writeManifest(CacheManifest manifest) {
        writeJson(getManifestPath(), manifest);
    }

    /**
     * Writes a JSON file. The file is written to a temporary name first and then moved
     * into place, so an interrupted write never leaves a half written file behind.
     */
    public static void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());

            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }

            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + path, e);
        }
    }

    /**
     * Reads a JSON file.
     */
    public static <T> T readJson(Path path, Class<T> type) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (IOException | JsonSyntaxException e) {
            throw new RuntimeException("Failed to read " + path, e);
        }
    }

    /**
     * The SHA-256 of a file, as lower case hexadecimal.
     */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();

        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }

        return toHex(digest.digest());
    }

    /**
     * The SHA-256 of a byte array, as lower case hexadecimal.
     */
    public static String sha256(byte[] data) {
        MessageDigest digest = newDigest();
        digest.update(data);
        return toHex(digest.digest());
    }

    /**
     * The SHA-256 of a stream. The stream is consumed but not closed.
     */
    public static String sha256(InputStream input) throws IOException {
        MessageDigest digest = newDigest();

        byte[] buffer = new byte[8192];
        int read;

        while ((read = input.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
        }

        return toHex(digest.digest());
    }

    /**
     * Hashes a list of strings in a stable order.
     */
    public static String fingerprint(Iterable<String> parts) {
        MessageDigest digest = newDigest();

        for (String part : parts) {
            digest.update(part.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }

        return toHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }

        return builder.toString();
    }
}
