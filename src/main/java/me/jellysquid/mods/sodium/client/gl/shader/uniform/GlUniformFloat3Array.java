package me.jellysquid.mods.sodium.client.gl.shader.uniform;

import org.lwjgl.opengl.GL30C;

/**
 * A vec3 uniform array. See {@link GlUniformFloat4Array}: the fixed-size classes reject
 * anything that is not exactly one element.
 */
public class GlUniformFloat3Array extends GlUniform<float[]> {
    public GlUniformFloat3Array(int index) {
        super(index);
    }

    @Override
    public void set(float[] value) {
        if (value.length % 3 != 0) {
            throw new IllegalArgumentException("value.length is not a multiple of 3");
        }

        GL30C.glUniform3fv(this.index, value);
    }
}
