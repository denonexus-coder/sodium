package me.jellysquid.mods.sodium.client.render.chunk.shader;

import com.mojang.blaze3d.platform.GlStateManager;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.device.GLRenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3Array;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4Array;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.AnalyticLighting;
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

    // Grid engine: a 2D atlas sampled by the shader.
    private final GlUniformInt uniformColorLightTex;
    private final GlUniformFloat3v uniformColorLightOrigin;
    private final GlUniformFloat3v uniformColorLightScale;

    // Analytic engine: a short list of lights summed per vertex.
    private final GlUniformFloat4Array uniformLightPos;
    private final GlUniformFloat3Array uniformLightColor;
    private final GlUniformInt uniformLightCount;

    // Both engines share the hand light.
    private final GlUniformFloat4v uniformHandLight;
    private final GlUniformFloat3v uniformHandLightColor;
    private final GlUniformFloat uniformCameraY;

    // Colored shadows are part of the colored-light vertex path.
    private final GlUniformFloat uniformShadowStrength;
    private final GlUniformFloat uniformShadowDepth;
    private final GlUniformFloat uniformShadowTint;

    // Surface reflection is an optional fragment path; the uniforms exist only when compiled in.
    private final GlUniformFloat uniformReflectionStrength;
    private final GlUniformFloat uniformReflectionSoftness;

    private final float[] handLight = new float[4];
    private final float[] handColor = new float[3];
    private final float[] lightPositions;
    private final float[] lightColors;

    // The fog shader component used by this program in order to setup the appropriate GL state
    private final ChunkShaderFogComponent fogShader;

    public ChunkShaderInterface(ShaderBindingContext context, ChunkShaderOptions options, boolean coloredLighting, boolean analyticLighting) {
        this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformProjectionMatrix = context.bindUniform("u_ProjectionMatrix", GlUniformMatrix4f::new);
        this.uniformRegionOffset = context.bindUniform("u_RegionOffset", GlUniformFloat3v::new);
        this.uniformTexCoordShrink = context.bindUniform("u_TexCoordShrink", GlUniformFloat2v::new);

        this.uniformTextures = new EnumMap<>(ChunkShaderTextureSlot.class);
        this.uniformTextures.put(ChunkShaderTextureSlot.BLOCK, context.bindUniform("u_BlockTex", GlUniformInt::new));
        this.uniformTextures.put(ChunkShaderTextureSlot.LIGHT, context.bindUniform("u_LightTex", GlUniformInt::new));

        if (coloredLighting) {
            this.uniformHandLight = context.bindUniform("u_HandLight", GlUniformFloat4v::new);
            this.uniformHandLightColor = context.bindUniform("u_HandLightColor", GlUniformFloat3v::new);
            this.uniformCameraY = context.bindUniform("u_CameraY", GlUniformFloat::new);
            this.uniformShadowStrength = context.bindUniform("u_ShadowStrength", GlUniformFloat::new);
            this.uniformShadowDepth = context.bindUniform("u_ShadowDepth", GlUniformFloat::new);
            this.uniformShadowTint = context.bindUniform("u_ShadowTint", GlUniformFloat::new);
        } else {
            this.uniformHandLight = null;
            this.uniformHandLightColor = null;
            this.uniformCameraY = null;
            this.uniformShadowStrength = null;
            this.uniformShadowDepth = null;
            this.uniformShadowTint = null;
        }

        if (options.surfaceReflection()) {
            this.uniformReflectionStrength = context.bindUniform("u_ReflectionStrength", GlUniformFloat::new);
            this.uniformReflectionSoftness = context.bindUniform("u_ReflectionSoftness", GlUniformFloat::new);
        } else {
            this.uniformReflectionStrength = null;
            this.uniformReflectionSoftness = null;
        }

        if (coloredLighting && !analyticLighting) {
            this.uniformColorLightTex = context.bindUniform("u_ColorLightTex", GlUniformInt::new);
            this.uniformColorLightOrigin = context.bindUniform("u_ColorLightOrigin", GlUniformFloat3v::new);
            this.uniformColorLightScale = context.bindUniform("u_ColorLightScale", GlUniformFloat3v::new);
        } else {
            this.uniformColorLightTex = null;
            this.uniformColorLightOrigin = null;
            this.uniformColorLightScale = null;
        }

        if (analyticLighting) {
            this.uniformLightPos = context.bindUniform("u_LightPos", GlUniformFloat4Array::new);
            this.uniformLightColor = context.bindUniform("u_LightColor", GlUniformFloat3Array::new);
            this.uniformLightCount = context.bindUniform("u_LightCount", GlUniformInt::new);

            int maxLights = ColoredLighting.getMaxLights();
            this.lightPositions = new float[maxLights * 4];
            this.lightColors = new float[maxLights * 3];
        } else {
            this.uniformLightPos = null;
            this.uniformLightColor = null;
            this.uniformLightCount = null;
            this.lightPositions = null;
            this.lightColors = null;
        }

        this.fogShader = options.fog().getFactory().apply(context);
    }

    @Deprecated // the shader interface should not modify pipeline state
    public void setupState() {
        this.bindTexture(ChunkShaderTextureSlot.BLOCK, TextureUtil.getBlockTextureId());
        this.bindTexture(ChunkShaderTextureSlot.LIGHT, TextureUtil.getLightTextureId());

        Vec3d cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

        if (this.uniformColorLightTex != null) {
            // Unit 2 is free: the block atlas is 0 and the lightmap is 1. The colored-light
            // texture is a normal 2D atlas, so GlStateManager can bind it without the 3D-target
            // trap that some GLES translation layers crash on.
            GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 2);
            GlStateManager._bindTexture(ColoredLighting.getTextureId());
            this.uniformColorLightTex.setInt(2);

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

            // Leave the texture unit in the state the next renderer expects.
            GlStateManager._activeTexture(GL32C.GL_TEXTURE0);
        }

        if (this.uniformHandLight != null) {
            ColoredLighting.writeHandLight(cameraPos, this.handLight, this.handColor);
            this.uniformHandLight.set(this.handLight);
            this.uniformHandLightColor.set(this.handColor);
            this.uniformCameraY.setFloat((float) cameraPos.y);
        }

        if (this.uniformShadowStrength != null) {
            var experimental = SodiumClientMod.options().experimental;
            boolean enabled = experimental.coloredShadows && !ColoredLighting.isIrisLoaded();

            this.uniformShadowStrength.setFloat(enabled ? percent(experimental.shadowStrength) : 0.0F);
            this.uniformShadowDepth.setFloat(enabled ? percent(experimental.shadowDepth) : 0.0F);
            this.uniformShadowTint.setFloat(enabled ? percent(experimental.shadowTint) : 0.0F);
        }

        if (this.uniformReflectionStrength != null) {
            var experimental = SodiumClientMod.options().experimental;
            boolean enabled = experimental.surfaceReflection && !ColoredLighting.isIrisLoaded();

            this.uniformReflectionStrength.setFloat(enabled ? percent(experimental.reflectionStrength) : 0.0F);
            this.uniformReflectionSoftness.setFloat(2.0F + 14.0F * percent(experimental.reflectionSoftness));
        }

        if (this.uniformLightPos != null) {
            AnalyticLighting.writeUniforms(cameraPos, this.lightPositions, this.lightColors);
            this.uniformLightPos.set(this.lightPositions);
            this.uniformLightColor.set(this.lightColors);
            this.uniformLightCount.setInt(AnalyticLighting.getCount());
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

    /** Config values are edited as 0..100 integers; the shader wants a 0..1 uniform. */
    private static float percent(int value) {
        return Math.max(0, Math.min(100, value)) * 0.01F;
    }
}
