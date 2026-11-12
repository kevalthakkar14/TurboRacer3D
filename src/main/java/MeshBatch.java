import com.jme3.material.Material;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Collects lots of small static shapes (boxes, flat quads, cylinders, ellipsoids) and turns them into
 * ONE Geometry per material. A whole city block (roads, kerbs, lamps, trees, benches...) therefore costs
 * only a handful of draw calls instead of hundreds.
 */
public class MeshBatch {

    private static final class Part {
        float[] pos = new float[384];
        float[] nor = new float[384];
        float[] uv = new float[256];
        int[] idx = new int[384];
        int vc;
        int ic;

        int vertex(float x, float y, float z, float nx, float ny, float nz, float u, float v) {
            if (vc * 3 + 3 > pos.length) {
                pos = Arrays.copyOf(pos, pos.length * 2);
                nor = Arrays.copyOf(nor, nor.length * 2);
            }
            if (vc * 2 + 2 > uv.length) {
                uv = Arrays.copyOf(uv, uv.length * 2);
            }
            pos[vc * 3] = x;
            pos[vc * 3 + 1] = y;
            pos[vc * 3 + 2] = z;
            nor[vc * 3] = nx;
            nor[vc * 3 + 1] = ny;
            nor[vc * 3 + 2] = nz;
            uv[vc * 2] = u;
            uv[vc * 2 + 1] = v;
            return vc++;
        }

        void tri(int a, int b, int c) {
            if (ic + 3 > idx.length) {
                idx = Arrays.copyOf(idx, idx.length * 2);
            }
            idx[ic++] = a;
            idx[ic++] = b;
            idx[ic++] = c;
        }
    }

    private final Map<Material, Part> parts = new IdentityHashMap<>();

    private Part part(Material m) {
        Part p = parts.get(m);
        if (p == null) {
            p = new Part();
            parts.put(m, p);
        }
        return p;
    }

    /** Four corners given counter-clockwise as seen from outside. */
    private void quad(Part p, float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx, float dy, float dz,
                      float nx, float ny, float nz) {
        int a = p.vertex(ax, ay, az, nx, ny, nz, 0f, 0f);
        int b = p.vertex(bx, by, bz, nx, ny, nz, 1f, 0f);
        int c = p.vertex(cx, cy, cz, nx, ny, nz, 1f, 1f);
        int d = p.vertex(dx, dy, dz, nx, ny, nz, 0f, 1f);
        p.tri(a, b, c);
        p.tri(a, c, d);
    }

    /** Axis aligned box (no bottom face). */
    public void box(Material m, float cx, float cy, float cz, float hx, float hy, float hz) {
        Part p = part(m);
        float x0 = cx - hx;
        float x1 = cx + hx;
        float y0 = cy - hy;
        float y1 = cy + hy;
        float z0 = cz - hz;
        float z1 = cz + hz;
        quad(p, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0);      // top
        quad(p, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0);      // +x
        quad(p, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0);     // -x
        quad(p, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1);      // +z
        quad(p, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1);     // -z
    }

    /**
     * Horizontal quad facing up. If tile > 0 the texture repeats every `tile` metres and is aligned to
     * world coordinates (uOff / vOff = world position of the local origin), so neighbouring pieces and
     * neighbouring cells line up without seams. If tile <= 0 the texture is stretched 0..1.
     */
    public void flat(Material m, float x0, float x1, float z0, float z1, float y,
                     float tile, float uOff, float vOff) {
        Part p = part(m);
        float u0 = 0f;
        float u1 = 1f;
        float v0 = 0f;
        float v1 = 1f;
        if (tile > 0f) {
            u0 = (x0 + uOff) / tile;
            u1 = (x1 + uOff) / tile;
            v0 = (z0 + vOff) / tile;
            v1 = (z1 + vOff) / tile;
        }
        int a = p.vertex(x0, y, z0, 0, 1, 0, u0, v0);
        int b = p.vertex(x0, y, z1, 0, 1, 0, u0, v1);
        int c = p.vertex(x1, y, z1, 0, 1, 0, u1, v1);
        int d = p.vertex(x1, y, z0, 0, 1, 0, u1, v0);
        p.tri(a, b, c);
        p.tri(a, c, d);
    }

