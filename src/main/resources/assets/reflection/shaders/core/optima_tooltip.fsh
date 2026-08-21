#version 150

// GR Physical Black Hole — ported from ShaderToy multi-pass (Buffer A core).
// Geodesic ray marching, Novikov-Thorne accretion disc, relativistic jets.
// TAA removed (single-frame), bloom preprocess replaced with Image tonemapping.
// Camera auto-rotates (no iMouse). Procedural Perlin noise (no iChannel).

uniform float time;
uniform vec2 screenSize;
uniform float yaw;
uniform float pitch;

in vec4 vertexColor;
out vec4 fragColor;

#define iTime time
#define iResolution screenSize

const float kPi              = 3.141592653589;
const float kGravityConstant = 6.673e-11;
const float kSpeedOfLight    = 299792458.0;
const float kSigma           = 5.670373e-8;
const float kLightYear       = 9460730472580800.0;
const float kSolarMass       = 1.9884e30;
const float TEST = 1.0;

float RandomStep(vec2 Input, float Seed)
{
    return fract(sin(dot(Input + fract(11.4514 * sin(Seed)), vec2(12.9898, 78.233))) * 43758.5453);
}

float CubicInterpolate(float x)
{
    return 3.0 * x * x - 2.0 * x * x * x;
}

float PerlinNoise(vec3 Position)
{
    vec3 PosInt   = floor(Position);
    vec3 PosFloat = fract(Position);

    float v000 = 2.0 * fract(sin(dot(vec3(PosInt.x,       PosInt.y,       PosInt.z),       vec3(12.9898, 78.233, 213.765))) * 43758.5453) - 1.0;
    float v100 = 2.0 * fract(sin(dot(vec3(PosInt.x + 1.0, PosInt.y,       PosInt.z),       vec3(12.9898, 78.233, 213.765))) * 43758.5453) - 1.0;
    float v010 = 2.0 * fract(sin(dot(vec3(PosInt.x,       PosInt.y + 1.0, PosInt.z),       vec3(12.9898, 78.233, 213.765))) * 43758.5453) - 1.0;
    float v110 = 2.0 * fract(sin(dot(vec3(PosInt.x + 1.0, PosInt.y + 1.0, PosInt.z),       vec3(12.9898, 78.233, 213.765))) * 43758.5453) - 1.0;
    float v001 = 2.0 * fract(sin(dot(vec3(PosInt.x,       PosInt.y,       PosInt.z + 1.0), vec3(12.9898, 78.233, 213.765))) * 43758.5453) - 1.0;
    float v101 = 2.0 * fract(sin(dot(vec3(PosInt.x + 1.0, PosInt.y,       PosInt.z + 1.0), vec3(12.9898, 78.233, 213.765))) * 43758.5453) - 1.0;
    float v011 = 2.0 * fract(sin(dot(vec3(PosInt.x,       PosInt.y + 1.0, PosInt.z + 1.0), vec3(12.9898, 78.233, 213.765))) * 43758.5453) - 1.0;
    float v111 = 2.0 * fract(sin(dot(vec3(PosInt.x + 1.0, PosInt.y + 1.0, PosInt.z + 1.0), vec3(12.9898, 78.233, 213.765))) * 43758.5453) - 1.0;

    float v00 = v001 * CubicInterpolate(PosFloat.z) + v000 * CubicInterpolate(1.0 - PosFloat.z);
    float v10 = v101 * CubicInterpolate(PosFloat.z) + v100 * CubicInterpolate(1.0 - PosFloat.z);
    float v01 = v011 * CubicInterpolate(PosFloat.z) + v010 * CubicInterpolate(1.0 - PosFloat.z);
    float v11 = v111 * CubicInterpolate(PosFloat.z) + v110 * CubicInterpolate(1.0 - PosFloat.z);
    float v0  = v01  * CubicInterpolate(PosFloat.y) + v00  * CubicInterpolate(1.0 - PosFloat.y);
    float v1  = v11  * CubicInterpolate(PosFloat.y) + v10  * CubicInterpolate(1.0 - PosFloat.y);

    return v1 * CubicInterpolate(PosFloat.x) + v0 * CubicInterpolate(1.0 - PosFloat.x);
}

float PerlinNoise1D(float Position)
{
    float PosInt   = floor(Position);
    float PosFloat = fract(Position);
    float v0 = 2.0 * fract(sin(PosInt*12.9898) * 43758.5453) - 1.0;
    float v1 = 2.0 * fract(sin((PosInt+1.0)*12.9898) * 43758.5453) - 1.0;
    return v1 * CubicInterpolate(PosFloat) + v0 * CubicInterpolate(1.0 - PosFloat);
}

