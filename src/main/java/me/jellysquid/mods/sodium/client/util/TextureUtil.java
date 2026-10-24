package me.jellysquid.mods.sodium.client.util;

import com.mojang.blaze3d.systems.RenderSystem;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.io.IOException;

/**
 * NOTE: Must be called while a RenderLayer is active.
 */
public class TextureUtil {

    private static int makeupWaterTextureId = -1;
    private static int makeupCloudsTextureId = -1;

    /**
     * NOTE: Must be called while a RenderLayer is active.
     */
    public static int getLightTextureId() {
        return RenderSystem.getShaderTexture(2);
    }

    /**
     * NOTE: Must be called while a RenderLayer is active.
     */
    public static int getBlockTextureId() {
        return RenderSystem.getShaderTexture(0);
    }

    /**
     * Returns the MakeUp water texture ID, loading it on first use.
     * Texture: sodium:textures/makeup/water_256_RG_8bit.png
     */
    public static int getMakeupWaterTextureId() {
        if (makeupWaterTextureId == -1) {
            makeupWaterTextureId = loadMakeupTexture("water_256_RG_8bit");
        }
        return makeupWaterTextureId;
    }

    /**
     * Returns the MakeUp clouds texture ID, loading it on first use.
     * Texture: sodium:textures/makeup/clouds_natural_512_R_8bit.png
     */
    public static int getMakeupCloudsTextureId() {
        if (makeupCloudsTextureId == -1) {
            makeupCloudsTextureId = loadMakeupTexture("clouds_natural_512_R_8bit");
        }
        return makeupCloudsTextureId;
    }

    private static int loadMakeupTexture(String name) {
        TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
        Identifier id = new Identifier("sodium", "textures/makeup/" + name + ".png");
        
        // Check if already loaded
        if (textureManager.getTexture(id) != null) {
            return textureManager.getTexture(id).getGlId();
        }
        
        // Load the texture
        try {
            NativeImage image = NativeImage.read(SodiumClientMod.getResourceManager().getResourceOrThrow(id).getInputStream());
            int textureId = textureManager.getTexture(id).getGlId();
            
            // Upload the image data
            RenderSystem.bindTexture(textureId);
            image.upload(0, 0, 0, false);
            
            // Set texture parameters for water/cloud textures
            RenderSystem.texParameter(3553, 10241, 9729); // GL_LINEAR min filter
            RenderSystem.texParameter(3553, 10240, 9729); // GL_LINEAR mag filter
            RenderSystem.texParameter(3553, 10242, 10497); // GL_REPEAT wrap S
            RenderSystem.texParameter(3553, 10243, 10497); // GL_REPEAT wrap T
            
            return textureId;
        } catch (IOException e) {
            SodiumClientMod.LOGGER.error("Failed to load MakeUp texture: {}", name, e);
            return 0;
        }
    }
}
