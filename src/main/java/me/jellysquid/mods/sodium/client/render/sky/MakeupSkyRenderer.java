package me.jellysquid.mods.sodium.client.render.sky;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.GlShader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.MakeupProfile;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import me.jellysquid.mods.sodium.client.util.TextureUtil;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * The one full-screen pass of the MakeUp profile: a sky gradient with a sun, a moon, stars, the
 * sunset glow and the rain tint.
 *
 * <p>It replaces vanilla's sky dome, not the terrain. The chunk shader already computes the same
 * palette, so the two surfaces agree at sunset. No framebuffer, texture, shadow map or extra
 * geometry is involved: three vertices and one fragment shader.
 */
public final class MakeupSkyRenderer {
    private static GlProgram<Uniforms> program;
    private static int vao;
    private static int vbo;
    private static boolean failed;

    private MakeupSkyRenderer() {
    }

    /**
     * @return true when the frame was drawn and the caller should cancel vanilla's sky pass.
     */
    public static boolean render(MatrixStack matrices, Matrix4f projectionMatrix, float tickDelta, Camera camera) {
        ClientWorld world = MinecraftClient.getInstance().world;

        if (world == null || !MakeupProfile.isSkyActive()) {
            return false;
        }

        ensureProgram();

        if (program == null) {
            return false;
        }

        MakeupProfile.Frame frame = MakeupProfile.frame(world, tickDelta);

        Matrix4f inverseProjection = new Matrix4f(projectionMatrix).invert();
        Matrix4f inverseView = new Matrix4f(matrices.peek().getPositionMatrix()).invert();

        boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);

        ByteBuffer previousColorMask = BufferUtils.createByteBuffer(4);
        GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, previousColorMask);

        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glColorMask(true, true, true, true);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);

        GL30C.glBindVertexArray(vao);
        program.bind();

        Uniforms uniforms = program.getInterface();
        uniforms.inverseProjection.set(inverseProjection);
        uniforms.inverseView.set(inverseView);
        uniforms.zenith.set(frame.zenith());
        uniforms.horizon.set(frame.horizon());
        uniforms.sunColor.set(frame.directColor());
        uniforms.sunDirection.set(frame.sunX(), frame.sunY(), frame.sunZ());
        uniforms.rain.setFloat(frame.rain());
        uniforms.night.setFloat(frame.night());
        uniforms.time.setFloat((world.getTime() + tickDelta) * 0.05F);
        
        // Bind clouds texture to unit 3
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + 3);
        GlStateManager._bindTexture(TextureUtil.getMakeupCloudsTextureId());
        uniforms.cloudsTex.setInt(3);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0);

        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        program.unbind();
        GL20C.glUseProgram(previousProgram);
        GL30C.glBindVertexArray(previousVao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);

        GL11C.glDepthFunc(depthFunc);
        GL11C.glDepthMask(depthMask);
        GL11C.glColorMask(previousColorMask.get(0) != 0, previousColorMask.get(1) != 0,
                previousColorMask.get(2) != 0, previousColorMask.get(3) != 0);

        if (depthTest) {
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        } else {
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        }

        if (cull) {
            GL11C.glEnable(GL11C.GL_CULL_FACE);
        } else {
            GL11C.glDisable(GL11C.GL_CULL_FACE);
        }

        if (blend) {
            GL11C.glEnable(GL11C.GL_BLEND);
        } else {
            GL11C.glDisable(GL11C.GL_BLEND);
        }

        return true;
    }

    /**
     * Loads a sky shader, retrying as ESSL when the driver rejects {@code #version 330 core}.
     *
     * <p>The chunk path already asks for ESSL when the colored engine runs on that path; the sky
     * did not, so MobileGlues forwarded the raw 330 and the driver answered "version 330 not
     * supported" (latest.log, 21:49:50). The retry keeps the desktop path untouched and makes the
     * sky work on a GLES-only device.
     */
    private static GlShader loadShader(ShaderType type, Identifier id, ShaderConstants constants) {
        try {
            return ShaderLoader.loadShader(type, id, constants, false);
        } catch (RuntimeException first) {
            SodiumClientMod.logger().warn("MakeUp sky shader rejected as GLSL 330; retrying as ESSL", first);

            try {
                return ShaderLoader.loadShader(type, id, constants, true);
            } catch (RuntimeException nested) {
                nested.addSuppressed(first);
                throw nested;
            }
        }
    }

    private static void ensureProgram() {
        if (program != null || failed) {
            return;
        }

        try {
            ShaderConstants constants = ShaderConstants.builder().build();

            GlShader vertex = loadShader(ShaderType.VERTEX,
                    new Identifier("sodium", "sky/makeup_sky.vsh"), constants);
            GlShader fragment = loadShader(ShaderType.FRAGMENT,
                    new Identifier("sodium", "sky/makeup_sky.fsh"), constants);

            try {
                program = GlProgram.builder(new Identifier("sodium", "makeup_sky"))
                        .attachShader(vertex)
                        .attachShader(fragment)
                        .bindAttribute("a_Pos", 0)
                        .bindFragmentData("fragColor", 0)
                        .link(Uniforms::new);
            } finally {
                vertex.delete();
                fragment.delete();
            }

            int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);

            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers();

            if (vao == 0 || vbo == 0) {
                throw new IllegalStateException("Could not create the sky quad buffers");
            }

            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);

            FloatBuffer triangle = BufferUtils.createFloatBuffer(6);
            triangle.put(-1.0F).put(-1.0F);
            triangle.put(3.0F).put(-1.0F);
            triangle.put(-1.0F).put(3.0F);
            triangle.flip();

            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, triangle, GL15C.GL_STATIC_DRAW);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 2, GL11C.GL_FLOAT, false, 0, 0L);

            GL30C.glBindVertexArray(previousVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
        } catch (RuntimeException e) {
            failed = true;
            program = null;

            SodiumClientMod.logger().error("MakeUp sky shader failed to compile; keeping the vanilla sky", e);
        }
    }

    private static final class Uniforms {
        private final GlUniformMatrix4f inverseProjection;
        private final GlUniformMatrix4f inverseView;

        private final GlUniformFloat3v zenith;
        private final GlUniformFloat3v horizon;
        private final GlUniformFloat3v sunColor;
        private final GlUniformFloat3v sunDirection;

        private final GlUniformFloat rain;
        private final GlUniformFloat night;
        private final GlUniformFloat time;

        private final GlUniformInt cloudsTex;

        private Uniforms(ShaderBindingContext context) {
            this.inverseProjection = context.bindUniform("u_InvProjection", GlUniformMatrix4f::new);
            this.inverseView = context.bindUniform("u_InvView", GlUniformMatrix4f::new);

            this.zenith = context.bindUniform("u_Zenith", GlUniformFloat3v::new);
            this.horizon = context.bindUniform("u_Horizon", GlUniformFloat3v::new);
            this.sunColor = context.bindUniform("u_SunColor", GlUniformFloat3v::new);
            this.sunDirection = context.bindUniform("u_SunDir", GlUniformFloat3v::new);

            this.rain = context.bindUniform("u_Rain", GlUniformFloat::new);
            this.night = context.bindUniform("u_Night", GlUniformFloat::new);
            this.time = context.bindUniform("u_Time", GlUniformFloat::new);

            this.cloudsTex = context.bindUniform("u_MakeupCloudsTex", GlUniformInt::new);
        }
    }
}
