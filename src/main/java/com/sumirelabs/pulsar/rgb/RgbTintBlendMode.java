/* SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from GTNewHorizons/SuperNova TintBlendMode.java
 * Reference commit: 22d8559c3064d77d115e58523c58cde824f4e214
 * https://github.com/GTNewHorizons/SuperNova
 * Pulsar adaptations: lazy OkLab table, canonical brightness attenuation,
 * allocation-free ABGR application and prototype compatibility mode.
 */
package com.sumirelabs.pulsar.rgb;

/**
 * Controls how block light RGB and sky light RGB are combined to produce a vertex/entity color tint. The lightmap texture handles brightness; the tint only
 * controls color.
 *
 */
public enum RgbTintBlendMode {

    /** Original Pulsar prototype formula, retained for comparisons. */
    LEGACY {
        @Override protected void computeTintImpl(float br, float bg, float bb, float sr, float sg, float sb, float[] out) {
            float max = .6f + Math.max(br + sr, Math.max(bg + sg, bb + sb));
            out[0] = (.6f + br + sr) / max;
            out[1] = (.6f + bg + sg) / max;
            out[2] = (.6f + bb + sb) / max;
        }
    },

    ADDITIVE {
        @Override
        protected void computeTintImpl(float br, float bg, float bb, float sr, float sg, float sb, float[] out) {
            float tr = br + sr;
            float tg = bg + sg;
            float tb = bb + sb;
            float tmax = Math.max(tr, Math.max(tg, tb));
            if (tmax < 0.5f) {
                out[0] = out[1] = out[2] = 1f;
                return;
            }
            out[0] = tr / tmax;
            out[1] = tg / tmax;
            out[2] = tb / tmax;
        }
    },

    SQUARED_WEIGHT {
        @Override
        protected void computeTintImpl(float br, float bg, float bb, float sr, float sg, float sb, float[] out) {
            float blockMax = Math.max(br, Math.max(bg, bb));
            float skyMax = Math.max(sr, Math.max(sg, sb));

            if (blockMax < 0.5f && skyMax < 0.5f) {
                out[0] = out[1] = out[2] = 1f;
                return;
            }

            float bm2 = blockMax * blockMax;
            float sm2 = skyMax * skyMax;
            float total = bm2 + sm2 + 0.001f;
            float bw = bm2 / total;
            float sw = sm2 / total;

            float btR = blockMax > 0.5f ? br / blockMax : 1f;
            float btG = blockMax > 0.5f ? bg / blockMax : 1f;
            float btB = blockMax > 0.5f ? bb / blockMax : 1f;
            float stR = skyMax > 0.5f ? sr / skyMax : 1f;
            float stG = skyMax > 0.5f ? sg / skyMax : 1f;
            float stB = skyMax > 0.5f ? sb / skyMax : 1f;

            out[0] = btR * bw + stR * sw;
            out[1] = btG * bw + stG * sw;
            out[2] = btB * bw + stB * sw;
        }
    },

    LINEAR_WEIGHT {
        @Override
        protected void computeTintImpl(float br, float bg, float bb, float sr, float sg, float sb, float[] out) {
            float blockMax = Math.max(br, Math.max(bg, bb));
            float skyMax = Math.max(sr, Math.max(sg, sb));

            if (blockMax < 0.5f && skyMax < 0.5f) {
                out[0] = out[1] = out[2] = 1f;
                return;
            }

            float total = blockMax + skyMax + 0.001f;
            float bw = blockMax / total;
            float sw = skyMax / total;

            float btR = blockMax > 0.5f ? br / blockMax : 1f;
            float btG = blockMax > 0.5f ? bg / blockMax : 1f;
            float btB = blockMax > 0.5f ? bb / blockMax : 1f;
            float stR = skyMax > 0.5f ? sr / skyMax : 1f;
            float stG = skyMax > 0.5f ? sg / skyMax : 1f;
            float stB = skyMax > 0.5f ? sb / skyMax : 1f;

            out[0] = btR * bw + stR * sw;
            out[1] = btG * bw + stG * sw;
            out[2] = btB * bw + stB * sw;
        }
    },

