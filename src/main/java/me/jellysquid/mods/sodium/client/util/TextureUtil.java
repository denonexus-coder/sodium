package me.jellysquid.mods.sodium.client.util;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11C;

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
     * Texture: sodium:textures/makeup/water_256_rg_8bit.png
     */
    public static int getMakeupWaterTextureId() {
        if (makeupWaterTextureId == -1) {
            makeupWaterTextureId = loadMakeupTexture("water_256_rg_8bit");
        }
        return makeupWaterTextureId;
    }

    /**
     * Returns the MakeUp clouds texture ID, loading it on first use.
     * Texture: sodium:textures/makeup/clouds_natural_512_r_8bit.png
     */
    public static int getMakeupCloudsTextureId() {
        if (makeupCloudsTextureId == -1) {
            makeupCloudsTextureId = loadMakeupTexture("clouds_natural_512_r_8bit");
        }
        return makeupCloudsTextureId;
    }

    private static int loadMakeupTexture(String name) {
        Identifier id = new Identifier("sodium", "textures/makeup/" + name + ".png");

        // Generate and upload the texture directly. Asking the TextureManager for the GL id would
        // return null here: the identifier is not registered with it, so getTexture(id) is null
        // and the previous code threw a NullPointerException that the IOException catch did not
        // handle, taking the whole profile down with it.
        try (NativeImage image = NativeImage.read(MinecraftClient.getInstance().getResourceManager()
                .getResourceOrThrow(id).getInputStream())) {
            int textureId = GlStateManager._genTexture();
            GlStateManager._bindTexture(textureId);

            image.upload(0, 0, 0, false); // the try-with-resources below closes the image

            RenderSystem.texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            RenderSystem.texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            RenderSystem.texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            RenderSystem.texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);

            return textureId;
        } catch (IOException e) {
            SodiumClientMod.logger().error("Failed to load MakeUp texture: {}", name, e);
            return 0;
        }
    }
}
