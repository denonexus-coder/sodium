package me.jellysquid.mods.sodium.client.gl.shader.uniform;

import org.lwjgl.opengl.GL30C;

public class GlUniformFloat4v extends GlUniform<float[]> {
    private final float[] lastValue = new float[4];
    private boolean hasLastValue;

    public GlUniformFloat4v(int index) {
        super(index);
    }

    @Override
    public void set(float[] value) {
        if (value.length != 4) {
            throw new IllegalArgumentException("value.length != 4");
        }

        if (this.hasLastValue && skipUnchanged()
                && Float.compare(this.lastValue[0], value[0]) == 0
                && Float.compare(this.lastValue[1], value[1]) == 0
                && Float.compare(this.lastValue[2], value[2]) == 0
                && Float.compare(this.lastValue[3], value[3]) == 0) {
            return;
        }

        GL30C.glUniform4fv(this.index, value);

        System.arraycopy(value, 0, this.lastValue, 0, 4);
        this.hasLastValue = true;
    }
}
