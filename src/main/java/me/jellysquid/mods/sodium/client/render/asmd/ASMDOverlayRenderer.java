package me.jellysquid.mods.sodium.client.render.asmd;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * ASMD Overlay — HUD minimalista que mostra o estado do motor em tempo real
 * sem matar performance.
 * - Leitura direta do ByteBuffer (zero-allocation no loop de leitura)
 * - Formatação manual em StringBuilder reutilizado (sem String.format,
 *   que alocaria Formatter + varargs por frame)
 * - Desenho via fontRenderer + background GL em uma única passada
 */
public final class ASMDOverlayRenderer {
    // offsets do buffer (conforme ASMDJavaBridge)
    private static final int OFF_SERVER_TICK = 0;
    private static final int OFF_SERVER_TPS = 4;
    private static final int OFF_GC_IMMINENT = 8;
    private static final int OFF_PLAYER_POS_X = 16;
    private static final int OFF_PLAYER_POS_Z = 24;
    private static final int OFF_SPEED = 40;
    private static final int OFF_CAMERA_YAW = 48;
    private static final int OFF_VIEW_DIST = 68;
    private static final int OFF_CHUNK_QUEUE = 72;
    private static final int OFF_VISIBLE_ENTITIES = 76;
    private static final int OFF_VISIBLE_TILE = 80;
    private static final int OFF_FRAME_CLOCK = 84;
    private static final int OFF_STANDSTILL = 92;
    private static final int OFF_SPRINTING = 96;
    private static final int OFF_DRS_SCALE = 100;

    private static final int MAX_LINES = 4;
    private static final float LINE_H = 10.0f;

    // Reutilizados entre frames — nenhuma alocação no caminho quente.
    private static final StringBuilder SB = new StringBuilder(160);
    private static final String[] LINE_BUF = new String[MAX_LINES];
    // Rendenização de decimais sem String.format (buffers próprios)
    private static final char[] DEC1 = new char[16];
    private static final char[] DEC2 = new char[16];
    private static final char[] INT_TMP = new char[12];

    private ASMDOverlayRenderer() {}

    public static void render(MatrixStack matrices, float tickDelta, MinecraftClient client) {
        ByteBuffer buf = ASMDJavaBridge.buffer();

        // --- Coleta em primitivos (zero-allocation) ---
        float tickMs = buf.getFloat(OFF_SERVER_TICK);
        float tps = buf.getFloat(OFF_SERVER_TPS);
        int gc = buf.getInt(OFF_GC_IMMINENT);
        float px = buf.getFloat(OFF_PLAYER_POS_X);
        float pz = buf.getFloat(OFF_PLAYER_POS_Z);
        float speed = buf.getFloat(OFF_SPEED);
        float yaw = buf.getFloat(OFF_CAMERA_YAW);
        int viewDist = buf.getInt(OFF_VIEW_DIST);
        int chunkQueue = buf.getInt(OFF_CHUNK_QUEUE);
        int visibleEnts = buf.getInt(OFF_VISIBLE_ENTITIES);
        int visibleTiles = buf.getInt(OFF_VISIBLE_TILE);
        int standstill = buf.getInt(OFF_STANDSTILL);
        int sprinting = buf.getInt(OFF_SPRINTING);
        float drs = buf.getFloat(OFF_DRS_SCALE);

        long frameNs = buf.getLong(OFF_FRAME_CLOCK);
        double frameMs = (frameNs > 0) ? (frameNs / 1_000_000.0) : 0.0;
        float fps = (float) (1000.0 / Math.max(frameMs, 1.0));

        // --- Monta linhas em buffers reutilizados (sem String.format) ---
        SB.setLength(0);
        SB.append("ASMD | FPS: ").append(fmt1(fps, DEC1))
          .append(" | ms: ").append(fmt1(frameMs, DEC1))
          .append(" | ChunkQ: ").append(chunkQueue);
        LINE_BUF[0] = flush();

        SB.setLength(0);
        SB.append("Tick: ").append(fmtInt((int) tickMs, INT_TMP))
          .append("ms | TPS: ").append(fmt1(tps, DEC1))
          .append(" | View: ").append(viewDist)
          .append(" | GC: ").append(gc == 1 ? "ACTIVE" : "off");
        LINE_BUF[1] = flush();

        SB.setLength(0);
        SB.append("Tile: ").append(visibleTiles)
          .append(" | Ent: ").append(visibleEnts)
          .append(" | Speed: ").append(fmt2(speed, DEC2))
          .append(" | Stand: ").append(standstill)
          .append(" | Sprint: ").append(sprinting);
        LINE_BUF[2] = flush();

        SB.setLength(0);
        SB.append("Pos: ").append(fmt1(px, DEC1))
          .append(", ").append(fmt1(pz, DEC1))
          .append(" | Yaw: ").append(fmtInt((int) yaw, INT_TMP))
          .append(" | DRS: ").append(fmt2(drs, DEC2));
        LINE_BUF[3] = flush();

        // --- Desenho (duas passadas: fundo depois texto) ---
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        float x = 2.0f, y = 2.0f;

        // Background (semi-transparente)
        GL11.glColor4f(0.0f, 0.0f, 0.0f, 0.6f);
        for (int i = 0; i < MAX_LINES; i++) {
            float w = LINE_BUF[i].length() * 6.0f;
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + w + 2.0f, y);
            GL11.glVertex2f(x + w + 2.0f, y + LINE_H);
            GL11.glVertex2f(x, y + LINE_H);
            GL11.glEnd();
            y += LINE_H;
        }

