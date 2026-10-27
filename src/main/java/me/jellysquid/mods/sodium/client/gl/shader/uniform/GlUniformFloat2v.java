package me.jellysquid.mods.sodium.client.gl.shader.uniform;

import org.lwjgl.opengl.GL30C;

public class GlUniformFloat2v extends GlUniform<float[]> {
    private final float[] lastValue = new float[2];
    private boolean hasLastValue;

    public GlUniformFloat2v(int index) {
        super(index);
    }

    @Override
    public void set(float[] value) {
        if (value.length != 2) {
            throw new IllegalArgumentException("value.length != 2");
        }

        this.set(value[0], value[1]);
    }

    public void set(float x, float y) {
        if (this.hasLastValue && Float.compare(this.lastValue[0], x) == 0
                && Float.compare(this.lastValue[1], y) == 0 && skipUnchanged()) {
            return;
        }

        GL30C.glUniform2f(this.index, x, y);

        this.lastValue[0] = x;
        this.lastValue[1] = y;
        this.hasLastValue = true;
    }
}
