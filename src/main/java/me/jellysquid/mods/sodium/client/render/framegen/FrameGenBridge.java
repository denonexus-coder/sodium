package me.jellysquid.mods.sodium.client.render.framegen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * Ponte de dados de frame do Sodium para o MobileGlues (Infinitium FrameGen).
 *
 * Entrega as tres coisas que um frame generation de verdade precisa e que so o
 * motor enxerga (especificacao "INFINITUM FRAMEGEN - LADO JAVA / SODIUM"):
 *
 * <ol>
 *   <li>Matrizes View-Projection do frame ATUAL e do frame ANTERIOR, mais as
 *       posicoes da camera, para o shader reconstruir o vetor de movimento
 *       exato de cada pixel pela matriz em vez de adivinhar por busca de
 *       pixels. Capturados no passo de chunks do Sodium
 *       (core WorldRendererMixin.renderLayer), exatamente onde o proprio
 *       Sodium monta as ChunkRenderMatrices -- a mesma projecao e a mesma
 *       view com que ele desenha os vertices.</li>
 *   <li>Fase da interface 2D: true no HEAD e false no RETURN do render do HUD
 *       (HudRenderPhaseMixin), marcando o instante em que o mundo 3D acabou e
 *       a UI comecou.</li>
 *   <li>O attachment de profundidade do framebuffer principal
 *       (FramebufferDepthMixin no resize + ClientFramebufferInitMixin na
 *       criacao), recriado sempre que a superficie muda.</li>
 * </ol>
 *
 * Regras de desempenho e robustez:
 * <ul>
 *   <li>Zero alocacao por frame: buffers de matriz e Matrix4f temporario sao
 *       static final; os arrays sao reaproveitados.</li>
 *   <li>Nada de glFinish()/glFlush() por aqui: sincronizacao fica com o
 *       driver e com o MobileGlues.</li>
 *   <li>UnsatisfiedLinkError/NoClassDefFoundError desligam a ponte em vez de
 *       crashear: com uma libmobileglues.so antiga (sem estes natives) o mod
 *       continua funcional, apenas sem entregar dados.</li>
 *   <li>A rotacao do frame anterior so acontece uma vez por frame, marcada
 *       pelas transicoes do HUD; as varias camadas de chunk do mesmo frame
 *       reutilizam a mesma captura (o "prev" nunca e do mesmo frame).</li>
 * </ul>
 */
public final class FrameGenBridge {
    private FrameGenBridge() {
    }

    private static final Logger LOGGER = LogManager.getLogger("FrameGenBridge");

    // ---- Estado persistente entre frames (nada e alocado no loop de render) ----
    private static final float[] CURR_VIEW_PROJ = new float[16];
    private static final float[] PREV_VIEW_PROJ = new float[16];
    private static final Matrix4f TMP = new Matrix4f();

    private static double camX, camY, camZ;
    private static double prevCamX, prevCamY, prevCamZ;

    /** A proxima captura pertence a um frame novo (entao rotaciona o prev). */
    private static boolean newFrame = true;
    private static boolean nativeLoaded = true;
    private static boolean unexpectedLogged = false;

    // ---- Natives: assinaturas exatas da especificacao Infinitium FrameGen ----
    // Resolvem em libmobileglues.so (fg_jni_bridge.cpp), pelo mesmo mecanismo
    // do MGRendererControl.
    public static native void nativeUpdateCameraMatrices(float[] currViewProj, float[] prevViewProj,
            double camX, double camY, double camZ,
            double prevCamX, double prevCamY, double prevCamZ);

    public static native void nativeSetUIRenderingPhase(boolean isRenderingUI);

    public static native void nativeBindDepthTexture(int depthTextureId);

    // ---- Natives de telemetria (Infinitium Telemetry) ----
    // Resolvem na mesma libmobileglues.so (fg_jni_bridge.cpp), mas guardam um
    // estado de link SEPARADO do framegen: com um .so antigo (sem estas
    // entradas) apenas a linha GPU do overlay fica em "—" -- o framegen e as
    // matrizes continuam funcionando.

    /**
     * Tempo do ultimo lote do SuperDrawer acumulado desde a ultima leitura, em
     * milissegundos. Retorna -1 quando nao ha dado (overlay ligado antes de
     * qualquer lote, lib sem a entrada, ou janela sem chamadas de desenho).
     *
     * <p>Chamado uma vez por frame pelo overlay; a troca la dentro e um
     * exchange atomico (custo de um JNI curto, ~microssegundos).</p>
     */
    public static native float nativeGetGpuDrawerTimeMs();

