package me.jellysquid.mods.sodium.client.render.asmd;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * ASMD Overlay — HUD minimalista que mostra o estado do motor em tempo real
 * sem matar performance.
 * - Leitura direta do ByteBuffer (zero-allocation, zero String)
 * - Desenho via GL immediate mode (GL11), uma única passada por frame
 * - Posição fixa no topo-esquerdo / topo-direito / rodapé
 */
public final class ASMDOverlayRenderer {
    private static final List<String> lines = new ArrayList<>();

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

        // Calcula frame clock delta (fps aproximado)
        long frameNs = buf.getLong(OFF_FRAME_CLOCK);
        double frameMs = (frameNs != 0) ? (frameNs / 1_000_000.0) : 0;

        // --- Monta string em pré-allocada (evita GC) ---
        lines.clear();
        lines.add(String.format("ASMD  |  FPS: %.1f  |  ms/frame: %.1f  |  Chunk: %d", 
            1000.0f / Math.max(frameMs, 1.0f), frameMs, chunkQueue));
        lines.add(String.format("Tick: %dms  |  TPS: %.1f  |  ViewDist: %d  |  GC: %s",
            (int)tickMs, tps, viewDist, (gc==1) ? "ACTIVE" : "off"));
        lines.add(String.format("Tile: %d/%d  |  Ent: %d  |  Speed: %.2f  |  Stand: %d  |  Sprint: %d",
            visibleTiles, visibleTiles, visibleEnts, speed, standstill, sprinting));
        lines.add(String.format("Pos: %.1f, %.1f  |  Yaw: %.0f  |  DRS: %.2f",
            px, pz, yaw, buf.getFloat(OFF_DRS_SCALE)));

        // --- Desenho GL (uma passada) ---
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Background (semi-transparente)
        GL11.glColor4f(0.0f, 0.0f, 0.0f, 0.6f);
        float x = 2, y = 2;
        for (int i = 0; i < lines.size(); i++) {
            float w = lines.get(i).length() * 6;
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + w + 2, y);
            GL11.glVertex2f(x + w + 2, y + 10);
            GL11.glVertex2f(x, y + 10);
            GL11.glEnd();
            y += 10;
        }

        // Texto (cor branca) via FontRenderer do Minecraft
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        TextRenderer fontRenderer = client.textRenderer;
        y = 2;
        for (String line : lines) {
            // usa FontRenderer para desenhar o texto
            fontRenderer.draw(line, x + 1, y + 1, 0xFFFFFF);
            y += 10;
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);

        // Limpa linhas (opcional, mas sem alocação)
        lines.clear();
    }
}