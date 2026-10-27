package me.jellysquid.mods.sodium.client.gl.shader.uniform;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL30C;

import java.nio.FloatBuffer;

public class GlUniformMatrix4f extends GlUniform<Matrix4fc>  {
    // Scratch + last-sent value. JOML writes with absolute puts, so the buffer's position stays
    // where it was; clear() before every read keeps that true regardless of JOML's behaviour and
    // leaves the buffer with 16 elements remaining for glUniformMatrix4fv.
    private final FloatBuffer scratch = BufferUtils.createFloatBuffer(16);
    private final float[] lastValue = new float[16];
    private boolean hasLastValue;

    public GlUniformMatrix4f(int index) {
        super(index);
    }

    @Override
    public void set(Matrix4fc value) {
        this.scratch.clear();
        value.get(this.scratch);

        if (this.hasLastValue && skipUnchanged()) {
            boolean unchanged = true;

            for (int i = 0; i < 16; i++) {
                if (Float.compare(this.scratch.get(i), this.lastValue[i]) != 0) {
                    unchanged = false;
                    break;
                }
            }

            if (unchanged) {
                return;
            }
        }

        this.scratch.clear();
        GL30C.glUniformMatrix4fv(this.index, false, this.scratch);

        for (int i = 0; i < 16; i++) {
            this.lastValue[i] = this.scratch.get(i);
        }

        this.hasLastValue = true;
    }
}
