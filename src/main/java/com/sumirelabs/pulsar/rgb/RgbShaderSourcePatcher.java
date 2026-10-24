/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.rgb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Original integration edits. Shaderpack expressions stay in memory and are never bundled. */
public final class RgbShaderSourcePatcher {
    private static final String MARKER = "PULSAR_LIGHTFIELD_V1";
    private static final Pattern FUNCTIONS = Pattern.compile("\\b(?:void|vec[234])\\s+(GetLighting|DoLighting|gbuffersLighting|get_diffuse_lighting)\\s*\\(");
    private static final String HELPERS = helpers();
    private RgbShaderSourcePatcher() {}
    public record Result(String vertex, String fragment, int blockSites, int skySites, int lightmapSites) {
        public boolean changed() { return blockSites + skySites + lightmapSites > 0; }
    }
    private record Edit(int start, int end, String value) {}

    public static Result patch(String vertex, String fragment, boolean intermediateStages) {
        if (fragment == null || fragment.contains(MARKER) || !modern(fragment)) return new Result(vertex, fragment, 0, 0, 0);
        String mask = mask(fragment);
        List<Edit> edits = new ArrayList<>(); int blocks = 0, skies = 0;
        Matcher functions = FUNCTIONS.matcher(mask);
        while (functions.find()) {
            int open = mask.indexOf('(', functions.start()), close = matching(mask, open, '(', ')');
            if (close < 0) continue;
            int body = skipSpace(mask, close + 1);
            if (body >= mask.length() || mask.charAt(body) != '{') continue;
            int end = matching(mask, body, '{', '}'); if (end < 0) continue;
            String name = functions.group(1);
            String position = name.equals("DoLighting") ? "playerPos" : name.equals("get_diffuse_lighting") ? "scene_pos" : "worldPos";
            if (!word(mask.substring(open, close), position)) continue;
            int before = edits.size();
            if (name.equals("get_diffuse_lighting")) {
                Matcher assignments = Pattern.compile("\\blighting\\s*\\+=").matcher(mask);
                assignments.region(body, end);
                while (assignments.find()) {
                    int from = assignments.end(), to = statementEnd(mask, from, end);
                    if (to < 0) continue;
                    String expression = mask.substring(from, to);
                    String original = fragment.substring(from, to);
                    if (word(expression, "mc_blocklight") || word(expression, "get_lpv_blocklight")) {
                        edits.add(new Edit(from, to, " pulsarBlockTerm((" + original + "), mc_blocklight, pulsarBlockData, pulsarHaveField)")); ++blocks;
                    } else if (word(expression, "skylight") || word(expression, "shadows") || word(expression, "sunlight")) {
                        edits.add(new Edit(from, to, " pulsarSkyTerm((" + original + "), pulsarSkyData, pulsarHaveField)")); ++skies;
                    }
                }
            } else {
                String sink = name.equals("DoLighting") ? "finalDiffuse" : name.equals("gbuffersLighting") ? "diffuseAlbedo" : "albedo";
                Matcher assignments = Pattern.compile("\\b" + sink + "\\s*(?:\\*=|=)").matcher(mask);
                assignments.region(body, end);
                while (assignments.find()) {
                    int from = assignments.end(), to = statementEnd(mask, from, end); if (to < 0) continue;
                    blocks += wrapIdentifier(mask, from, to, "blockLighting", "pulsarBlockTerm", "pulsarBlockData", edits);
                    skies += wrapIdentifier(mask, from, to, "sceneLighting", "pulsarSkyTerm", "pulsarSkyData", edits);
                }
            }
            if (edits.size() != before)
                edits.add(new Edit(body + 1, body + 1, "\nvec4 pulsarBlockData, pulsarSkyData;\nbool pulsarHaveField = pulsarSample(" + position + ", pulsarBlockData, pulsarSkyData);\n"));
        }
        int lightmaps = 0;
        // Simple lightmap programs have no named lighting terms. Their material/alpha
        // remains untouched: only the fetched lightmap value is replaced.
        if (blocks + skies == 0 && vertex != null && !intermediateStages && modern(vertex)) {
            String patchedVertex = positionVertex(vertex);
            if (patchedVertex != null) {
                Matcher calls = Pattern.compile("\\b(?:texture2D|texture)\\s*\\(\\s*lightmap\\s*,").matcher(mask);
                while (calls.find()) {
                    int open = mask.indexOf('(', calls.start()), end = matching(mask, open, '(', ')');
                    if (end < 0) continue;
                    int coordinateEnd = argumentEnd(mask, calls.end(), end);
                    String coordinates = fragment.substring(calls.end(), coordinateEnd);
                    edits.add(new Edit(calls.start(), end + 1, "pulsarLightmap(" + fragment.substring(calls.start(), end + 1) + ", pulsarLightPosition, vec2(" + coordinates + "))"));
                    ++lightmaps;
                }
                if (lightmaps > 0) vertex = patchedVertex;
            }
        }
        if (edits.isEmpty()) return new Result(vertex, fragment, 0, 0, 0);
        String output = apply(fragment, edits);
        output = inject(output, "// " + MARKER + "\n" + HELPERS + (lightmaps > 0 ? "\nin vec3 pulsarLightPosition;\n" : "\n"));
        return new Result(vertex, output, blocks, skies, lightmaps);
    }
    private static int wrapIdentifier(String mask, int start, int end, String identifier, String function, String data, List<Edit> edits) {
        Matcher matcher = Pattern.compile("\\b" + identifier + "\\b").matcher(mask); matcher.region(start, end); int count = 0;
        while (matcher.find()) { edits.add(new Edit(matcher.start(), matcher.end(), function + "(" + identifier + ", " + data + ", pulsarHaveField)")); ++count; }
        return count;
    }
    private static String positionVertex(String source) {
        String mask = mask(source);
        String position = word(mask, "_celeritas_getVertexPosition") ? "_celeritas_getVertexPosition()" : word(mask, "iris_Vertex") ? "iris_Vertex" : null;
        if (position == null || !word(mask, "iris_ModelViewMatrix")) return null;
        Matcher main = Pattern.compile("\\bvoid\\s+(main)\\s*\\(").matcher(mask);
        if (!main.find()) return null;
        String result = source.substring(0, main.start(1)) + "pulsarOriginalMain" + source.substring(main.end(1));
        result = inject(result, "out vec3 pulsarLightPosition;\n" + (word(mask, "gbufferModelViewInverse") ? "" : "uniform mat4 gbufferModelViewInverse;\n"));
        return result + "\nvoid main() { pulsarOriginalMain(); pulsarLightPosition = (gbufferModelViewInverse * iris_ModelViewMatrix * " + position + ").xyz; }\n";
    }
    private static boolean modern(String source) { return Pattern.compile("(?m)^\\s*#version\\s+(?:3[3-9][0-9]|[4-9][0-9][0-9])\\b").matcher(source).find(); }
    private static boolean word(String source, String name) { return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source).find(); }
    private static int skipSpace(String value, int start) { while (start < value.length() && Character.isWhitespace(value.charAt(start))) ++start; return start; }
    private static int matching(String value, int start, char opening, char closing) {
        int depth = 0;
        for (int i = start; i < value.length(); i++) {
            if (value.charAt(i) == opening) ++depth;
            else if (value.charAt(i) == closing && --depth == 0) return i;
        }
        return -1;
    }
    private static int statementEnd(String value, int start, int limit) {
        int parens = 0, brackets = 0;
        for (int i = start; i < limit; i++) {
            char c = value.charAt(i);
            if (c == '(') ++parens; else if (c == ')') --parens;
            else if (c == '[') ++brackets; else if (c == ']') --brackets;
            else if (c == ';' && parens == 0 && brackets == 0) return i;
        }
        return -1;
    }
    private static int argumentEnd(String value, int start, int limit) {
        int depth = 0;
        for (int i = start; i < limit; i++) {
            char c = value.charAt(i);
            if (c == '(' || c == '[') ++depth;
            else if (c == ')' || c == ']') --depth;
            else if (c == ',' && depth == 0) return i;
        }
        return limit;
    }
    private static String apply(String source, List<Edit> edits) {
        edits.sort(Comparator.comparingInt(Edit::start).reversed()); StringBuilder result = new StringBuilder(source); int bound = source.length();
        for (Edit edit : edits) {
            if (edit.end > bound) throw new IllegalArgumentException("Overlapping shader edits");
            result.replace(edit.start, edit.end, edit.value); bound = edit.start;
        }
        return result.toString();
    }
    private static String inject(String source, String addition) {
        String mask = mask(source); int at = skipSpace(mask, 0);
        return source.substring(0, at) + addition + source.substring(at);
    }
    /** Keep offsets while excluding comments, quoted preprocessing text and directives. */
    static String mask(String source) {
        char[] value = source.toCharArray(); boolean line = false, block = false, directive = false, string = false, escaped = false, lineStart = true;
        for (int i = 0; i < value.length; i++) {
            char c = source.charAt(i), next = i + 1 < value.length ? source.charAt(i + 1) : 0;
            if (c == '\n' || c == '\r') {
                int previous = i - 1; if (previous >= 0 && source.charAt(previous) == '\r') --previous;
                directive = directive && previous >= 0 && source.charAt(previous) == '\\';
                line = false; lineStart = true; continue;
            }
            if (line || directive) { value[i] = ' '; continue; }
            if (block) { value[i] = ' '; if (c == '*' && next == '/') { value[++i] = ' '; block = false; } continue; }
            if (string) { value[i] = ' '; if (c == '"' && !escaped) string = false; escaped = c == '\\' && !escaped; continue; }
            if (lineStart && c == '#') { directive = true; value[i] = ' '; continue; }
            if (!Character.isWhitespace(c)) lineStart = false;
            if (c == '/' && next == '/') { line = true; value[i] = value[++i] = ' '; }
            else if (c == '/' && next == '*') { block = true; value[i] = value[++i] = ' '; }
            else if (c == '"') { string = true; value[i] = ' '; }
        }
        return new String(value);
    }
    private static String helpers() {
        try (var input = RgbShaderSourcePatcher.class.getResourceAsStream("/assets/pulsar/shaders/lightfield.glsl")) {
            if (input == null) throw new IOException("Missing shader field resource");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Cannot load RGB shader interface", failure); }
    }
}
