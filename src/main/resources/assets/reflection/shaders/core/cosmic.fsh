#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform float time;
uniform vec2 screenSize;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

#define iTime time

#define R(p,a,t) mix(a*dot(p,a),p,cos(t))+sin(t)*cross(p,a)
#define R2(p,t) p*cos(t)+vec2(p.y,-p.x)*sin(t)
#define H(h) (cos((h)*6.3+vec3(0,23,21))*.5+.5)

#define iterations 12
#define formuparam 0.53

#define volsteps 20
#define stepsize 0.1

#define zoom   0.800
#define tile   0.850
#define speed  0.000

#define brightness 0.0015
#define darkmatter 0.300
#define distfading 0.730
#define saturation 0.850

#define NUM_LAYERS 8.
#define TAU 6.28318
#define PI 3.141592
#define Velocity .025
#define StarGlow 0.025
#define StarSize 02.
#define CanvasView 20.


float Star(vec2 uv, float flare){
    float d = length(uv);
    float m = sin(StarGlow*1.2)/d;
    float rays = max(0., .5-abs(uv.x*uv.y*1000.));
    m += (rays*flare)*2.;
    m *= smoothstep(1., .1, d);
    return m;
}

float Hash21(vec2 p){
    p = fract(p*vec2(123.34, 456.21));
    p += dot(p, p+45.32);
    return fract(p.x*p.y);
}

vec3 StarLayer(vec2 uv){
    vec3 col = vec3(0);
    vec2 gv = fract(uv);
    vec2 id = floor(uv);
    for(int y=-1;y<=1;y++){
        for(int x=-1; x<=1; x++){
            vec2 offs = vec2(x,y);
            float n = Hash21(id+offs);
            float size = fract(n);
            float star = Star(gv-offs-vec2(n, fract(n*34.))+.5, smoothstep(.1,.9,size)*.46);
            vec3 color = sin(vec3(.2,.3,.9)*fract(n*2345.2)*TAU)*.25+.75;
            color = color*vec3(.9,.59,.9+size);
            star *= sin(iTime*.6+n*TAU)*.5+.5;
            col += star*size*color;
        }
    }
    return col;
}

