package me.jellysquid.mods.sodium.client.gl.shader;

import net.minecraft.util.Identifier;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ShaderLoader {
    /**
     * Creates an OpenGL shader from GLSL sources. The GLSL source file should be made available on the classpath at the
     * path of `/assets/{namespace}/shaders/{path}`. User defines can be used to declare variables in the shader source
     * after the version header, allowing for conditional compilation with macro code.
     *
     * @param type The type of shader to create
     * @param name The identifier used to locate the shader source file
     * @param constants A list of constants for shader specialization
     * @return An OpenGL shader object compiled with the given user defines
     */
    public static GlShader loadShader(ShaderType type, Identifier name, ShaderConstants constants) {
        return loadShader(type, name, constants, false);
    }

    /**
     * @param essl when true, the version header is rewritten to {@code #version 320 es} before the
     *             source is parsed. MobileGlues checks the header and sends an ESSL source straight
     *             to the driver instead of translating it; a desktop GLSL source goes through its
     *             translator. The rest of the file is the same either way, which is the point: the
     *             two paths have to behave the same or the comparison is worthless.
     */
    public static GlShader loadShader(ShaderType type, Identifier name, ShaderConstants constants, boolean essl) {
        String source = getShaderSource(name);

        if (essl) {
            source = toEssl(source);
        }

        return new GlShader(type, name, ShaderParser.parseShader(source, constants));
    }

    private static String toEssl(String source) {
        String header = "#version 330 core";

        if (!source.startsWith(header)) {
            return source;
        }

        // The fragment stage has no default float precision in ESSL, so it must be declared before
        // any other declaration. The vertex stage has one, but declaring it keeps both files
        // identical and catches a driver that is stricter than the spec.
        return "#version 320 es\nprecision highp float;\nprecision highp int;" + source.substring(header.length());
    }

    public static String getShaderSource(Identifier name) {
        String path = String.format("/assets/%s/shaders/%s", name.getNamespace(), name.getPath());

        try (InputStream in = ShaderLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: " + path);
            }

            return IOUtils.toString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source for " + path, e);
        }
    }
}
