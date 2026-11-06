package me.jellysquid.mods.sodium.client.render.chunk.greedy;

import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.BakedChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Ponte entre o UltraGreedyMesher e os buffers nativos do Sodium.
 *
 * Recebe um QuadBuffer (saída primitiva do greedy mesher) e escreve os
 * vértices diretamente nos ChunkMeshBufferBuilder do Sodium, reutilizando
 * o encoder existente para manter iluminação, cores e UVs consistentes.
 */
public final class GreedyMeshRenderer {

    private GreedyMeshRenderer() {}

    /**
     * Escreve os quads do buffer em um modelo assado existente.
     *
     * @param buffer   QuadBuffer com os quads mesclados
     * @param blocks   Array de block states da seção (índice = stateIdx no buffer)
     * @param builder  Modelo assado alvo (já iniciado via begin())
     * @param sectionIndex Índice da seção para o encoder
     */
    public static void renderQuads(
            UltraGreedyMesher.QuadBuffer buffer,
            BlockState[] blocks,
            BakedChunkModelBuilder builder,
            int sectionIndex) {

        for (int i = 0; i < buffer.size; i++) {
            int faceOrd = buffer.face[i];
            int x = buffer.x[i];
            int y = buffer.y[i];
            int z = buffer.z[i];
            int w = buffer.w[i];
            int h = buffer.h[i];
            int stateIdx = buffer.stateIdx[i];

            BlockState state = blocks[stateIdx];
            Direction face = Direction.values()[faceOrd];

            // Material baseado no bloco
            me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material material =
                    me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials.forBlockState(state);

            ChunkModelBuilder modelBuilder = builder.getVertexBuffer(
                    me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing.values()[faceOrd]);

            ChunkMeshBufferBuilder vb = modelBuilder.getVertexBuffer(me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing.values()[faceOrd]);

            ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

            // Posições dos 4 cantos do quad (baseado na face)
            // x, y, z são as coordenadas do canto inferior-esquerdo do quad
            // w = largura, h = altura
            float fx = x;
            float fy = y;
            float fz = z;

            switch (face) {
                case DOWN:
                    // Quad no plano XZ, y constante
                    vertices[0].x = fx;     vertices[0].y = fy;     vertices[0].z = fz;
                    vertices[1].x = fx + w; vertices[1].y = fy;     vertices[1].z = fz;
                    vertices[2].x = fx + w; vertices[2].y = fy;     vertices[2].z = fz + h;
                    vertices[3].x = fx;     vertices[3].y = fy;     vertices[3].z = fz + h;
                    break;
                case UP:
                    vertices[0].x = fx;     vertices[0].y = fy + 1; vertices[0].z = fz;
                    vertices[1].x = fx + w; vertices[1].y = fy + 1; vertices[1].z = fz;
                    vertices[2].x = fx + w; vertices[2].y = fy + 1; vertices[2].z = fz + h;
                    vertices[3].x = fx;     vertices[3].y = fy + 1; vertices[3].z = fz + h;
                    break;
                case NORTH:
                    // Quad no plano YZ, z constante
                    vertices[0].x = fx;     vertices[0].y = fy;     vertices[0].z = fz;
                    vertices[1].x = fx;     vertices[1].y = fy + h; vertices[1].z = fz;
                    vertices[2].x = fx;     vertices[2].y = fy + h; vertices[2].z = fz + 1;
                    vertices[3].x = fx;     vertices[3].y = fy;     vertices[3].z = fz + 1;
                    break;
                case SOUTH:
                    vertices[0].x = fx;     vertices[0].y = fy;     vertices[0].z = fz + 1;
                    vertices[1].x = fx;     vertices[1].y = fy + h; vertices[1].z = fz + 1;
                    vertices[2].x = fx;     vertices[2].y = fy + h; vertices[2].z = fz;
                    vertices[3].x = fx;     vertices[3].y = fy;     vertices[3].z = fz;
                    break;
                case WEST:
                    // Quad no plano XZ, x constante
                    vertices[0].x = fx;     vertices[0].y = fy;     vertices[0].z = fz;
                    vertices[1].x = fx;     vertices[1].y = fy + h; vertices[1].z = fz;
                    vertices[2].x = fx;     vertices[2].y = fy + h; vertices[2].z = fz + w;
                    vertices[3].x = fx;     vertices[3].y = fy;     vertices[3].z = fz + w;
                    break;
                case EAST:
                default:
                    vertices[0].x = fx + 1; vertices[0].y = fy;     vertices[0].z = fz;
                    vertices[1].x = fx + 1; vertices[1].y = fy + h; vertices[1].z = fz;
                    vertices[2].x = fx + 1; vertices[2].y = fy + h; vertices[2].z = fz + w;
                    vertices[3].x = fx + 1; vertices[3].y = fy;     vertices[3].z = fz + w;
                    break;
            }

            // UVs padrão (0-1), cor branca, luz máxima
            for (int v = 0; v < 4; v++) {
                vertices[v].u = (v % 2 == 0) ? 0.0f : 1.0f;
                vertices[v].v = (v < 2) ? 0.0f : 1.0f;
                vertices[v].color = 0xFFFFFFFF; // branco
                vertices[v].light = 0xFFFFFF;   // luz máxima
            }

            // Escreve no buffer nativo via encoder
            vb.push(vertices, material);
        }
    }
}