float SoftSaturate(float x)
{
    return 1.0 - 1.0 / (max(x, 0.0) + 1.0);
}

float GenerateAccretionDiskNoise(vec3 Position, float NoiseStartLevel, float NoiseEndLevel, float ContrastLevel)
{
    float NoiseAccumulator = 10.0;
    float start = NoiseStartLevel;
    float end = NoiseEndLevel;
    int iStart = int(floor(start));
    int iEnd = int(ceil(end));
    int maxIterations = iEnd - iStart;
    for (int delta = 0; delta < maxIterations; delta++)
    {
        int i = iStart + delta;
        float iFloat = float(i);
        float w = max(0.0, min(end, iFloat + 1.0) - max(start, iFloat));
        if (w <= 0.0) continue;
        float NoiseFrequency = pow(3.0, iFloat);
        vec3 ScaledPosition = NoiseFrequency * Position;
        float noise = PerlinNoise(ScaledPosition);
        NoiseAccumulator *= (1.0 + 0.1 * noise * w);
    }
    return log(1.0 + pow(0.1 * NoiseAccumulator, ContrastLevel));
}

float Vec2ToTheta(vec2 v1, vec2 v2)
{
    if (dot(v1, v2) > 0.0)
    {
        return asin(0.999999 * (v1.x * v2.y - v1.y * v2.x) / length(v1) / length(v2));
    }
    else if (dot(v1, v2) < 0.0 && (-v1.x * v2.y + v1.y * v2.x) < 0.0)
    {
        return kPi - asin(0.999999 * (v1.x * v2.y - v1.y * v2.x) / length(v1) / length(v2));
    }
    else if (dot(v1, v2) < 0.0 && (-v1.x * v2.y + v1.y * v2.x) > 0.0)
    {
        return -kPi - asin(0.999999 * (v1.x * v2.y - v1.y * v2.x) / length(v1) / length(v2));
    }
    return 0.0;
}

vec3 KelvinToRgb(float Kelvin)
{
    if (Kelvin < 400.01) return vec3(0.0);
    float Teff = (Kelvin - 6500.0) / (6500.0 * Kelvin * 2.2);
    vec3 RgbColor = vec3(0.0);
    RgbColor.r = exp(2.05539304e4 * Teff);
    RgbColor.g = exp(2.63463675e4 * Teff);
    RgbColor.b = exp(3.30145739e4 * Teff);
    float BrightnessScale = 1.0 / max(max(RgbColor.r, RgbColor.g), RgbColor.b);
    if (Kelvin < 1000.0) BrightnessScale *= (Kelvin - 400.0) / 600.0;
    RgbColor *= BrightnessScale;
    return RgbColor;
}

float GetKeplerianAngularVelocity(float Radius, float Rs)
{
    return sqrt(kSpeedOfLight / kLightYear * kSpeedOfLight * Rs / kLightYear / ((2.0 * Radius - 3.0 * Rs) * Radius * Radius));
}

vec3 WorldToBlackHoleSpace(vec4 Position, vec3 BlackHolePos, vec3 DiskNormal, vec3 WorldUp)
{
    if (DiskNormal == WorldUp) DiskNormal += 0.0001 * vec3(1.0, 0.0, 0.0);
    vec3 BlackHoleSpaceY = normalize(DiskNormal);
    vec3 BlackHoleSpaceZ = normalize(cross(WorldUp, BlackHoleSpaceY));
    vec3 BlackHoleSpaceX = normalize(cross(BlackHoleSpaceY, BlackHoleSpaceZ));
    mat4 Translate = mat4(1.0, 0.0, 0.0, -BlackHolePos.x,
                          0.0, 1.0, 0.0, -BlackHolePos.y,
                          0.0, 0.0, 1.0, -BlackHolePos.z,
                          0.0, 0.0, 0.0, 1.0);
    mat4 Rotate = mat4(BlackHoleSpaceX.x, BlackHoleSpaceX.y, BlackHoleSpaceX.z, 0.0,
                       BlackHoleSpaceY.x, BlackHoleSpaceY.y, BlackHoleSpaceY.z, 0.0,
                       BlackHoleSpaceZ.x, BlackHoleSpaceZ.y, BlackHoleSpaceZ.z, 0.0,
                       0.0,               0.0,               0.0,               1.0);
    Position = transpose(Translate) * Position;
    Position = transpose(Rotate) * Position;
    return Position.xyz;
}

