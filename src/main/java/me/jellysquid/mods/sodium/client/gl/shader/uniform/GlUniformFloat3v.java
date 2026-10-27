package me.jellysquid.mods.sodium.client.gl.shader.uniform;

import org.lwjgl.opengl.GL30C;

public class GlUniformFloat3v extends GlUniform<float[]> {
    private final float[] lastValue = new float[3];
    private boolean hasLastValue;

    public GlUniformFloat3v(int index) {
        super(index);
    }

    @Override
    public void set(float[] value) {
        if (value.length != 3) {
            throw new IllegalArgumentException("value.length != 3");
        }

        this.set(value[0], value[1], value[2]);
    }

    public void set(float x, float y, float z) {
        if (this.hasLastValue && Float.compare(this.lastValue[0], x) == 0
                && Float.compare(this.lastValue[1], y) == 0
                && Float.compare(this.lastValue[2], z) == 0 && skipUnchanged()) {
            return;
        }

        GL30C.glUniform3f(this.index, x, y, z);

        this.lastValue[0] = x;
        this.lastValue[1] = y;
        this.lastValue[2] = z;
        this.hasLastValue = true;
    }
}
