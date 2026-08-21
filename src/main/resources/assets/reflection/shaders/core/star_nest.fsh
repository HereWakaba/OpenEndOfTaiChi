#version 150

uniform float time;
uniform vec2 screenSize;

in vec4 vertexColor;
out vec4 fragColor;

#define iTime time

// Star Nest by Pablo Román Andrioli
// This content is under the MIT License.
// Original post by Kali https://www.shadertoy.com/view/XlfGRj

#define iterations 17
#define formuparam 0.53

#define volsteps 20
#define stepsize 0.1

#define zoom   0.800
#define tile   0.850
#define speed  0.002

#define brightness 0.002
#define darkmatter 0.300
#define distfading 0.750
#define saturation 0.750

float SCurve (float value) {

    if (value < 0.5)
    {
        return value * value * value * value * value * 16.0;
    }

    value -= 1.0;

    return value * value * value * value * value * 16.0 + 1.0;
}

void main(){
    //get coords and direction
    vec2 uv=gl_FragCoord.xy/screenSize-.5;
    uv.y*=screenSize.y/screenSize.x;
    vec3 dir=vec3(uv*zoom,1.);
    float t=iTime*speed+.25;

    //mouse rotation（tooltip 背景无鼠标语义：固定为 iMouse=0 对应的角度）
    float a1=.5;
    float a2=.8;
    mat2 rot1=mat2(cos(a1),sin(a1),-sin(a1),cos(a1));
    mat2 rot2=mat2(cos(a2),sin(a2),-sin(a2),cos(a2));
    dir.xz*=rot1;
    dir.xy*=rot2;
    vec3 from=vec3(1.,.5,0.5);
    from+=vec3(t*2.,t,-2.);
    from.xz*=rot1;
    from.xy*=rot2;

    //volumetric rendering
    float s=0.1,fade=1.;
    vec3 v=vec3(0.);
    for (int r=0; r<volsteps; r++) {
        vec3 p=from+s*dir*.5;
        p = abs(vec3(tile)-mod(p,vec3(tile*2.))); // tiling fold
        float pa,a=pa=0.;
        for (int i=0; i<iterations; i++) {
            p=abs(p)/dot(p,p)-formuparam; // the magic formula
            a+=abs(length(p)-pa); // absolute sum of average change
            pa=length(p);
        }
        float dm=max(0.,darkmatter-a*a*.001); //dark matter
        a = pow(a, 2.5); // add contrast
        if (r>6) fade*=1.-dm; // dark matter, don't render near
        v+=fade;
        v+=vec3(s,s*s,s*s*s*s)*a*brightness*fade; // coloring based on distance
        fade*=distfading; // distance fading
        s+=stepsize;
    }

    v=mix(vec3(length(v)),v,saturation); //color adjust

    vec4 C = vec4(v*.01,1.);

    C.r = pow(C.r, 0.35);
    C.g = pow(C.g, 0.36);
    C.b = pow(C.b, 0.4);

    vec4 L = C;

    C.r = mix(L.r, SCurve(C.r), 1.0);
    C.g = mix(L.g, SCurve(C.g), 0.9);
    C.b = mix(L.b, SCurve(C.b), 0.6);

    // alpha 走顶点色（淡入），RGB 为全屏星空
    fragColor = vec4(C.rgb, 1.0) * vertexColor;
}
