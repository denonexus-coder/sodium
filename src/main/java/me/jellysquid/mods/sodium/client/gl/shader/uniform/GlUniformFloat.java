package me.jellysquid.mods.sodium.client.gl.shader.uniform;

import org.lwjgl.opengl.GL30C;

public class GlUniformFloat extends GlUniform<Float> {
    private float lastValue;
    private boolean hasLastValue;

    public GlUniformFloat(int index) {
        super(index);
    }

    @Override
    public void set(Float value) {
        this.setFloat(value);
    }

    public void setFloat(float value) {
        if (this.hasLastValue && Float.compare(this.lastValue, value) == 0 && skipUnchanged()) {
            return;
        }

        GL30C.glUniform1f(this.index, value);

        this.lastValue = value;
        this.hasLastValue = true;
    }
}
