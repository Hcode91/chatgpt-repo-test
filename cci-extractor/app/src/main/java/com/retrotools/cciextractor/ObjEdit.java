package com.retrotools.cciextractor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads back an OBJ exported with {@link UnityModels#toObj(UnityModels.Mesh, int[])} after it was edited in a 3D
 * program (moved / sculpted points, changed UVs, deleted faces). New points or faces can't go into the game,
 * because each point also carries skeleton weights the OBJ doesn't have, so those are refused with a clear message.
 *
 * Points are matched by order (Blender keeps OBJ point order when nothing is added or removed); faces are matched by
 * their three corner points, which ties every corner back to the original vertex for its UV.
 */
final class ObjEdit {

    /** An edit in the viewer's convention, for the original (unedited) mesh. */
    static final class Edit {
        float[] pos, normal, uv;
        boolean[][] hidden;      // per part, per triangle of the original mesh
        int moved, hiddenCount;
    }

    /** Squared distance below which a point counts as not moved (0.001 units; OBJ text keeps 6 decimals). */
    static final float UNMOVED2 = 1e-6f;

    private ObjEdit() { }

    /**
     * {@code base}: the original mesh; {@code weld}: {@link UnityModels#weld} of it. Throws with a message for the
     * user when the OBJ doesn't fit.
     */
    static Edit read(UnityModels.Mesh base, int[] weld, String obj) throws IOException {
        int welded = 0;
        for (int w : weld) welded = Math.max(welded, w + 1);
        List<float[]> vs = new ArrayList<>(), vts = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();   // per triangle: v0, vt0, v1, vt1, v2, vt2 (0-based, vt -1 = none)
        try (BufferedReader r = new BufferedReader(new StringReader(obj))) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.startsWith("v ")) vs.add(floats(line, 3, lineNo));
                else if (line.startsWith("vt ")) vts.add(floats(line, 2, lineNo));
                else if (line.startsWith("f ")) {
                    String[] t = line.substring(2).trim().split("\\s+");
                    if (t.length < 3) continue;
                    int[][] c = new int[t.length][];
                    for (int i = 0; i < t.length; i++) c[i] = corner(t[i], vs.size(), vts.size(), lineNo);
                    for (int i = 1; i + 1 < t.length; i++)        // fan-triangulate polygons
                        faces.add(new int[]{c[0][0], c[0][1], c[i][0], c[i][1], c[i + 1][0], c[i + 1][1], -1, -1, -1});
                }
            }
        }
        // OBJs from app versions before 0.25 have one point per vertex (seam copies included): map them onto the welded
        // points. Where a 3D program moved the copies of one point apart (a torn seam), join them at their average.
        boolean legacy = vs.size() == base.vertexCount && vs.size() != welded;
        if (legacy) {
            float[] sum = new float[welded * 3];
            int[] cnt = new int[welded];
            for (int i = 0; i < base.vertexCount; i++) {
                float[] v = vs.get(i);
                for (int k = 0; k < 3; k++) sum[weld[i] * 3 + k] += v[k];
                cnt[weld[i]]++;
            }
            int[] firstOf = new int[welded];
            java.util.Arrays.fill(firstOf, -1);
            for (int i = 0; i < base.vertexCount; i++) if (firstOf[weld[i]] < 0) firstOf[weld[i]] = i;
            List<float[]> joined = new ArrayList<>(welded);
            for (int w = 0; w < welded; w++) {
                // if the copies weren't moved apart, take the first exactly (no averaging noise)
                float[] first = vs.get(firstOf[w]); boolean apart = false;
                float[] avg = {sum[w * 3] / cnt[w], sum[w * 3 + 1] / cnt[w], sum[w * 3 + 2] / cnt[w]};
                for (int k = 0; k < 3; k++) if (Math.abs(avg[k] - first[k]) > 1e-4f) apart = true;
                joined.add(apart ? avg : first);
            }
            vs = joined;
            // remember which original vertex each corner is (exact UV target), then switch to welded points
            for (int[] f : faces) for (int c = 0; c < 3; c++) { f[6 + c] = f[c * 2]; f[c * 2] = weld[f[c * 2]]; }
        }
        if (vs.size() != welded)
            throw new IOException("The OBJ has " + vs.size() + " points but this model has " + welded + " (or " + base.vertexCount +
                    " in OBJs from app versions before 0.25). Only moving points, " +
                    "changing UVs and deleting faces can go back into the game: adding or removing points (subdivide, remesh, " +
                    "merge by distance, dyntopo sculpting) can't. Export the model again from the app and start from that file.");

        // original triangles by their (unordered) welded corners. Double-sided faces use the same three points twice;
        // 3D programs keep only one face for them, so one face in the OBJ stands for every copy.
        Map<Long, List<int[]>> byCorners = new HashMap<>();
        for (int p = 0; p < base.parts.length; p++) {
            int[] idx = base.parts[p];
            for (int t = 0; t + 2 < idx.length; t += 3)
                byCorners.computeIfAbsent(key(weld[idx[t]], weld[idx[t + 1]], weld[idx[t + 2]]), k -> new ArrayList<>()).add(new int[]{p, t / 3});
        }
        Edit e = new Edit();
        int n = base.vertexCount;
        e.pos = new float[n * 3];
        for (int i = 0; i < n; i++) {
            float[] v = vs.get(weld[i]);
            e.pos[i * 3] = -v[0]; e.pos[i * 3 + 1] = v[1]; e.pos[i * 3 + 2] = v[2];
            for (int k = 0; k < 3; k++) if (!Float.isFinite(e.pos[i * 3 + k])) throw new IOException("The OBJ has a point that isn't a number");
            // closer than the OBJ's text rounding: it wasn't moved, so keep the exact original value
            float dx = e.pos[i * 3] - base.pos[i * 3], dy = e.pos[i * 3 + 1] - base.pos[i * 3 + 1], dz = e.pos[i * 3 + 2] - base.pos[i * 3 + 2];
            if (dx * dx + dy * dy + dz * dz <= UNMOVED2) System.arraycopy(base.pos, i * 3, e.pos, i * 3, 3);
        }
        e.uv = base.uv == null ? null : base.uv.clone();
        e.hidden = new boolean[base.parts.length][];
        boolean[][] seen = new boolean[base.parts.length][];
        for (int p = 0; p < base.parts.length; p++) { e.hidden[p] = new boolean[base.parts[p].length / 3]; seen[p] = new boolean[e.hidden[p].length]; }
        int unknown = 0;
        for (int[] f : faces) {
            List<int[]> group = byCorners.get(key(f[0], f[2], f[4]));
            if (group == null) { unknown++; continue; }
            for (int[] hit : group) seen[hit[0]][hit[1]] = true;
            if (e.uv == null) continue;
            int[] first = group.get(0);
            for (int c = 0; c < 3; c++) {
                int vt = f[c * 2 + 1];
                if (vt < 0) continue;
                float[] t = vts.get(vt);
                if (f[6 + c] >= 0) {                     // old-style OBJ: the corner is exactly this vertex
                    int ov = f[6 + c];
                    if (Math.abs(base.uv[ov * 2] - t[0]) > 2e-6f || Math.abs(base.uv[ov * 2 + 1] - t[1]) > 2e-6f) { e.uv[ov * 2] = t[0]; e.uv[ov * 2 + 1] = t[1]; }
                    continue;
                }
                int of = vertexAt(base, weld, first, f[c * 2]);
                // the first copy takes the OBJ's UV; other copies only if the UV was actually changed
                boolean changed = of >= 0 && (Math.abs(base.uv[of * 2] - t[0]) > 1e-5f || Math.abs(base.uv[of * 2 + 1] - t[1]) > 1e-5f);
                for (int[] hit : group) {
                    if (hit != first && !changed) continue;
                    int ov = vertexAt(base, weld, hit, f[c * 2]);
                    if (ov >= 0 && (Math.abs(base.uv[ov * 2] - t[0]) > 2e-6f || Math.abs(base.uv[ov * 2 + 1] - t[1]) > 2e-6f)) { e.uv[ov * 2] = t[0]; e.uv[ov * 2 + 1] = t[1]; }
                }
            }
        }
        if (unknown > 0)
            throw new IOException(unknown + " face(s) in the OBJ aren't in the original model. New faces can't go into the game " +
                    "(they'd have no skeleton weights): only move points, change UVs or delete faces.");
        for (int p = 0; p < seen.length; p++) for (int t = 0; t < seen[p].length; t++) if (!seen[p][t]) { e.hidden[p][t] = true; e.hiddenCount++; }

        // normals: keep the originals where nothing around a point moved; recompute smooth ones where something did
        boolean[] movedW = new boolean[welded];
        for (int i = 0; i < n; i++) {
            float dx = e.pos[i * 3] - base.pos[i * 3], dy = e.pos[i * 3 + 1] - base.pos[i * 3 + 1], dz = e.pos[i * 3 + 2] - base.pos[i * 3 + 2];
            if (dx * dx + dy * dy + dz * dz > UNMOVED2) movedW[weld[i]] = true;
        }
        boolean[] dirtyW = movedW.clone();
        float[] acc = new float[welded * 3];
        for (int p = 0; p < base.parts.length; p++) {
            int[] idx = base.parts[p];
            for (int t = 0; t + 2 < idx.length; t += 3) {
                int a = idx[t], b = idx[t + 1], c = idx[t + 2];
                if (movedW[weld[a]] || movedW[weld[b]] || movedW[weld[c]]) { dirtyW[weld[a]] = dirtyW[weld[b]] = dirtyW[weld[c]] = true; }
                if (e.hidden[p][t / 3]) continue;
                float ux = e.pos[b * 3] - e.pos[a * 3], uy = e.pos[b * 3 + 1] - e.pos[a * 3 + 1], uz = e.pos[b * 3 + 2] - e.pos[a * 3 + 2];
                float vx = e.pos[c * 3] - e.pos[a * 3], vy = e.pos[c * 3 + 1] - e.pos[a * 3 + 1], vz = e.pos[c * 3 + 2] - e.pos[a * 3 + 2];
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;   // area-weighted
                for (int v : new int[]{a, b, c}) { acc[weld[v] * 3] += nx; acc[weld[v] * 3 + 1] += ny; acc[weld[v] * 3 + 2] += nz; }
            }
        }
        e.normal = base.normal != null ? base.normal.clone() : new float[n * 3];
        for (int i = 0; i < n; i++) {
            if (movedW[weld[i]]) e.moved++;
            if (!dirtyW[weld[i]] && base.normal != null) continue;
            int w = weld[i];
            float x = acc[w * 3], y = acc[w * 3 + 1], z = acc[w * 3 + 2], len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len < 1e-20f) continue;
            // the viewer space is mirrored relative to the triangle winding: keep the original side if we flipped
            float sx = x / len, sy = y / len, sz = z / len;
            if (base.normal != null) {
                float dot = sx * base.normal[i * 3] + sy * base.normal[i * 3 + 1] + sz * base.normal[i * 3 + 2];
                if (dot < 0) { sx = -sx; sy = -sy; sz = -sz; }
            }
            e.normal[i * 3] = sx; e.normal[i * 3 + 1] = sy; e.normal[i * 3 + 2] = sz;
        }
        return e;
    }

    /** The edited mesh for showing in the viewer: new positions, normals and UVs, hidden triangles left out. */
    static UnityModels.Mesh apply(UnityModels.Mesh base, Edit e) {
        UnityModels.Mesh m = new UnityModels.Mesh();
        m.name = base.name; m.vertexCount = base.vertexCount;
        m.pos = e.pos.clone(); m.normal = e.normal.clone(); m.uv = e.uv == null ? null : e.uv.clone();
        m.parts = new int[base.parts.length][];
        for (int p = 0; p < base.parts.length; p++) {
            int[] idx = base.parts[p];
            int keep = 0;
            for (int t = 0; t < idx.length / 3; t++) if (!e.hidden[p][t]) keep++;
            int[] out = new int[keep * 3];
            for (int t = 0, k = 0; t < idx.length / 3; t++) if (!e.hidden[p][t]) { System.arraycopy(idx, t * 3, out, k, 3); k += 3; }
            m.parts[p] = out;
        }
        m.min = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        m.max = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < m.vertexCount; i++) for (int k = 0; k < 3; k++) {
            m.min[k] = Math.min(m.min[k], m.pos[i * 3 + k]); m.max[k] = Math.max(m.max[k], m.pos[i * 3 + k]);
        }
        if (m.vertexCount == 0) { m.min = new float[3]; m.max = new float[3]; }
        return m;
    }

    /** The original vertex of triangle {part, tri} that sits at welded point {@code w}, or -1. */
    private static int vertexAt(UnityModels.Mesh base, int[] weld, int[] tri, int w) {
        int[] idx = base.parts[tri[0]];
        for (int k = 0; k < 3; k++) { int ov = idx[tri[1] * 3 + k]; if (weld[ov] == w) return ov; }
        return -1;
    }

    private static long key(int a, int b, int c) {
        int lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c)), mid = a + b + c - lo - hi;
        return ((long) lo << 42) ^ ((long) mid << 21) ^ hi;
    }

    private static float[] floats(String line, int n, int lineNo) throws IOException {
        String[] t = line.split("\\s+");
        if (t.length < n + 1) throw new IOException("OBJ line " + lineNo + " is incomplete");
        float[] out = new float[n];
        try { for (int i = 0; i < n; i++) out[i] = Float.parseFloat(t[i + 1]); }
        catch (NumberFormatException ex) { throw new IOException("OBJ line " + lineNo + " has a value that isn't a number"); }
        return out;
    }

    /** "v", "v/vt", "v//vn" or "v/vt/vn", 1-based or negative (relative). */
    private static int[] corner(String s, int nv, int nvt, int lineNo) throws IOException {
        String[] p = s.split("/", -1);
        try {
            int v = Integer.parseInt(p[0]);
            v = v < 0 ? nv + v : v - 1;
            int vt = -1;
            if (p.length > 1 && !p[1].isEmpty()) { vt = Integer.parseInt(p[1]); vt = vt < 0 ? nvt + vt : vt - 1; }
            if (v < 0 || v >= nv || vt >= nvt) throw new IOException("OBJ line " + lineNo + " refers to a point that doesn't exist");
            return new int[]{v, vt};
        } catch (NumberFormatException ex) { throw new IOException("OBJ line " + lineNo + " has a bad face"); }
    }
}
