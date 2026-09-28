#version 330 core

in vec3 vWorldPos;

uniform vec4 uWaterColor;
uniform vec3 uSunDirection;
uniform float uAmbient;
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
uniform vec3 uCameraPos;
uniform vec3 uLightTint;     // time of day (phase 9d)

out vec4 fragColor;

void main() {
    float diffuse = max(uSunDirection.y, 0.0);  // flat surface, normal = +Y
    vec3 lit = uWaterColor.rgb * (uAmbient + (1.0 - uAmbient) * diffuse);

    // Fade into the fog color and become opaque with distance, so the far sea blends with the sky.
    lit *= uLightTint;
    float fog = smoothstep(uFogStart, uFogEnd, distance(vWorldPos, uCameraPos));
    fragColor = vec4(mix(lit, uFogColor, fog), mix(uWaterColor.a, 1.0, fog));
}