vec3 ApplyBlackHoleRotation(vec4 Position, vec3 BlackHolePos, vec3 DiskNormal, vec3 WorldUp)
{
    if (DiskNormal == WorldUp) DiskNormal += 0.0001 * vec3(1.0, 0.0, 0.0);
    vec3 BlackHoleSpaceY = normalize(DiskNormal);
    vec3 BlackHoleSpaceZ = normalize(cross(WorldUp, BlackHoleSpaceY));
    vec3 BlackHoleSpaceX = normalize(cross(BlackHoleSpaceY, BlackHoleSpaceZ));
    mat4 Rotate = mat4(BlackHoleSpaceX.x, BlackHoleSpaceX.y, BlackHoleSpaceX.z, 0.0,
                       BlackHoleSpaceY.x, BlackHoleSpaceY.y, BlackHoleSpaceY.z, 0.0,
                       BlackHoleSpaceZ.x, BlackHoleSpaceZ.y, BlackHoleSpaceZ.z, 0.0,
                       0.0,               0.0,               0.0,               1.0);
    Position = transpose(Rotate) * Position;
    return Position.xyz;
}

// Camera — fixed angle (no rotation, avoids time-wrap jump)
vec4 GetCamera(vec4 Position)
{
    float Theta = 4.0 * kPi * 0.15;
    float Phi   = 0.999 * kPi * 0.5 + 0.0005;
    float R = TEST * 0.000807;
    vec3 Rotcen = vec3(0.0);
    vec3 reposcam = vec3(R * sin(Phi) * cos(Theta), -R * cos(Phi), -R * sin(Phi) * sin(Theta));
    vec3 Campos = Rotcen + reposcam;
    vec3 vecy = vec3(0.0, 1.0, 0.0);
    vec3 X = normalize(cross(vecy, reposcam));
    vec3 Y = normalize(cross(reposcam, X));
    vec3 Z = normalize(reposcam);
    Position = transpose(mat4(1., 0., 0., -Campos.x, 0., 1., 0., -Campos.y, 0., 0., 1., -Campos.z, 0., 0., 0., 1.)) * Position;
    Position = transpose(mat4(X.x, X.y, X.z, 0., Y.x, Y.y, Y.z, 0., Z.x, Z.y, Z.z, 0., 0., 0., 0., 1.)) * Position;
    return Position;
}

vec4 GetCameraRot(vec4 Position)
{
    float Theta = 4.0 * kPi * 0.15;
    float Phi   = 0.999 * kPi * 0.5 + 0.0005;
    vec3 reposcam = vec3(0.000807 * sin(Phi) * cos(Theta), -0.000807 * cos(Phi), -0.000807 * sin(Phi) * sin(Theta));
    vec3 vecy = vec3(0.0, 1.0, 0.0);
    vec3 X = normalize(cross(vecy, reposcam));
    vec3 Y = normalize(cross(reposcam, X));
    vec3 Z = normalize(reposcam);
    Position = transpose(mat4(X.x, X.y, X.z, 0., Y.x, Y.y, Y.z, 0., Z.x, Z.y, Z.z, 0., 0., 0., 0., 1.)) * Position;
    return Position;
}

vec3 FragUvToDir(vec2 FragUv, float Fov)
{
    return normalize(vec3(Fov * (2.0 * FragUv.x - 1.0), Fov * (2.0 * FragUv.y - 1.0) * iResolution.y / iResolution.x, -1.0));
}

vec2 DirToFragUv(vec3 Dir)
{
    return vec2(0.5 - 0.5 * Dir.x / Dir.z, 0.5 - 0.5 * Dir.y / Dir.z * iResolution.x / iResolution.y);
}

float Shape(float x, float Alpha, float Beta)
{
    float k = pow(Alpha + Beta, Alpha + Beta) / (pow(Alpha, Alpha) * pow(Beta, Beta));
    return k * pow(x, Alpha) * pow(1.0 - x, Beta);
}


