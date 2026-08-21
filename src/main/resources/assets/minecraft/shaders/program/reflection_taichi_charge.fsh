#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;
uniform float EffectTime;
uniform float ChargeProgress;
uniform float InvertAmount;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    float p = clamp(ChargeProgress, 0.0, 1.0);   // 蓄力扭曲强度
    float iv = clamp(InvertAmount, 0.0, 1.0);     // 全反强度
    vec2 uv = texCoord;
    vec2 center = uv - 0.5;
    float dist = length(center);
    vec2 dir = center / (dist + 1e-5);
    float t = EffectTime * 60.0;

    // 扭曲：蓄力正弦扭曲 + 全反径向波纹（各自权重，切换时叠加=交叉过渡）
    vec2 warp = vec2(sin(uv.y * 28.0 + t) * 0.006, cos(uv.x * 24.0 + t * 1.3) * 0.006) * p
              + dir * (sin(dist * 42.0 - EffectTime * 3.0) * 0.004) * iv;
    vec2 wuv = uv + warp;

    // 色散（两模式共享，取较强者）
    float pk = max(p, iv);
    float ca = 0.008 * pk;
    float r = texture(DiffuseSampler, wuv + dir * ca).r;
    float g = texture(DiffuseSampler, wuv).g;
    float b = texture(DiffuseSampler, wuv - dir * ca).b;
    vec3 col = vec3(r, g, b);

    // —— 蓄力模式：Sobel 描边 + 黑白 ——（p 驱动）
    float c0 = luma(texture(DiffuseSampler, wuv).rgb);
    float cX = luma(texture(DiffuseSampler, wuv + vec2(oneTexel.x, 0.0)).rgb);
    float cY = luma(texture(DiffuseSampler, wuv + vec2(0.0, oneTexel.y)).rgb);
    float edge = smoothstep(0.06, 0.25, abs(c0 - cX) + abs(c0 - cY)) * p;
    col = mix(col, vec3(1.0), edge * 0.7);
    float gray = luma(col);
    col = mix(col, vec3(gray), p);

    // —— 全反模式：反色 + 去饱和 + 扫描线 + 脉动暗角 ——（iv 驱动）
    if (iv > 0.001) {
        vec3 inv = vec3(1.0) - col;
        float ig = luma(inv);
        vec3 styled = mix(inv, vec3(ig), 0.25);
        float scan = 0.90 + 0.10 * sin(uv.y * InSize.y * 1.5 + EffectTime * 8.0);
        styled *= scan;
        float vigEdge = 0.34 + 0.06 * sin(EffectTime * 2.0);
        float vig = smoothstep(0.95, vigEdge, dist);
        styled *= mix(0.55, 1.0, vig);
        col = mix(col, styled, iv);
    }

    fragColor = vec4(col, 1.0);
}
