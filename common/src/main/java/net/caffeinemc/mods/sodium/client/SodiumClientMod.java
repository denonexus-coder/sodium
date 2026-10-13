package net.caffeinemc.mods.sodium.client;

import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.console.Console;
import net.caffeinemc.mods.sodium.client.console.message.MessageLevel;
import net.caffeinemc.mods.sodium.client.data.fingerprint.FingerprintMeasure;
import net.caffeinemc.mods.sodium.client.data.fingerprint.HashedFingerprint;
import net.caffeinemc.mods.sodium.client.gui.SodiumDebugEntry;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.services.PlatformRuntimeInformation;
import net.caffeinemc.mods.sodium.client.util.MgCorePin;
import net.caffeinemc.mods.sodium.mixin.features.gui.hooks.debug.DebugScreenEntriesAccessor;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class SodiumClientMod {
    private static SodiumOptions OPTIONS;
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium");
    public static final Identifier SODIUM_DEBUG_ENTRY_FULL = Identifier.fromNamespaceAndPath("sodium", "debug_full");
    public static final Identifier SODIUM_DEBUG_ENTRY_REDUCED = Identifier.fromNamespaceAndPath("sodium", "debug_reduced");

    private static String MOD_VERSION;

    public static void onInitialization(String version) {
        var entries = DebugScreenEntriesAccessor.getEntries();
        entries.put(SODIUM_DEBUG_ENTRY_FULL, new SodiumDebugEntry(true));
        entries.put(SODIUM_DEBUG_ENTRY_REDUCED, new SodiumDebugEntry(false));

        // Fase 1 — SÓ fixa a render thread no modo "full" (antigo 3+1, que derrubou o jogo
        // para 1/4/10 fps). No modo "workers" (PADRÃO) a render thread fica DESpinada para
        // que o scheduler a coloque num big core livre (cpu2-3), deixando headroom para GC,
        // JIT e driver — era a falta disso que travava tudo. No-op se a ponte não carregar.
        if (MgCorePin.pinRender()) {
            int rc = MgCorePin.pinCurrentToCpu(MgCorePin.RENDER_CPU);
            LOGGER.info("Render thread pinned to cpu{} (rc={}, now on cpu{})",
                    MgCorePin.RENDER_CPU, rc, MgCorePin.currentCpu());
        } else {
            LOGGER.info("MgCorePin modo={} — render thread NAO fixada (livre p/ scheduler); workers fixos em {} cpus",
                    MgCorePin.MODE, MgCorePin.CHUNK_CPUS.length);
        }

        MOD_VERSION = version;

        OPTIONS = loadConfig();

        try {
            updateFingerprint();
        } catch (Throwable t) {
            LOGGER.error("Failed to update fingerprint", t);
        }
    }

    public static SodiumOptions options() {
        if (OPTIONS == null) {
            throw new IllegalStateException("Config not yet available");
        }

        return OPTIONS;
    }

    public static Logger logger() {
        if (LOGGER == null) {
            throw new IllegalStateException("Logger not yet available");
        }

        return LOGGER;
    }

    private static SodiumOptions loadConfig() {
        try {
            return SodiumOptions.loadFromDisk();
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration file", e);
            LOGGER.error("Using default configuration file in read-only mode");

            Console.instance().logMessage(MessageLevel.SEVERE, "sodium.console.config_not_loaded", true, 12.5);

            var config = SodiumOptions.defaults();
            config.setReadOnly();

            return config;
        }
    }

    public static void restoreDefaultOptions() {
        OPTIONS = SodiumOptions.defaults();

        try {
            SodiumOptions.writeToDisk(OPTIONS);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config file", e);
        }
    }

    public static String getVersion() {
        if (MOD_VERSION == null) {
            throw new NullPointerException("Mod version hasn't been populated yet");
        }

        return MOD_VERSION;
    }

    private static void updateFingerprint() {
        var current = FingerprintMeasure.create();

        if (current == null) {
            return;
        }

        HashedFingerprint saved = null;

        try {
            saved = HashedFingerprint.loadFromDisk();
        } catch (Throwable t) {
            LOGGER.error("Failed to load existing fingerprint",  t);
        }

        if (saved == null || !current.looselyMatches(saved)) {
            HashedFingerprint.writeToDisk(current.hashed());

            OPTIONS.notifications.hasSeenDonationPrompt = false;
            OPTIONS.notifications.hasClearedDonationButton = false;

            try {
                SodiumOptions.writeToDisk(OPTIONS);
            } catch (IOException e) {
                LOGGER.error("Failed to update config file", e);
            }
        }
    }

    public static boolean allowDebuggingOptions() {
        return PlatformRuntimeInformation.getInstance().isDevelopmentEnvironment();
    }
}