vec4 DiskColor(vec4 BaseColor, float TimeRate, float StepLength, vec3 RayPos, vec3 LastRayPos,
               vec3 RayDir, vec3 LastRayDir, vec3 WorldUp, vec3 BlackHolePos, vec3 DiskNormal,
               float Rs, float InterRadius, float OuterRadius, float Thin, float DiskTemperatureArgument,
               float QuadraticedPeakTemperature, float ShiftMax)
{
    vec3 CameraPos = WorldToBlackHoleSpace(vec4(0.0, 0.0, 0.0, 1.0), BlackHolePos, DiskNormal, WorldUp);
    vec3 PosOnDisk = WorldToBlackHoleSpace(vec4(RayPos, 1.0), BlackHolePos, DiskNormal, WorldUp);
    vec3 DirOnDisk = ApplyBlackHoleRotation(vec4(RayDir, 1.0), BlackHolePos, DiskNormal, WorldUp);
    float PosR = length(PosOnDisk);
    float PosY = PosOnDisk.y;
    float Hopper = 1.5;
    Thin += max(0.0, (length(PosOnDisk.xz) - 3.0*Rs) * Hopper);
    Thin *= 0.5 + 0.5*exp(-30.0*pow(PosR/OuterRadius, 4.0));
    vec4 Color = vec4(0.0);
    vec4 Result = vec4(0.0);
    if (abs(PosY) < 0.5 * Thin && PosR < OuterRadius && PosR > InterRadius)
    {
        float EffectiveRadius = 1.0 - ((PosR - InterRadius) / (OuterRadius - InterRadius) * 0.5);
        if ((OuterRadius - InterRadius) > 9.0 * Rs)
        {
            if (PosR < 5.0 * Rs + InterRadius)
                EffectiveRadius = 1.0 - ((PosR - InterRadius) / (9.0 * Rs) * 0.5);
            else
                EffectiveRadius = 1.0 - (0.5 / 0.9 * 0.5 + ((PosR - InterRadius) / (OuterRadius - InterRadius) -
                                  5.0 * Rs / (OuterRadius - InterRadius)) / (1.0 - 5.0 * Rs / (OuterRadius - InterRadius)) * 0.5);
        }
        if ((abs(PosY) < 0.5 * Thin * Shape(EffectiveRadius, 4.0, 0.9)) || (PosY < 0.5 * Thin * (1.0 - 5.0 * pow(2.0 * (1.0 - EffectiveRadius), 2.0))))
        {
            float AngularVelocity = GetKeplerianAngularVelocity(PosR, Rs);
            float HalfPiTimeInside = kPi / GetKeplerianAngularVelocity(3.0 * Rs, Rs);
            float SpiralTheta = 12.0*2.0/sqrt(3.0)*(atan(sqrt(0.6666666*(PosR/Rs)-1.0)));
            float InnerTheta = kPi / HalfPiTimeInside * iTime * TimeRate;
            float PosThetaForInnerCloud = Vec2ToTheta(PosOnDisk.zx, vec2(cos(0.666666*InnerTheta), sin(0.666666*InnerTheta)));
            float PosTheta = Vec2ToTheta(PosOnDisk.zx, vec2(cos(-SpiralTheta), sin(-SpiralTheta)));
            float PosLogarithmicTheta = Vec2ToTheta(PosOnDisk.zx, vec2(cos(-2.0*log(PosR/Rs)), sin(-2.0*log(PosR/Rs))));
            float DiskTemperature = pow(DiskTemperatureArgument * pow(Rs/PosR, 3.0) * max(1.0 - sqrt(InterRadius / PosR), 0.000001), 0.25);
            vec3 CloudVelocity = kLightYear / kSpeedOfLight * AngularVelocity * cross(vec3(0., 1., 0.), PosOnDisk);
            float RelativeVelocity = dot(-DirOnDisk, CloudVelocity);
            float Dopler = sqrt((1.0 + RelativeVelocity) / (1.0 - RelativeVelocity));
            float RedShift = Dopler * sqrt(max(1.0 - Rs / PosR, 0.000001)) / sqrt(max(1.0 - Rs / length(CameraPos), 0.000001));
            float Density = 0.0;
            float Thick = 0.0;
            float VerticalMixFactor = 0.0;
            float RotPosR = PosR/Rs + 0.3*sqrt(3.0)*kSpeedOfLight/kLightYear/3.0/sqrt(3.0)/Rs*TimeRate*iTime;
            vec4 Color0 = vec4(0.0);
            Density = max(0.0, Shape(EffectiveRadius, 4.0, 0.9) - 0.0);
            if (abs(PosY) < 0.5 * Thin * Density)
            {
                Thick = 0.5 * Thin * Density;
                VerticalMixFactor = max(0.0, (1.0 - abs(PosY) / Thick));
                Density *= 0.7 * VerticalMixFactor * Density;
                Color0 = vec4(GenerateAccretionDiskNoise(vec3(0.1*(RotPosR), 0.1*PosY/Rs, 0.02*pow(OuterRadius/Rs,0.7)*PosTheta), 2.0-0.91*log(1.0+(0.06/0.91*max(0.0,PosR/Rs-10.0))), 4.0-0.91*log(1.0+(0.06/0.91*max(0.0,PosR/Rs-10.0))), 80.0-80.0*log(1.0+(0.1*0.06*max(0.0,PosR/Rs-10.0)))));
                if (PosTheta+kPi < 0.1*kPi)
                {
                    Color0 *= (PosTheta+kPi)/(0.1*kPi);
                    Color0 += (1.0-((PosTheta+kPi)/(0.1*kPi)))*vec4(GenerateAccretionDiskNoise(vec3(0.1*(RotPosR), 0.1*PosY/Rs, 0.02*pow(OuterRadius/Rs,0.7)*(PosTheta+2.0*kPi)), 2.0-0.91*log(1.0+(0.06/0.91*max(0.0,PosR/Rs-10.0))), 4.0-0.91*log(1.0+(0.06/0.91*max(0.0,PosR/Rs-10.0))), 80.0-80.0*log(1.0+(0.1*0.06*max(0.0,PosR/Rs-10.0)))));
                }
                if (PosR > 0.15379*OuterRadius)
                {
                    float Spir = GenerateAccretionDiskNoise(vec3(0.1*(PosR-0.1*sqrt(3.0)*kSpeedOfLight/kLightYear/3.0/sqrt(3.0)/Rs*TimeRate*iTime-0.08*OuterRadius/Rs*PosLogarithmicTheta), 0.1*PosY/Rs, 0.02*pow(OuterRadius/Rs,0.7)*PosLogarithmicTheta), 2.0-0.91*log(1.0+(0.06/0.91*max(0.0,PosR/Rs-10.0))), 3.0-0.91*log(1.0+(0.06/0.91*max(0.0,PosR/Rs-10.0))), 80.0-80.0*log(1.0+(0.1*0.06*max(0.0,PosR/Rs-10.0))));
                    if (PosLogarithmicTheta+kPi < 0.1*kPi)
                    {
                        Spir *= (PosLogarithmicTheta+kPi)/(0.1*kPi);
                        Spir += (1.0-((PosLogarithmicTheta+kPi)/(0.1*kPi)))*GenerateAccretionDiskNoise(vec3(0.1*(PosR-0.1*sqrt(3.0)*kSpeedOfLight/kLightYear/3.0/sqrt(3.0)/Rs*TimeRate*iTime-0.08*OuterRadius/Rs*(PosLogarithmicTheta+2.0*kPi)), 0.1*PosY/Rs, 0.02*pow(OuterRadius/Rs,0.7)*(PosLogarithmicTheta+2.0*kPi)), 2.0-0.91*log(1.0+(0.06/0.91*max(0.0,PosR/Rs-10.0))), 3.0-0.91*log(1.0+(0.06/0.91*max(0.0,PosR/Rs-10.0))), 80.0-80.0*log(1.0+(0.1*0.06*max(0.0,PosR/Rs-10.0))));
                    }
                    Color0 *= mix(1.0, clamp(0.7*Spir*1.5-0.5, 0.0, 3.0), 0.5+0.5*max(-1.0, 1.0-exp(-1.5*0.1*(100.0*PosR/OuterRadius-20.0))));
                }
                Color0.xyz *= Density * 1.4 * (0.2 + 0.8*VerticalMixFactor + (0.8 - 0.8*VerticalMixFactor));
                Color0.a *= Density * Density / 0.3;
                Color0.xyz *= max(0.0, (0.2+4.0*sqrt(pow(PosY/Thin, 2.0)+0.001)));
            }
            Color = Color0;
            float BrightWithoutRedshift = 0.7 / exp((PosR - InterRadius) / (0.12 * ((0.8+0.2*abs(normalize(CameraPos).y))*OuterRadius - InterRadius)));
            if (DiskTemperature > 1000.0)
                DiskTemperature = max(1000.0, DiskTemperature * RedShift * Dopler * Dopler);
            Color.xyz *= BrightWithoutRedshift * KelvinToRgb(DiskTemperature);
            Color.xyz *= min(ShiftMax, RedShift) * min(ShiftMax, Dopler);
            RedShift = min(RedShift, ShiftMax);
            Color.a *= 0.5;
            Color *= mix(vec4(100.0*Rs/OuterRadius), vec4(vec3(0.3+0.7*100.0*Rs/OuterRadius), 1.0), exp(-pow(20.0*PosR/OuterRadius, 2.0)));
            float SmallRCenterSparse = min(1.0, max(0.0, (OuterRadius/Rs/100.0)-1.0));
            Color.a *= 0.5+0.5*SmallRCenterSparse+(0.5-0.5*SmallRCenterSparse)*clamp((PosR/Rs-18.0)*0.3, 0.0, 1.0);
            Color.xyz *= 0.64+0.36*SmallRCenterSparse+(0.36-0.36*SmallRCenterSparse)*clamp((PosR/Rs-18.0)*0.3, 0.0, 1.0);
            Color *= StepLength / Rs;
        }
    }
    else
    {
        return BaseColor;
    }
    // 星际红化和饱和度控制
    float Reddening = 0.3;
    float Saturation = 0.5;
    float aR = 1.0 + Reddening*(1.0-1.0);
    float aG = 1.0 + Reddening*(3.0-1.0);
    float aB = 1.0 + Reddening*(6.0-1.0);
    float Sum_rgb = (Color.r + Color.g + Color.b)*pow(1.0 - BaseColor.a, aG);
    float r001 = 0.0, g001 = 0.0, b001 = 0.0;
    float Denominator = Color.r*pow(1.0-BaseColor.a, aR) + Color.g*pow(1.0-BaseColor.a, aG) + Color.b*pow(1.0-BaseColor.a, aB);
    if (Denominator > 0.000001)
    {
        r001 = Sum_rgb * Color.r * pow(1.0-BaseColor.a, aR) / Denominator;
        g001 = Sum_rgb * Color.g * pow(1.0-BaseColor.a, aG) / Denominator;
        b001 = Sum_rgb * Color.b * pow(1.0-BaseColor.a, aB) / Denominator;
        r001 *= pow(3.0*r001/(r001+g001+b001), Saturation);
        g001 *= pow(3.0*g001/(r001+g001+b001), Saturation);
        b001 *= pow(3.0*b001/(r001+g001+b001), Saturation);
    }
    Result.r = BaseColor.r + r001;
    Result.g = BaseColor.g + g001;
    Result.b = BaseColor.b + b001;
    Result.a = BaseColor.a + Color.a * pow((1.0 - BaseColor.a), 1.0);
    return Result;
}

