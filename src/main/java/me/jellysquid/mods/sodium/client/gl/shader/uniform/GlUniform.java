package me.jellysquid.mods.sodium.client.gl.shader.uniform;

import me.jellysquid.mods.sodium.client.SodiumClientMod;

public abstract class GlUniform<T> {
    protected final int index;

    protected GlUniform(int index) {
        this.index = index;
    }

    public abstract void set(T value);

    /**
     * A/B switch ({@code performance.uniformValueCache}): subclasses remember the last value they
     * sent and ask here whether an unchanged one may skip the {@code glUniform*} call. A uniform
     * only ever changes through its own {@code GlUniform} instance, so skipping an unchanged value
     * is unobservable; the switch exists purely so the effect can be measured either way.
     */
    protected static boolean skipUnchanged() {
        return SodiumClientMod.options().performance.uniformValueCache;
    }
}
