package net.caffeinemc.mods.sodium.client.util;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Afinidade de núcleo — LAYOUT CORRIGIDO (Fase 1).
 *
 * <p>Topologia medida neste aparelho (octa-core A53, PowerVR GE8320):
 * cpu0-3 = cluster rápido (2.3 GHz), cpu4-7 = lento (1.8 GHz / LITTLE).
 *
 * <p>O layout antigo "3+1" (render→cpu3, workers→cpu0-2) reservava OS 4 BIG CORES
 * INTOS para o jogo e empurrava GC/JIT/driver para os LITTLE já ocupados — isso
 * derrubou o jogo de 40/60 fps para 1/4/10 fps. Motivo: o GC (crítico em Java, e o
 * meshing do Sodium aloca muito) ficou sem nenhum big core, e as pauses explodiram.
 *
 * <p>Layout corrigido — REGRAS:
 * <ul>
 *   <li><b>NUNCA</b> reservar todos os big cores para o jogo; deixa SEMPRE 1+ big core
 *       sem pin, onde caem naturalmente GC, JIT, driver PowerVR e o compositor.</li>
 *   <li>Só as threads de <i>throughput</i> que controlamos são fixadas.</li>
 *   <li>GC nunca é mandado para os LITTLE.</li>
 * </ul>
 *
 * <p>Modos (A/B em jogo sem rebuild — via {@code -Dmg.pin.mode=...} ou env {@code MG_PIN_MODE}):
 * <ul>
 *   <li>{@code workers} (PADRÃO): workers→cpu0-1, render DESpinada (scheduler põe-a num
 *       big core livre). cpu2-3 + cpu4-7 livres p/ GC, JIT, driver, Android. Layout seguro.</li>
 *   <li>{@code off}: nenhum pinning (baseline puro ~40/60 fps).</li>
 *   <li>{@code full}: 3+1 antigo (render→cpu3, workers→cpu0-2) — SÓ para A/B, causou 1 fps.</li>
 * </ul>
 *
 * <p>O ponte nativo ({@code libmgcorepin.so}) é C puro, ~6 KB, extraído UMA vez no
 * class-init. Se o loader falhar, {@link #isAvailable()} é {@code false} e tudo vira
 * no-op — o Sodium nunca quebra.
 */
public final class MgCorePin {
    /** Modo activo: "off" | "workers" | "full". */
    public static final String MODE;

    /** Render thread cpu — usado APENAS no modo "full". */
    public static final int RENDER_CPU = 3;

    /** CPUs dos chunk workers (vazio no modo "off"). workers={0,1}, full={0,1,2}. */
    public static final int[] CHUNK_CPUS;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("MgCorePin");
    private static final boolean AVAILABLE;

    static {
        String mode = System.getProperty("mg.pin.mode",
                System.getenv().getOrDefault("MG_PIN_MODE", "workers")).trim().toLowerCase();
        if (!mode.equals("off") && !mode.equals("workers") && !mode.equals("full")) {
            LOGGER.warn("MgCorePin: modo desconhecido '{}' — a usar 'workers'", mode);
            mode = "workers";
        }
        MODE = mode;

        int[] cpus;
        switch (mode) {
            case "full":
                cpus = new int[]{0, 1, 2};
                break;
            case "workers":
                cpus = new int[]{0, 1};
                break;
            default: // off
                cpus = new int[0];
                break;
        }
        CHUNK_CPUS = cpus;

        boolean ok = false;
        String reason = "not-arm64-or-non-linux";
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            boolean linuxArm64 = os.contains("linux") &&
                    (arch.contains("aarch64") || arch.contains("arm64"));
            if (linuxArm64) {
                String res = "/assets/sodium/mgcorepin/libmgcorepin.so";
                try (InputStream in = MgCorePin.class.getResourceAsStream(res)) {
                    if (in != null) {
                        Path tmp = Files.createTempFile("mgcorepin", ".so");
                        tmp.toFile().deleteOnExit();
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                        System.load(tmp.toAbsolutePath().toString());
                        ok = true;
                        reason = null;
                    } else {
                        reason = "resource-missing:" + res;
                    }
                }
            }
        } catch (Throwable t) {
            ok = false;
            reason = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        AVAILABLE = ok;

        if (ok) {
            LOGGER.info("MgCorePin: lib carregada (modo={}, workers={} cpus{}, render={}, big-livre a partir de cpu2)",
                    MODE, CHUNK_CPUS.length, renderPinSuffix(), CHUNK_CPUS.length == 0 ? "nenhuma" : describeWorkers());
        } else {
            LOGGER.warn("MgCorePin: lib NAO carregada ({}) — pinning desativado, Sodium opera normalmente", reason);
        }
    }

    private static String describeWorkers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CHUNK_CPUS.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("cpu").append(CHUNK_CPUS[i]);
        }
        return sb.toString();
    }

    private static String renderPinSuffix() {
        return pinRender() ? ("cpu" + RENDER_CPU) : "auto (sem pin)";
    }

    private static int nativeCpuCountSafe() {
        try {
            return nativeCpuCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Linha para o overlay F3. Devolve {@code null} quando não há pinning (não poluir);
     * caso contrário mostra o modo e, no modo "full", se a render thread está no core
     * esperado ("LIVE=cpuX!" quando fugiu).
     */
    public static String overlayStatusLine() {
        if (!AVAILABLE) {
            return null;
        }
        if (MODE.equals("off")) {
            return "Core Pin: OFF (baseline)";
        }
        if (MODE.equals("full")) {
            int live = currentCpu();
            String livePart = (live >= 0 && live != RENDER_CPU) ? " LIVE=cpu" + live + "!" : "=cpu" + live;
            return String.format("Core Pin: full  render=%s  workers=cpu0,1,2  (OBS: saturava os 4 big)",
                    "cpu" + RENDER_CPU + livePart);
        }
        // modo workers (padrão): render despinada, 2 workers fixos, cpu2-7 livres
        int live = currentCpu();
        return String.format("Core Pin: workers  render=auto(live=cpu%d)  workers=%s  free=cpu2-7",
                live, describeWorkers());
    }

    private MgCorePin() {}

    /** true se a ponte nativa carregou (independente do modo). */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Modo pede para fixar a render thread? (só "full"). */
    public static boolean pinRender() {
        return AVAILABLE && MODE.equals("full");
    }

    /** Modo fixa os chunk workers? (workers/full, e com CPUs definidos). */
    public static boolean workersPinned() {
        return AVAILABLE && CHUNK_CPUS.length > 0;
    }

    /** Fixa a thread CHAMADORA a um núcleo. 0 = ok, -1 = indisponível, senão -errno. */
    public static int pinCurrentToCpu(int cpu) {
        return AVAILABLE ? nativePinCurrentToCpu(cpu) : -1;
    }

    /** Fixa a thread chamadora a um conjunto de núcleos (bit i = cpu i). */
    public static int pinCurrentToMask(long mask) {
        return AVAILABLE ? nativePinCurrentToMask(mask) : -1;
    }

    /** Núcleo onde a thread chamadora está rodando agora (-1 se indisponível). */
    public static int currentCpu() {
        return AVAILABLE ? nativeCurrentCpu() : -1;
    }

    /** CPUs online segundo o kernel (fallback: java.lang.Runtime). */
    public static int cpuCount() {
        return AVAILABLE ? nativeCpuCount() : Runtime.getRuntime().availableProcessors();
    }

    private static native int nativePinCurrentToCpu(int cpu);

    private static native int nativePinCurrentToMask(long mask);

    private static native int nativeCurrentCpu();

    private static native int nativeCpuCount();
}
