package me.jellysquid.mods.sodium.client.render.asmd;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.nio.ByteBuffer;

/**
 * ASMD Overlay — HUD minimalista que mostra o estado do motor em tempo real
 * sem matar performance.
 *
 * - Leitura direta do ByteBuffer: zero alocação para coleta de dados
 * - Formatação manual em StringBuilder/char[] reutilizados (sem String.format,
 *   que alocaria Formatter + varargs + dezenas de Strings por frame)
 * - Desenho via DrawContext (fill + drawTextWithShadow), mesma fase 2D do F3,
 *   sem tocar no estado GL manualmente
 *
 * Custo por frame com o overlay ligado: 4 Strings pequenas transientes
 * (a API de texto do Minecraft só aceita String) + ~8 draw calls.
 * Com o overlay desligado: nenhum.
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
    private static final int LINE_H = 10;
    private static final int PAD = 2;

    // Reutilizados entre frames — nenhuma alocação no caminho quente.
    private static final StringBuilder SB = new StringBuilder(160);
    private static final char[] DEC1 = new char[16];
    private static final char[] DEC2 = new char[16];
    private static final char[] INT_TMP = new char[12];

    private ASMDOverlayRenderer() {}

    public static void render(DrawContext context, float tickDelta, MinecraftClient client) {
        if (context == null || client == null) {
            return;
        }
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

        // --- Monta linhas em buffer reutilizado (sem String.format) ---
        SB.setLength(0);
        SB.append("ASMD | FPS: ").append(fmt(fps, 1, DEC1))
          .append(" | ms: ").append(fmt(frameMs, 1, DEC1))
          .append(" | ChunkQ: ").append(chunkQueue);
        String line0 = SB.toString();

        SB.setLength(0);
        SB.append("Tick: ").append(fmtInt((int) tickMs, INT_TMP))
          .append("ms | TPS: ").append(fmt(tps, 1, DEC1))
          .append(" | View: ").append(viewDist)
          .append(" | GC: ").append(gc == 1 ? "ACTIVE" : "off");
        String line1 = SB.toString();

        SB.setLength(0);
        SB.append("Tile: ").append(visibleTiles)
          .append(" | Ent: ").append(visibleEnts)
          .append(" | Speed: ").append(fmt(speed, 2, DEC2))
          .append(" | Stand: ").append(standstill)
          .append(" | Sprint: ").append(sprinting);
        String line2 = SB.toString();

        SB.setLength(0);
        SB.append("Pos: ").append(fmt(px, 1, DEC1))
          .append(", ").append(fmt(pz, 1, DEC1))
          .append(" | Yaw: ").append(fmtInt((int) yaw, INT_TMP))
          .append(" | DRS: ").append(fmt(drs, 2, DEC2));
        String line3 = SB.toString();

        TextRenderer font = client.textRenderer;
        int x = PAD;

        // Fundo (uma passada de fill por linha)
        int top = PAD;
        int bottom = PAD + (MAX_LINES * LINE_H);
        context.fill(x, top, x + widthOf(font, line0, line2), bottom, 0xA0000000);

        // Texto (branco com sombra) — fase 2D do HUD, por cima do mundo
        int y = PAD;
        context.drawTextWithShadow(font, line0, x + 1, y + 1, 0xFFFFFF);
        y += LINE_H;
        context.drawTextWithShadow(font, line1, x + 1, y + 1, 0xFFFFFF);
        y += LINE_H;
        context.drawTextWithShadow(font, line2, x + 1, y + 1, 0xFFFFFF);
        y += LINE_H;
        context.drawTextWithShadow(font, line3, x + 1, y + 1, 0xFFFFFF);
    }

    /** Largura do fundo: a maior das duas linhas medidas (evita medida por linha). */
    private static int widthOf(TextRenderer font, String a, String b) {
        int wa = font.getWidth(a);
        int wb = font.getWidth(b);
        return Math.max(wa, wb) + 6;
    }

    /**
     * Formatação decimal manual (não usa String.format/Formatter).
     * O resultado é um char[] fornecido pelo chamador; a String retornada é o
     * mínimo possível com a API String-based do TextRenderer.
     */
    private static String fmt(double v, int decimals, char[] out) {
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
            long div = scale / 10;
            int d = decimals;
            while (d > 1 && frac < div) {
                out[n++] = '0';
                div /= 10;
                d--;
            }
            n += writeLong(frac, out, n);
        }
        return new String(out, 0, n);
    }

    private static String fmtInt(int v, char[] out) {
        boolean neg = v < 0;
        long a = neg ? -(long) v : v;
        int n = 0;
        if (neg) out[n++] = '-';
        n += writeLong(a, out, n);
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
        int i = start, j = idx - 1;
        while (i < j) {
            char t = out[i];
            out[i++] = out[j];
            out[j--] = t;
        }
        return idx - start;
    }
}