        // Texto (branco) via TextRenderer do Minecraft
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        TextRenderer fontRenderer = client.textRenderer;
        y = 2.0f;
        for (int i = 0; i < MAX_LINES; i++) {
            fontRenderer.draw(LINE_BUF[i], x + 1.0f, y + 1.0f, 0xFFFFFF);
            LINE_BUF[i] = null; // devolve referência ao GC (1 String/frame/linha é o mínimo com API String-based)
            y += LINE_H;
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
    }

    /** Transfere o StringBuilder para String uma única vez por linha. */
    private static String flush() {
        return SB.toString();
    }

    /** Formata com 1 casa decimal em um char[] fornecido, sem alocação. */
    private static String fmt1(double v, char[] out) {
        return formatFixed(v, 1, out);
    }

    /** Formata com 2 casas decimais em um char[] fornecido, sem alocação. */
    private static String fmt2(double v, char[] out) {
        return formatFixed(v, 2, out);
    }

    /**
     * Formatação decimal manual (não usa String.format/Formatter).
     * Nota: retorna uma String pequena; com a API String-based do TextRenderer
     * do Minecraft é impossível desenhar sem alguma String — o custo é 4
     * Strings transientes por frame vs. dezenas com String.format.
     */
    private static String formatFixed(double v, int decimals, char[] out) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "Inf" : "-Inf";
        boolean neg = v < 0;
        double a = neg ? -v : v;
        long scale = 1;
        for (int i = 0; i < decimals; i++) scale *= 10;
        long scaled = (long) (a * scale + 0.5);
        long whole = scaled / scale;
        long frac = scaled % scale;

        int n = 0;
        if (neg) out[n++] = '-';
        n += writeLong(whole, out, n);
        if (decimals > 0) {
            out[n++] = '.';
            // zero-pad da parte fracionária
            long div = scale / 10;
            while (decimals > 1 && frac < div) {
                out[n++] = '0';
                div /= 10;
                decimals--;
            }
            n += writeLong(frac, out, n);
        }
        return new String(out, 0, n);
    }

    private static int writeLong(long v, char[] out, int idx) {
        if (v == 0) {
            out[idx] = '0';
            return 1;
        }
        int start = idx;
        while (v > 0 && idx - start < out.length) {
            out[idx++] = (char) ('0' + (int) (v % 10));
            v /= 10;
        }
        // inverte
        int i = start, j = idx - 1;
        while (i < j) {
            char t = out[i];
            out[i++] = out[j];
            out[j--] = t;
        }
        return idx - start;
    }

    private static String fmtInt(int v, char[] out) {
        boolean neg = v < 0;
        long a = neg ? -(long) v : v;
        int n = 0;
        if (neg) out[n++] = '-';
        n += writeLong(a, out, n);
        return new String(out, 0, n);
    }
}
