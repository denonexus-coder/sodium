package me.jellysquid.mods.sodium.client.render.asmd;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.nio.ByteBuffer;

/**
 * ASMD Overlay — HUD em tempo real do motor adaptativo.
 *
 * Zero-lag:
 * - Leitura direta do ByteBuffer (sem JNI, sem cópia)
 * - StringBuilder/char[] reutilizados (sem String.format)
 * - DrawContext (mesma fase 2D do F3), sem tocar GL manualmente
 * - Toggle por tecla (F8) — quando desligado: custo zero
 *
 * Layout:
 *   Linha 1: FPS | frame ms | draws | técnica
 *   Linha 2: K dinâmico | trend | SGD | tick ms
 *   Linha 3: chunks | ent | speed | GC | stand/sprint
 *   Linha 4: pos | yaw | view dist
 */
public final class ASMDOverlayRenderer {
    // Offsets do buffer (conforme ASMDJavaBridge)
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
    private static final int OFF_STANDSTILL = 92;
    private static final int OFF_SPRINTING = 96;
    // Engine state (offsets 104-127, preenchidos pelo C++)
    private static final int OFF_TECHNIQUE = 104;
    private static final int OFF_DYNAMIC_K = 108;
    private static final int OFF_TREND = 112;
    private static final int OFF_SGD = 116;
    private static final int OFF_DRAW_COUNT = 120;
    private static final int OFF_FRAME_MS = 124;

    private static final int MAX_LINES = 4;
    private static final int LINE_H = 10;
    private static final int PAD = 3;

    // Toggle (F8)
    private static boolean visible = false;

    // Reutilizados entre frames — zero alocação no caminho quente.
    private static final StringBuilder SB = new StringBuilder(200);
    private static final char[] DEC1 = new char[16];
    private static final char[] DEC2 = new char[16];
    private static final char[] INT_TMP = new char[12];

    private ASMDOverlayRenderer() {}

    public static void toggle() {
        visible = !visible;
    }

    public static boolean isVisible() {
        return visible;
    }

    public static void render(DrawContext context, float tickDelta, MinecraftClient client) {
        if (!visible || context == null || client == null) {
            return;
        }
        ByteBuffer buf = ASMDJavaBridge.buffer();

        // --- Coleta em primitivos (zero-allocation) ---
        float tickMs = buf.getFloat(OFF_SERVER_TICK);
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

        // Engine state (offsets 104-127)
        int technique = buf.getInt(OFF_TECHNIQUE);
        int dynK = buf.getInt(OFF_DYNAMIC_K);
        int trend = buf.getInt(OFF_TREND);
        float sgd = buf.getFloat(OFF_SGD);
        int drawCount = buf.getInt(OFF_DRAW_COUNT);
        float frameMs = buf.getFloat(OFF_FRAME_MS);

        float fps = (float) (1000.0 / Math.max(frameMs, 1.0));

        // --- Linha 1: FPS | frame | draws | técnica ---
        SB.setLength(0);
        SB.append("ASMD v6 | ").append(fmt(fps, 0, DEC1)).append(" fps")
          .append(" | ").append(fmt(frameMs, 1, DEC1)).append(" ms")
          .append(" | ").append(drawCount).append(" draws")
          .append(" | ").append(techName(technique));
        String line0 = SB.toString();

        // --- Linha 2: K | trend | SGD | tick ---
        SB.setLength(0);
        SB.append("K=").append(dynK)
          .append(" | ").append(trendName(trend))
          .append(" | SGD: ").append(fmt(sgd, 1, DEC1))
          .append(" | Tick: ").append(fmtInt((int) tickMs, INT_TMP)).append("ms")
          .append(" | GC: ").append(gc == 1 ? "ON" : "off");
        String line1 = SB.toString();

        // --- Linha 3: chunks | ent | speed | stand/sprint ---
        SB.setLength(0);
        SB.append("CQ:").append(chunkQueue)
          .append(" Ent:").append(visibleEnts)
          .append(" Tile:").append(visibleTiles)
          .append(" | ").append(fmt(speed, 1, DEC1)).append(" m/s")
          .append(" | ").append(standstill == 1 ? "SS" : "--")
          .append(sprinting == 1 ? " SP" : "");
        String line2 = SB.toString();

        // --- Linha 4: pos | yaw | view ---
        SB.setLength(0);
        SB.append("Pos: ").append(fmt(px, 0, DEC1)).append(", ").append(fmt(pz, 0, DEC1))
          .append(" | Yaw: ").append(fmtInt((int) yaw, INT_TMP))
          .append(" | View: ").append(viewDist);
        String line3 = SB.toString();

        TextRenderer font = client.textRenderer;
        int x = PAD;

        // Fundo (uma passada de fill)
        int w = Math.max(
            Math.max(font.getWidth(line0), font.getWidth(line1)),
            Math.max(font.getWidth(line2), font.getWidth(line3))) + 8;
        int top = PAD;
        int bottom = PAD + (MAX_LINES * LINE_H) + 2;
        context.fill(x, top, x + w, bottom, 0xB0000000);

        // Texto com cor por linha
        int y = PAD + 1;
        context.drawTextWithShadow(font, line0, x + 2, y, techColor(technique));
        y += LINE_H;
        context.drawTextWithShadow(font, line1, x + 2, y, 0x55FF55);
        y += LINE_H;
        context.drawTextWithShadow(font, line2, x + 2, y, 0xAAAAFF);
        y += LINE_H;
        context.drawTextWithShadow(font, line3, x + 2, y, 0xCCCCCC);
    }

    private static String techName(int t) {
        return switch (t) {
            case 1 -> "UNROLL";
            case 2 -> "MERGED";
            case 3 -> "UBO";
            case 4 -> "PANIC";
            default -> "---";
        };
    }

    private static int techColor(int t) {
        return switch (t) {
            case 1 -> 0x55FF55;  // verde
            case 2 -> 0x55AAFF;  // azul
            case 3 -> 0xFF55FF;  // roxo
            case 4 -> 0xFF5555;  // vermelho
            default -> 0xFFFFFF;
        };
    }

    private static String trendName(int t) {
        return switch (t) {
            case -1 -> "UP";
            case 1 -> "DOWN";
            default -> "FLAT";
        };
    }

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
