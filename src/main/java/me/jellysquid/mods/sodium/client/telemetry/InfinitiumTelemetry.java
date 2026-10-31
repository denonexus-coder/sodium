package me.jellysquid.mods.sodium.client.telemetry;

import me.jellysquid.mods.sodium.client.render.MGRendererControl;
import me.jellysquid.mods.sodium.client.render.framegen.FrameGenBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Infinitium Telemetry -- overlay de diagnostico em tempo real (Sodium).
 *
 * Disseca o pipeline em tempos exatos para provar ONDE esta o gargalo:
 *
 *   SRV  - MSPT do servidor integrado (tecnica do spark: nanoTime no HEAD e no
 *          RETURN de MinecraftServer.tick, o mesmo par START/END_SERVER_TICK
 *          que o FabricTickHook do spark usa via eventos do Fabric -- aqui por
 *          mixin porque o fork nao depende de fabric-lifecycle-events).
 *   CHK  - chunks entregues ao pipeline por segundo + cadencia (gap) entre
 *          chegadas, medido em ClientPlayNetworkHandler.scheduleRenderChunk.
 *   MSH  - tempo de montagem das malhas nas threads do Sodium
 *          (ChunkBuilderMeshingTask.execute).
 *   GPU  - tempo do SuperDrawer vindo do MobileGlues por JNI (timer de hardware
 *          GL_EXT_timer_query quando a GE8320 o suporta, submissao senao).
 *   FG   - estado do frame generation + FPS real do loop de render.
 *
 * Truques emprestados do spark, todos ultra-leves:
 *  - EWMA (valor += alfa * (amostra - valor)) em vez de valores crus: o numero
 *    na tela fica legivel em vez de tremer a cada frame (~20 linhas, o mesmo
 *    espirito do RollingAverage do spark);
 *  - paleta termica spark: verde <25 ms, amarelo <45, laranja <60, vermelho
 *    >=60 para o SRV; 16/40 ms para a cadencia de chunks e GPU (budgets de
 *    frame 60/25 FPS);
 *  - nada de async-profiler/SIGPROF: so System.nanoTime e um JNI curto.
 *
 * Custo com o overlay desligado (padrao): um getstatic de boolean por frame no
 * HUD, um por task de malha e um por tick do servidor -- ramo que nao entra.
 * Zero alocacao nas threads quentes (os records sao so leitura/escrita de
 * volatile + multiplicacao).
 */
public final class InfinitiumTelemetry {
    private InfinitiumTelemetry() {
    }

    private static final Logger LOGGER = LogManager.getLogger("InfinitiumTelemetry");

    // EWMA: alfa por amostra. 0.25 acompanha bem sem tremer; 0.1 no frame time
    // porque la os pics sao maiores (variancia de vsync/input).
    private static final double ALPHA = 0.25;
    private static final double ALPHA_FRAME = 0.10;

    /** Staleness: sem dados ha mais de 3 s a linha fica cinza (sem mentira). */
    private static final long STALE_NANOS = 3_000_000_000L;

    // ---- estado lido/escrito entre threads (volatile: sem locks, sem Custo) ----
    private static volatile boolean enabled = false;

    // SRV: escrito na Server thread, lido na de render.
    private static volatile double serverMs = -1.0;
    private static volatile long serverLastNanos = 0L;

    // CHK: a janela de contagem e trocada pela render; o gap vem de quem recebe.
    private static final Object countLock = new Object(); // protege o par de janelas de1s
    private static int chunkWindow = 0;
    private static int chunkWindowPublished = 0;
    private static volatile double gapMs = -1.0;
    private static volatile long lastChunkNanos = 0L;
    private static long lastPublishNanos = 0L;

    // MSH: varios workers escrevem -- perdida de passo de EWMA e inofensiva.
    private static volatile double meshMs = -1.0;

    // GPU: so a render thread faz poll do JNI.
    private static volatile double gpuMs = -1.0;
    private static volatile int gpuMode = -1; // -1 sem dado, 0 submissao, 1 timer HW

