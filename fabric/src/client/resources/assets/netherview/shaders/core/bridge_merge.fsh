#version 330
#extension GL_ARB_separate_shader_objects : require

// The bridge view (same camera and projection as the main view): colour and depth.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(Sampler1, texCoord).r;
    // Reversed Z: 0 is the far plane, i.e. the bridge drew nothing here (sky).
    if (depth <= 0.0) {
        discard;
    }
    fragColor = vec4(texture(Sampler0, texCoord).rgb, 1.0);
    // Depth-tested (>=) against the main view, so only its unfilled pixels change.
    gl_FragDepth = depth;
}
