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
uniform float uAlpha;       // translucent effects (clouds)

out vec4 fragColor;

void main() {
    float diffuse = max(dot(normalize(vNormal), uSunDirection), 0.0);
    vec3 lit = vColor * (uAmbient + (1.0 - uAmbient) * diffuse);

    lit *= uLightTint;
    float fog = smoothstep(uFogStart, uFogEnd, distance(vWorldPos, uCameraPos));
    fragColor = vec4(mix(lit, uFogColor, fog), uAlpha);
}
