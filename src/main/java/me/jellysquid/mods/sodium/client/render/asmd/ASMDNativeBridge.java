package me.jellysquid.mods.sodium.client.render.asmd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Ponte JNI da telemetria ASMD (128 bytes) para o co-processador em C++.
 *
 * <h2>Por que precisa de System.load</h2>
 * A libmobileglues.so ja esta carregada no processo — o launcher abre ela como
 * renderer, com {@code dlopen(RTLD_GLOBAL|RTLD_LAZY)} puro, bem antes do Fabric
 * nascer. So que {@code dlopen} apenas carrega: nao registra a lib em nenhum
 * classloader do JVM. O HotSpot resolve um {@code native} procurando <b>so nas
 * libs registradas no classloader que definiu a classe</b>; para classes do
 * Fabric (KnotClassLoader) ele <b>nao</b> cai para {@code RTLD_DEFAULT}, mesmo
 * que o symbol esteja visivel la. Resultado: {@link UnsatisfiedLinkError} em
 * toda chamada — o que o probe antigo engolia em silencio.
 *
 * <p>Entao aqui a lib e (re)registrada com {@code System.load} a partir de uma
 * classe do proprio Sodium. O linker deduplica por inode, entao um {@code load}
 * de um arquivo ja carregado devolve o handle existente <b>sem</b> reinicializar
 * o renderer (medido: "Initializing MobileGlues" acontece uma unica vez).
 *
 * <h2>Por que o caminho vem de /proc/self/maps</h2>
 * O UID da aplicacao muda a cada reinstalação, entao qualquer path fixo ou
 * montado a partir de config quebra. O /proc/self/maps e lido em runtime e
 * mostra exatamente de onde o launcher abriu a lib — independente de UID,
 * de pasta e de launcher. Ja o {@code System.loadLibrary("mobileglues")}
 * nao serve: o .so nao esta no {@code java.library.path}.
 *
 * <h2>Custos</h2>
 * A deteccao e o load rodam no maximo {@link #MAX_LOAD_ATTEMPTS} vezes, antes
 * de qualquer frame. Depois de {@link #ready} o caminho quente nao aloca nem
 * le arquivo nenhum: {@link #isSupported()} vira uma leitura de boolean.
 */
public final class ASMDNativeBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium-ASMD");

    /**
     * Budget de tentativas de handshake. O Fabric init roda antes de a janela
     * GL existir, entao a 1a tentativa (boot) pode falhar por o .so ainda nao
     * ter sido carregado; as seguintes acontecem uma por frame, ate pegar.
     */
    private static final int MAX_ATTEMPTS = 30;

    /** Mesmo budget para o {@code System.load} — namespace pode demorar a abrir. */
    private static final int MAX_LOAD_ATTEMPTS = 30;

    /** Nome do arquivo procurado no /proc/self/maps (bate com o DT_SONAME). */
    private static final String LIB_MARKER = "libmobileglues";

    private static boolean ready;
    private static int attempts;

    private static boolean loaded;
    private static int loadAttempts;
    private static boolean loadFailureLogged;
    private static boolean probeFailureLogged;

    private ASMDNativeBridge() {
    }

    /**
     * Registra a lib no classloader do Fabric, lendo o caminho real de
     * /proc/self/maps. Idempotente: depois do primeiro sucesso nao faz I/O.
     *
     * @return true se a lib esta registrada (ou ja estava)
     */
    private static boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        if (loadAttempts >= MAX_LOAD_ATTEMPTS) {
            return false;
        }
        loadAttempts++;

        String path = findLibraryPath();
        if (path == null) {
            if (!loadFailureLogged) {
                loadFailureLogged = true;
                LOGGER.warn("[Sodium] ASMD: " + LIB_MARKER
                        + " nao aparece em /proc/self/maps — launcher nao carregou a lib"
                        + " ou o processo e de outro usuario.");
            }
            return false;
        }

        try {
            System.load(path);
            loaded = true;
            LOGGER.info("[Sodium] ASMD: lib registrada no classloader do Fabric via {}"
                    + " (ja estava carregada pelo launcher; handle reaproveitado, sem re-init).",
                    path);
            return true;
        } catch (Throwable t) {
            // Loga a 1a vez com a causa EXATA — o catch silencioso de antes era
            // o motivo de nenhum log mostrar o motivo real da falha.
            if (!loadFailureLogged) {
                loadFailureLogged = true;
                LOGGER.warn("[Sodium] ASMD: System.load({}) falhou: {}: {}",
                        path, t.getClass().getSimpleName(), String.valueOf(t.getMessage()));
            }
            return false;
        }
    }

    /**
     * Procura o caminho absoluto da lib no /proc/self/maps.
     *
     * <p>Formato da linha: {@code address perms offset dev inode pathname}, com
     * o pathname a partir do primeiro '/'. Como os campos iniciais (hex, perms,
     * offset, {@code fc:33}, inode) nunca contem '/', o primeiro '/' ja marca o
     * inicio do caminho — entao nao ha depender de colunas fixas.
     *
     * @return o path absoluto, ou null se nao encontrar
     */
    private static String findLibraryPath() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"), 4096)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int slash = line.indexOf('/');
                if (slash < 0) {
                    continue; // mappings anonimos ([heap], [stack], etc.)
                }
                String path = line.substring(slash);
                if (path.indexOf(LIB_MARKER) < 0) {
                    continue;
                }
                // O kernel acrescenta " (deleted)" quando o arquivo saiu do disco
                // depois do dlopen; o path util e' o que vem antes disso.
                int deleted = path.indexOf(" (deleted)");
                return deleted > 0 ? path.substring(0, deleted) : path;
            }
        } catch (IOException e) {
            if (!loadFailureLogged) {
                loadFailureLogged = true;
                LOGGER.warn("[Sodium] ASMD: nao consegui ler /proc/self/maps: {}",
                        String.valueOf(e.getMessage()));
            }
        }
        return null;
    }

    /**
     * Entrega o endereço do ByteBuffer direto ao co-processador e confirma que
     * os dois natives existem. Idempotente.
     *
     * <p>Chamado no boot ({@code SodiumClientMod.onInitializeClient}) e de
     * novo pelo render loop enquanto nao der certo.
     *
     * @return true se os dois natives responderam
     */
    public static boolean init() {
        if (ready) {
            return true;
        }
        if (attempts >= MAX_ATTEMPTS) {
            return false;
        }

        attempts++;

        // Garante o registro da lib ANTES de qualquer chamada nativa. E' isso
        // que faz o lookup do HotSpot achar os symbols; sem isto o probe de
        // baixo falha com UnsatisfiedLinkError em toda tentativa.
        if (!ensureLoaded()) {
            return false;
        }

        try {
            // Registra o buffer off-heap (trabalho de verdade)...
            initTelemetryBufferAddress(ASMDJavaBridge.nativeAddress());
            // ...e sonda o segundo symbol. Se faltar, cai no catch e tentamos
            // de novo no frame seguinte. Se existir, e' um stamp de relogio.
            notifyFrameStart();

            ready = true;
            return true;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            // So chega aqui com a lib registrada: ou o .so em disco e' antigo e
            // nao exporta os symbols, ou algo mais specifico. Loga a 1a vez que
            // acontece (independente da tentativa) para nunca silenciar a causa.
            if (!probeFailureLogged) {
                probeFailureLogged = true;
                LOGGER.warn("[Sodium] ASMD: probe dos natives falhou mesmo com a lib"
                        + " registrada (tentativa {}): {}: {}",
                        attempts, e.getClass().getSimpleName(),
                        String.valueOf(e.getMessage()));
            }
            return false;
        }
    }

    public static boolean isSupported() {
        if (ready) {
            return true;
        }
        if (attempts >= MAX_ATTEMPTS) {
            return false;
        }
        return init();
    }

    /**
     * Chamado uma vez por frame no HEAD de {@code GameRenderer.render}.
     * So chega aqui se {@link #isSupported()} for true, entao nao ha try/catch
     * no caminho quente.
     */
    public static native void notifyFrameStart();

    private static native void initTelemetryBufferAddress(long memoryAddress);
}