    /** Upright cylinder with a top cap. The cap uses world aligned UVs when tile > 0. */
    public void cylinder(Material m, float cx, float y0, float cz, float r, float h, int seg,
                         float tile, float uOff, float vOff) {
        Part p = part(m);
        float y1 = y0 + h;
        int centre = p.vertex(cx, y1, cz, 0, 1, 0,
                tile > 0f ? (cx + uOff) / tile : 0.5f, tile > 0f ? (cz + vOff) / tile : 0.5f);
        int[] ring = new int[seg + 1];
        for (int i = 0; i <= seg; i++) {
            float a = 6.2831853f * i / seg;
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            float x = cx + r * c;
            float z = cz + r * s;
            ring[i] = p.vertex(x, y1, z, 0, 1, 0,
                    tile > 0f ? (x + uOff) / tile : 0.5f + 0.5f * c,
                    tile > 0f ? (z + vOff) / tile : 0.5f + 0.5f * s);
        }
        for (int i = 0; i < seg; i++) {
            p.tri(centre, ring[i + 1], ring[i]);
        }
        for (int i = 0; i < seg; i++) {
            float a0 = 6.2831853f * i / seg;
            float a1 = 6.2831853f * (i + 1) / seg;
            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);
            int va = p.vertex(cx + r * c1, y0, cz + r * s1, c1, 0, s1, 0f, 0f);
            int vb = p.vertex(cx + r * c0, y0, cz + r * s0, c0, 0, s0, 1f, 0f);
            int vc = p.vertex(cx + r * c0, y1, cz + r * s0, c0, 0, s0, 1f, 1f);
            int vd = p.vertex(cx + r * c1, y1, cz + r * s1, c1, 0, s1, 0f, 1f);
            p.tri(va, vb, vc);
            p.tri(va, vc, vd);
        }
    }

    /** Ellipsoid (tree crowns, bushes, domes). */
    public void ellipsoid(Material m, float cx, float cy, float cz, float rx, float ry, float rz,
                          int rings, int seg) {
        Part p = part(m);
        for (int k = 0; k < rings; k++) {
            float phi0 = -1.5707963f + 3.1415927f * k / rings;
            float phi1 = -1.5707963f + 3.1415927f * (k + 1) / rings;
            for (int i = 0; i < seg; i++) {
                float th0 = 6.2831853f * i / seg;
                float th1 = 6.2831853f * (i + 1) / seg;
                int a = sphereVertex(p, cx, cy, cz, rx, ry, rz, phi0, th0);
                int b = sphereVertex(p, cx, cy, cz, rx, ry, rz, phi0, th1);
                int c = sphereVertex(p, cx, cy, cz, rx, ry, rz, phi1, th1);
                int d = sphereVertex(p, cx, cy, cz, rx, ry, rz, phi1, th0);
                p.tri(a, c, b);
                p.tri(a, d, c);
            }
        }
    }

    private int sphereVertex(Part p, float cx, float cy, float cz, float rx, float ry, float rz,
                             float phi, float th) {
        float dx = (float) (Math.cos(phi) * Math.cos(th));
        float dy = (float) Math.sin(phi);
        float dz = (float) (Math.cos(phi) * Math.sin(th));
        float nx = dx / rx;
        float ny = dy / ry;
        float nz = dz / rz;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return p.vertex(cx + rx * dx, cy + ry * dy, cz + rz * dz, nx / len, ny / len, nz / len,
                th / 6.2831853f, (phi + 1.5707963f) / 3.1415927f);
    }

    /** Creates one Geometry per material and attaches them to the parent. */
    public void build(Node parent, ShadowMode shadow, boolean transparent) {
        for (Map.Entry<Material, Part> e : parts.entrySet()) {
            Part p = e.getValue();
            if (p.ic == 0) {
                continue;
            }
            Mesh mesh = new Mesh();
            mesh.setBuffer(VertexBuffer.Type.Position, 3,
                    BufferUtils.createFloatBuffer(Arrays.copyOf(p.pos, p.vc * 3)));
            mesh.setBuffer(VertexBuffer.Type.Normal, 3,
                    BufferUtils.createFloatBuffer(Arrays.copyOf(p.nor, p.vc * 3)));
            mesh.setBuffer(VertexBuffer.Type.TexCoord, 2,
                    BufferUtils.createFloatBuffer(Arrays.copyOf(p.uv, p.vc * 2)));
            mesh.setBuffer(VertexBuffer.Type.Index, 3,
                    BufferUtils.createIntBuffer(Arrays.copyOf(p.idx, p.ic)));
            mesh.updateBound();
            mesh.updateCounts();
            Geometry g = new Geometry("Batch", mesh);
            g.setMaterial(e.getKey());
            g.setShadowMode(shadow);
            if (transparent) {
                g.setQueueBucket(RenderQueue.Bucket.Transparent);
            }
            parent.attachChild(g);
        }
    }
}