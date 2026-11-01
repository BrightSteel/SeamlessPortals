#version 330
#extension GL_ARB_separate_shader_objects : require

// The bridge view (same camera and projection as the main view): colour and depth.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(Sampler1, texCoord).r;
    // The far plane means the bridge drew nothing here (sky): 0 with reversed Z (26.2+),
    // 1 with the standard depth range (26.1, whose pipelines define NETHERVIEW_STANDARD_DEPTH).
#ifdef NETHERVIEW_STANDARD_DEPTH
    if (depth >= 1.0) {
#else
    if (depth <= 0.0) {
#endif
        discard;
    }
    fragColor = vec4(texture(Sampler0, texCoord).rgb, 1.0);
    // Depth-tested (nearer or equal) against the main view, so only its unfilled pixels change.
    gl_FragDepth = depth;
}
