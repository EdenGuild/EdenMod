#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform GammaConfig {
    float Exponent;
};

out vec4 fragColor;

void main() {
    vec3 color = texture(InSampler, texCoord).rgb;
    // Raising each channel to a power below one lifts darks, but it also pulls the channels toward each other,
    // which is what makes a frame look washed out. Lifting the brightness alone and scaling the colour to match
    // keeps its hue and saturation.
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float lifted = pow(luma, Exponent);
    vec3 result = color * (lifted / max(luma, 0.0001));
    // A very saturated colour can be scaled past white; bring it back by its brightest channel, not by clipping.
    result /= max(1.0, max(result.r, max(result.g, result.b)));
    fragColor = vec4(result, 1.0);
}
