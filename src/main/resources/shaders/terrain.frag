#version 330 core

in vec3 vWorldPos;
in vec3 vNormal;
in vec3 vColor;

uniform vec3 uSunDirection;   // normalized, pointing towards the sun
uniform float uAmbient;
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
uniform vec3 uCameraPos;
uniform vec3 uLightTint;     // time of day (phase 9d)
uniform float uSnow;         // snow cover 0..1 (phase 9e)
uniform sampler2D uScorch;   // burnt ground per tile (phase 9e)
uniform vec2 uMapSize;

out vec4 fragColor;

void main() {
    float diffuse = max(dot(normalize(vNormal), uSunDirection), 0.0);
    float scorch = uMapSize.x > 0.0 ? texture(uScorch, vWorldPos.xz / uMapSize).r : 0.0; // terrain only
    vec3 base = mix(vColor, vec3(0.09, 0.07, 0.06), scorch);
    float level = smoothstep(0.55, 0.85, normalize(vNormal).y);
    base = mix(base, vec3(0.93, 0.95, 0.99), clamp(uSnow * level * 1.2, 0.0, 0.95));
    vec3 lit = base * (uAmbient + (1.0 - uAmbient) * diffuse);

    lit *= uLightTint;
    float fog = smoothstep(uFogStart, uFogEnd, distance(vWorldPos, uCameraPos));
    fragColor = vec4(mix(lit, uFogColor, fog), 1.0);
}
