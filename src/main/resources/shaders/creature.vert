#version 330 core

// Per vertex (PartMeshBuilder)
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec3 aSwing;     // pivot y, pivot z, direction (0 = rigid part)
// Per instance (divisor 1): model matrix (locations 4-7), color modulation, walk animation
layout(location = 4) in mat4 aModel;
layout(location = 8) in vec3 aTint;
layout(location = 9) in vec2 aWalk;      // phase (radians), amplitude (radians)

uniform mat4 uProjection;
uniform mat4 uView;

out vec3 vWorldPos;
out vec3 vNormal;
out vec3 vColor;

void main() {
    vec3 position = aPosition;
    vec3 normal = aNormal;
    if (aSwing.z != 0.0) {
        // Legs swing around the X axis through the hip.
        float angle = aSwing.z * sin(aWalk.x) * aWalk.y;
        float c = cos(angle);
        float s = sin(angle);
        vec2 local = vec2(position.y - aSwing.x, position.z - aSwing.y);
        position.y = aSwing.x + local.x * c - local.y * s;
        position.z = aSwing.y + local.x * s + local.y * c;
        normal = vec3(normal.x, normal.y * c - normal.z * s, normal.y * s + normal.z * c);
    }
    vec4 world = aModel * vec4(position, 1.0);
    vWorldPos = world.xyz;
    vNormal = mat3(aModel) * normal;   // rotation + uniform scale only
    vColor = aColor * aTint;
    gl_Position = uProjection * uView * world;
}
