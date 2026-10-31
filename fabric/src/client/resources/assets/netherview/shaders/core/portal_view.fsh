#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

// The other dimension, rendered offscreen with the same projection as the main view.
uniform sampler2D Sampler0;

layout(location = 0) out vec4 fragColor;

void main() {
    // Both images share a projection, so the pixel behind this fragment is at the same
    // screen position in the offscreen view.
    fragColor = vec4(texture(Sampler0, gl_FragCoord.xy / ScreenSize).rgb, 1.0);
}
