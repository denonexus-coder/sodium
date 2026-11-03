package me.jellysquid.mods.sodium.client.render.asmd;

/**
 * Ponte JNI da telemetria ASMD (128 bytes) para o co-processador em C++.
 *
 * A libmobileglues.so ja esta carregada quando a ponte EGL/GLES nasce — mesmo
 * padrao de {@code MGNativeDrawer} / {@code MGUltraBridge}: nao ha
 * {@code System.loadLibrary} aqui, os symbols resolvem direto. (Um
 * {@code System.loadLibrary("mobileglues")} falharia em silencio porque o .so
 * nao esta no {@code java.library.path} e deixaria a telemetria morta.)
 *
 * O handshake e por probe: chama-se o native dentro de try/catch. Se o .so em
 * disco for antigo e nao exportar os dois symbols, a telemetria nativa fica
 * desligada em vez de lancar {@link UnsatisfiedLinkError} em toda frame. Cada
 * tentativa custa uma excecao no maximo, e o total e limitado por
 * {@link #MAX_ATTEMPTS}.
 *
 * Zero alocacao: depois de pronto, {@link #isSupported()} le um boolean.
 */
public final class ASMDNativeBridge {
    /**
     * Budget de tentativas de handshake. O Fabric init roda antes de a janela
     * GL existir, entao a 1a tentativa (boot) pode falhar por o .so ainda nao
     * ter sido carregado; as seguintes acontecem uma por frame, ate pegar.
     */
    private static final int MAX_ATTEMPTS = 30;

    private static boolean ready;
    private static int attempts;

    private ASMDNativeBridge() {
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

        try {
            // Registra o buffer off-heap (trabalho de verdade)...
            initTelemetryBufferAddress(ASMDJavaBridge.nativeAddress());
            // ...e sonda o segundo symbol. Se faltar, cai no catch e tentamos
            // de novo no frame seguinte. Se existir, e' um stamp de relogio.
            notifyFrameStart();

            ready = true;
            return true;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
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