#version 330 core

#import <sodium:include/fog.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

in float v_MaterialMipBias;
in float v_MaterialAlphaCutoff;

#ifdef SURFACE_REFLECTION
in vec3 v_ViewPos;
in vec3 v_SkyColor;
in float v_SkyLight;

uniform float u_ReflectionStrength;
uniform float u_ReflectionSoftness;
#endif

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

out vec4 fragColor; // The output fragment for the color framebuffer

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    // Apply per-vertex color
    diffuseColor.rgb *= v_Color.rgb;

    // Apply ambient occlusion "shade"
    diffuseColor.rgb *= v_Color.a;

#ifdef SURFACE_REFLECTION
    if (u_ReflectionStrength > 0.0) {
        // The chunk format carries no normals. A flat face normal can be recovered from the
        // screen-space derivatives of the interpolated view position, which costs a few
        // instructions per fragment and no extra vertex bandwidth.
        vec3 viewDir = normalize(-v_ViewPos);
        vec3 normal = normalize(cross(dFdx(v_ViewPos), dFdy(v_ViewPos)));
        normal = faceforward(normal, -viewDir, normal);

        float fresnel = pow(clamp(1.0 - max(dot(normal, viewDir), 0.0), 0.0, 1.0),
                u_ReflectionSoftness);
        float skyMask = smoothstep(0.0, 0.35, v_SkyLight);
        float amount = fresnel * skyMask * u_ReflectionStrength;

        // A sky-tinted sheen, not a mirror: enough to read as a surface reflection at grazing
        // angles while keeping the block texture underneath. With the option off, the shader is
        // not compiled at all and this branch does not exist.
        diffuseColor.rgb = mix(diffuseColor.rgb, v_SkyColor, amount * 0.45);
    }
#endif

    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}