#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>

// The window box around the portal opening, in camera-relative world space.
layout(location = 0) in vec3 Position;

// Where on the box this fragment is (camera-relative): the fragment shader follows the line of
// sight through it back to the portal's plane.
layout(location = 0) out vec3 sightPoint;

void main() {
    sightPoint = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
