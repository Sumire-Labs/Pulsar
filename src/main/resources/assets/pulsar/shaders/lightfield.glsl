// SPDX-License-Identifier: LGPL-3.0-only
// Original Pulsar field interface. No shaderpack or loader implementation is included.
uniform usampler2D pulsarLightData;
uniform isampler2D pulsarLightIndex;
uniform int pulsarLightEnabled;
uniform int pulsarLightSide;
uniform int pulsarLightMinSection;
uniform int pulsarLightSections;
uniform int pulsarCameraBlockX;
uniform int pulsarCameraBlockY;
uniform int pulsarCameraBlockZ;
uniform float pulsarCameraFractX;
uniform float pulsarCameraFractY;
uniform float pulsarCameraFractZ;
uniform float pulsarSkyScale;
uniform int pulsarHasSky;

bool pulsarReadCell(ivec3 cell, out vec4 blockLight, out vec4 skyLight) {
    ivec3 chunk = cell >> 4;
    int section = chunk.y - pulsarLightMinSection;
    if (section < 0 || section >= pulsarLightSections) return false;
    int mask = pulsarLightSide - 1;
    int index = (chunk.x & mask) + (chunk.z & mask) * pulsarLightSide
        + section * pulsarLightSide * pulsarLightSide;
    ivec4 entry = texelFetch(pulsarLightIndex, ivec2(index & 255, index >> 8), 0);
    if (entry.x == -1 || entry.z != chunk.x || entry.w != chunk.z) return false;
    int local = (cell.x & 15) | ((cell.z & 15) << 4) | ((cell.y & 15) << 8);
    uint value = entry.x == -2 ? uint(entry.y) : texelFetch(pulsarLightData, ivec2(local, entry.x), 0).r;
    blockLight = vec4(float((value >> 8u) & 15u), float((value >> 4u) & 15u),
        float(value & 15u), float((value >> 24u) & 15u));
    skyLight = vec4(float((value >> 20u) & 15u), float((value >> 16u) & 15u),
        float((value >> 12u) & 15u), float(value >> 28u));
    return true;
}

bool pulsarSample(vec3 relativePosition, out vec4 blockLight, out vec4 skyLight) {
    blockLight = vec4(0.0); skyLight = vec4(0.0);
    if (pulsarLightEnabled == 0) return false;
    vec3 fraction = vec3(pulsarCameraFractX, pulsarCameraFractY, pulsarCameraFractZ);
    ivec3 base = ivec3(floor(relativePosition + fraction - vec3(0.5)))
        + ivec3(pulsarCameraBlockX, pulsarCameraBlockY, pulsarCameraBlockZ);
    bool found = false;
    for (int z = 0; z < 2; ++z) for (int x = 0; x < 2; ++x) for (int y = 0; y < 2; ++y) {
        vec4 b, s;
        if (pulsarReadCell(base + ivec3(x, y, z), b, s)) {
            blockLight = max(blockLight, b); skyLight = max(skyLight, s); found = true;
        }
    }
    return found;
}

float pulsarPeak(vec3 value) { return max(value.r, max(value.g, value.b)); }

// Named lighting adapters share one field stencil per lighting invocation.
vec3 pulsarBlockTerm(vec3 original, vec4 blockLight, bool found) {
    if (!found) return original;
    float peak = pulsarPeak(blockLight.rgb);
    if (peak <= 0.0) return vec3(0.0);
    return max(0.0, pulsarPeak(original)) * blockLight.rgb / peak
        * min(1.0, peak / max(1.0, blockLight.a));
}
vec3 pulsarBlockTerm(vec3 original, vec3 vanillaFallback, vec4 blockLight, bool found) {
    if (!found) return original;
    float peak = pulsarPeak(blockLight.rgb);
    if (peak <= 0.0) return vec3(0.0);
    float energy = max(pulsarPeak(original), pulsarPeak(vanillaFallback));
    return max(0.0, energy) * blockLight.rgb / peak * min(1.0, peak / max(1.0, blockLight.a));
}
vec3 pulsarSkyTerm(vec3 original, vec4 skyLight, bool found) {
    if (!found || pulsarHasSky == 0) return original;
    float peak = pulsarPeak(skyLight.rgb);
    if (peak <= 0.0) return vec3(0.0);
    return original * skyLight.rgb / peak * min(1.0, peak / max(1.0, skyLight.a));
}

vec4 pulsarLightmap(vec4 original, vec3 relativePosition, vec2 coordinates) {
    if (all(greaterThanEqual(coordinates, vec2(0.96)))) return original;
    vec4 blockLight, skyLight;
    if (!pulsarSample(relativePosition, blockLight, skyLight)) return original;
    float skyDarkening = 15.0 - 15.0 * pulsarSkyScale;
    vec3 effectiveSky = max(vec3(0.0), skyLight.rgb - vec3(skyDarkening));
    vec3 combined = vec3(0.6) + blockLight.rgb + effectiveSky;
    float peak = pulsarPeak(combined);
    if (peak <= 0.0) return vec4(vec3(0.0), original.a);
    float canonical = 0.6 + blockLight.a + max(0.0, skyLight.a - skyDarkening);
    vec3 tint = combined / peak;
    float attenuation = min(1.0, (0.6 + pulsarPeak(blockLight.rgb) + pulsarPeak(effectiveSky)) / max(0.6, canonical));
    if (all(greaterThanEqual(tint, vec3(0.999)))) return vec4(original.rgb * attenuation, original.a);
    return vec4(max(0.0, pulsarPeak(original.rgb)) * tint * attenuation, original.a);
}
