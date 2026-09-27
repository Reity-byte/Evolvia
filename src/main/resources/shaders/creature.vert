#version 330 core

// Per vertex
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in float aShade;
// Per instance (divisor 1): model matrix (locations 3-6) and color
layout(location = 3) in mat4 aModel;
layout(location = 7) in vec3 aColor;

uniform mat4 uProjection;
uniform mat4 uView;

out vec3 vWorldPos;
out vec3 vNormal;
out vec3 vColor;

void main() {
    vec4 world = aModel * vec4(aPosition, 1.0);
    vWorldPos = world.xyz;
    vNormal = mat3(aModel) * aNormal;   // rotation + uniform scale only
    vColor = aColor * aShade;
    gl_Position = uProjection * uView * world;
}
