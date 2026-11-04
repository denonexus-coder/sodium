package me.jellysquid.mods.sodium.client.gl.tessellation;

import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

public abstract class GlAbstractTessellation implements GlTessellation {
    protected final GlPrimitiveType primitiveType;
    protected final TessellationBinding[] bindings;

    protected GlAbstractTessellation(GlPrimitiveType primitiveType, TessellationBinding[] bindings) {
        this.primitiveType = primitiveType;
        this.bindings = bindings;
    }

    @Override
    public GlPrimitiveType getPrimitiveType() {
        return this.primitiveType;
    }

    protected void bindAttributes(CommandList commandList) {
        for (TessellationBinding binding : this.bindings) {
            commandList.bindBuffer(binding.target(), binding.buffer());

            for (GlVertexAttributeBinding attrib : binding.attributeBindings()) {
                if (attrib.isIntType()) {
                    GL30C.glVertexAttribIPointer(attrib.getIndex(), attrib.getCount(), attrib.getFormat(),
                            attrib.getStride(), attrib.getPointer());
                } else {
                    GL20C.glVertexAttribPointer(attrib.getIndex(), attrib.getCount(), attrib.getFormat(), attrib.isNormalized(),
                            attrib.getStride(), attrib.getPointer());
                }
                GL20C.glEnableVertexAttribArray(attrib.getIndex());
            }
        }
    }

    /**
     * Retorna o buffer associado ao target OpenGL especificado, sem assumir
     * posição fixa no array de bindings. Usado para extrair o VBO (ARRAY_BUFFER)
     * e o EBO (ELEMENT_BUFFER) da tesselação ativa e passá-los ao caminho nativo.
     */
    public GlBuffer getBufferForTarget(GlBufferTarget target) {
        for (TessellationBinding binding : this.bindings) {
            if (binding.target() == target) {
                return binding.buffer();
            }
        }
        throw new IllegalArgumentException("No TessellationBinding for target " + target);
    }
}
