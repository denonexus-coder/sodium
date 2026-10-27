package me.jellysquid.mods.sodium.client.render.chunk.shader;

import com.mojang.blaze3d.platform.GlStateManager;
import me.jellysquid.mods.sodium.client.gl.device.GLRenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.infinitium.InfinitiumLightEngine;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import me.jellysquid.mods.sodium.client.util.TextureUtil;
import me.jellysquid.mods.sodium.mixin.core.render.texture.SpriteAtlasTextureAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL32C;

import java.util.EnumMap;
import java.util.Map;

/**
 * A forward-rendering shader program for chunks.
 */
public class ChunkShaderInterface {
    private final Map<ChunkShaderTextureSlot, GlUniformInt> uniformTextures;

    private final GlUniformMatrix4f uniformModelViewMatrix;
    private final GlUniformMatrix4f uniformProjectionMatrix;
    private final GlUniformFloat3v uniformRegionOffset;
    private final GlUniformFloat2v uniformTexCoordShrink;

    // Buffers de trabalho da luz da mao: a luz e lida por pass de render, nao pode alocar no
    // caminho quente.
    private static final float[] HAND_LIGHT = new float[4];
    private static final float[] HAND_COLOR = new float[3];

    // INFINITIUM LIGHT ENGINE -- existem apenas quando o programa e compilado com
    // INFINITIUM_LIGHT; com o motor desligado sao todos null e nada e carregado.
    private final GlUniformInt uniformInfinitiumTex;
    private final GlUniformFloat3v uniformInfinitiumOrigin;
    private final GlUniformFloat3v uniformInfinitiumScale;
    private final GlUniformFloat uniformInfinitiumTint;
    private final GlUniformFloat4v uniformInfinitiumHand;
    private final GlUniformFloat3v uniformInfinitiumHandColor;

    // The fog shader component used by this program in order to setup the appropriate GL state
    private final ChunkShaderFogComponent fogShader;

    public ChunkShaderInterface(ShaderBindingContext context, ChunkShaderOptions options) {
        this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformProjectionMatrix = context.bindUniform("u_ProjectionMatrix", GlUniformMatrix4f::new);
        this.uniformRegionOffset = context.bindUniform("u_RegionOffset", GlUniformFloat3v::new);
        this.uniformTexCoordShrink = context.bindUniform("u_TexCoordShrink", GlUniformFloat2v::new);

        this.uniformTextures = new EnumMap<>(ChunkShaderTextureSlot.class);
        this.uniformTextures.put(ChunkShaderTextureSlot.BLOCK, context.bindUniform("u_BlockTex", GlUniformInt::new));
        this.uniformTextures.put(ChunkShaderTextureSlot.LIGHT, context.bindUniform("u_LightTex", GlUniformInt::new));

        if (options.infinitium()) {
            this.uniformInfinitiumTex = context.bindUniform("u_InfinitiumTex", GlUniformInt::new);
            this.uniformInfinitiumOrigin = context.bindUniform("u_InfinitiumOrigin", GlUniformFloat3v::new);
            this.uniformInfinitiumScale = context.bindUniform("u_InfinitiumScale", GlUniformFloat3v::new);
            this.uniformInfinitiumTint = context.bindUniform("u_InfinitiumTint", GlUniformFloat::new);
            this.uniformInfinitiumHand = context.bindUniform("u_InfinitiumHand", GlUniformFloat4v::new);
            this.uniformInfinitiumHandColor = context.bindUniform("u_InfinitiumHandColor", GlUniformFloat3v::new);
        } else {
            this.uniformInfinitiumTex = null;
            this.uniformInfinitiumOrigin = null;
            this.uniformInfinitiumScale = null;
            this.uniformInfinitiumTint = null;
            this.uniformInfinitiumHand = null;
            this.uniformInfinitiumHandColor = null;
        }

        this.fogShader = options.fog().getFactory().apply(context);
    }

    @Deprecated // the shader interface should not modify pipeline state
    public void setupState() {
        this.bindTexture(ChunkShaderTextureSlot.BLOCK, TextureUtil.getBlockTextureId());
        this.bindTexture(ChunkShaderTextureSlot.LIGHT, TextureUtil.getLightTextureId());

        if (this.uniformInfinitiumTex != null) {
            this.setupInfinitiumState();
        }

        var textureAtlas = (SpriteAtlasTextureAccessor) MinecraftClient.getInstance()
                .getTextureManager()
                .getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);

        // There is a limited amount of sub-texel precision when using hardware texture sampling. The mapped texture
        // area must be "shrunk" by at least one sub-texel to avoid bleed between textures in the atlas. And since we
        // offset texture coordinates in the vertex format by one texel, we also need to undo that here.
        double subTexelPrecision = (1 << GLRenderDevice.INSTANCE.getSubTexelPrecisionBits());
        double subTexelOffset = 1.0f / CompactChunkVertex.TEXTURE_MAX_VALUE;

        this.uniformTexCoordShrink.set(
                (float) (subTexelOffset - (((1.0D / textureAtlas.getWidth()) / subTexelPrecision))),
                (float) (subTexelOffset - (((1.0D / textureAtlas.getHeight()) / subTexelPrecision)))
        );

        this.fogShader.setup();
    }

    /**
     * INFINITIUM LIGHT ENGINE (S7/S8). A grelha vive na unidade 2 (0 atlas, 1 lightmap); a
     * origem e camera-relativa porque o vertex shader trabalha nesse espaco. A luz da mao e
     * lida por frame, ja que depende da posicao da camera e do item na mao.
     */
    private void setupInfinitiumState() {
        this.bindTexture(ChunkShaderTextureSlot.INF, InfinitiumLightEngine.getTextureId());

        Vec3d cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

        this.uniformInfinitiumTex.setInt(ChunkShaderTextureSlot.INF.ordinal());

        this.uniformInfinitiumOrigin.set(
                InfinitiumLightEngine.getOriginX() - (float) cameraPos.x,
                InfinitiumLightEngine.getOriginY() - (float) cameraPos.y,
                InfinitiumLightEngine.getOriginZ() - (float) cameraPos.z);

        this.uniformInfinitiumScale.set(
                InfinitiumLightEngine.getScaleX(),
                InfinitiumLightEngine.getScaleY(),
                InfinitiumLightEngine.getScaleZ());

        this.uniformInfinitiumTint.setFloat(InfinitiumLightEngine.getTintStrength());

        InfinitiumLightEngine.writeHandLight(cameraPos, HAND_LIGHT, HAND_COLOR);

        this.uniformInfinitiumHand.set(HAND_LIGHT);
        this.uniformInfinitiumHandColor.set(HAND_COLOR);
    }

    @Deprecated(forRemoval = true) // should be handled properly in GFX instead.
    private void bindTexture(ChunkShaderTextureSlot slot, int textureId) {
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + slot.ordinal());
        GlStateManager._bindTexture(textureId);

        var uniform = this.uniformTextures.get(slot);
        uniform.setInt(slot.ordinal());
    }

    public void setProjectionMatrix(Matrix4fc matrix) {
        this.uniformProjectionMatrix.set(matrix);
    }

    public void setModelViewMatrix(Matrix4fc matrix) {
        this.uniformModelViewMatrix.set(matrix);
    }

    public void setRegionOffset(float x, float y, float z) {
        this.uniformRegionOffset.set(x, y, z);
    }
}
