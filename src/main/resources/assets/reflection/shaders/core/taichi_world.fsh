#version 150

uniform float time;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 uv = texCoord0 * 2.0 - 1.0; // -1 .. 1

    // 旋转
    float c = cos(time);
    float s = sin(time);
    uv = mat2(c, s, -s, c) * uv;

    float r = length(uv);
    if (r > 1.0) discard; // 圆外透明

    vec3 black = vec3(0.02);
    vec3 white = vec3(0.98);

    float halfR = 0.5;
    float innerR = 1.0 / 6.0;
    vec2 c1 = vec2(halfR, 0.0);
    vec2 c2 = vec2(-halfR, 0.0);
    float d1 = length(uv - c1);
    float d2 = length(uv - c2);

    // 抗锯齿宽度（约 1 像素，随分辨率/视角自适应）——旋转时边界不再爬行闪烁
    float wy = fwidth(uv.y);
    float w1 = fwidth(d1);
    float w2 = fwidth(d2);

    // 上黑下白 + 左右裂片 + 鱼眼，全部用 smoothstep 软化边界
    vec3 col = mix(white, black, smoothstep(-wy, wy, uv.y));
    col = mix(col, black, 1.0 - smoothstep(halfR - w1, halfR + w1, d1));
    col = mix(col, white, 1.0 - smoothstep(halfR - w2, halfR + w2, d2));
    col = mix(col, white, 1.0 - smoothstep(innerR - w1, innerR + w1, d1));
    col = mix(col, black, 1.0 - smoothstep(innerR - w2, innerR + w2, d2));

    // 外圈金色符文光环（基于 r，旋转不变）
    if (r > 0.90) {
        col = mix(col, vec3(1.0, 0.82, 0.30), 0.85);
    }
    // 边缘柔化淡出
    float alpha = smoothstep(1.0, 0.94, r);

    fragColor = vec4(col, alpha) * vertexColor;
}
