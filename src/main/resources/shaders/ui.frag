#version 330 core

in vec2 vUv;
in vec4 vColor;

uniform sampler2D uAtlas;   // single channel glyph coverage

out vec4 fragColor;

void main() {
    float coverage = vUv.x < 0.0 ? 1.0 : texture(uAtlas, vUv).r;
    fragColor = vec4(vColor.rgb, vColor.a * coverage);
}