    // FG/FPS: so a render thread.
    private static volatile double frameMs = -1.0;
    private static long lastFrameNanos = 0L;

    private static boolean unexpectedLogged = false;

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Liga/desliga o overlay E o timer do SuperDrawer la no MobileGlues (o JNI
     * guarda so um atomico; as queries GL so existem enquanto isto esta true,
     * entao o build de jogo segue com custo zero por padrao).
     */
    public static void setEnabled(boolean value) {
        enabled = value;
        FrameGenBridge.setTelemetryEnabled(value);
    }

    // ------------------------------------------------------------------
    // Recorders (chamados pelos mixins/edits; todos verificam o gate)
    // ------------------------------------------------------------------

    /** ServerTickMixin: fim - inicio de MinecraftServer.tick = MSPT real. */
    public static void recordServerTickMs(double ms) {
        if (!enabled) {
            return;
        }
        serverMs = ewma(serverMs, ms, ALPHA);
        serverLastNanos = System.nanoTime();
    }

    /** ChunkDeliveryMixin: um chunk entrou no pipeline do render. */
    public static void recordChunkDelivered() {
        if (!enabled) {
            return;
        }
        long now = System.nanoTime();
        long prev = lastChunkNanos;
        lastChunkNanos = now;

        if (prev != 0L) {
            double gap = (now - prev) / 1.0e6;
            // Gap gigante = parada, nao cadencia: nao polui a media.
            if (gap < 10_000.0) {
                gapMs = ewma(gapMs, gap, ALPHA);
            }
        }
        synchronized (countLock) {
            chunkWindow++;
        }
    }

    /**
     * ChunkBuilderMeshingTask: duracao de uma montagem de malha numa thread do
     * Sodium. Cancelamentos (retorno null) nao chegam aqui de proposito.
     */
    public static void recordMeshMs(double ms) {
        if (!enabled) {
            return;
        }
        meshMs = ewma(meshMs, ms, ALPHA);
    }

    // ------------------------------------------------------------------
    // Overlay (TelemetryHudOverlayMixin, no RETURN do InGameHud.render)
    // ------------------------------------------------------------------

