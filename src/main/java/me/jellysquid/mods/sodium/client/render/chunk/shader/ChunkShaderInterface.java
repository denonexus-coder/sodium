package me.jellysquid.mods.sodium.client.render.chunk.shader;

import com.mojang.blaze3d.platform.GlStateManager;
import me.jellysquid.mods.sodium.client.gl.device.GLRenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.ColoredLighting;
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

    private final GlUniformInt uniformColorLightTex;
    private final GlUniformFloat3v uniformColorLightOrigin;
    private final GlUniformFloat3v uniformColorLightScale;
    private final GlUniformFloat4v uniformHandLight;
    private final GlUniformFloat3v uniformHandLightColor;

    private final float[] handLight = new float[4];
    private final float[] handColor = new float[3];

    // The fog shader component used by this program in order to setup the appropriate GL state
    private final ChunkShaderFogComponent fogShader;

    public ChunkShaderInterface(ShaderBindingContext context, ChunkShaderOptions options, boolean coloredLighting) {
        this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformProjectionMatrix = context.bindUniform("u_ProjectionMatrix", GlUniformMatrix4f::new);
        this.uniformRegionOffset = context.bindUniform("u_RegionOffset", GlUniformFloat3v::new);
        this.uniformTexCoordShrink = context.bindUniform("u_TexCoordShrink", GlUniformFloat2v::new);

        this.uniformTextures = new EnumMap<>(ChunkShaderTextureSlot.class);
        this.uniformTextures.put(ChunkShaderTextureSlot.BLOCK, context.bindUniform("u_BlockTex", GlUniformInt::new));
        this.uniformTextures.put(ChunkShaderTextureSlot.LIGHT, context.bindUniform("u_LightTex", GlUniformInt::new));

        if (coloredLighting) {
            this.uniformColorLightTex = context.bindUniform("u_ColorLightTex", GlUniformInt::new);
            this.uniformColorLightOrigin = context.bindUniform("u_ColorLightOrigin", GlUniformFloat3v::new);
            this.uniformColorLightScale = context.bindUniform("u_ColorLightScale", GlUniformFloat3v::new);
            this.uniformHandLight = context.bindUniform("u_HandLight", GlUniformFloat4v::new);
            this.uniformHandLightColor = context.bindUniform("u_HandLightColor", GlUniformFloat3v::new);
        } else {
            this.uniformColorLightTex = null;
            this.uniformColorLightOrigin = null;
            this.uniformColorLightScale = null;
            this.uniformHandLight = null;
            this.uniformHandLightColor = null;
        }

        this.fogShader = options.fog().getFactory().apply(context);
    }

    @Deprecated // the shader interface should not modify pipeline state
    public void setupState() {
        this.bindTexture(ChunkShaderTextureSlot.BLOCK, TextureUtil.getBlockTextureId());
        this.bindTexture(ChunkShaderTextureSlot.LIGHT, TextureUtil.getLightTextureId());

        if (this.uniformColorLightTex != null) {
            // Unit 2 is free: the block atlas is 0 and the lightmap is 1. The colored-light
            // texture is a normal 2D atlas, so GlStateManager can bind it without the 3D-target
            // trap that some GLES translation layers crash on.
            GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 2);
            GlStateManager._bindTexture(ColoredLighting.getTextureId());
            this.uniformColorLightTex.setInt(2);

            Vec3d cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

            // The shader works in camera-relative world space, so the grid origin moves with the
            // camera instead of with the player. The grid itself stays anchored to the world.
            this.uniformColorLightOrigin.set(
                    ColoredLighting.getOriginX() - (float) cameraPos.x,
                    ColoredLighting.getOriginY() - (float) cameraPos.y,
                    ColoredLighting.getOriginZ() - (float) cameraPos.z);

            this.uniformColorLightScale.set(
                    ColoredLighting.getScaleX(),
                    ColoredLighting.getScaleY(),
                    ColoredLighting.getScaleZ());

            ColoredLighting.writeHandLight(cameraPos, this.handLight, this.handColor);
            this.uniformHandLight.set(this.handLight);
            this.uniformHandLightColor.set(this.handColor);

            // Leave the texture unit in the state the next renderer expects.
            GlStateManager._activeTexture(GL32C.GL_TEXTURE0);
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
