package me.jellysquid.mods.sodium.client.gl.shader.uniform;

import org.lwjgl.opengl.GL30C;

public class GlUniformInt extends GlUniform<Integer> {
    private int lastValue;
    private boolean hasLastValue;

    public GlUniformInt(int index) {
        super(index);
    }

    @Override
    public void set(Integer value) {
        this.setInt(value);
    }

    public void setInt(int value) {
        if (this.hasLastValue && this.lastValue == value && skipUnchanged()) {
            return;
        }

        GL30C.glUniform1i(this.index, value);

        this.lastValue = value;
        this.hasLastValue = true;
    }
}
