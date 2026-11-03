package me.jellysquid.mods.sodium.client;

import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.data.fingerprint.FingerprintMeasure;
import me.jellysquid.mods.sodium.client.data.fingerprint.HashedFingerprint;
import me.jellysquid.mods.sodium.client.gui.console.Console;
import me.jellysquid.mods.sodium.client.gui.console.message.MessageLevel;
import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import me.jellysquid.mods.sodium.client.render.asmd.ASMDNativeBridge;
import me.jellysquid.mods.sodium.client.render.asmd.GCMonitorThread;
import me.jellysquid.mods.sodium.client.util.FlawlessFrames;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class SodiumClientMod implements ClientModInitializer {
    private static SodiumGameOptions CONFIG;
    private static Logger LOGGER;

    private static String MOD_VERSION;

    @Override
    public void onInitializeClient() {
        ModContainer mod = FabricLoader.getInstance()
                .getModContainer("sodium")
                .orElseThrow(NullPointerException::new);

        MOD_VERSION = mod.getMetadata()
                .getVersion()
                .getFriendlyString();

        LOGGER = LoggerFactory.getLogger("Sodium");
        CONFIG = loadConfig();

        FlawlessFrames.onClientInitialization();

        initAsmdTelemetry();

        try {
            updateFingerprint();
        } catch (Throwable t) {
            LOGGER.error("Failed to update fingerprint", t);
        }
    }

    /**
     * ASMD — publica o buffer de telemetria (128 B, off-heap) para o
     * co-processador em C++ e sobe o monitor de GC em thread própria.
     *
     * <p>O handshake JNI pode falhar aqui de propósito: o Fabric init roda
     * antes de a janela GL nascer, e a libmobileglues.so só é carregada junto
     * com a ponte EGL/GLES. Nesse caso o {@code RenderLoopMixin} repete a
     * tentativa nos frames seguintes — ver {@code ASMDNativeBridge.init()}.
     */
    private void initAsmdTelemetry() {
        // Sem DRS ativo o viewport é nativo: escala 1.0. (Não existe resolução
        // dinâmica no código — FSR1 está fora do escopo da ASMD.)
        ASMDJavaBridge.setDrsViewportScale(1.0f);

        if (ASMDNativeBridge.init()) {
            LOGGER.info("[Sodium] ASMD: buffer de telemetria registrado no co-processador nativo.");
        } else {
            LOGGER.info("[Sodium] ASMD: ponte JNI ainda indisponível no boot; " +
                    "tentando de novo no primeiro frame.");
        }

        GCMonitorThread.startMonitor();
    }

    public static SodiumGameOptions options() {
        if (CONFIG == null) {
            throw new IllegalStateException("Config not yet available");
        }

        return CONFIG;
    }

    public static Logger logger() {
        if (LOGGER == null) {
            throw new IllegalStateException("Logger not yet available");
        }

        return LOGGER;
    }

    private static SodiumGameOptions loadConfig() {
        try {
            return SodiumGameOptions.loadFromDisk();
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration file", e);
            LOGGER.error("Using default configuration file in read-only mode");

            Console.instance().logMessage(MessageLevel.SEVERE, Text.translatable("sodium.console.config_not_loaded"), 12.5);

            var config = SodiumGameOptions.defaults();
            config.setReadOnly();

            return config;
        }
    }

    public static void restoreDefaultOptions() {
        CONFIG = SodiumGameOptions.defaults();

        try {
            SodiumGameOptions.writeToDisk(CONFIG);
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

            CONFIG.notifications.hasSeenDonationPrompt = false;
            CONFIG.notifications.hasClearedDonationButton = false;

            try {
                SodiumGameOptions.writeToDisk(CONFIG);
            } catch (IOException e) {
                LOGGER.error("Failed to update config file", e);
            }
        }
    }
}