    OKLAB {
        @Override
        protected void computeTintImpl(float br, float bg, float bb, float sr, float sg, float sb, float[] out) {
            float blockMax = Math.max(br, Math.max(bg, bb));
            float skyMax = Math.max(sr, Math.max(sg, sb));

            if (blockMax < 0.5f && skyMax < 0.5f) {
                out[0] = out[1] = out[2] = 1f;
                return;
            }

            float bm2 = blockMax * blockMax;
            float sm2 = skyMax * skyMax;
            float total = bm2 + sm2 + 0.001f;
            float bw = bm2 / total;
            float sw = sm2 / total;

            float bL = 0, ba = 0, bLab_b = 0;
            float sL = 0, sa = 0, sLab_b = 0;
            if (blockMax > 0.5f) {
                int lutIdx = ((int) br << 8 | (int) bg << 4 | (int) bb) * 3;
                bL = OkLabTable.VALUES[lutIdx];
                ba = OkLabTable.VALUES[lutIdx + 1];
                bLab_b = OkLabTable.VALUES[lutIdx + 2];
            }
            if (skyMax > 0.5f) {
                int lutIdx = ((int) sr << 8 | (int) sg << 4 | (int) sb) * 3;
                sL = OkLabTable.VALUES[lutIdx];
                sa = OkLabTable.VALUES[lutIdx + 1];
                sLab_b = OkLabTable.VALUES[lutIdx + 2];
            }

            float L = bL * bw + sL * sw;
            float a = ba * bw + sa * sw;
            float b = bLab_b * bw + sLab_b * sw;

            float l_ = L + 0.3963377774f * a + 0.2158037573f * b;
            float m_ = L - 0.1055613458f * a - 0.0638541728f * b;
            float s_ = L - 0.0894841775f * a - 1.2914855480f * b;

            float rr = 4.0767416621f * l_ * l_ * l_ - 3.3077115913f * m_ * m_ * m_ + 0.2309699292f * s_ * s_ * s_;
            float gg = -1.2684380046f * l_ * l_ * l_ + 2.6097574011f * m_ * m_ * m_ - 0.3413193965f * s_ * s_ * s_;
            float bb2 = -0.0041960863f * l_ * l_ * l_ - 0.7034186147f * m_ * m_ * m_ + 1.7076147010f * s_ * s_ * s_;

            rr = Math.max(0, rr);
            gg = Math.max(0, gg);
            bb2 = Math.max(0, bb2);
            float tmax = Math.max(rr, Math.max(gg, bb2));
            if (tmax < 0.001f) {
                out[0] = out[1] = out[2] = 1f;
                return;
            }
            out[0] = rr / tmax;
            out[1] = gg / tmax;
            out[2] = bb2 / tmax;
        }
    },

    VIVID {
        @Override
        protected void computeTintImpl(float br, float bg, float bb, float sr, float sg, float sb, float[] out) {
            SQUARED_WEIGHT.computeTintImpl(br, bg, bb, sr, sg, sb, out);
            if (out[0] >= 1.0f && out[1] >= 1.0f && out[2] >= 1.0f) return;

            float avg = (out[0] + out[1] + out[2]) * (1.0f / 3.0f);
            final float boost = 1.5f;
            out[0] = Math.max(0, avg + (out[0] - avg) * boost);
            out[1] = Math.max(0, avg + (out[1] - avg) * boost);
            out[2] = Math.max(0, avg + (out[2] - avg) * boost);

            float tmax = Math.max(out[0], Math.max(out[1], out[2]));
            if (tmax > 0.001f) {
                out[0] /= tmax;
                out[1] /= tmax;
                out[2] /= tmax;
            } else {
                out[0] = out[1] = out[2] = 1f;
            }
        }
    };

