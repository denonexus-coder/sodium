#version 330 core

in vec2 v_Ndc;

out vec4 fragColor;

uniform mat4 u_InvProjection;
uniform mat4 u_InvView;

uniform vec3 u_Zenith;
uniform vec3 u_Horizon;
uniform vec3 u_SunColor;
uniform vec3 u_SunDir;

uniform float u_Rain;
uniform float u_Night;
uniform float u_Time;

float _hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;

    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

void main() {
    // Reconstruct a world-space view ray from the pixel. z=1 is the far plane, and the depth
    // test is what keeps the sky behind terrain.
    vec4 view = u_InvProjection * vec4(v_Ndc, 1.0, 1.0);
    vec3 dirView = normalize(view.xyz / view.w);
    vec3 dir = normalize(mat3(u_InvView) * dirView);

    float h = clamp(dir.y, 0.0, 1.0);
    vec3 sky = mix(u_Horizon, u_Zenith, pow(h, 0.45));

    // Below the horizon the sky darkens instead of showing a second horizon through a missing
    // chunk. This is only visible for a frame while a section is being built.
    if (dir.y < 0.0) {
        sky = mix(u_Horizon, u_Horizon * 0.55, clamp(-dir.y * 4.0, 0.0, 1.0));
    }

    float sunDot = dot(dir, u_SunDir);
    float sunDisc = smoothstep(0.9997, 0.9999, sunDot);
    float sunGlow = pow(max(sunDot, 0.0), 128.0);
    float moonDot = dot(dir, -u_SunDir);
    float moonDisc = smoothstep(0.9997, 0.9999, moonDot);

    // Rain hides the discs and most of the glow, but leaves the sky's rain tint untouched.
    float clear = 1.0 - u_Rain;
    vec3 sun = u_SunColor * ((sunDisc * 3.0 + sunGlow * 0.5) * clear);
    vec3 moon = vec3(0.72, 0.78, 0.88) * (moonDisc * 0.9 * clear);
    sky += sun + moon;

    // Warm glow on the sun's side of the horizon. It uses the same direct-light colour as the
    // terrain, so the sunset does not end up orange in the sky and pink on the ground.
    vec2 flatDir = dir.xz;
    vec2 flatSun = u_SunDir.xz;
    float az = max(dot(normalize(flatDir + vec2(1.0e-5)), normalize(flatSun + vec2(1.0e-5))), 0.0);
    sky += u_SunColor * (pow(az, 4.0) * (1.0 - smoothstep(0.0, 0.45, h)) * 0.35 * clear);

    // A cheap star field. The cells are anchored to the world direction, so they do not swim
    // with the camera.
    if (u_Night > 0.01) {
        vec3 p = dir * 220.0;
        vec3 cell = floor(p);
        vec3 f = fract(p) - 0.5;
        float h1 = _hash(cell);

        if (h1 > 0.9975) {
            float d = length(f);
            float star = 1.0 - smoothstep(0.0, 0.42, d);
            float twinkle = 0.65 + 0.35 * sin(u_Time * 3.0 + h1 * 120.0);
            sky += vec3(0.8, 0.86, 1.0) * (star * twinkle * u_Night * clear);
        }
    }

    // Final rain flattening. The zenith and horizon already carry MakeUp's ZENITH/HORIZON rain
    // mix; this only removes the saturated glare that a procedural gradient can leave behind.
    sky = mix(sky, vec3(dot(sky, vec3(0.3, 0.59, 0.11))), u_Rain * 0.25);

    fragColor = vec4(sky, 1.0);
}
