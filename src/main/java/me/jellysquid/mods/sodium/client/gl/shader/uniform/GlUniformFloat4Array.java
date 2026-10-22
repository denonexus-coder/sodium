package me.jellysquid.mods.sodium.client.gl.shader.uniform;

import org.lwjgl.opengl.GL30C;

/**
 * A vec4 uniform array. Unlike {@link GlUniformFloat4v}, the array may hold any number of
 * elements, which is what the analytic light list needs.
 */
public class GlUniformFloat4Array extends GlUniform<float[]> {
    public GlUniformFloat4Array(int index) {
        super(index);
    }

    @Override
    public void set(float[] value) {
        if ((value.length & 3) != 0) {
            throw new IllegalArgumentException("value.length is not a multiple of 4");
        }

        GL30C.glUniform4fv(this.index, value);
    }
}