vec4 JetColor(vec4 BaseColor, float TimeRate, float StepLength, vec3 RayPos, vec3 LastRayPos,
              vec3 RayDir, vec3 LastRayDir, vec3 WorldUp, vec3 BlackHolePos, vec3 DiskNormal,
              float Rs, float InterRadius, float OuterRadius, float Thin, float DiskTemperatureArgument,
              float QuadraticedPeakTemperature, float ShiftMax)
{
    vec3 PosOnDisk = WorldToBlackHoleSpace(vec4(RayPos, 1.0), BlackHolePos, DiskNormal, WorldUp);
    float PosR = length(PosOnDisk);
    float PosY = PosOnDisk.y;
    vec4 Color = vec4(0.0);
    bool NotInJet = true;
    vec4 Result = vec4(0.0);
    if (length(PosOnDisk.xz)*length(PosOnDisk.xz) < 2.0*InterRadius*InterRadius+0.03*0.03*PosY*PosY && PosR < sqrt(2.0)*OuterRadius)
    {
        NotInJet = false;
        float InnerTheta = 3.0*GetKeplerianAngularVelocity(InterRadius, Rs)*(iTime*TimeRate - kLightYear/0.8/kSpeedOfLight*abs(PosY));
        float ShapeVal = 1.0/sqrt(InterRadius*InterRadius+0.02*0.02*PosY*PosY);
        float a = mix(0.7+0.3*PerlinNoise1D(0.3*(iTime*TimeRate-kLightYear/0.8/kSpeedOfLight*abs(abs(PosY)+100.0*(dot(PosOnDisk.xz,PosOnDisk.xz)/PosR)))/(OuterRadius/100.0)/(kLightYear/0.8/kSpeedOfLight)), 1.0, exp(-0.01*0.01*PosY*PosY/Rs/Rs));
        vec4 Color0 = vec4(1.0,1.0,1.0,0.5)*max(0.0, 1.0-5.0*Rs*ShapeVal*abs(1.0-pow(length(PosOnDisk.xz)*ShapeVal, 2.0)))*Rs*ShapeVal;
        Color0 *= a;
        Color0 *= max(0.0, 1.0-1.0*exp(-0.0001*PosY/InterRadius*PosY/InterRadius));
        Color0 *= exp(-4.0/2.0*PosR/OuterRadius*PosR/OuterRadius);
        Color0 *= 0.5;
        Color += Color0;
    }
    float Wid = abs(PosY);
    if (length(PosOnDisk.xz) < 1.3*InterRadius+0.25*Wid && length(PosOnDisk.xz) > 0.7*InterRadius+0.15*Wid && PosR < 30.0*InterRadius)
    {
        NotInJet = false;
        float InnerTheta = 2.0*GetKeplerianAngularVelocity(InterRadius, Rs)*(iTime*TimeRate - kLightYear/0.8/kSpeedOfLight*abs(PosY));
        float ShapeVal = 1.0/(InterRadius+0.2*Wid);
        vec4 Color1 = vec4(1.0,1.0,1.0,0.5)*max(0.0, 1.0-2.0*abs(1.0-pow(length(PosOnDisk.xz + 0.2*(1.1-exp(-0.1*0.1*PosY*PosY/Rs/Rs))*Rs*(PerlinNoise1D(0.35*(iTime*TimeRate-kLightYear/0.8/kSpeedOfLight*abs(PosY))/Rs/(kLightYear/0.8/kSpeedOfLight))-0.5)*vec2(cos(0.666666*InnerTheta),-sin(0.666666*InnerTheta)))*ShapeVal, 2.0)))*Rs*ShapeVal;
        Color1 *= 1.0-exp(-PosY/InterRadius*PosY/InterRadius);
        Color1 *= exp(-0.005*PosY/InterRadius*PosY/InterRadius);
        Color1 *= 0.5;
        Color += Color1;
    }
    if (!NotInJet)
    {
        Color.xyz *= 1.0 * KelvinToRgb(100000.0);
        Color *= StepLength / Rs;
    }
    if (NotInJet) return BaseColor;
    float Reddening = 0.0;
    float Saturation = 0.0;
    float aR = 1.0 + Reddening*(1.0-1.0);
    float aG = 1.0 + Reddening*(2.5-1.0);
    float aB = 1.0 + Reddening*(4.5-1.0);
    float Sum_rgb = (Color.r + Color.g + Color.b)*pow(1.0-BaseColor.a, aG);
    float r001 = 0.0, g001 = 0.0, b001 = 0.0;
    float Denominator = Color.r*pow(1.0-BaseColor.a, aR) + Color.g*pow(1.0-BaseColor.a, aG) + Color.b*pow(1.0-BaseColor.a, aB);
    if (Denominator > 0.000001)
    {
        r001 = Sum_rgb * Color.r * pow(1.0-BaseColor.a, aR) / Denominator;
        g001 = Sum_rgb * Color.g * pow(1.0-BaseColor.a, aG) / Denominator;
        b001 = Sum_rgb * Color.b * pow(1.0-BaseColor.a, aB) / Denominator;
        r001 *= pow(3.0*r001/(r001+g001+b001), Saturation);
        g001 *= pow(3.0*g001/(r001+g001+b001), Saturation);
        b001 *= pow(3.0*b001/(r001+g001+b001), Saturation);
    }
    Result.r = BaseColor.r + r001;
    Result.g = BaseColor.g + g001;
    Result.b = BaseColor.b + b001;
    Result.a = BaseColor.a + Color.a * pow((1.0 - BaseColor.a), 1.0);
    return Result;
}

