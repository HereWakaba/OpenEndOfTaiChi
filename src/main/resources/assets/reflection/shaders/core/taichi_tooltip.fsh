#version 150

uniform float time;
uniform vec2 screenSize;
uniform float yaw;
uniform float pitch;

in vec4 vertexColor;
out vec4 fragColor;

#define iTime time
#define iResolution screenSize

void main() {
    vec2 fragCoord = gl_FragCoord.xy - vec2(yaw, pitch);

    vec2 uv = fragCoord - iResolution.xy / 2.0;
    mat2 rotate = mat2(cos(iTime), sin(iTime), -sin(iTime), cos(iTime));
    uv = rotate * uv;

    float min_len = min(iResolution.x, iResolution.y) / 2.0;
    float R = 3.0 * min_len / 8.0;       // 半圆裂片半径（原 radius.x）
    float rInner = 1.0 * min_len / 8.0;  // 鱼眼半径（原 radius.y）
    float outer = R * 2.0;               // 太极外圆半径

    vec3 black = vec3(0.0);
    vec3 white = vec3(1.0);

    vec2 center1 = vec2(R, 0.0);
    vec2 center2 = vec2(-R, 0.0);
    float r = length(uv);
    float d1 = length(uv - center1);
    float d2 = length(uv - center2);

    // 抗锯齿宽度（约 1 像素）——旋转时边界不再爬行闪烁
    float wy = fwidth(uv.y);
    float w1 = fwidth(d1);
    float w2 = fwidth(d2);
    float wr = fwidth(r);

    vec3 col = mix(white, black, smoothstep(-wy, wy, uv.y));
    col = mix(col, black, 1.0 - smoothstep(R - w1, R + w1, d1));
    col = mix(col, white, 1.0 - smoothstep(R - w2, R + w2, d2));
    col = mix(col, white, 1.0 - smoothstep(rInner - w1, rInner + w1, d1));
    col = mix(col, black, 1.0 - smoothstep(rInner - w2, rInner + w2, d2));

    // 外圆柔化边缘（替代硬 discard，旋转/静止都不锯齿）
    float alpha = 1.0 - smoothstep(outer - wr, outer + wr, r);
    if (alpha <= 0.001) discard;

    fragColor = vec4(col, alpha) * vertexColor;
}
