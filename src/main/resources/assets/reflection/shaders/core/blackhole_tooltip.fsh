#version 150

#define Pi 3.1415926

uniform float time;
uniform vec2 screenSize;
uniform float yaw;
uniform float pitch;

in vec4 vertexColor;
out vec4 fragColor;

#define iTime time
#define iResolution screenSize

float torus_sdf(vec3 p, vec2 t) {
    vec2 q = vec2(length(p.xz)-t.x, p.y);
    return length(q)-t.y;
}

float sphere_sdf(vec3 pos, float r) { return length(pos) - r; }

const float bh_r = 10.;
float blackhole(vec3 pos) { return sphere_sdf(pos, bh_r); }

mat2 Rot(float a) {
    float s = sin(a), c = cos(a);
    return mat2(c, -s, s, c);
}

vec2 N22(vec2 id) {
    id = id*vec2(123.1,456.2);
    id += dot(id,id);
    return fract(sin(id)*vec2(5.123,123.3));
}

float N21(vec2 id) {
    id = id*vec2(227.1,125.2);
    return fract(sin(dot(id,id))*215.3);
}

vec2 rPos(vec2 id) { return N22(id) - .5; }

vec3 star(vec2 uv, vec2 id) {
    float l = length(uv);
    float center = 0.035/l;
    float st = center;
    float N = N21(id);
    float Size = N*2.;
    vec3 color = sin(vec3(0.2,0.5,0.7)*fract(N*73.1)*15.)*0.5+0.5;
    return st * N * smoothstep(1.,0.,l) * color*vec3(1.0,0.7,Size);
}

vec3 starLayer(vec2 uv,float i) {
    vec2 id = floor(uv);
    uv = fract(uv)-0.5;
    vec3 col = vec3(0.);
    for(float x = -1.;x<=1.;++x) {
        for(float y = -1.;y<=1.;++y) {
            vec2 nid = id + vec2(x,y);
            vec2 rpos = rPos(nid+i+1.);
            vec2 nuv = uv + rpos - vec2(x,y);
            vec3 st = star(nuv,nid);
            col += st*fract(cos((i+1.)*100.)*23.1)*2.5;
        }
    }
    return col;
}

float hash(float x){ return fract(sin(x)*152754.742); }
float hash(vec2 x){ return hash(x.x + hash(x.y)); }

float value(vec2 p, float f) {
    float bl = hash(floor(p*f + vec2(0.,0.)));
    float br = hash(floor(p*f + vec2(1.,0.)));
    float tl = hash(floor(p*f + vec2(0.,1.)));
    float tr = hash(floor(p*f + vec2(1.,1.)));

    vec2 fr = fract(p*f);
    fr = (3. - 2.*fr)*fr*fr;
    float b = mix(bl, br, fr.x);
    float t = mix(tl, tr, fr.x);
    return mix(b,t, fr.y);
}

const int N = 380;
const vec3 bhp = vec3(0.0, -25., 100);

vec3 ray_marching(vec3 pos, vec3 dir) {
    vec3 hitpos = pos;
    float dt = 0.2;
    float hitbh = .0;
    vec3 speed = dir;
    vec3 torCol = vec3(0.0);
    vec3 center = bhp + 30.5*vec3(sin(0.7*iTime), sin(.5*iTime), 0);
    vec3 glowColor = vec3(0.);
    for(int i = 0; i < N; ++i) {
        hitpos += speed * dt * (1.0 - hitbh);
        float d = blackhole(hitpos - center);
        hitbh = smoothstep(3., -3., d);

        // disk rotation
        float rotangle = Pi/18.0;
        mat3 torRot = mat3(
            vec3(cos(rotangle), -sin(rotangle), 0),
            vec3(sin(rotangle), cos(rotangle), 0),
            vec3(0, 0, 1)
        );
        vec3 torpos = (torRot*(hitpos - center)) * vec3(1.0, 42., 1.02);
        float tor = torus_sdf(torpos, vec2(35.0, 52))/20.;

        vec3 uvPos = hitpos - center;
        float v = smoothstep(0., 1., length(uvPos.xz)/118.);
        float u = atan(uvPos.z, uvPos.x)/Pi * v - iTime*0.1;

        vec2 toruv = vec2(u, v)*vec2(2.3, 3.1);

        float fadeTor = max(0., pow(v-0.1, 2.));
        vec3 c1 = vec3(0.9, 0.35, 0.1);
        vec3 c2 = vec3(1.0, 0.6, 0.4);
        vec3 mainColor = vec3(1.)*mix(c2, c1, fadeTor);
        float torTex = value(toruv, 30.);

        float ch = smoothstep(.1, -.5, tor);
        vec3 c = mainColor;
        c *= max(0., torTex);
        c *= 20.8/pow(length(uvPos) - 0., 1.5);

        float bds = smoothstep(0., 3., d);
        torCol += c * ch * (1.0 - hitbh) * bds;

        vec3 bhv = torRot*(hitpos - center);
        // jets
        float t = iTime;
        float jet = (0.5*sin(t+sin(t+sin(t+sin(t*2.))))+0.5);
        jet = smoothstep(0.5, 0.6, jet);
        jet = 300.*jet;
        vec3 jetColor = mix(vec3(0.3,0.3,0.6), vec3(0.6,0.3,0.3), smoothstep(0.,50., abs(bhv.y)));
        torCol += jetColor
            * .7/dot(bhv.xz, bhv.xz)*(1.0-hitbh)* step(0.8, 1.-hitbh)
            * smoothstep(jet, 0., abs(bhv.y));

        // glow
        glowColor += (vec3(1.0, 0.9, 0.7) * (1./dot(bhv, bhv))*1.5) * (1.0-hitbh);

        // gravity
        vec3 b = center - hitpos;
        float G = 10.5;
        float l = length(b);
        float a = G/(l*l);
        speed += a * normalize(b);
    }

    vec3 bg = starLayer(hitpos.xy/25.0, 5.) * (1.0-hitbh);
    vec3 col = bg*0.09 + torCol + glowColor*0.3;
    return col;
}

void main() {
    vec2 localCoord = gl_FragCoord.xy - vec2(yaw, pitch);
    vec2 st = localCoord / iResolution;
    st = st * 2.0 - 1.0;
    st.x *= iResolution.x / iResolution.y;

    // Auto-rotate camera
    float angle = sin(iTime * 0.1) * 0.3;
    mat2 rot = Rot(Pi/6.);

    float zoom = 3.;
    vec3 ro = vec3(0., 85., -1.0);
    ro.yz -= bhp.yz;
    ro.yz = rot * ro.yz;
    ro.yz += bhp.yz;
    vec3 lookat = bhp;
    vec3 f = normalize(lookat - ro);
    vec3 r = cross(vec3(0,1,0), f);
    vec3 u = cross(f, r);
    vec3 sd = ro + f * zoom + st.x * r + st.y * u;
    vec3 rd = sd - ro;

    vec3 color = ray_marching(ro, rd);

    // vignette
    float vignette = 1.0 - length(st) * 0.5;
    color *= clamp(vignette, 0.0, 1.0);

    fragColor = vec4(color, 1.0) * vertexColor;
}
