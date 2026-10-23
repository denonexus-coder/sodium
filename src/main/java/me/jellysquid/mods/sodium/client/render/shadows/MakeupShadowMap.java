package me.jellysquid.mods.sodium.client.render.shadows;

import com.mojang.blaze3d.platform.GlStateManager;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.MakeupProfile;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * MakeUp Low's optional real shadow map.
 *
 * <p>This is deliberately small: one depth-only 300x300 texture, an orthographic sun camera
 * covering 75 blocks around the player, and terrain/cutout chunks only. It shares the existing
 * chunk meshes and the profile shader, so no second vertex format or scene copy exists. Updates
 * are cached and only happen when the camera moves, the sun moves or a block update arrives.
 */
public final class MakeupShadowMap {
    public static final int RESOLUTION = 300;
    private static final float HALF_EXTENT = 75.0F;
    private static final float SUN_DISTANCE = 120.0F;

    private static int framebuffer = -1;
    private static int texture = -1;
    private static boolean ready;
    private static boolean dirty = true;
    private static boolean failed;
    private static boolean shaderUnavailable;

    private static final Matrix4f shadowMatrix = new Matrix4f();
    private static final Matrix4f lightProjection = new Matrix4f();
    private static final Matrix4f lightView = new Matrix4f();

    private static ClientWorld lastWorld;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static float lastSunX;
    private static float lastSunY;
    private static float lastSunZ;
    private static long lastUpdateTick = Long.MIN_VALUE;

    private MakeupShadowMap() {
    }

    public static boolean isReady() {
        return !shaderUnavailable && ready && texture > 0 && framebuffer > 0;
    }

    public static int getTextureId() {
        return isReady() ? texture : 0;
    }

    public static Matrix4f getShadowMatrix() {
        return shadowMatrix;
    }

    public static float getTexelSize() {
        return 1.0F / RESOLUTION;
    }

    /** Called from block-update hooks; the map is rebuilt on the next rendered frame. */
    public static void markDirty() {
        dirty = true;
    }

    /**
     * Called when the Low sampler could not be compiled or linked. The profile keeps working
     * without a map, and the renderer must not keep paying for a texture nobody samples.
     */
    public static void markShaderUnavailable() {
        shaderUnavailable = true;
        ready = false;
        dirty = true;
    }

    /**
     * Builds and draws the shadow map when needed. This runs before the solid terrain pass while
     * the vanilla framebuffer is still bound, then restores every state it touched.
     */
    public static void renderIfNeeded(RenderSectionManager sections) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;

        if (world == null || shaderUnavailable || !MakeupProfile.isLowShadowsActive()) {
            if (ready) {
                ready = false;
                dirty = true;
            }

            return;
        }

        Camera camera = client.gameRenderer.getCamera();
        MakeupProfile.Frame frame = MakeupProfile.frame();

        // Low follows MakeUp's daylight profile. A sun below the horizon has no useful caster
        // direction; the No Effects direct term still handles the look at night. This is the
        // sun's actual elevation, not the MakeUp SunLight option.
        if (frame.sunY() < 0.10F) {
            if (ready) {
                ready = false;
            }

            return;
        }

        Vec3d position = camera.getPos();
        long tick = world.getTime();
        boolean worldChanged = world != lastWorld;
        boolean moved = (position.x - lastX) * (position.x - lastX)
                + (position.y - lastY) * (position.y - lastY)
                + (position.z - lastZ) * (position.z - lastZ) > 4.0D;
        boolean sunMoved = Math.abs(frame.sunX() - lastSunX) > 0.002F
                || Math.abs(frame.sunY() - lastSunY) > 0.002F
                || Math.abs(frame.sunZ() - lastSunZ) > 0.002F;
        boolean periodic = tick - lastUpdateTick >= 20L;

        if (!worldChanged && !moved && !sunMoved && !periodic && !dirty && ready) {
            return;
        }

        if (!ensureFramebuffer()) {
            return;
        }