void main()
{
    vec2 fc = gl_FragCoord.xy - vec2(yaw, pitch);
    vec4 color = vec4(0.0);  // 纯透明底（无背景色，只画黑洞本体）
    vec2 FragUv = fc / iResolution.xy;
    float Fov = 0.5;
    float TimeRate = TEST * 3000.0;
    float MBlackHole = 1.49e7;
    float a0 = 0.0;
    float Rs = 2.0 * MBlackHole * kGravityConstant / kSpeedOfLight / kSpeedOfLight * kSolarMass;
    float z1 = 1.0 + pow(1.0-a0*a0, 0.333333333)*( pow(1.0+a0*a0, 0.333333333) + pow(1.0-a0, 0.333333333) );
    float RmsRatio = (3.0 + sqrt(3.0*a0*a0 + z1*z1) - sqrt((3.0-z1)*(3.0+z1+2.0*sqrt(3.0*a0*a0+z1*z1)))) / 2.0;
    float AccEff = sqrt(1.0 - 1.0/RmsRatio);
    float mu = 1.0;
    float dmdtEdd = 6.327*mu/kSpeedOfLight/kSpeedOfLight*MBlackHole*kSolarMass/AccEff;
    float dmdt = 2e1 * dmdtEdd;
    float diskA = 3.0*kGravityConstant*kSolarMass/Rs/Rs/Rs*MBlackHole*dmdt/(8.0*kPi*kSigma);
    float QuadraticedPeakTemperature = diskA * 0.05665278;
    Rs = Rs / kLightYear;
    float InterRadius = 0.7 * RmsRatio * Rs;
    float OuterRadius = TEST * 100.0 * Rs;
    float Thin = 8.0 * Rs;
    float shiftMax = 1.25;

    vec3 WorldUp = GetCameraRot(vec4(0., 1., 0., 0.)).xyz;
    vec4 BlackHoleAPos = vec4(0.0, 0.0, 5.0*Rs, 1.0);
    vec4 BlackHoleADiskNormal = vec4(normalize(vec3(1.9, 1.0, 0.0)), 0.0);
    vec3 BlackHoleRPos = GetCamera(BlackHoleAPos).xyz;
    vec3 BlackHoleRDiskNormal = GetCameraRot(BlackHoleADiskNormal).xyz;
    vec3 RayDir = FragUvToDir(FragUv, Fov);
    vec3 RayPos = vec3(0.0);

    vec3 PosToBlackHole = RayPos - BlackHoleRPos;
    float DistanceToBlackHole = length(PosToBlackHole);
    vec3 NormalizedPosToBlackHole = PosToBlackHole / DistanceToBlackHole;
    RayDir = normalize(RayDir - NormalizedPosToBlackHole*dot(NormalizedPosToBlackHole, RayDir)*(-sqrt(max(1.0-Rs*CubicInterpolate(max(min(1.0-(0.01*DistanceToBlackHole/Rs-1.0)/4.0, 1.0), 0.0))/DistanceToBlackHole, 0.00000000000000001))+1.0));

    vec3 LastRayPos;
    vec3 LastRayDir;
    float StepLength = 0.0;
    float LastR = length(PosToBlackHole);
    float CosTheta, DeltaPhi, DeltaPhiRate, RayStep;
    bool flag = true;
    int Count = 0;

    while (flag && Count < 400)
    {
        PosToBlackHole = RayPos - BlackHoleRPos;
        DistanceToBlackHole = length(PosToBlackHole);
        NormalizedPosToBlackHole = PosToBlackHole / DistanceToBlackHole;
        if (DistanceToBlackHole > 2.5*OuterRadius && DistanceToBlackHole > LastR && Count > 50)
            flag = false;
        if (DistanceToBlackHole < 0.1*Rs)
            flag = false;
        if (flag)
        {
            color = DiskColor(color, TimeRate, StepLength, RayPos, LastRayPos, RayDir, LastRayDir, WorldUp, BlackHoleRPos, BlackHoleRDiskNormal, Rs, InterRadius, OuterRadius, Thin, diskA, QuadraticedPeakTemperature, shiftMax);
            color = JetColor(color, TimeRate, StepLength, RayPos, LastRayPos, RayDir, LastRayDir, WorldUp, BlackHoleRPos, BlackHoleRDiskNormal, Rs, InterRadius, OuterRadius, Thin, diskA, QuadraticedPeakTemperature, shiftMax);
        }
        if (color.a > 0.99) flag = false;
        LastRayPos = RayPos;
        LastRayDir = RayDir;
        LastR = DistanceToBlackHole;
        CosTheta = length(cross(NormalizedPosToBlackHole, RayDir));
        DeltaPhiRate = -1.0 * CosTheta*CosTheta*CosTheta * (1.5*Rs/DistanceToBlackHole);
        RayStep = (Count == 0) ? 0.5 : 1.0;
        RayStep *= 0.15 + 0.25*min(max(0.0, 0.5*(0.5*DistanceToBlackHole/max(10.0*Rs, OuterRadius)-1.0)), 1.0);
        if (DistanceToBlackHole >= 2.0*OuterRadius)
            RayStep *= DistanceToBlackHole;
        else if (DistanceToBlackHole >= 1.0*OuterRadius)
            RayStep *= ((Rs+0.25*max(DistanceToBlackHole-12.0*Rs, 0.0))*(2.0*OuterRadius-DistanceToBlackHole) + DistanceToBlackHole*(DistanceToBlackHole-OuterRadius)) / OuterRadius;
        else
            RayStep *= min(Rs+0.25*max(DistanceToBlackHole-12.0*Rs, 0.0), DistanceToBlackHole);
        RayPos += RayDir * RayStep;
        DeltaPhi = RayStep / DistanceToBlackHole * DeltaPhiRate;
        RayDir = normalize(RayDir + (DeltaPhi + DeltaPhi*DeltaPhi*DeltaPhi/3.0) * cross(cross(RayDir, NormalizedPosToBlackHole), RayDir) / CosTheta);
        StepLength = RayStep;
        Count++;
    }

    // Tonemapping (from Image pass — replaces bloom preprocess)
    vec3 col = color.rgb;
    col = pow(col, vec3(1.5));
    col = col / (1.0 + col);
    col = pow(col, vec3(1.0 / 1.5));
    col = mix(col, col * col * (3.0 - 2.0 * col), vec3(1.0));
    col = pow(col, vec3(1.3, 1.20, 1.0));
    col = clamp(col * 1.01, 0.0, 1.0);
    col = pow(col, vec3(0.7 / 2.2));

    fragColor = vec4(col, clamp(color.a, 0.0, 1.0)) * vertexColor;
}