    // Only OKLAB users allocate this table; SCALAR and other modes do not.
    private static final class OkLabTable {
        static final float[] VALUES = new float[16 * 16 * 16 * 3];
        static {
            for (int r = 0; r < 16; r++) for (int g = 0; g < 16; g++) for (int b = 0; b < 16; b++)
                linearRgbToOkLab(r / 15f, g / 15f, b / 15f, VALUES, (r << 8 | g << 4 | b) * 3);
        }
    }
    private static final class Scratch {
        static final ThreadLocal<float[]> TINT = ThreadLocal.withInitial(() -> new float[3]);
    }
    /** Currently active blend mode. Changed at runtime via keybind or config. */

    public static volatile RgbTintBlendMode current = LEGACY;

    /**
     * 0 (neutral white) to 1 (full saturation): hue is normalized against the channel max, so at low levels it is 4-bit quantization noise.
     * Curve is vanilla's own {@code WorldProvider.generateLightBrightnessTable}.
     */
    static float hueConfidence(float maxLight) {
        final float l = Math.min(15f, Math.max(0f, maxLight));
        return l / (60f - 3f * l);
    }

    static void linearRgbToOkLab(float r, float g, float b, float[] out, int offset) {
        float l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
        float m = 0.2119034982f * r + 0.7159317017f * g + 0.0721750003f * b;
        float s = 0.1615319361f * r + 0.0882119793f * g + 0.7501670696f * b;
        float l_ = (float) Math.cbrt(l), m_ = (float) Math.cbrt(m), s_ = (float) Math.cbrt(s);
        out[offset] = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_;
        out[offset + 1] = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_;
        out[offset + 2] = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_;
    }

    public final void computeTint(float br, float bg, float bb, float sr, float sg, float sb, float[] out) {
        computeTintImpl(br, bg, bb, sr, sg, sb, out);
        final float maxLight = Math.max(Math.max(br, Math.max(bg, bb)), Math.max(sr, Math.max(sg, sb)));
        final float w = hueConfidence(maxLight);
        if (w >= 1f) return;
        out[0] = 1f + (out[0] - 1f) * w;
        out[1] = 1f + (out[1] - 1f) * w;
        out[2] = 1f + (out[2] - 1f) * w;
    }

    public int tintAbgr(int color, int r, int g, int b, int canonical,
                        int sr, int sg, int sb, int skyCanonical, float skyScale) {
        float red = r + sr * skyScale, green = g + sg * skyScale, blue = b + sb * skyScale;
        float scalar = canonical + skyCanonical * skyScale;
        if (this == LEGACY) {
            float max = .6f + Math.max(scalar, Math.max(red, Math.max(green, blue)));
            return color & 0xFF000000 | Math.round((color & 255) * (.6f + red) / max)
                    | Math.round((color >>> 8 & 255) * (.6f + green) / max) << 8
                    | Math.round((color >>> 16 & 255) * (.6f + blue) / max) << 16;
        }
        float[] tint = Scratch.TINT.get();
        computeTint(r, g, b, sr * skyScale, sg * skyScale, sb * skyScale, tint);
        // Compare each lane's RGB peak to its own canonical brightness. Taking
        // the peak AFTER adding different hues incorrectly darkens RGB_MAX too
        // (red block light + blue sky becomes half brightness a second time).
        // VANILLA still needs attenuation when canonical light survives a filter.
        float rgbBrightness = Math.max(r, Math.max(g, b))
                + Math.max(sr, Math.max(sg, sb)) * skyScale;
        float brightness = Math.min(1f, (.6f + rgbBrightness) / (.6f + scalar));
        return color & 0xFF000000 | component(color & 255, tint[0] * brightness)
                | component(color >>> 8 & 255, tint[1] * brightness) << 8
                | component(color >>> 16 & 255, tint[2] * brightness) << 16;
    }
    private static int component(int material, float tint) {
        return Math.max(0, Math.min(255, Math.round(material * tint)));
    }

    protected abstract void computeTintImpl(float br, float bg, float bb, float sr, float sg, float sb, float[] out);
}
