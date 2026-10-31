#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) out vec4 fragColor;

// A portal over the live-view budget: a flat card in the far side's fog colour.
void main() {
    fragColor = vec4(ColorModulator.rgb, 1.0);
}
