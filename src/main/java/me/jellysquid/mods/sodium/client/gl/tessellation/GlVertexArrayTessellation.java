package me.jellysquid.mods.sodium.client.gl.tessellation;

import me.jellysquid.mods.sodium.client.gl.array.GlVertexArray;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;

public class GlVertexArrayTessellation extends GlAbstractTessellation {
    private final GlVertexArray array;

    public GlVertexArrayTessellation(GlVertexArray array, GlPrimitiveType primitiveType, TessellationBinding[] bindings) {
        super(primitiveType, bindings);

        this.array = array;
    }

    /**
     * Expõe o VAO associado a esta tesselação para que o caminho nativo
     * (MGNativeDrawer.drawBatchZeroCopy) possa passar o handle real em vez de
     * 0 — um VAO nulo desliga todos os attribute pointers e o desenho some.
     */
    public GlVertexArray getArray() {
        return this.array;
    }

    public void init(CommandList commandList) {
        this.bind(commandList);
        this.bindAttributes(commandList);
        this.unbind(commandList);
    }

    @Override
    public void delete(CommandList commandList) {
        commandList.deleteVertexArray(this.array);
    }

    @Override
    public void bind(CommandList commandList) {
        commandList.bindVertexArray(this.array);
    }

    @Override
    public void unbind(CommandList commandList) {
        commandList.unbindVertexArray();
    }
}
