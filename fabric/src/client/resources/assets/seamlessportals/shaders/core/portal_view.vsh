#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>

// The portal opening, in camera-relative world space.
layout(location = 0) in vec3 Position;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
