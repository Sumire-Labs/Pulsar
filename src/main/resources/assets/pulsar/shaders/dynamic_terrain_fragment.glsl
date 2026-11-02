#define PULSAR_MAX_DYNAMIC 64
uniform int pulsarFrameEnabled;
uniform int pulsarDynamicCount;
uniform int pulsarDynamicRgb;
uniform int pulsarDynamicProjected;
uniform vec3 pulsarViewFraction;
uniform sampler2D pulsarDynamicLoss;
uniform sampler2D u_LightTex;
uniform vec4 pulsarDynamicPosition[PULSAR_MAX_DYNAMIC];
uniform ivec4 pulsarDynamicBox[PULSAR_MAX_DYNAMIC];
uniform vec4 pulsarDynamicEmission[PULSAR_MAX_DYNAMIC];
in vec3 pulsarRelativePosition;
in vec4 pulsarMaterial;
in vec2 pulsarLightUV;

vec4 pulsarLossCell(int source,ivec3 cell) {
    ivec4 box=pulsarDynamicBox[source];ivec3 p=cell-box.xyz;
    if(any(lessThan(p,ivec3(0)))||any(greaterThanEqual(p,ivec3(box.w))))return vec4(1024.0);
    int index=source*24576+p.x+box.w*(p.z+box.w*p.y);
    return texelFetch(pulsarDynamicLoss,ivec2(index&1023,index>>10),0);
}
vec4 pulsarSourceLight(int source,vec3 position,float distance) {
    vec3 grid=position-vec3(0.5),fraction=fract(grid);ivec3 base=ivec3(floor(grid));
    vec4 result=vec4(0.0);
    for(int y=0;y<2;y++)for(int z=0;z<2;z++)for(int x=0;x<2;x++) {
        vec3 w=mix(vec3(1.0)-fraction,fraction,vec3(x,y,z));
        vec4 loss=pulsarLossCell(source,base+ivec3(x,y,z));
        result+=max(vec4(0.0),vec4(1.0)-(vec4(distance)+loss)/7.75)*(w.x*w.y*w.z);
    }
    return result*pulsarDynamicEmission[source];
}
vec3 pulsarFrameLight(vec3 baseline) {
    vec4 light=vec4(0.0);
    vec3 normal=cross(dFdx(pulsarRelativePosition),dFdy(pulsarRelativePosition));
    float normalLength=dot(normal,normal);
    normal=normalLength>1e-12?normal*inversesqrt(normalLength):vec3(0.0);
    if(dot(normal,pulsarViewFraction-pulsarRelativePosition)<0.0)normal=-normal;
    // Sample the visible side of the surface, not a blend with the opaque block underneath it.
    vec3 maskPosition=pulsarRelativePosition+normal*0.501;
    for(int i=0;i<PULSAR_MAX_DYNAMIC;i++) {
        if(i>=pulsarDynamicCount)break;
        float distance=length(pulsarRelativePosition-pulsarDynamicPosition[i].xyz);
        if(distance>=7.75)continue;
        light=max(light,pulsarSourceLight(i,maskPosition,distance));
    }
    if(pulsarDynamicRgb==0) {
        float scalar=pulsarDynamicProjected!=0?max(light.r,max(light.g,light.b)):light.a;
        light.rgb=vec3(scalar);
    }
    float peak=max(light.r,max(light.g,light.b));
    if(peak<=0.0)return baseline;
    vec3 tint=light.rgb/peak;
    float confidence=peak/(60.0-3.0*peak);
    tint+=tint*(vec3(1.0)-tint)*(1.0-confidence);
    vec2 uv=clamp(vec2(peak*16.0,pulsarLightUV.y)/256.0,vec2(0.5/16.0),vec2(15.5/16.0));
    vec3 dynamicLighting=texture(u_LightTex,uv).rgb*tint;
    // Adding a light may brighten a material, never darken the existing ambient/static light.
    // An absorbed RGB component contributes zero; the baseline is not part of the dynamic source.
    return max(baseline,pulsarMaterial.rgb*dynamicLighting);
}
