package me.jellysquid.mods.sodium.client.render.afk;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkType;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detector de AFK (jogador parado) com gatilho de bench ultrarrápido.
 *
 * Quando o jogador fica parado por {@link #AFK_THRESHOLD_MS} (default 3s),
 * o sistema dispara um bench de recalibração em menos de 10s que testa
 * todos os métodos de desenho (Merge, UBO_Meu, UBO_MG, Unroll, Instanced)
 * para cada tipo de chunk e recalibra a tabela de decisão em tempo real.
 *
 * Zero alocação no caminho quente. Apenas leitura de estado do MinecraftClient.
 */
public final class AFKDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium-AFK");

    // Jogador parado por 3 segundos = AFK
    private static final long AFK_THRESHOLD_MS = 3_000L;

    // Bench deve rodar em menos de 10 segundos (acessível por AFKBenchRunner)
    static final long BENCH_MAX_DURATION_MS = 10_000L;

    // Após o bench, aguardar este tempo antes de permitir outro
    private static final long BENCH_COOLDOWN_MS = 30_000L;

    private static boolean enabled = true;

    // Estado de AFK
    private static final AtomicLong lastMovementNs = new AtomicLong(System.nanoTime());
    private static final AtomicBoolean isAfk = new AtomicBoolean(false);
    private static final AtomicLong lastBenchNs = new AtomicLong(0L);
    private static final AtomicBoolean benchRunning = new AtomicBoolean(false);

    // Última posição conhecida da câmera para detecção de movimento
    private static final float[] lastCameraPos = new float[]{0.0f, 0.0f, 0.0f};
    private static final float[] lastCameraRot = new float[]{0.0f, 0.0f}; // yaw, pitch

    private AFKDetector() {}

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Chamado por frame no HEAD do render loop.
     * Atualiza a posição da câmera e detecta se o jogador entrou em AFK.
     *
     * @return true se entrou em AFK neste frame (borda de subida)
     */
    public static boolean update(float camX, float camY, float camZ,
                                 float yaw, float pitch) {
        if (!enabled) {
            return false;
        }

        long now = System.nanoTime();

        // Detecta movimento significativo (> 0.001 blocos ou > 0.01 graus)
        boolean moved =
            Math.abs(camX - lastCameraPos[0]) > 0.001f ||
            Math.abs(camY - lastCameraPos[1]) > 0.001f ||
            Math.abs(camZ - lastCameraPos[2]) > 0.001f ||
            Math.abs(yaw - lastCameraRot[0]) > 0.01f ||
            Math.abs(pitch - lastCameraRot[1]) > 0.01f;

        if (moved) {
            // Captura o valor ANTES de atualizar: é o início do período AFK que acabou.
            long afkStartNs = lastMovementNs.getAndSet(now);
            lastCameraPos[0] = camX;
            lastCameraPos[1] = camY;
            lastCameraPos[2] = camZ;
            lastCameraRot[0] = yaw;
            lastCameraRot[1] = pitch;

            if (isAfk.getAndSet(false)) {
                // Saiu do AFK
                LOGGER.debug("[AFK] Jogador voltou do AFK após {}ms",
                    (now - afkStartNs) / 1_000_000.0);
            }
            return false;
        }

        // Verifica se entrou em AFK
        long afkForNs = now - lastMovementNs.get();
        boolean wasAfk = isAfk.get();
        boolean nowAfk = afkForNs >= AFK_THRESHOLD_MS * 1_000_000L;

        if (nowAfk && !wasAfk) {
            isAfk.set(true);
            LOGGER.info("[AFK] Jogador detectado AFK após {}ms", afkForNs / 1_000_000.0);
            return true; // borda de subida - disparar bench
        }

        return false;
    }

    /**
     * Verifica se podemos iniciar um bench (não está rodando, não está em cooldown).
     */
    public static boolean canStartBench() {
        if (!enabled) return false;
        if (benchRunning.get()) return false;
        long now = System.nanoTime();
        long sinceLastBench = now - lastBenchNs.get();
        return sinceLastBench >= BENCH_COOLDOWN_MS * 1_000_000L;
    }

    /**
     * Marca o início do bench.
     */
    public static void benchStarted() {
        benchRunning.set(true);
        lastBenchNs.set(System.nanoTime());
    }

    /**
     * Marca o fim do bench.
     */
    public static void benchFinished() {
        benchRunning.set(false);
    }

    public static boolean isBenchRunning() {
        return benchRunning.get();
    }

    public static boolean isAfk() {
        return isAfk.get();
    }
}
