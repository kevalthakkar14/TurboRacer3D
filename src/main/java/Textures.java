import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;

import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Procedurally generated, seamlessly tiling textures (no image files needed):
 * worn asphalt, paving slabs, grass and brick pavers, plus a soft light-pool sprite.
 */
public final class Textures {

    private static final int N = 256;

    private Textures() {
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int clamp(float v) {
        int i = (int) v;
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static int rgb(float r, float g, float b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /** Smooth value noise in 0..1 that wraps around, so the final texture tiles. */
    private static float[] noise(int cells, long seed) {
        Random r = new Random(seed);
        float[] grid = new float[cells * cells];
        for (int k = 0; k < grid.length; k++) {
            grid[k] = r.nextFloat();
        }
        float[] out = new float[N * N];
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float fx = (float) x * cells / N;
                float fy = (float) y * cells / N;
                int x0 = (int) fx;
                int y0 = (int) fy;
                float tx = fx - x0;
                float ty = fy - y0;
                tx = tx * tx * (3f - 2f * tx);
                ty = ty * ty * (3f - 2f * ty);
                int x1 = (x0 + 1) % cells;
                int y1 = (y0 + 1) % cells;
                float a = grid[y0 * cells + x0];
                float b = grid[y0 * cells + x1];
                float c = grid[y1 * cells + x0];
                float d = grid[y1 * cells + x1];
                out[y * N + x] = lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
            }
        }
        return out;
    }

    private static Texture2D finish(int[] px, int size, boolean alpha, boolean repeat) {
        BufferedImage img = new BufferedImage(size, size,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, size, size, px, 0, size);
        Image image = new AWTLoader().load(img, false);
        Texture2D t = new Texture2D(image);
        t.setWrap(repeat ? Texture.WrapMode.Repeat : Texture.WrapMode.EdgeClamp);
        t.setMinFilter(Texture.MinFilter.Trilinear);
        t.setMagFilter(Texture.MagFilter.Bilinear);
        t.setAnisotropicFilter(8);
        return t;
    }

    /** Worn asphalt: grain, aggregate specks, blotches and hairline cracks. base ~ 40 (road) .. 62 (lot). */
    public static Texture2D asphalt(int base, long seed) {
        float[] n1 = noise(4, seed);
        float[] n2 = noise(16, seed + 1);
        float[] n3 = noise(64, seed + 2);
        Random r = new Random(seed + 7);
        float[] lum = new float[N * N];
        for (int k = 0; k < N * N; k++) {
            float v = base + (n1[k] - 0.5f) * 16f + (n2[k] - 0.5f) * 10f + (n3[k] - 0.5f) * 8f
                    + (r.nextFloat() - 0.5f) * 10f;
            float s = r.nextFloat();
            if (s > 0.985f) {
                v += 36f;
            } else if (s < 0.012f) {
                v -= 16f;
            }
            lum[k] = v;
        }
        for (int c = 0; c < 5; c++) {
            float x = r.nextInt(N);
            float y = r.nextInt(N);
            float a = r.nextFloat() * 6.28f;
            int len = 40 + r.nextInt(80);
            for (int s = 0; s < len; s++) {
                a += (r.nextFloat() - 0.5f) * 0.6f;
                x += (float) Math.cos(a);
                y += (float) Math.sin(a);
                int ix = ((int) x % N + N) % N;
                int iy = ((int) y % N + N) % N;
                lum[iy * N + ix] -= 20f;
                lum[((iy + 1) % N) * N + ix] -= 7f;
            }
        }
        int[] px = new int[N * N];
        for (int k = 0; k < px.length; k++) {
            px[k] = rgb(lum[k], lum[k], lum[k] + 3f);
        }
        return finish(px, N, false, true);
    }

    /** Pavement: 2 m slabs (when tiled every 8 m) with joints, per-slab tone, stains and grain. */
    public static Texture2D concrete(int base, long seed) {
        float[] n1 = noise(8, seed);
        float[] n2 = noise(32, seed + 1);
        Random r = new Random(seed + 5);
        float[] tone = new float[16];
        for (int k = 0; k < tone.length; k++) {
            tone[k] = (r.nextFloat() - 0.5f) * 14f;
        }
        int[] px = new int[N * N];
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int k = y * N + x;
                float v = base + tone[(y / 64) * 4 + (x / 64)] + (n1[k] - 0.5f) * 14f
                        + (n2[k] - 0.5f) * 8f + (r.nextFloat() - 0.5f) * 8f;
                int mx = x % 64;
                int my = y % 64;
                if (mx < 2 || my < 2) {
                    v -= 38f;
                } else if (mx == 2 || my == 2) {
                    v += 6f;
                }
                px[k] = rgb(v + 3f, v + 1f, v - 5f);
            }
        }
        return finish(px, N, false, true);
    }

    public static Texture2D grass(long seed) {
        float[] n1 = noise(4, seed);
        float[] n2 = noise(16, seed + 1);
        float[] n3 = noise(64, seed + 2);
        Random r = new Random(seed + 3);
        int[] px = new int[N * N];
        for (int k = 0; k < px.length; k++) {
            float g = 1f + (n1[k] - 0.5f) * 0.35f + (n2[k] - 0.5f) * 0.25f + (n3[k] - 0.5f) * 0.2f
                    + (r.nextFloat() - 0.5f) * 0.18f;
            float rr = 56f * g;
            float gg = 92f * g;
            float bb = 38f * g;
            if (r.nextFloat() > 0.97f) {
                rr += 22f;
                gg += 28f;
            }
            px[k] = rgb(rr, gg, bb);
        }
        return finish(px, N, false, true);
    }

    /** Brick pavers in a running bond (for plazas and park paths). */
    public static Texture2D pavers(long seed) {
        Random r = new Random(seed);
        float[] tone = new float[16 * 8];
        for (int k = 0; k < tone.length; k++) {
            tone[k] = r.nextFloat();
        }
        int[] px = new int[N * N];
        for (int y = 0; y < N; y++) {
            int row = y / 16;
            int off = (row & 1) == 0 ? 0 : 16;
            for (int x = 0; x < N; x++) {
                int xx = (x + off) % N;
                int bx = xx % 32;
                int col = xx / 32;
                int by = y % 16;
                if (bx < 1 || by < 1) {
                    float m = 90f + (r.nextFloat() - 0.5f) * 10f;
                    px[y * N + x] = rgb(m + 3f, m, m - 4f);
                } else {
                    float t = 0.88f + 0.24f * tone[(row % 16) * 8 + col];
                    float g = (r.nextFloat() - 0.5f) * 10f;
                    px[y * N + x] = rgb(148f * t + g, 106f * t + g, 90f * t + g);
                }
            }
        }
        return finish(px, N, false, true);
    }

    /** Soft round white sprite with falling alpha - used as the light pool under street lamps. */
    public static Texture2D glowPool() {
        int size = 128;
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x + 0.5f - size / 2f) / (size / 2f);
                float dy = (y + 0.5f - size / 2f) / (size / 2f);
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float a = Math.max(0f, 1f - d);
                a = a * a;
                px[y * size + x] = (clamp(a * 255f) << 24) | 0x00FFFFFF;
            }
        }
        return finish(px, size, true, false);
    }
}