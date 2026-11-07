uniform int pulsarFrameEnabled;
uniform int pulsarStaticRgb;
uniform int pulsarBlendMode;
uniform ivec3 pulsarCameraModulo;
uniform vec3 pulsarViewFraction;
out vec3 pulsarRelativePosition;
out vec4 pulsarMaterial;
out vec2 pulsarLightUV;
#ifdef PULSAR_BILINEAR_MATERIAL
out vec4 pulsarMaterialCorrection;
out vec2 pulsarQuadCoord;
#endif

vec3 pulsarRegionBase(vec3 offset) {
    // Recover the exact integer region origin without large world-coordinate floats.
    vec3 size = vec3(128.0,64.0,128.0);
    return round((offset + pulsarViewFraction + vec3(pulsarCameraModulo)) / size) * size - vec3(pulsarCameraModulo);
}
vec3 pulsarToLab(vec3 c) {
    vec3 lms=pow(max(mat3(0.4122214708,0.2119034982,0.0883024619,
        0.5363325363,0.6806995451,0.2817188376,0.0514459929,0.1073969566,0.6299787005)*c,vec3(0.0)),vec3(1.0/3.0));
    return mat3(0.2104542553,1.9779984951,0.0259040371,0.7936177850,-2.4285922050,0.7827717662,-0.0040720468,0.4505937099,-0.8086757660)*lms;
}
vec3 pulsarFromLab(vec3 c) {
    vec3 lms=mat3(1,1,1,0.3963377774,-0.1055613458,-0.0894841775,0.2158037573,-0.0638541728,-1.2914855480)*c;
    return max(mat3(4.0767416621,-1.2684380046,-0.0041960863,-3.3077115913,2.6097574011,-0.7034186147,0.2309699292,-0.3413193965,1.7076147010)*(lms*lms*lms),vec3(0.0));
}
vec3 pulsarStaticTint(vec3 position) {
    if(pulsarStaticRgb==0)return vec3(1.0);
    vec4 block,sky;
    if(!pulsarSample(position,block,sky))return vec3(1.0);
    if(pulsarBlendMode==0) {
        vec3 combined=block.rgb+sky.rgb*pulsarSkyScale;
        return (vec3(0.6)+combined)/(0.6+max(block.a+sky.a*pulsarSkyScale,pulsarPeak(combined)));
    }
    vec3 s=max(sky.rgb-vec3(15.0-15.0*pulsarSkyScale),vec3(0.0));
    float bPeak=pulsarPeak(block.rgb),sPeak=pulsarPeak(s),peak=max(bPeak,sPeak);
    vec3 tint=vec3(1.0);
    if(peak>=0.5) {
        if(pulsarBlendMode==1) tint=max(block.rgb,s)/peak;
        else if(pulsarBlendMode==2) {tint=block.rgb+s;tint/=max(0.0001,pulsarPeak(tint));}
        else {
            float bw=pulsarBlendMode==4?bPeak:bPeak*bPeak;
            float sw=pulsarBlendMode==4?sPeak:sPeak*sPeak;
            float total=bw+sw+0.001;bw/=total;sw/=total;
            if(pulsarBlendMode==5) {
                tint=pulsarFromLab((bPeak>0.5?pulsarToLab(block.rgb/15.0):vec3(0.0))*bw+(sPeak>0.5?pulsarToLab(s/15.0):vec3(0.0))*sw);
                tint/=max(0.001,pulsarPeak(tint));
            } else {
                tint=(bPeak>0.5?block.rgb/bPeak:vec3(1.0))*bw+(sPeak>0.5?s/sPeak:vec3(1.0))*sw;
                if(pulsarBlendMode==6) {float avg=(tint.r+tint.g+tint.b)/3.0;tint=max(vec3(0.0),vec3(avg)+(tint-vec3(avg))*1.5);tint/=max(0.001,pulsarPeak(tint));}
            }
        }
        float confidence=peak/(60.0-3.0*peak);
        tint+=tint*(vec3(1.0)-tint)*(1.0-confidence);
    }
    float canonical=block.a+max(0.0,sky.a-(15.0-15.0*pulsarSkyScale));
    return tint*min(1.0,(0.6+bPeak+sPeak)/(0.6+canonical));
}