    /** -1 = ainda nao mediu, 0 = submissao (parede), 1 = timer de hardware. */
    public static native int nativeGetGpuTimerMode();

    /**
     * Liga/desliga a medicao do SuperDrawer no lado nativo (opt-in: quando
     * desligado, Drawer::draw nao abre nem fecha query nenhuma).
     */
    public static native void nativeSetTelemetryEnabled(boolean enabled);

    /** False apos o primeiro erro de link: a lib antiga nao conhece estes natives. */
    public static boolean isSupported() {
        return nativeLoaded;
    }

    /**
     * Ponto 1 - matrizes da camera.
     *
     * Chamado pelo WorldRendererMixin no inicio do passo de chunks, onde
     * {@code projection} e {@code matrices.peek()} sao os valores exatos que o
     * Sodium alimenta no ChunkRenderMatrices do frame. A ViewProj final e
     * projection * view (ordem column-major do JOML/OpenGL).
     */
    public static void onWorldMatrices(Matrix4f projection, MatrixStack matrices) {
        if (!nativeLoaded || !newFrame) {
            return;
        }

        try {
            newFrame = false;

            TMP.set(projection).mul(matrices.peek().getPositionMatrix());
            TMP.get(CURR_VIEW_PROJ);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.gameRenderer != null) {
                Camera camera = client.gameRenderer.getCamera();

                if (camera != null) {
                    Vec3d pos = camera.getPos();
                    camX = pos.x;
                    camY = pos.y;
                    camZ = pos.z;
                }
            }

            nativeUpdateCameraMatrices(CURR_VIEW_PROJ, PREV_VIEW_PROJ,
                    camX, camY, camZ, prevCamX, prevCamY, prevCamZ);

            // Agora que o frame atual virou o "anterior" da proxima chamada.
            System.arraycopy(CURR_VIEW_PROJ, 0, PREV_VIEW_PROJ, 0, 16);
            prevCamX = camX;
            prevCamY = camY;
            prevCamZ = camZ;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            nativeLoaded = false;
        } catch (Throwable t) {
            if (!unexpectedLogged) {
                unexpectedLogged = true;
                LOGGER.warn("Captura de matrizes do FrameGen desativada apos erro inesperado", t);
            }
        }
    }

    /**
     * Ponto 2 - fase do HUD (true no HEAD, false no RETURN do InGameHud.render).
     *
     * Cada transicao tambem marca frame novo: o mundo do frame acabou, a
     * proxima captura de matrizes ja e do frame seguinte. Sem esse marco, varias
     * camadas de chunk de um mesmo frame sobrescreveriam o "prev".
     */
    public static void onRenderHudPhase(boolean renderingUI) {
        newFrame = true;

        if (!nativeLoaded) {
            return;
        }

        try {
            nativeSetUIRenderingPhase(renderingUI);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            nativeLoaded = false;
        }
    }

    /** Ponto 3 - attachment de profundidade do framebuffer principal (0 = limpa). */
    public static void onDepthAttachment(int depthTextureId) {
        if (!nativeLoaded) {
            return;
        }

        try {
            nativeBindDepthTexture(depthTextureId);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            nativeLoaded = false;
        }
    }

    // ------------------------------------------------------------------
    // Wrappers de telemetria (flag propia: ver comentario das natives)
    // ------------------------------------------------------------------

    private static boolean telemetryLoaded = true;

    /** Milissegundos do SuperDrawer na ultima janela, ou -1 sem dado. */
    public static float gpuDrawerTimeMs() {
        if (!telemetryLoaded) {
            return -1.0f;
        }

        try {
            return nativeGetGpuDrawerTimeMs();
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            telemetryLoaded = false;
            return -1.0f;
        }
    }

    /** Modo de medicao: -1 sem dado, 0 parede, 1 timer HW. */
    public static int gpuTimerMode() {
        if (!telemetryLoaded) {
            return -1;
        }

        try {
            return nativeGetGpuTimerMode();
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            telemetryLoaded = false;
            return -1;
        }
    }

    /**
     * Opt-in do timer do SuperDrawer no MobileGlues. Nunca lanca: se a lib for
     * antiga apenas ignora (o custo do overlay e so Java entao).
     */
    public static void setTelemetryEnabled(boolean enabled) {
        if (!telemetryLoaded) {
            return;
        }

        try {
            nativeSetTelemetryEnabled(enabled);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            telemetryLoaded = false;
        }
    }
}