    public static void renderOverlay(DrawContext context) {
        if (!enabled) {
            return;
        }

        try {
            sampleFrame();
            sampleGpu();

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.textRenderer == null) {
                return;
            }

            long now = System.nanoTime();
            int x = 4;
            int y = 4;
            int line = 10;

            y = drawLine(context, client.textRenderer, "INFINITIUM TELEMETRY", 0xFFFFFFFF, x, y, line);
            y = drawLine(context, client.textRenderer, serverLine(), serverColor(now), x, y, line);
            y = drawLine(context, client.textRenderer, chunkLine(), chunkColor(now), x, y, line);
            y = drawLine(context, client.textRenderer, meshLine(), colorFor(meshMs, 8.0, 16.0), x, y, line);
            y = drawLine(context, client.textRenderer, gpuLine(), colorFor(gpuMs, 16.0, 40.0), x, y, line);
            drawLine(context, client.textRenderer, frameLine(), colorFor(frameMs > 0 ? 1000.0 / frameMs : -1.0, 50.0, 30.0), x, y, line);
        } catch (Throwable t) {
            if (!unexpectedLogged) {
                unexpectedLogged = true;
                LOGGER.warn("Overlay de telemetria desativado apos erro inesperado", t);
                enabled = false;
            }
        }
    }

    private static void sampleFrame() {
        long now = System.nanoTime();
        long prev = lastFrameNanos;
        lastFrameNanos = now;

        if (prev != 0L) {
            double ms = (now - prev) / 1.0e6;
            if (ms < 5_000.0) { // pausa de menu/alt-tab nao vira "1 frame de 4 s"
                frameMs = ewma(frameMs, ms, ALPHA_FRAME);
            }
        }
    }

    private static void sampleGpu() {
        float ms = FrameGenBridge.gpuDrawerTimeMs();
        if (ms >= 0.0f) {
            gpuMs = ewma(gpuMs, ms, ALPHA);
        }
        if (gpuMode < 0) {
            gpuMode = FrameGenBridge.gpuTimerMode();
        }
    }

    // ------------------------------------------------------------------
    // Linhas e paleta
    // ------------------------------------------------------------------

    private static String serverLine() {
        double ms = serverMs;
        if (ms < 0.0 || System.nanoTime() - serverLastNanos > STALE_NANOS) {
            return "SRV: — (sem servidor local)";
        }
        double tps = ms > 0.05 ? Math.min(20.0, 1000.0 / ms) : 20.0;
        return String.format("SRV: %.1f ms (%.1f TPS)", ms, tps);
    }

    private static int serverColor(long now) {
        if (serverMs < 0.0 || now - serverLastNanos > STALE_NANOS) {
            return GRAY;
        }
        // Paleta spark de MSPT: 25 / 45 / 60 ms.
        return colorFor(serverMs, 25.0, 45.0, 60.0);
    }

    private static String chunkLine() {
        int count = publishChunkWindow();
        if (gapMs < 0.0) {
            return String.format("CHK: %d/s", count);
        }
        return String.format("CHK: %d/s · gap %.0f ms", count, gapMs);
    }

    private static int chunkColor(long now) {
        if (lastChunkNanos == 0L || now - lastChunkNanos > STALE_NANOS) {
            return GRAY;
        }
        // Budgets do primeiro spec: <16 ms entrega rapida, <40 ok, >40 lenta.
        return colorFor(gapMs, 16.0, 40.0);
    }

    private static String meshLine() {
        return meshMs < 0.0 ? "MSH: —" : String.format("MSH: %.2f ms", meshMs);
    }

    private static String gpuLine() {
        if (gpuMs < 0.0) {
            return "GPU: — (sem SuperDrawer)";
        }
        String mode = gpuMode == 1 ? "HW" : gpuMode == 0 ? "CPU" : "?";
        return String.format("GPU: %.2f ms (%s)", gpuMs, mode);
    }

    private static String frameLine() {
        boolean fg = MGRendererControl.isSupported() && MGRendererControl.currentFramegen();
        if (frameMs < 0.0) {
            return String.format("FG: %s", fg ? "ON" : "OFF");
        }
        return String.format("FG: %s · %.0f FPS", fg ? "ON" : "OFF", 1000.0 / frameMs);
    }

    private static int publishChunkWindow() {
        long now = System.nanoTime();
        if (now - lastPublishNanos >= 1_000_000_000L) {
            synchronized (countLock) {
                chunkWindowPublished = chunkWindow;
                chunkWindow = 0;
            }
            lastPublishNanos = now;
        }
        return chunkWindowPublished;
    }

    // ------------------------------------------------------------------
    // Paleta (ARGB; formato que o drawTextWithShadow espera)
    // ------------------------------------------------------------------

    private static final int GREEN  = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int ORANGE = 0xFFFFAA00;
    private static final int RED    = 0xFFFF5555;
    private static final int GRAY   = 0xFFAAAAAA;

    private static int colorFor(double value, double warn, double bad) {
        if (value < 0.0) return GRAY;
        if (value < warn) return GREEN;
        if (value < bad) return YELLOW;
        return RED;
    }

    private static int colorFor(double value, double warn, double bad, double critical) {
        if (value < 0.0) return GRAY;
        if (value < warn) return GREEN;
        if (value < bad) return YELLOW;
        if (value < critical) return ORANGE;
        return RED;
    }

    private static int drawLine(DrawContext context, net.minecraft.client.font.TextRenderer textRenderer,
                                String text, int color, int x, int y, int line) {
        context.drawTextWithShadow(textRenderer, text, x, y, color);
        return y + line;
    }

    private static double ewma(double current, double sample, double alpha) {
        if (current < 0.0) {
            return sample;
        }
        return current + alpha * (sample - current);
    }
}
