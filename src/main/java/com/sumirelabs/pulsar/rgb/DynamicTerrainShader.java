package com.sumirelabs.pulsar.rgb;

import java.nio.charset.StandardCharsets;

/** Patches only the known plain Celeritas terrain shader contract. Other pipelines retain their fallback. */
public final class DynamicTerrainShader {
    private DynamicTerrainShader() {}
    private static String resource(String path) {
        try(var in=DynamicTerrainShader.class.getResourceAsStream(path)) {
            if(in==null)throw new IllegalStateException("Missing shader "+path);
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public static String patch(String name,String source) {
        if(source==null||source.contains("PULSAR_FRAME_LIGHT_V1")||!name.contains("blocks/block_layer_opaque."))return source;
        boolean vertex=name.endsWith(".vsh"),fragment=name.endsWith(".fsh");
        if(!vertex&&!fragment)return source;
        String vertexSite="vs_out.v_Color = _vert_color * _sample_lightmap(u_LightTex, _vert_tex_light_coord);";
        String fragmentSite="vec4 m_color = fs_in.v_Color;";
        // Celeritas 2.5 uses separate varyings and a bilinear material correction.
        // Apply dynamic lighting after that correction and retain it in the unlit material too.
        boolean bilinear=vertex?source.contains("v_Color = _vert_color * lightColor;")&&source.contains("v_RdhFactor.rgb *= lightColor.rgb;")
                :source.contains("vec4 m_color = v_Color;")&&source.contains("m_color += v_RdhFactor * correctionWeight;");
        if(bilinear){vertexSite="v_RdhFactor.rgb *= lightColor.rgb;";fragmentSite="m_color += v_RdhFactor * correctionWeight;";}
        if(vertex&&(!source.contains(vertexSite)||!source.contains("vec3 position = _vert_position + translation;")))return source;
        if(fragment&&!source.contains(fragmentSite))return source;
        String common="\n// PULSAR_FRAME_LIGHT_V1\n#ifndef CELERITAS_NO_LIGHTMAP\n";
        if(bilinear)common+="#define PULSAR_BILINEAR_MATERIAL\n";
        if(vertex) {
            common += resource("/assets/pulsar/shaders/lightfield.glsl") + resource("/assets/pulsar/shaders/dynamic_terrain_vertex.glsl");
            source=source.replace(vertexSite,vertexSite+(bilinear
                    ?"\n    if(pulsarFrameEnabled!=0) { vec3 pulsarTint = pulsarStaticTint(pulsarRelativePosition); v_Color.rgb *= pulsarTint; v_RdhFactor.rgb *= pulsarTint; }"
                    :"\n    if(pulsarFrameEnabled!=0) vs_out.v_Color.rgb *= pulsarStaticTint(pulsarRelativePosition);"));
            source=source.replace("vec3 position = _vert_position + translation;", "vec3 position = _vert_position + translation;\n#ifndef CELERITAS_NO_LIGHTMAP\n"
                    +"    pulsarRelativePosition = _vert_position + _get_draw_translation(_draw_id) + pulsarRegionBase(u_RegionOffset);\n"
                    +"    pulsarMaterial = _vert_color; pulsarLightUV = vec2(_vert_tex_light_coord);\n"
                    +(bilinear?"    pulsarMaterialCorrection = _vert_rdh_factor * 2.0; int pulsarCorner = gl_VertexID & 3; pulsarQuadCoord = vec2(pulsarCorner >= 2 ? 1.0 : 0.0, pulsarCorner == 1 || pulsarCorner == 2 ? 1.0 : 0.0);\n":"")+"#endif");
        } else {
            common += resource("/assets/pulsar/shaders/dynamic_terrain_fragment.glsl");
            source=source.replace(fragmentSite,fragmentSite+"\n#ifndef CELERITAS_NO_LIGHTMAP\n    if(pulsarFrameEnabled!=0) m_color.rgb = pulsarFrameLight(m_color.rgb);\n#endif");
        }
        // Uniforms/helpers must follow imports/types and precede main, without moving #version.
        int main=source.indexOf("void main()");
        if(main<0)return source;
        return source.substring(0,main)+common+"\n#endif\n"+source.substring(main);
    }
}