void main(){
    // 屏幕空间坐标（与 Shadertoy 一致）：星云/星层随 iTime 旋转流动，屏幕锚定不穿帮；
    // 剑形仍由 mask 裁剪（EQUAL_DEPTH_TEST + mask.r），手持/背包/展示框三种上下文效果一致
    vec2 uv = gl_FragCoord.xy / screenSize - .5;                        // 原 fragCoord.xy / iResolution.xy - .5（非等比）
    vec2 ua = (gl_FragCoord.xy - .5*screenSize) / screenSize.y;         // 原 (fragCoord-.5*iResolution.xy)/iResolution.y（等比）
    float t3 = iTime * .1 + ((.25 + .05 * sin(iTime * .1))/(length(uv.xy) + .07)) * 2.2;
    vec4 O = vec4(0.);
    vec3 dir=vec3(uv*zoom,1.);
    vec3 from=vec3(1.,.5,0.5);
    //volumetric rendering
    float s=0.1,fade=1.;
    vec3 v=vec3(0.);
    vec2 M = vec2(0);
    M -= vec2(M.x+sin(iTime*0.22), M.y-cos(iTime*0.22));
    float t4 = iTime*Velocity;
    vec3 col4= vec3(0);
    for(float i=0.; i<1.; i+=1./NUM_LAYERS){
        float depth = fract(i+t4);
        float scale = mix(CanvasView, .5, depth);
        float fade = depth*smoothstep(1.,.9,depth);
        col4 += StarLayer(uv*scale+i*453.2-iTime*.05+M)*fade;}
    for (int r=0; r<volsteps; r++) {
        vec3 p3=from+s*dir*.5;
        p3 = abs(vec3(tile)-mod(p3,vec3(tile*2.))); // tiling fold
        float pa,a=pa=0.;
        for (int i=0; i<iterations; i++) {
            p3=abs(p3)/dot(p3,p3)-formuparam;
            p3.xy*=mat2(cos(iTime*0.01),sin(iTime*0.01),-sin(iTime*0.01) ,cos(iTime*0.01));// the magic formula
            a+=abs(length(p3)-pa); // absolute sum of average change
            pa=length(p3);
        }
        float dm=max(0.,darkmatter-a*a*.001); //dark matter
        a*=a*a; // add contrast
        if (r>6) fade*=1.2-dm; // dark matter, don't render near
        v+=fade;
        v+=vec3(s,s*s,s*s*s*s)*a*brightness*fade; // coloring based on distance
        fade*=distfading; // distance fading
        s+=stepsize;
    }
    v=mix(vec3(length(v)),v,saturation); //color adjust
    vec4 o = vec4(v*.03,1.);
    o-=o;
    for(float d,t = -iTime*0.01, i = 0. ; i > -1.; i -= .06 ){
        d = fract( i -3.*t );
        vec4 c = vec4( ua*d ,i,0 ) * 2.;
        for (int j=0 ; j++ <27; )
            c.xzyw = abs( c / dot(c,c)
                    -vec4( 7.-.2*sin(t) , 6.3 , .7 , 1.-cos(t/.8))/7.);
        o -= c * c.yzww  * d--*d  / vec4(1,2,1,1);
    }
    vec3 p,q,c=vec3(0),
    d=normalize(vec3(ua,1.));
    float i=0.,e,g=0.,t=iTime;
    for(;i++<90.;){
        p=R(g*d,normalize(H(t*.001)*2.),0.);
        q=p;
        p.z-=t*1.01;
        p=abs(fract(p)-.5);
        e=length(p)-.15;
        p=p.x<p.z?p.zyx:p;
        p=p.x>p.y?p.yxz:p;
        g+=e=max(-e,length(p.xy))*0.7;
        c+=mix(vec3(1),H(q.z*1.15+.4),.7)*.4/exp(30.*e)/g;
    }
    O=vec4(c,1);
    float v1, v2, v3;
    v1 = v2 = v3 = 0.0;

    float s3 = 0.0;
    for (int i2 = 0; i2 < 40; i2++){
        vec3 p2 = s3 * vec3(uv, 0.0);
        p2 += vec3(.22, .3, s - 1.5 - sin(iTime * .13) * .1)+O.xyz*o.xyz;
        for (int j2 = 0; j2 < 8; j2++) p2 = abs(p2) / dot(p2,p2) - 0.659;
        v1 += dot(p2,p2) * .0015 * (1.8 + sin(length(uv.xy * 13.0) + .5  - iTime * .2));
        v2 += dot(p2,p2) * .0013 * (1.5 + sin(length(uv.xy * 14.5) + 1.2 - iTime * .3));
        v3 += length(p2.xy*10.) * .0003;
        s3  += .035;
    }

    float len = length(uv);
    v1 *= smoothstep(2.2, .0, len);
    v2 *= smoothstep(.52, .0, len);
    v3 *= smoothstep(2.5, .0, len);

    vec3 col = vec3( v3 * (1.5 + sin(iTime * .2) * .4),
                    (v1 + v3) * .3,
                     v2) + smoothstep(0.2, .0, len) * .85 + smoothstep(.0, .6, v3) * .3;

    vec3 finalCol = min(pow(abs(col*v*.03*col4)*30.+o.xyz*vec3(0.1,0.1,4.)*3., vec3(1.2)), 1.0);

    // 与 MC 光照轻微融合（同上一版 lightmix = 0.2）
    const float lightmix = 0.2;
    vec3 shade = vertexColor.rgb * (lightmix) + vec3(1.0-lightmix);
    finalCol *= shade;

    // 剑形 mask 二次裁剪（EQUAL_DEPTH_TEST 已限定轮廓，mask.r 修边并给出软 alpha）
    vec4 mask = texture(Sampler0, texCoord0.xy);

    fragColor = linear_fog(vec4(finalCol, mask.r) * ColorModulator, vertexDistance, FogStart, FogEnd, FogColor);
}
