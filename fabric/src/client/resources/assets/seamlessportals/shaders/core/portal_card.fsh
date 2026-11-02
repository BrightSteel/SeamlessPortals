#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 sightPoint;
layout(location = 0) out vec4 fragColor;

// Whether the line of sight through this fragment crosses the portal's plane inside its opening.
// The window is a box behind the opening (so it still covers it with the camera right at the
// plane), and from close up and askew the box covers more of the screen than the opening does:
// only what's seen through the opening itself is the far side. TextureMat holds the opening:
// its middle (camera-relative), its right and up axes over its half width and height, and the
// plane's normal.
bool throughOpening(vec3 sight) {
    vec3 middle = TextureMat[0].xyz;
    vec3 normal = TextureMat[3].xyz;
    float along = dot(normal, sight);
    if (along == 0.0) {
        return false;
    }
    float t = dot(normal, middle) / along;
    if (t <= 0.0) {
        return false;
    }
    vec3 offset = sight * t - middle;
    return abs(dot(offset, TextureMat[1].xyz)) <= 1.0 && abs(dot(offset, TextureMat[2].xyz)) <= 1.0;
}

// A portal over the live-view budget: a flat card in the far side's fog colour.
void main() {
    if (!throughOpening(sightPoint)) {
        discard;
    }
    fragColor = vec4(ColorModulator.rgb, 1.0);
}
