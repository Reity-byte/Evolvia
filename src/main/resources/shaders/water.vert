#version 330 core

layout(location = 0) in vec3 aPosition;

uniform mat4 uProjection;
uniform mat4 uView;
uniform float uLevel;        // flood: the sea rises (phase 9e)

out vec3 vWorldPos;

void main() {
    vWorldPos = aPosition + vec3(0.0, uLevel, 0.0);
    gl_Position = uProjection * uView * vec4(vWorldPos, 1.0);
}
