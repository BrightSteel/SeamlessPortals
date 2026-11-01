#version 330
#extension GL_ARB_separate_shader_objects : require

// The remote view (same projection as the main view): colour and depth.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    // Replaces the whole frame, sky included: the camera is already on the far side.
    fragColor = vec4(texture(Sampler0, texCoord).rgb, 1.0);
    gl_FragDepth = texture(Sampler1, texCoord).r;
}
