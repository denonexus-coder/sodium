package net.caffeinemc.mods.sodium.client.util;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Afinidade de núcleo para o layout 3+1 medido neste aparelho (SoC octa-core
 * A53 do PowerVR GE8320): cpu0-3 são o cluster rápido (2.3 GHz / capacity 1024),
 * cpu4-7 o lento (1.8 GHz / capacity 801). Render thread -> cpu3, chunk workers
 * -> cpu0-2, cpu4-7 livres para GC/áudio/netty.
 *
 * <p>O ponte nativa ({@code libmgcorepin.so}) é C puro (sem libc++), ~6 KB, e é
 * extraído para um arquivo temporário e carregado UMA vez no class-init. Se o
 * loader falhar (arquitetura errada, lib ausente), {@link #isAvailable()} devolve
 * {@code false} e todos os métodos viram no-op — o Sodium segue funcionando sem
 * afinidade, nunca quebra.
 */
public final class MgCorePin {
    // Render thread no big core cpu3 (isolado dos workers).
    public static final int RENDER_CPU = 3;
    // Chunk workers nos big cores cpu0-2.
    public static final int[] CHUNK_CPUS = {0, 1, 2};

    private static final boolean AVAILABLE;

    static {
        boolean ok = false;
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
                    }
                }
            }
        } catch (Throwable t) {
            ok = false; // degrada para no-op em vez de propagar
        }
        AVAILABLE = ok;
    }

    private MgCorePin() {}

    /** true se a ponte nativa carregou e os métodos têm efeito real. */
    public static boolean isAvailable() {
        return AVAILABLE;
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
