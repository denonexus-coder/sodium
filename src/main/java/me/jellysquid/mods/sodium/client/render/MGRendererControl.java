package me.jellysquid.mods.sodium.client.render;

/**
 * Interruptores de runtime do renderer do MobileGlues (upscale FSR1 e frame generation).
 *
 * Os setters NAO fazem GL: gravam um atomico dentro de libmobileglues.so, que o
 * proximo eglSwapBuffers atende na thread de render com o contexto corrente.
 * Um clique no menu portanto vale no frame seguinte -- sem reiniciar o jogo,
 * sem recarregar o renderer e sem tocar na configuracao do /sdcard/MG.
 *
 * Os getters leem o estado REAL da lib a cada query, por isso o menu mostra
 * sempre o que esta a ser usado, mesmo que o config.json da lib tenha posto
 * outro valor no arranque.
 *
 * O simbolo resolve pelo mesmo mecanismo do MGUltraBridge: o VM encontra os
 * natives em libmobileglues.so ja carregado pela ponte EGL/GLES.
 */
public final class MGRendererControl {
    private MGRendererControl() {
    }

    /**
     * Presets na mesma ordem dos ints do FSR1_Quality_Preset da lib
     * (0=Disabled, 1=UltraQuality, 2=Quality, 3=Balanced, 4=Performance).
     */
    public enum Fsr1Preset {
        Disabled,
        UltraQuality,
        Quality,
        Balanced,
        Performance
    }

    private static final boolean SUPPORTED;

    static {
        boolean ok = false;
        try {
            ok = nPresent();
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            ok = false;
        }
        SUPPORTED = ok;
    }

    /** True quando libmobileglues.so exporta os natives de runtime. */
    public static boolean isSupported() {
        return SUPPORTED;
    }

    /** Preset que a lib esta a usar AGORA (lido da lib, nao do menu). */
    public static Fsr1Preset currentFsr1() {
        int v = getFsr1();
        Fsr1Preset[] all = Fsr1Preset.values();
        return (v >= 0 && v < all.length) ? all[v] : Fsr1Preset.Disabled;
    }

    /** Frame generation a correr agora. */
    public static boolean currentFramegen() {
        return getFramegen();
    }

    public static void setFsr1(Fsr1Preset preset) {
        setFsr1(preset.ordinal());
    }

    private static native boolean nPresent();

    private static native int getFsr1();

    private static native boolean getFramegen();

    private static native void setFsr1(int preset);

    /**
     * Publica e nativa direta: o .so exporta o simbolo setFramegen(boolean),
     * e um wrapper homonimo aqui seria o proprio metodo (mesma assinatura).
     */
    public static native void setFramegen(boolean on);
}
