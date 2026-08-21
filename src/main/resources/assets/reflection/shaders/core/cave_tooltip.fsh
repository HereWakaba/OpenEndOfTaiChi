#version 150

// CAVE 09 — path-traced procedural cave with neon cubes.
// Ported: iChannel0 seed → procedural hash; iChannel1 temporal accum → removed; iterations 200→100.

uniform float time;
uniform vec2 screenSize;
uniform float yaw;
uniform float pitch;

in vec4 vertexColor;
out vec4 fragColor;

#define iTime time
#define iResolution screenSize
#define fs(i) (fract(sin((i)*114.514)*1919.810))
#define lofi(i,j) (floor((i)/(j))*(j))

const float PI = acos(-1.);

float seed;

mat2 r2d(float t) {
    return mat2(cos(t), sin(t), -sin(t), cos(t));
}

mat3 orthBas(vec3 z) {
    z = normalize(z);
    vec3 up = abs(z.y) > .999 ? vec3(0, 0, 1) : vec3(0, 1, 0);
    vec3 x = normalize(cross(up, z));
    return mat3(x, cross(z, x), z);
}

float random() {
    seed++;
    return fs(seed);
}

vec3 uniformLambert(vec3 n) {
    float p = PI * 2. * random();
    float cost = sqrt(random());
    float sint = sqrt(1.0 - cost * cost);
    return orthBas(n) * vec3(cos(p) * sint, sin(p) * sint, cost);
}

vec4 tbox(vec3 ro, vec3 rd, vec3 s) {
    vec3 or2 = ro / rd;
    vec3 pl = abs(s / rd);
    vec3 f = -or2 - pl;
    vec3 b = -or2 + pl;
    float fl = max(f.x, max(f.y, f.z));
    float bl = min(b.x, min(b.y, b.z));
    if (bl < fl || fl < 0.) return vec4(1E2);
    vec3 n = -sign(rd) * step(f.yzx, f.xyz) * step(f.zxy, f.xyz);
    return vec4(n, fl);
}

struct QTR {
    vec3 cell;
    vec3 pos;
    float len;
    float size;
    bool hole;
};

bool isHole(vec3 p) {
    if (abs(p.x) < .5 && abs(p.y) < .5) return true;
    float dice = fs(dot(p, vec3(-2, -5, 7)));
    if (dice < .3) return true;
    return false;
}

QTR qt(vec3 ro, vec3 rd) {
    vec3 haha = lofi(ro + rd * 1E-2, .5);
    float ha = fs(dot(haha, vec3(6, 2, 0)));
    ha = smoothstep(-0.2, 0.2, sin(0.5 * iTime + PI * 2. * (ha - .5)));
    ro.z += ha;

    QTR r;
    r.size = 1.;
    for (int i = 0; i < 4; i++) {
        r.size /= 2.;
        r.cell = lofi(ro + rd * 1E-2 * r.size, r.size) + r.size / 2.;
        if (isHole(r.cell)) break;
        float dice = fs(dot(r.cell, vec3(5, 6, 7)));
        if (dice > r.size) break;
    }

    vec3 or2 = (ro - r.cell) / rd;
    vec3 pl = abs(r.size / 2. / rd);
    vec3 b = -or2 + pl;
    r.len = min(b.x, min(b.y, b.z));
    r.pos = r.cell - vec3(0, 0, ha);
    r.hole = isHole(r.cell);
    return r;
}

void main() {
    vec2 fc = gl_FragCoord.xy - vec2(yaw, pitch);
    vec2 uv = fc / iResolution;
    vec2 p = uv * 2. - 1.;
    p.x *= iResolution.x / iResolution.y;

    // Procedural seed (replaces iChannel0 texture)
    seed = fs(dot(fc, vec2(12.9898, 78.233)) + fract(iTime * 7.13));

    float haha = iTime * 62.0 / 60.0;
    float haha2 = floor(haha) - .2 * exp(-fract(haha));

    p = r2d(iTime * .2 + .2 * floor(haha)) * p;

    vec3 ro0 = vec3(0, 0, 1);
    ro0.z -= haha2;
    ro0 += .02 * vec3(sin(iTime * 1.36), sin(iTime * 1.78), 0);

    vec3 rd0 = normalize(vec3(p, -1.));

    vec3 ro = ro0;
    vec3 rd = rd0;
    vec3 fp = ro + rd * 2.;
    ro += vec3(0.04 * vec2(random(), random()) * mat2(1, 1, -1, 1), 0);
    rd = normalize(fp - ro);

    float rl = .01;
    vec3 rp = ro + rd * rl;

    vec3 col = vec3(0);
    vec3 colRem = vec3(1);
    float samples = 1.;

    for (int i = 0; i < 100; i++) {
        QTR qtr = qt(rp, rd);

        vec4 isect;
        if (qtr.hole) {
            isect = vec4(1E2);
        } else {
            float size = qtr.size * .5;
            size -= .01;
            size -= .02 * (.5 + .5 * sin(5.0 * iTime + 15.0 * qtr.cell.z));
            isect = tbox(rp - qtr.pos, rd, vec3(size));
        }

        if (isect.w < 1E2) {
            float fog = exp(-.2 * rl);
            colRem *= fog;

            rl += isect.w;
            rp = ro + rd * rl;

            vec3 mtl = fs(cross(qtr.cell, vec3(4, 8, 1)));
            vec3 n = isect.xyz;

            if (mtl.x < .1) {
                col += colRem * vec3(10, 1, 1);
                colRem *= 0.;
            } else if (mtl.x < .2) {
                col += colRem * vec3(6, 8, 11);
                colRem *= 0.;
            } else {
                colRem *= 0.3;
            }

            ro = ro + rd * rl;
            rd = mix(uniformLambert(n), reflect(rd, n), pow(random(), .3));
            rl = .01;
        } else {
            rl += qtr.len;
            rp = ro + rd * rl;
        }

        if (colRem.x < .01) {
            ro = ro0;
            rd = rd0;
            fp = ro + rd * 2.;
            ro += vec3(0.04 * vec2(random(), random()) * mat2(1, 1, -1, 1), 0);
            rd = normalize(fp - ro);
            rl = .01;
            rp = ro + rd * rl;
            colRem = vec3(1);
            samples++;
        }
    }

    col = pow(col / samples, vec3(.4545));
    col *= 1.0 - 0.4 * length(p);
    col = vec3(
        smoothstep(.1, .9, col.x),
        smoothstep(.0, 1.0, col.y),
        smoothstep(-.1, 1.1, col.z)
    );

    fragColor = vec4(col, 1.0) * vertexColor;
}
