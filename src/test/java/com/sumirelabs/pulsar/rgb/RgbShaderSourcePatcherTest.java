package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Entirely original synthetic shaders; no third-party pack source is used. */
class RgbShaderSourcePatcherTest {
    private static final String VERTEX = "#version 330 core\nuniform mat4 iris_ModelViewMatrix; in vec4 iris_Vertex; void main(){ gl_Position=iris_Vertex; }";
    private static final String FORWARD = "#version 330 core\nvoid GetLighting(inout vec3 albedo, vec3 worldPos){ vec3 sceneLighting=vec3(1); vec3 blockLighting=vec3(0.5); albedo *= sceneLighting+blockLighting; }";

    @Test void lightingTermsAreChangedWithoutRewritingMaterialOrPbrOutputs() {
        String source = FORWARD + "\nlayout(location=1)out vec4 normalOut; void main(){ normalOut=vec4(0,1,0,1); }";
        var result = RgbShaderSourcePatcher.patch(VERTEX, source, false);
        assertEquals(1, result.blockSites()); assertEquals(1, result.skySites());
        assertTrue(result.fragment().contains("pulsarBlockTerm(blockLighting, pulsarBlockData, pulsarHaveField)"));
        assertTrue(result.fragment().contains("normalOut=vec4(0,1,0,1)"));
        assertTrue(result.fragment().startsWith("#version 330 core\n"));
        assertSame(VERTEX, result.vertex());
    }
    @Test void commentedFunctionsAndContinuedDirectivesAreNotEdited() {
        String source = "#version 330 core\n#define TEXT \\\n void GetLighting(inout vec3 albedo, vec3 worldPos){ albedo *= blockLighting; }\n"
                + "/* void GetLighting(inout vec3 albedo, vec3 worldPos){albedo*=blockLighting;} */\nvoid main(){}";
        var result = RgbShaderSourcePatcher.patch(VERTEX, source, false);
        assertFalse(result.changed()); assertSame(source, result.fragment());
    }
    @Test void simpleLightmapUsesItsOwnVaryingAndKeepsAlphaAndCoordinates() {
        String source = "#version 330 core\nuniform sampler2D lightmap; in vec2 uv; out vec4 color; void main(){color=texture(lightmap, uv);}";
        var result = RgbShaderSourcePatcher.patch(VERTEX, source, false);
        assertEquals(1, result.lightmapSites());
        assertTrue(result.fragment().contains("pulsarLightmap(texture(lightmap, uv), pulsarLightPosition, vec2( uv))"));
        assertTrue(result.vertex().contains("void pulsarOriginalMain()"));
        assertTrue(result.vertex().contains("pulsarOriginalMain(); pulsarLightPosition"));
        assertTrue(result.fragment().contains("original.a"));
        assertTrue(result.fragment().contains("greaterThanEqual(coordinates"));
    }
    @Test void unknownIntermediateStageNeverGetsAnUnlinkedVarying() {
        String source = "#version 330 core\nuniform sampler2D lightmap; void main(){vec4 c=texture(lightmap,vec2(0));}";
        assertFalse(RgbShaderSourcePatcher.patch(VERTEX, source, true).changed());
        assertTrue(RgbShaderSourcePatcher.patch(VERTEX, FORWARD, true).changed());
    }
    @Test void emittedInterfaceIsIdempotentAndLeavesShadowOnlySourcesAlone() {
        var once = RgbShaderSourcePatcher.patch(VERTEX, FORWARD, false);
        var twice = RgbShaderSourcePatcher.patch(once.vertex(), once.fragment(), false);
        assertFalse(twice.changed()); assertSame(once.fragment(), twice.fragment());
        String shadow = "#version 330 core\nout vec4 color;void main(){color=vec4(1,0,0,0.4);}";
        assertFalse(RgbShaderSourcePatcher.patch(VERTEX, shadow, false).changed());
        assertFalse(RgbShaderSourcePatcher.patch(VERTEX, FORWARD.replace("330", "120"), false).changed());
    }
    @Test void deferredBlocklightCallRetainsTheLoaderFallbackAndHandlight() {
        String source = "#version 430 core\nvec3 get_diffuse_lighting(vec3 scene_pos){vec3 mc_blocklight=vec3(1),lighting=vec3(0),skylight=vec3(1);"
                + "lighting += get_lpv_blocklight(scene_pos,mc_blocklight); lighting += skylight; lighting += get_handheld_lighting(scene_pos); return lighting;}";
        var result = RgbShaderSourcePatcher.patch(VERTEX, source, false);
        assertEquals(1, result.blockSites()); assertEquals(1, result.skySites());
        assertTrue(result.fragment().contains("pulsarBlockTerm(( get_lpv_blocklight(scene_pos,mc_blocklight)), mc_blocklight, pulsarBlockData, pulsarHaveField)"));
        assertTrue(result.fragment().contains("lighting += get_handheld_lighting(scene_pos)"));
    }
}