        Vector3f sun = new Vector3f(frame.sunX(), frame.sunY(), frame.sunZ()).normalize();
        Vector3f center = new Vector3f((float) position.x, (float) position.y, (float) position.z);
        Vector3f eye = new Vector3f(center).fma(SUN_DISTANCE, sun);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);

        // The palette keeps the sun in the X/Y plane, so at noon it is parallel to the default
        // up vector and lookAt would be singular. Use the Z axis for that small window.
        if (Math.abs(sun.y) > 0.95F) {
            up.set(0.0F, 0.0F, 1.0F);
        }

        lightProjection.identity().ortho(-HALF_EXTENT, HALF_EXTENT, -HALF_EXTENT, HALF_EXTENT,
                0.1F, SUN_DISTANCE + 200.0F);
        lightView.identity().lookAt(eye, center, up);

        // Chunk positions are relative to the player camera. viewRelative therefore has to
        // translate the world-space light view by the camera position.
        Matrix4f viewRelative = new Matrix4f(lightView).translate(center.x, center.y, center.z);
        shadowMatrix.set(lightProjection).mul(lightView);

        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        bindFramebuffer();
        ready = false; // while drawing, never let the sampled texture be the target

        try {
            renderDepth(sections, new ChunkRenderMatrices(lightProjection, viewRelative), camera, position);
            ready = true;
            dirty = false;
            failed = false;
            lastWorld = world;
            lastX = position.x;
            lastY = position.y;
            lastZ = position.z;
            lastSunX = frame.sunX();
            lastSunY = frame.sunY();
            lastSunZ = frame.sunZ();
            lastUpdateTick = tick;
        } catch (RuntimeException e) {
            ready = false;
            SodiumClientMod.logger().error("MakeUp Low shadow map failed; keeping the profile without the map", e);
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
        }
    }

    private static void renderDepth(RenderSectionManager sections, ChunkRenderMatrices matrices,
                                    Camera camera, Vec3d position) {
        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);

        boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        float clearDepth = GL11C.glGetFloat(GL11C.GL_DEPTH_CLEAR_VALUE);
        boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean polygonOffset = GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL);
        int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        float offsetFactor = GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_FACTOR);
        float offsetUnits = GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_UNITS);
        ByteBuffer colorMask = BufferUtils.createByteBuffer(4);
        GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, colorMask);

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL11C.glViewport(0, 0, RESOLUTION, RESOLUTION);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GL11C.glDepthMask(true);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glColorMask(false, false, false, false);
        GL11C.glEnable(GL11C.GL_POLYGON_OFFSET_FILL);
        GL11C.glPolygonOffset(1.0F, 2.0F);
        GL11C.glClearDepth(1.0D);
        GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);

        try {
            sections.renderShadow(matrices, new CameraTransform(position.x, position.y, position.z));
        } finally {
            GL11C.glPolygonOffset(offsetFactor, offsetUnits);
            if (!polygonOffset) {
                GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_FILL);
            }

            GL11C.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0,
                    colorMask.get(2) != 0, colorMask.get(3) != 0);
            GL11C.glDepthFunc(depthFunc);
            GL11C.glDepthMask(depthMask);
            GL11C.glClearDepth(clearDepth);

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

            if (scissor) {
                GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            } else {
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            }

            GL11C.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
        }
    }

    private static boolean ensureFramebuffer() {
        if (failed) {
            return false;
        }

        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);

        if (texture <= 0) {
            // Go through GlStateManager so its texture cache stays in sync with the driver.
            // Binding and then unbinding with raw GL used to leave the cache claiming that the
            // previous chunk atlas was still bound, so the next setupState() could skip binding
            // it and sample texture unit 0 (black or white terrain).
            int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
            int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);

            try {
                texture = GL11C.glGenTextures();

                if (texture <= 0) {
                    failed = true;
                    return false;
                }

                GlStateManager._bindTexture(texture);

                // The four taps provide the blur. Nearest sampling avoids relying on linear
                // filtering of depth textures, which is the more fragile path on GLES drivers.
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL14C.GL_DEPTH_COMPONENT24, RESOLUTION, RESOLUTION,
                        0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_UNSIGNED_INT, 0);
            } finally {
                GlStateManager._bindTexture(previousTexture);
                GlStateManager._activeTexture(previousUnit);
            }
        }

        if (framebuffer <= 0) {
            framebuffer = GL30C.glGenFramebuffers();

            if (framebuffer <= 0) {
                failed = true;
                return false;
            }

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                    GL11C.GL_TEXTURE_2D, texture, 0);
            GL11C.glDrawBuffer(GL11C.GL_NONE);
            GL11C.glReadBuffer(GL11C.GL_NONE);

            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);

            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                SodiumClientMod.logger().error("MakeUp Low shadow framebuffer is incomplete: {}", status);
                failed = true;
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
                return false;
            }
        }

        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
        return true;
    }

    private static void bindFramebuffer() {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
    }

}
