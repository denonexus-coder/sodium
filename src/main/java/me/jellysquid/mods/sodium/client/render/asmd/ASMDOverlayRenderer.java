package me.jellysquid.mods.sodium.client.render.asmd;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.nio.ByteBuffer;

/**
 * ASMD Overlay v7 — HUD compacto do motor adaptativo.
 *
 * Layout (5 linhas, 1 bloco):
 *   1: FPS [min-med-max] | frame ms [min-med-max] | draws [min-med-max] | técnica
 *   2: K | trend | SGD | distribuição UNROLL/MERGED/UBO
 *   3: CPU draw ms | GPU ms | merge% | tick ms | GC
 *   4: chunks fila | ent | tile | speed | stand/sprint
 *   5: pos | yaw | view | GC churn
 *
 * Zero-lag: ByteBuffer direto, StringBuilder reutilizado, toggle F8.
 */
public final class ASMDOverlayRenderer {
    // Offsets do buffer (conforme ASMDJavaBridge)
    private static final int OFF_SERVER_TICK = 0;
    private static final int OFF_GC_IMMINENT = 8;
    private static final int OFF_GC_CHURN = 12;
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
    // Engine state
    private static final int OFF_TECHNIQUE = 104;
    private static final int OFF_DYNAMIC_K = 108;
    private static final int OFF_TREND = 112;
    private static final int OFF_SGD = 116;
    private static final int OFF_DRAW_COUNT = 120;
    private static final int OFF_FRAME_MS = 124;
    // Stats
    private static final int OFF_FPS_MIN = 128;
    private static final int OFF_FPS_MED = 132;
    private static final int OFF_FPS_MAX = 136;
    private static final int OFF_MS_MIN = 140;
    private static final int OFF_MS_MED = 144;
    private static final int OFF_MS_MAX = 148;
    private static final int OFF_DRAWS_MIN = 152;
    private static final int OFF_DRAWS_MED = 156;
    private static final int OFF_DRAWS_MAX = 160;
    private static final int OFF_GPU_MS = 164;
    private static final int OFF_CPU_DRAW_MS = 168;
    private static final int OFF_MERGE_RATIO = 172;
    private static final int OFF_TECH_UNROLL_PCT = 176;
    private static final int OFF_TECH_MERGED_PCT = 180;
    private static final int OFF_TECH_UBO_PCT = 184;
    private static final int OFF_GC_CHURN2 = 188;

    private static final int MAX_LINES = 5;
    private static final int LINE_H = 9;
    private static final int PAD = 3;

    private static boolean visible = false;

    private static final StringBuilder SB = new StringBuilder(256);
    private static final char[] DEC1 = new char[16];
    private static final char[] DEC2 = new char[16];
    private static final char[] INT_TMP = new char[12];

    private ASMDOverlayRenderer() {}

    public static void toggle() { visible = !visible; }
    public static boolean isVisible() { return visible; }

