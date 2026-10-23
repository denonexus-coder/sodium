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
import me.jellysquid.mods.sodium.client.render.MakeupProfile;
import me.jellysquid.mods.sodium.client.render.shadows.MakeupShadowMap;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.AnalyticLighting;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.ColoredLighting;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import me.jellysquid.mods.sodium.client.util.TextureUtil;
import me.jellysquid.mods.sodium.mixin.core.render.texture.SpriteAtlasTextureAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
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

    // Luz vibrante: lidos por frame das opções experimentais.
    private final GlUniformFloat uniformVibrantFalloff;
    private final GlUniformFloat uniformVibrantToneMap;
    private final GlUniformFloat uniformColorLightHeadroom;
    private final GlUniformFloat uniformInvLightRadiusSq;

    // Surface reflection is an optional fragment path; the uniforms exist only when compiled in.
    private final GlUniformFloat uniformReflectionStrength;
    private final GlUniformFloat uniformReflectionSoftness;

    // The MakeUp "No Effects" profile. The colors are computed once per pass on the CPU; the
    // fragment shader only needs the derivative normal and the light it already interpolates.
    private final GlUniformFloat3v uniformMakeupOmniColor;
    private final GlUniformFloat3v uniformMakeupOmniMin;
    private final GlUniformFloat3v uniformMakeupDirectColor;
    private final GlUniformFloat3v uniformMakeupCandleColor;
    private final GlUniformFloat3v uniformMakeupZenith;
    private final GlUniformFloat3v uniformMakeupHorizon;
    private final GlUniformFloat3v uniformMakeupWaterColor;
    private final GlUniformFloat3v uniformMakeupFogColor;
    private final GlUniformFloat3v uniformMakeupSunDirView;
    private final GlUniformFloat3v uniformMakeupUpView;
    private final GlUniformFloat3v uniformMakeupCameraPos;
    private final GlUniformFloat uniformMakeupSun;
    private final GlUniformInt uniformMakeupAmbient;
    private final GlUniformFloat uniformMakeupWater;
    private final GlUniformFloat uniformMakeupWaterAbsorption;
    private final GlUniformFloat uniformMakeupWaterReflection;
    private final GlUniformFloat uniformMakeupWaterWaves;
    private final GlUniformFloat uniformMakeupWaveStrength;
    private final GlUniformFloat uniformMakeupFog;
    private final GlUniformFloat uniformMakeupFogDensity;
    private final GlUniformFloat uniformMakeupEyeSky;
    private final GlUniformFloat uniformMakeupRain;
    private final GlUniformFloat uniformMakeupFar;
    private final GlUniformFloat uniformMakeupTime;

    // Low's optional depth map. These are only present in the normal terrain program; the
    // shadow-caster variant compiles without PROFILE_SHADOWS.
    private final GlUniformInt uniformProfileShadowMap;
    private final GlUniformMatrix4f uniformProfileShadowMatrix;
    private final GlUniformFloat uniformProfileShadowTexel;
    private final GlUniformInt uniformProfileShadowActive;

    private MakeupProfile.Frame makeupFrame;

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

        // Both the normal Sodium shader and the MakeUp profile sample the lightmap. The profile
        // uses its sky column as the fallback when the custom ambient palette is disabled.
        this.uniformTextures.put(ChunkShaderTextureSlot.LIGHT, context.bindUniform("u_LightTex", GlUniformInt::new));

        if (coloredLighting || options.makeupProfile()) {
            this.uniformHandLight = context.bindUniform("u_HandLight", GlUniformFloat4v::new);
            this.uniformHandLightColor = context.bindUniform("u_HandLightColor", GlUniformFloat3v::new);
        } else {
            this.uniformHandLight = null;
            this.uniformHandLightColor = null;
        }

        // The shadow uniforms live in _colored_light, which the profile does not call: its light
        // model has no shadow map and no colored-shadow ambient term. Binding them there would
        // ask for uniforms that the linker is allowed to eliminate.
        if (coloredLighting && !options.makeupProfile()) {
            this.uniformCameraY = context.bindUniform("u_CameraY", GlUniformFloat::new);
            this.uniformShadowStrength = context.bindUniform("u_ShadowStrength", GlUniformFloat::new);
            this.uniformShadowDepth = context.bindUniform("u_ShadowDepth", GlUniformFloat::new);
            this.uniformShadowTint = context.bindUniform("u_ShadowTint", GlUniformFloat::new);
            this.uniformVibrantToneMap = context.bindUniform("u_VibrantToneMap", GlUniformFloat::new);
        } else {
            this.uniformCameraY = null;
            this.uniformShadowStrength = null;
            this.uniformShadowDepth = null;
            this.uniformShadowTint = null;
            this.uniformVibrantToneMap = null;
        }

        // _colored_block é chamado tanto pelo caminho normal como pelo perfil MakeUp, pelo que
        // estes uniforms existem sempre que a iluminação colorida está compilada.
        if (coloredLighting) {
            this.uniformVibrantFalloff = context.bindUniform("u_VibrantFalloff", GlUniformFloat::new);
            this.uniformInvLightRadiusSq = context.bindUniform("u_InvLightRadiusSq", GlUniformFloat::new);
        } else {
            this.uniformVibrantFalloff = null;
            this.uniformInvLightRadiusSq = null;
        }

        if (options.surfaceReflection()) {
            this.uniformReflectionStrength = context.bindUniform("u_ReflectionStrength", GlUniformFloat::new);
            this.uniformReflectionSoftness = context.bindUniform("u_ReflectionSoftness", GlUniformFloat::new);
        } else {
            this.uniformReflectionStrength = null;
            this.uniformReflectionSoftness = null;
        }

        if (options.makeupProfile()) {
            this.uniformMakeupOmniColor = context.bindUniform("u_MakeupOmniColor", GlUniformFloat3v::new);
            this.uniformMakeupOmniMin = context.bindUniform("u_MakeupOmniMin", GlUniformFloat3v::new);
            this.uniformMakeupDirectColor = context.bindUniform("u_MakeupDirectColor", GlUniformFloat3v::new);
            // With the colored engine on, the block term comes from _colored_block and the
            // profile's candle palette is dead code. Drivers are free to eliminate it, so only
            // bind it when the warm fallback is actually compiled in.
            this.uniformMakeupCandleColor = coloredLighting
                    ? null
                    : context.bindUniform("u_MakeupCandleColor", GlUniformFloat3v::new);
            this.uniformMakeupZenith = context.bindUniform("u_MakeupZenith", GlUniformFloat3v::new);
            this.uniformMakeupHorizon = context.bindUniform("u_MakeupHorizon", GlUniformFloat3v::new);
            this.uniformMakeupWaterColor = context.bindUniform("u_MakeupWaterColor", GlUniformFloat3v::new);
            this.uniformMakeupFogColor = context.bindUniform("u_MakeupFogColor", GlUniformFloat3v::new);
            this.uniformMakeupSunDirView = context.bindUniform("u_MakeupSunDirView", GlUniformFloat3v::new);
            this.uniformMakeupUpView = context.bindUniform("u_MakeupUpView", GlUniformFloat3v::new);
            this.uniformMakeupCameraPos = context.bindUniform("u_MakeupCameraPos", GlUniformFloat3v::new);

            this.uniformMakeupSun = context.bindUniform("u_MakeupSun", GlUniformFloat::new);
            this.uniformMakeupAmbient = context.bindUniform("u_MakeupAmbient", GlUniformInt::new);
            this.uniformMakeupWater = context.bindUniform("u_MakeupWater", GlUniformFloat::new);
            this.uniformMakeupWaterAbsorption = context.bindUniform("u_MakeupWaterAbsorption", GlUniformFloat::new);
            this.uniformMakeupWaterReflection = context.bindUniform("u_MakeupWaterReflection", GlUniformFloat::new);
            this.uniformMakeupWaterWaves = context.bindUniform("u_MakeupWaterWaves", GlUniformFloat::new);
            this.uniformMakeupWaveStrength = context.bindUniform("u_MakeupWaveStrength", GlUniformFloat::new);
            this.uniformMakeupFog = context.bindUniform("u_MakeupFog", GlUniformFloat::new);
            this.uniformMakeupFogDensity = context.bindUniform("u_MakeupFogDensity", GlUniformFloat::new);
            this.uniformMakeupEyeSky = context.bindUniform("u_MakeupEyeSky", GlUniformFloat::new);
            this.uniformMakeupRain = context.bindUniform("u_MakeupRain", GlUniformFloat::new);
            this.uniformMakeupFar = context.bindUniform("u_MakeupFar", GlUniformFloat::new);
            this.uniformMakeupTime = context.bindUniform("u_MakeupTime", GlUniformFloat::new);
        } else {
            this.uniformMakeupOmniColor = null;
            this.uniformMakeupOmniMin = null;
            this.uniformMakeupDirectColor = null;
            this.uniformMakeupCandleColor = null;
            this.uniformMakeupZenith = null;
            this.uniformMakeupHorizon = null;
            this.uniformMakeupWaterColor = null;
            this.uniformMakeupFogColor = null;
            this.uniformMakeupSunDirView = null;
            this.uniformMakeupUpView = null;
            this.uniformMakeupCameraPos = null;
            this.uniformMakeupSun = null;
            this.uniformMakeupAmbient = null;
            this.uniformMakeupWater = null;
            this.uniformMakeupWaterAbsorption = null;
            this.uniformMakeupWaterReflection = null;
            this.uniformMakeupWaterWaves = null;
            this.uniformMakeupWaveStrength = null;
            this.uniformMakeupFog = null;
            this.uniformMakeupFogDensity = null;
            this.uniformMakeupEyeSky = null;
            this.uniformMakeupRain = null;
            this.uniformMakeupFar = null;
            this.uniformMakeupTime = null;
        }

        if (coloredLighting && !analyticLighting) {
            this.uniformColorLightTex = context.bindUniform("u_ColorLightTex", GlUniformInt::new);
            this.uniformColorLightOrigin = context.bindUniform("u_ColorLightOrigin", GlUniformFloat3v::new);
            this.uniformColorLightScale = context.bindUniform("u_ColorLightScale", GlUniformFloat3v::new);
            this.uniformColorLightHeadroom = context.bindUniform("u_ColorLightHeadroom", GlUniformFloat::new);
        } else {
            this.uniformColorLightTex = null;
            this.uniformColorLightOrigin = null;
            this.uniformColorLightScale = null;
            this.uniformColorLightHeadroom = null;
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

        if (options.makeupProfile() && options.profileShadows()) {
            this.uniformProfileShadowMap = context.bindUniform("u_ProfileShadowMap", GlUniformInt::new);
            this.uniformProfileShadowMatrix = context.bindUniform("u_ProfileShadowMatrix", GlUniformMatrix4f::new);
            this.uniformProfileShadowTexel = context.bindUniform("u_ProfileShadowTexel", GlUniformFloat::new);
            this.uniformProfileShadowActive = context.bindUniform("u_ProfileShadowActive", GlUniformInt::new);
        } else {
            this.uniformProfileShadowMap = null;
            this.uniformProfileShadowMatrix = null;
            this.uniformProfileShadowTexel = null;
            this.uniformProfileShadowActive = null;
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

            if (this.uniformColorLightHeadroom != null) {
                this.uniformColorLightHeadroom.setFloat(ColoredLighting.getUploadHeadroom());
            }
        }

        if (this.uniformHandLight != null) {
            ColoredLighting.writeHandLight(cameraPos, this.handLight, this.handColor);
            this.uniformHandLight.set(this.handLight);
            this.uniformHandLightColor.set(this.handColor);

            if (this.uniformCameraY != null) {
                this.uniformCameraY.setFloat((float) cameraPos.y);
            }
        }

        if (this.uniformMakeupOmniColor != null && MinecraftClient.getInstance().world != null) {
            MakeupProfile.Frame frame = MakeupProfile.frame();
            this.makeupFrame = frame;

            this.uniformMakeupOmniColor.set(frame.omniColor());
            this.uniformMakeupOmniMin.set(frame.omniMin());
            this.uniformMakeupDirectColor.set(frame.directColor());
            if (this.uniformMakeupCandleColor != null) {
                this.uniformMakeupCandleColor.set(frame.candleColor());
            }
            this.uniformMakeupZenith.set(frame.zenith());
            this.uniformMakeupHorizon.set(frame.horizon());
            this.uniformMakeupWaterColor.set(frame.waterColor());
            this.uniformMakeupFogColor.set(frame.fogColor());
            this.uniformMakeupCameraPos.set((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);

            this.uniformMakeupSun.setFloat(frame.sunLight() ? 1.0F : 0.0F);
            this.uniformMakeupAmbient.setInt(frame.ambient() ? 1 : 0);
            this.uniformMakeupWater.setFloat(frame.water() ? 1.0F : 0.0F);
            this.uniformMakeupWaterAbsorption.setFloat(frame.waterAbsorption());
            this.uniformMakeupWaterReflection.setFloat(frame.waterSkyReflection() ? 1.0F : 0.0F);
            this.uniformMakeupWaterWaves.setFloat(frame.waterWaves() ? 1.0F : 0.0F);
            this.uniformMakeupWaveStrength.setFloat(frame.waveStrength());
            this.uniformMakeupFog.setFloat(frame.fog() && MakeupProfile.isOverworld() ? 1.0F : 0.0F);
            this.uniformMakeupFogDensity.setFloat(frame.fogDensity());
            this.uniformMakeupEyeSky.setFloat(frame.eyeSky());
            this.uniformMakeupRain.setFloat(frame.rain());
            this.uniformMakeupFar.setFloat(frame.fogFar());

            MinecraftClient client = MinecraftClient.getInstance();
            this.uniformMakeupTime.setFloat((client.world.getTime() + client.getTickDelta()) * 0.05F);
        }

        if (this.uniformProfileShadowMap != null) {
            GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 3);
            GlStateManager._bindTexture(MakeupShadowMap.getTextureId());
            this.uniformProfileShadowMap.setInt(3);
            this.uniformProfileShadowMatrix.set(MakeupShadowMap.getShadowMatrix());
            this.uniformProfileShadowTexel.setFloat(MakeupShadowMap.getTexelSize());
            this.uniformProfileShadowActive.setInt(MakeupShadowMap.isReady() ? 1 : 0);
            GlStateManager._activeTexture(GL32C.GL_TEXTURE0);
        }

        if (this.uniformShadowStrength != null) {
            var experimental = SodiumClientMod.options().experimental;
            boolean enabled = experimental.coloredShadows && !ColoredLighting.isIrisLoaded();

            this.uniformShadowStrength.setFloat(enabled ? percent(experimental.shadowStrength) : 0.0F);
            this.uniformShadowDepth.setFloat(enabled ? percent(experimental.shadowDepth) : 0.0F);
            this.uniformShadowTint.setFloat(enabled ? percent(experimental.shadowTint) : 0.0F);
        }

        if (this.uniformVibrantFalloff != null) {
            var experimental = SodiumClientMod.options().experimental;

            this.uniformVibrantFalloff.setFloat(experimental.vibrantFalloff ? 1.0F : 0.0F);
            this.uniformInvLightRadiusSq.setFloat(ColoredLighting.invLightRadiusSq());
        }

        if (this.uniformVibrantToneMap != null) {
            var experimental = SodiumClientMod.options().experimental;
            this.uniformVibrantToneMap.setFloat(experimental.vibrantToneMap ? 1.0F : 0.0F);
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
        var uniform = this.uniformTextures.get(slot);

        if (uniform == null) {
            return;
        }

        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + slot.ordinal());
        GlStateManager._bindTexture(textureId);
        uniform.setInt(slot.ordinal());
    }

    public void setProjectionMatrix(Matrix4fc matrix) {
        this.uniformProjectionMatrix.set(matrix);
    }

    public void setModelViewMatrix(Matrix4fc matrix) {
        this.uniformModelViewMatrix.set(matrix);

        if (this.uniformMakeupSunDirView != null && this.makeupFrame != null) {
            Matrix4f view = new Matrix4f(matrix);
            MakeupProfile.Frame frame = this.makeupFrame;

            Vector3f sun = view.transformDirection(new Vector3f(frame.sunX(), frame.sunY(), frame.sunZ()));
            Vector3f up = view.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));

            this.uniformMakeupSunDirView.set(sun.x, sun.y, sun.z);
            this.uniformMakeupUpView.set(up.x, up.y, up.z);
        }
    }

    public void setRegionOffset(float x, float y, float z) {
        this.uniformRegionOffset.set(x, y, z);
    }

    /** Config values are edited as 0..100 integers; the shader wants a 0..1 uniform. */
    private static float percent(int value) {
        return Math.max(0, Math.min(100, value)) * 0.01F;
    }
}