    public static void render(DrawContext context, float tickDelta, MinecraftClient client) {
        if (!visible || context == null || client == null) return;
        ByteBuffer buf = ASMDJavaBridge.buffer();

        // --- Coleta (zero-allocation) ---
        float tickMs = buf.getFloat(OFF_SERVER_TICK);
        int gc = buf.getInt(OFF_GC_IMMINENT);
        float gcChurn = buf.getFloat(OFF_GC_CHURN2);
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

        int technique = buf.getInt(OFF_TECHNIQUE);
        int dynK = buf.getInt(OFF_DYNAMIC_K);
        int trend = buf.getInt(OFF_TREND);
        float sgd = buf.getFloat(OFF_SGD);
        int drawCount = buf.getInt(OFF_DRAW_COUNT);
        float frameMs = buf.getFloat(OFF_FRAME_MS);

        float fpsMin = buf.getFloat(OFF_FPS_MIN);
        float fpsMed = buf.getFloat(OFF_FPS_MED);
        float fpsMax = buf.getFloat(OFF_FPS_MAX);
        float msMin = buf.getFloat(OFF_MS_MIN);
        float msMed = buf.getFloat(OFF_MS_MED);
        float msMax = buf.getFloat(OFF_MS_MAX);
        int drawsMin = buf.getInt(OFF_DRAWS_MIN);
        int drawsMed = buf.getInt(OFF_DRAWS_MED);
        int drawsMax = buf.getInt(OFF_DRAWS_MAX);
        float gpuMs = buf.getFloat(OFF_GPU_MS);
        float cpuMs = buf.getFloat(OFF_CPU_DRAW_MS);
        float mergePct = buf.getFloat(OFF_MERGE_RATIO) * 100.0f;
        float techUnroll = buf.getFloat(OFF_TECH_UNROLL_PCT);
        float techMerged = buf.getFloat(OFF_TECH_MERGED_PCT);
        float techUbo = buf.getFloat(OFF_TECH_UBO_PCT);

        float fps = (float) (1000.0 / Math.max(frameMs, 1.0));

        // --- Linha 1: FPS + frame + draws + técnica ---
        SB.setLength(0);
        SB.append(fmt(fps, 0, DEC1)).append("fps [")
          .append(fmt(fpsMin, 0, DEC1)).append("-")
          .append(fmt(fpsMed, 0, DEC1)).append("-")
          .append(fmt(fpsMax, 0, DEC1)).append("] ")
          .append("| ").append(fmt(frameMs, 1, DEC1)).append("ms [")
          .append(fmt(msMin, 1, DEC1)).append("-")
          .append(fmt(msMed, 1, DEC1)).append("-")
          .append(fmt(msMax, 1, DEC1)).append("] ")
          .append("| ").append(drawCount).append("d [")
          .append(drawsMin).append("-").append(drawsMed).append("-")
          .append(drawsMax).append("] ")
          .append("| ").append(techName(technique));
        String l0 = SB.toString();

        // --- Linha 2: K + trend + SGD + distribuição ---
        SB.setLength(0);
        SB.append("K:").append(dynK)
          .append(" ").append(trendName(trend))
          .append(" SGD:").append(fmt(sgd, 1, DEC1))
          .append(" | U:").append(fmt(techUnroll, 0, DEC1)).append("%")
          .append(" M:").append(fmt(techMerged, 0, DEC1)).append("%")
          .append(" B:").append(fmt(techUbo, 0, DEC1)).append("%");
        String l1 = SB.toString();

        // --- Linha 3: CPU/GPU/merge/tick/GC ---
        SB.setLength(0);
        SB.append("CPU:").append(fmt(cpuMs, 2, DEC1)).append("ms")
          .append(" GPU:").append(fmt(gpuMs, 2, DEC1)).append("ms")
          .append(" Merge:").append(fmt(mergePct, 0, DEC1)).append("%")
          .append(" | Tick:").append(fmtInt((int) tickMs, INT_TMP)).append("ms")
          .append(" GC:").append(gc == 1 ? "!" : "-")
          .append(" ").append(fmt(gcChurn, 1, DEC1)).append("MB/s");
        String l2 = SB.toString();

        // --- Linha 4: chunks/ent/tile/speed/flags ---
        SB.setLength(0);
        SB.append("CQ:").append(chunkQueue)
          .append(" E:").append(visibleEnts)
          .append(" T:").append(visibleTiles)
          .append(" | ").append(fmt(speed, 1, DEC1)).append("m/s")
          .append(" | ").append(standstill == 1 ? "SS" : "--")
          .append(sprinting == 1 ? " SP" : "")
          .append(" | View:").append(viewDist);
        String l3 = SB.toString();

        // --- Linha 5: pos/yaw ---
        SB.setLength(0);
        SB.append("Pos:").append(fmt(px, 0, DEC1))
          .append(",").append(fmt(pz, 0, DEC1))
          .append(" Yaw:").append(fmtInt((int) yaw, INT_TMP));
        String l4 = SB.toString();

        TextRenderer font = client.textRenderer;
        int x = PAD;

        // Fundo
        int w = 0;
        w = Math.max(w, font.getWidth(l0));
        w = Math.max(w, font.getWidth(l1));
        w = Math.max(w, font.getWidth(l2));
        w = Math.max(w, font.getWidth(l3));
        w = Math.max(w, font.getWidth(l4));
        w += 8;
        int top = PAD;
        int bottom = PAD + (MAX_LINES * LINE_H) + 2;
        context.fill(x, top, x + w, bottom, 0xB0000000);

        // Texto
        int y = PAD + 1;
        context.drawTextWithShadow(font, l0, x + 2, y, techColor(technique));
        y += LINE_H;
        context.drawTextWithShadow(font, l1, x + 2, y, 0x55FF55);
        y += LINE_H;
        context.drawTextWithShadow(font, l2, x + 2, y, 0xFFFF55);
        y += LINE_H;
        context.drawTextWithShadow(font, l3, x + 2, y, 0x55AAFF);
        y += LINE_H;
        context.drawTextWithShadow(font, l4, x + 2, y, 0xAAAAAA);
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
            case 1 -> 0x55FF55;
            case 2 -> 0x55AAFF;
            case 3 -> 0xFF55FF;
            case 4 -> 0xFF5555;
            default -> 0xFFFFFF;
        };
    }

    private static String trendName(int t) {
        return switch (t) {
            case -1 -> "UP";
            case 1 -> "DN";
            default -> "--";
        };
    }

    private static String fmt(double v, int decimals, char[] out) {
        if (Double.isNaN(v)) return "0";
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
            while (d > 1 && frac < div) { out[n++] = '0'; div /= 10; d--; }
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
        if (v == 0) { out[idx] = '0'; return 1; }
        int start = idx;
        while (v > 0 && idx - start < out.length) {
            out[idx++] = (char) ('0' + (int) (v % 10));
            v /= 10;
        }
        int i = start, j = idx - 1;
        while (i < j) { char t = out[i]; out[i++] = out[j]; out[j--] = t; }
        return idx - start;
    }
}
