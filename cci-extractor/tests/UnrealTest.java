package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unreal Engine 4 OBB / pak support on a synthetic OBB (fixtures/unreal, built by gen_unreal_fixture.py and re-read by
 * repak): scan, decode against texture2ddecoder, patch textures in a stored and a multi-block zlib package, rebuild the
 * OBB and a loose pak, then re-read everything, check hashes and that untouched files are byte-identical.
 */
public class UnrealTest {
    static int checks = 0;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checks++; }

    static String pixelSha(UnrealAssets.Texture t, int[] px) {
        boolean single = UnityTextures.singleChannel(t.format);
        byte[] buf = new byte[px.length * (single ? 1 : 4)]; int p = 0;
        for (int c : px) {
            if (single) buf[p++] = (byte) c;
            else { buf[p++] = (byte) (c >> 16); buf[p++] = (byte) (c >> 8); buf[p++] = (byte) c; buf[p++] = (byte) (c >>> 24); }
        }
        return UnityObb.sha1(buf);
    }

    static float[] vertex(UnityModels.Mesh m, int i) { return new float[]{m.pos[i * 3], m.pos[i * 3 + 1], m.pos[i * 3 + 2], m.uv[i * 2], m.uv[i * 2 + 1], m.normal[i * 3], m.normal[i * 3 + 1], m.normal[i * 3 + 2]}; }
    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-3f; }

    /** Meshes, materials, the axis conversion, projection painting and rebuilding a painted texture. */
    static void models(File obb) throws Exception {
        try (FileInputStream in = new FileInputStream(obb); FileChannel ch = in.getChannel()) {
            UnrealObb.Info info = UnrealObb.scan(ch, obb.getName(), null);
            check(info.meshes.size() == 2 && info.meshes.get(0).skeletal() && info.meshes.get(0).name.equals("SK_Hero") &&
                    info.meshes.get(1).name.equals("SM_Box") && "StaticMesh".equals(info.meshes.get(1).cls), "two meshes, skinned first");
            check(info.description().contains("2 3D models"), "models counted in the description");
            UnrealPak pak = info.paks.get(0).pak;
            UnrealModels.PackageSource src = f -> { UnrealPak.Entry e = pak.byName.get(f); return e == null ? null : pak.read(e); };

            UnrealModels.MeshRef sk = info.meshes.get(0);
            byte[] b = pak.read(pak.byName.get(sk.path));
            UnrealAssets.Package pk = UnrealAssets.parse(b);
            UnrealModels.Loaded l = UnrealModels.mesh(b, pk, pk.exports.get(sk.export));
            UnityModels.Mesh m = l.mesh;
            check(m.vertexCount == 8 && m.parts.length == 2 && m.parts[0].length == 6 && m.parts[1].length == 6, "skinned mesh: 8 vertices, 2 parts of 2 triangles");
            // Unreal (x, y, z) = (-50, 10, 0), uv (0, 1) → viewer (50, 0, 10), uv (0, 0); normal +Y → +Z
            float[] v0 = vertex(m, 0), v2 = vertex(m, 2);
            check(near(v0[0], 50) && near(v0[1], 0) && near(v0[2], 10) && near(v0[3], 0) && near(v0[4], 0), "axis and v flip, vertex 0");
            check(near(v2[0], -50) && near(v2[1], 100) && near(v2[2], 10) && near(v2[3], 1) && near(v2[4], 1), "axis and v flip, vertex 2");
            check(Math.abs(v0[5]) < 0.01f && Math.abs(v0[6]) < 0.01f && v0[7] > 0.99f, "normal converted");
            check(near(m.min[1], 0) && near(m.max[1], 100) && near(m.min[2], -10) && near(m.max[2], 10), "bounds");
            check("/Game/Chars/Hero/MI_Hero/MI_Hero".equals(l.partMaterial[0]) && "/Game/Chars/Hero/M_Cloth/M_Cloth".equals(l.partMaterial[1]), "material per part");
            check("Game".equals(UnrealModels.project(sk.path)), "project folder");
            check("Game/Content/Chars/Hero/MI_Hero.uasset".equals(UnrealModels.packagePath(l.partMaterial[0], "Game")) &&
                    "Engine/Content/A/B.uasset".equals(UnrealModels.packagePath("/Engine/A/B/B", "Game")) && UnrealModels.packagePath("/Script/X", "Game") == null, "object path → package file");

            UnrealModels.Material mi = UnrealModels.material(src, l.partMaterial[0], "Game");
            check("/Game/Chars/Hero/T_Hero_D/T_Hero_D".equals(mi.texture), "instance: Diffusemap parameter chosen over the normal map");
            check(mi.cutout, "instance inherits BLEND_Masked from its parent");
            UnrealModels.Material cloth = UnrealModels.material(src, l.partMaterial[1], "Game");
            check("/Game/Chars/Hero/T_Cloth_D/T_Cloth_D".equals(cloth.texture) && !cloth.cutout, "base material: *_D import, opaque");
            check(UnrealModels.pickDiffuse(java.util.Collections.singletonList(new String[]{null, "/G/TX_Max01_EyeLashes"})) != null, "'_Max' isn't read as a mask suffix");
            check(UnrealModels.pickDiffuse(java.util.Arrays.asList(new String[]{null, "/G/T_Body_N"}, new String[]{null, "/G/T_Body_S"})) == null, "data maps aren't diffuse");

            UnrealModels.MeshRef sm = info.meshes.get(1);
            byte[] sb = pak.read(pak.byName.get(sm.path));
            UnrealAssets.Package spk = UnrealAssets.parse(sb);
            UnrealModels.Loaded sl = UnrealModels.mesh(sb, spk, spk.exports.get(sm.export));
            check(sl.mesh.vertexCount == 3 && sl.mesh.parts.length == 1 && sl.mesh.parts[0].length == 3, "static mesh: one triangle");
            float[] s1 = vertex(sl.mesh, 1);
            check(near(s1[0], -30) && near(s1[1], 0) && near(s1[2], 0) && near(s1[3], 1) && near(s1[4], 0), "static vertex converted");
            check("/Game/Chars/Hero/M_Cloth/M_Cloth".equals(sl.partMaterial[0]), "static material read from the native slot list");

            // paint from the Front view: only the front quad's texture changes
            Object[] heroTex = UnrealModels.findTexture(info, mi.texture, "Game"), clothTex = UnrealModels.findTexture(info, cloth.texture, "Game");
            check(heroTex != null && clothTex != null, "textures found from material paths");
            UnrealObb.PackageInfo hp = (UnrealObb.PackageInfo) heroTex[0]; UnrealAssets.Texture ht = (UnrealAssets.Texture) heroTex[1];
            UnrealObb.PackageInfo cp = (UnrealObb.PackageInfo) clothTex[0]; UnrealAssets.Texture ct = (UnrealAssets.Texture) clothTex[1];
            int[] hero = UnrealObb.decode(info, hp, ht), clothPx = UnrealObb.decode(info, cp, ct);
            int[] heroBefore = hero.clone(), clothBefore = clothPx.clone();
            UnityPaint.Geometry g = new UnityPaint.Geometry(m);
            UnityPaint.View3D view = UnityPaint.camera(UnityPaint.YAW_FRONT, 0, 1, g.center, g.radius, 400, 400);
            UnityPaint.Depth depth = UnityPaint.depth(g, view, 400);
            UnityPaint.Decal dc = new UnityPaint.Decal();
            dc.w = dc.h = 4; dc.argb = new int[16]; java.util.Arrays.fill(dc.argb, 0xFFFF0000);
            float[] centre = UnityPaint.project(view, g.center[0], g.center[1], g.center[2]);
            dc.cx = centre[0]; dc.cy = centre[1]; dc.size = 400;   // covers the whole view
            UnityPaint.Result r0 = UnityPaint.stamp(g, view, depth, dc, hero, ht.width, ht.height, java.util.Arrays.asList(0), java.util.Arrays.asList(0));
            UnityPaint.Result r1 = UnityPaint.stamp(g, view, depth, dc, clothPx, ct.width, ct.height, java.util.Arrays.asList(1), java.util.Arrays.asList(1));
            check(r0.painted > ht.width * ht.height * 9 / 10, "front quad painted (" + r0.painted + " texels)");
            check(r1.painted == 0 && java.util.Arrays.equals(clothPx, clothBefore), "back quad untouched");
            check(hero[ht.width * ht.height / 2 + ht.width / 2] == 0xFFFF0000 && !java.util.Arrays.equals(hero, heroBefore), "painted texels are the decal colour");
            // the left of the model as seen from the front is texture u = 0 (Unreal x = -50)
            float[] pl = UnityPaint.project(view, -50f, 50f, 10f);   // drawn geometry x is Unreal x
            check(pl != null && pl[0] < 200, "Unreal -x shows on the left from the front");

            // write it into the package, rebuild, read back
            byte[] pkgBytes = pak.read(UnrealObb.entry(info, hp));
            UnrealObb.patch(pkgBytes, ht, hero, ht.width, ht.height);
            Map<Integer, Map<String, byte[]>> changed = new HashMap<>();
            changed.computeIfAbsent(0, k -> new LinkedHashMap<>()).put(hp.path, pkgBytes);
            File out = File.createTempFile("unreal_painted", ".obb"); out.deleteOnExit();
            try (FileOutputStream fo = new FileOutputStream(out)) { UnrealObb.rebuild(ch, info, changed, fo, null); }
            try (FileInputStream ri = new FileInputStream(out); FileChannel rc = ri.getChannel()) {
                UnrealObb.Info again = UnrealObb.scan(rc, "painted", null);
                check(again.meshes.size() == 2 && again.textures == info.textures, "painted OBB rescans");
                Object[] t2 = UnrealModels.findTexture(again, mi.texture, "Game");
                int[] back = UnrealObb.decode(again, (UnrealObb.PackageInfo) t2[0], (UnrealAssets.Texture) t2[1]);
                int red = 0;
                for (int c : back) if (((c >> 16) & 255) > 200 && ((c >> 8) & 255) < 60 && (c & 255) < 60) red++;
                check(red > back.length * 9 / 10, "painted texture reads back red after ETC2 encoding (" + red + "/" + back.length + ")");
            }

            // corrupt mesh data fails cleanly
            byte[] bad = b.clone();
            UnrealAssets.Export x = pk.exports.get(sk.export);
            int matCount = x.serialOffset + 8 + 4 + 2 + 28;   // after "None", guid flag, strip flags, bounds
            check(UnrealAssets.i32At(b, matCount) == 2, "material count where expected");
            for (int i = matCount; i < matCount + 4; i++) bad[i] = (byte) 0x7f;
            try { UnrealModels.mesh(bad, pk, x); check(false, "corrupt mesh accepted"); } catch (java.io.IOException expectedFailure) { checks++; }
        }
    }

    /** Painting limited to one connected piece: three quads in one part, two of them joined across a UV seam. */
    static void areas() {
        UnityModels.Mesh m = new UnityModels.Mesh();
        m.name = "areas"; m.vertexCount = 12;
        // quads (x0, x1, u0, u1): A and C touch at x = -10 (separate vertices, as at a UV seam); B stands apart
        float[][] q = {{-30, -10, 0.0f, 0.3f}, {10, 30, 0.6f, 0.9f}, {-10, 0, 0.3f, 0.45f}};
        m.pos = new float[36]; m.normal = new float[36]; m.uv = new float[24];
        int[] part = new int[18];
        for (int k = 0; k < 3; k++) {
            float[][] corners = {{q[k][0], 0, q[k][2], 0}, {q[k][1], 0, q[k][3], 0}, {q[k][1], 20, q[k][3], 1}, {q[k][0], 20, q[k][2], 1}};
            for (int c = 0; c < 4; c++) {
                int v = k * 4 + c;
                m.pos[v * 3] = corners[c][0]; m.pos[v * 3 + 1] = corners[c][1]; m.pos[v * 3 + 2] = 0;
                m.normal[v * 3 + 2] = 1;
                m.uv[v * 2] = corners[c][2]; m.uv[v * 2 + 1] = corners[c][3];
            }
            int b = k * 4; int[] t = {b, b + 1, b + 2, b, b + 2, b + 3};
            System.arraycopy(t, 0, part, k * 6, 6);
        }
        m.parts = new int[][]{part};
        m.min = new float[]{-30, 0, 0}; m.max = new float[]{30, 20, 0};
        UnityPaint.Geometry g = new UnityPaint.Geometry(m);
        int[][] pieces = UnityPaint.pieces(g);
        check(pieces[0].length == 6 && pieces[0][0] == pieces[0][1] && pieces[0][0] == pieces[0][4] && pieces[0][0] != pieces[0][2],
                "pieces: A and C joined across the seam, B separate");
        UnityPaint.View3D view = UnityPaint.camera(UnityPaint.YAW_FRONT, 0, 1, g.center, g.radius, 400, 400);
        // drawn geometry mirrors x, so mesh quad A (x -30..-10) is drawn at +10..+30
        float[] onA = UnityPaint.project(view, 20, 10, 0), onB = UnityPaint.project(view, -20, 10, 0);
        int[] hitA = UnityPaint.pick(g, view, onA[0], onA[1]), hitB = UnityPaint.pick(g, view, onB[0], onB[1]);
        check(hitA != null && hitA[1] / 2 == 0 && hitB != null && hitB[1] / 2 == 1, "tap picks the quad under it");
        check(UnityPaint.pick(g, view, 2, 2) == null, "tap beside the model picks nothing");
        boolean[][] area = UnityPaint.areaOf(pieces, hitA[0], hitA[1]);
        int tw = 64, th = 16;
        int[] px = new int[tw * th]; java.util.Arrays.fill(px, 0xFF000000);
        UnityPaint.Decal dc = new UnityPaint.Decal();
        dc.w = dc.h = 1; dc.argb = new int[]{0xFFFF0000};
        float[] c = UnityPaint.project(view, 0, 10, 0);
        dc.cx = c[0]; dc.cy = c[1]; dc.size = 2000;
        UnityPaint.Depth depth = UnityPaint.depth(g, view, 400);
        UnityPaint.Result r = UnityPaint.stamp(g, view, depth, dc, px, tw, th, java.util.Collections.singletonList(0), java.util.Collections.singletonList(0), area);
        int redA = 0, redB = 0, redC = 0;
        for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) {
            if (px[y * tw + x] != 0xFFFF0000) continue;
            float u = (x + 0.5f) / tw;
            if (u > 0.02f && u < 0.28f) redA++; else if (u > 0.32f && u < 0.43f) redC++; else if (u > 0.62f && u < 0.88f) redB++;
        }
        check(r.painted > 0 && redA > 100 && redC > 50 && redB == 0, "area paint: A and its seam neighbour C painted, B untouched (" + redA + "/" + redC + "/" + redB + ")");
        java.util.Arrays.fill(px, 0xFF000000);
        UnityPaint.stamp(g, view, depth, dc, px, tw, th, java.util.Collections.singletonList(0), java.util.Collections.singletonList(0), null);
        int any = 0;
        for (int y = 0; y < th; y++) for (int x = (int) (0.62f * tw) + 1; x < (int) (0.88f * tw); x++) if (px[y * tw + x] == 0xFFFF0000) any++;
        check(any > 50, "without an area, B is painted too");
    }

    /** Export → edit the OBJ text like a 3D program would → import → write into the package → read back. */
    static void objRoundTrip(File obb) throws Exception {
        try (FileInputStream in = new FileInputStream(obb); FileChannel ch = in.getChannel()) {
            UnrealObb.Info info = UnrealObb.scan(ch, obb.getName(), null);
            UnrealModels.MeshRef sk = info.meshes.get(0);
            UnrealPak pak = info.paks.get(0).pak;
            byte[] b = pak.read(pak.byName.get(sk.path));
            UnrealAssets.Package pk = UnrealAssets.parse(b);
            UnrealAssets.Export x = pk.exports.get(sk.export);
            UnityModels.Mesh base = UnrealModels.mesh(b, pk, x).mesh;
            int[] weld = UnityModels.weld(base);
            String obj = UnityModels.toObj(base, weld);
            check(obj.split("\n(?=v )").length - 1 == 8 && obj.contains("f 1/1 3/3 2/2"), "editable OBJ: one point per position, UV per vertex");

            // move point 3 (Unreal (50, 10, 100)) up by 20, change vertex 1's UV, delete the back quad's 2nd triangle
            List<String> lines = new ArrayList<>(java.util.Arrays.asList(obj.split("\n")));
            int vSeen = 0, vtSeen = 0, fSeen = 0;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.startsWith("v ") && ++vSeen == 3) { String[] t = l.split(" "); lines.set(i, "v " + t[1] + " " + (Float.parseFloat(t[2]) + 20) + " " + t[3]); }
                if (l.startsWith("vt ") && ++vtSeen == 2) lines.set(i, "vt 0.750000 0.250000");
                if (l.startsWith("f ") && ++fSeen == 4) { lines.remove(i); i--; }
            }
            ObjEdit.Edit e = ObjEdit.read(base, weld, String.join("\n", lines));
            check(e.moved == 1 && e.hiddenCount == 1 && e.hidden[1][1] && !e.hidden[1][0] && !e.hidden[0][0], "edit: 1 point moved, 1 face removed");
            check(near(e.pos[2 * 3 + 1], base.pos[2 * 3 + 1] + 20) && near(e.uv[2], 0.75f) && near(e.uv[3], 0.25f), "moved point and changed UV read back");
            check(e.pos[0] == base.pos[0] && e.pos[4] == base.pos[4], "untouched points keep their exact values");

            byte[] edited = b.clone();
            UnrealModels.writeEdit(edited, pk, x, e);
            check(edited.length == b.length, "package keeps its size");
            UnityModels.Mesh back = UnrealModels.mesh(edited, UnrealAssets.parse(edited), x).mesh;
            boolean same = true;
            for (int i = 0; i < back.vertexCount * 3; i++) same &= Math.abs(back.pos[i] - e.pos[i]) < 1e-4f;
            for (int i = 0; i < back.vertexCount * 2; i++) same &= Math.abs(back.uv[i] - e.uv[i]) < 1e-3f;
            check(same, "edited positions and UVs read back from the package");
            int[] p1 = back.parts[1];
            check(p1[3] == p1[4] && p1[4] == p1[5] && p1[0] != p1[1], "removed face is zero-area, the other stays");
            check(back.normal[2 * 3 + 2] > 0.5f, "moved point's normal recomputed facing the same way");
            UnityModels.Mesh shown = ObjEdit.apply(base, e);
            check(shown.parts[1].length == 3 && shown.parts[0].length == 6 && near(shown.max[1], 120), "viewer mesh: face left out, bounds grow");
            // bounds widened in the package (origin.z + extent.z covers the new top at z = 120)
            int bAt = x.serialOffset + 8 + 4 + 2;
            float oz = Float.intBitsToFloat(UnrealAssets.i32At(edited, bAt + 8)), ez = Float.intBitsToFloat(UnrealAssets.i32At(edited, bAt + 20));
            check(oz + ez >= 120 - 1e-3f, "bounds widened for the taller model");
            check(UnrealModels.toHalf(0.75f) == 0x3A00 && UnrealModels.toHalf(1f) == 0x3C00 && UnrealModels.toHalf(0f) == 0 &&
                    UnrealModels.toHalf(-2f) == 0xC000 && Math.abs(UnityModels.half(UnrealModels.toHalf(0.123f)) - 0.123f) < 1e-3f, "float → half");

            // what can't go into the game is refused with a reason
            try { ObjEdit.read(base, weld, obj + "\nv 1 2 3\n"); check(false, "extra point accepted"); }
            catch (java.io.IOException ex) { check(ex.getMessage().contains("points but this model has"), "extra point refused"); }
            try { ObjEdit.read(base, weld, obj + "\nf 1 2 5\n"); check(false, "new face accepted"); }
            catch (java.io.IOException ex) { check(ex.getMessage().contains("aren't in the original model"), "new face refused"); }
            // quads (a program that joined triangles) are split back; Blender-style "v/vt/vn" corners work
            ObjEdit.Edit same2 = ObjEdit.read(base, weld, obj.replace("/", "/").replaceAll("f (\\d+)/(\\d+) (\\d+)/(\\d+) (\\d+)/(\\d+)", "f $1/$2/1 $3/$4/1 $5/$6/1") + "\nvn 0 0 1\n");
            check(same2.moved == 0 && same2.hiddenCount == 0, "unchanged OBJ with normals: nothing moved or removed");
        }
        // double-sided faces (same three points twice) count as one face in a 3D program
        UnityModels.Mesh m = new UnityModels.Mesh();
        m.name = "twosided"; m.vertexCount = 6;
        m.pos = new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0};
        m.normal = new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, -1, 0, 0, -1, 0, 0, -1};
        m.uv = new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        m.parts = new int[][]{{0, 1, 2, 3, 5, 4}};
        m.min = new float[]{0, 0, 0}; m.max = new float[]{1, 1, 0};
        int[] w = UnityModels.weld(m);
        check(w[3] == w[0] && w[4] == w[1], "weld joins the two sides");
        String one = "v 0 0 0\nv -1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 3/3 2/2\n";   // Blender kept one face
        ObjEdit.Edit e2 = ObjEdit.read(m, w, one);
        check(e2.hiddenCount == 0, "one face in the OBJ keeps both sides of a double-sided face");

        // OBJs from before v0.25: one point per vertex. Both sides survive as faces; each keeps its own UVs.
        String old = UnityModels.toObj(m);
        check(old.split("\n(?=v )").length - 1 == 6, "old-style export has a point per vertex");
        ObjEdit.Edit e3 = ObjEdit.read(m, w, old.replace("vt 1.000000 0.000000\nvt 0.000000 1.000000\nvt 0.000000 0.000000\nvt 1.000000 0.000000",
                "vt 1.000000 0.000000\nvt 0.000000 1.000000\nvt 0.500000 0.500000\nvt 1.000000 0.000000"));
        check(e3.moved == 0 && e3.hiddenCount == 0 && near(e3.uv[6], 0.5f) && near(e3.uv[0], 0f), "old-style OBJ: back side's UV change lands on the back side only");
        // a torn seam: one copy of point 0 moved, the other not → both end up at the average
        String torn = old.replaceFirst("v -0\\.000000 0\\.000000 0\\.000000|v 0\\.000000 0\\.000000 0\\.000000", "v 0.000000 0.000000 2.000000");
        ObjEdit.Edit e4 = ObjEdit.read(m, w, torn);
        check(near(e4.pos[2], 1f) && near(e4.pos[3 * 3 + 2], 1f), "torn seam joined at the average (" + e4.pos[2] + ", " + e4.pos[11] + ")");
        try { ObjEdit.read(m, w, old + "v 9 9 9\n"); check(false, "7 points accepted"); }
        catch (java.io.IOException ex) { check(ex.getMessage().contains("before 0.25"), "wrong count names both formats"); }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "tests/fixtures/unreal");
        File obb = new File(dir, "unreal_fixture.obb");
        List<String[]> expected = new ArrayList<>();
        for (String line : new String(Files.readAllBytes(new File(dir, "expected.tsv").toPath()), "UTF-8").split("\n"))
            if (!line.trim().isEmpty()) expected.add(line.split("\t"));

        File outObb = File.createTempFile("unreal_rebuilt", ".obb"); outObb.deleteOnExit();
        Map<String, int[]> edits = new HashMap<>();
        try (FileInputStream in = new FileInputStream(obb); FileChannel ch = in.getChannel()) {
            check(UnrealObb.isUnreal(ch), "fixture detected as Unreal");
            check(!UnityObb.isUnity(ch), "not mistaken for Unity");
            UnrealObb.Info info = UnrealObb.scan(ch, obb.getName(), null);
            check(!info.single && info.paks.size() == 1 && info.paks.get(0).entry.endsWith(".pak"), "one pak inside the OBB");
            check(info.paks.get(0).pak.version == 3 && info.paks.get(0).pak.entries.size() == 12, "pak v3 with 12 files");
            check(info.textures == expected.size() && info.packages.size() == 6 && info.failed == 0, info.description());
            for (String[] e : expected) {
                UnrealObb.Row r = new UnrealObb.Row();
                r.pak = info.paks.get(0).entry; r.pkg = e[0]; r.export = Integer.parseInt(e[1]);
                Object[] f = UnrealObb.find(info, r);
                check(f != null, "found " + e[2]);
                UnrealObb.PackageInfo p = (UnrealObb.PackageInfo) f[0];
                UnrealAssets.Texture t = (UnrealAssets.Texture) f[1];
                check(t.name.equals(e[2]) && t.pixelFormat.equals(e[3]) && t.width == Integer.parseInt(e[4]) &&
                        t.height == Integer.parseInt(e[5]) && t.mips.size() == Integer.parseInt(e[6]), "metadata of " + t.name);
                int[] px = UnrealObb.decode(info, p, t);
                check(pixelSha(t, px).equals(e[7]), "pixels of " + t.name + " match texture2ddecoder");
                String row = UnrealObb.row(info, p, t, UnrealObb.pngPath(p, t), "x");
                check(UnrealObb.parseIndex(UnrealObb.INDEX_HEADER + row).get(0).export == t.export, "index row " + t.name);
            }
            // png naming: a single texture named like its package → package folder + name; several → sub-folder
            UnrealObb.PackageInfo grey = null, level = null;
            for (UnrealObb.PackageInfo p : info.packages) { if (p.path.endsWith("T_Grey.uasset")) grey = p; if (p.path.endsWith("Level.umap")) level = p; }
            check(UnrealObb.pngPath(grey, grey.textures.get(0)).equals("Game/Content/Tex/T_Grey.png"), "png path single");
            check(UnrealObb.pngPath(level, level.textures.get(1)).equals("Game/Content/Maps/Level/T_LevelB__1.png"), "png path multi");

            // edit: stored G8 package, multi-block ETC2 package, and one of two textures in the map
            Map<Integer, Map<String, byte[]>> changed = new LinkedHashMap<>();
            for (UnrealObb.PackageInfo p : info.packages) for (UnrealAssets.Texture t : p.textures) {
                if (!(t.name.equals("T_Grey") || t.name.equals("T_Etc") || t.name.equals("T_LevelA"))) continue;
                int[] px = UnrealObb.decode(info, p, t);
                for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xFF000000) | (~px[i] & 0xFFFFFF);
                byte[] pkg = changed.computeIfAbsent(p.pak, k -> new LinkedHashMap<>()).computeIfAbsent(p.path,
                        k -> { try { return info.paks.get(0).pak.read(UnrealObb.entry(info, p)); } catch (Exception ex) { throw new RuntimeException(ex); } });
                UnrealObb.patch(pkg, t, px, t.width, t.height);
                UnrealAssets.Mip m = t.mips.get(0);
                edits.put(t.name, UnityTextures.decode(java.util.Arrays.copyOfRange(pkg, m.pos, m.pos + m.size), t.format, t.width, t.height, false));
            }
            try (FileOutputStream fo = new FileOutputStream(outObb)) { UnrealObb.rebuild(ch, info, changed, fo, null); }

            // the rebuilt OBB: same zip entries, pak re-reads (index SHA-1 checked on open), edits in place
            try (FileInputStream in2 = new FileInputStream(outObb); FileChannel c2 = in2.getChannel()) {
                UnrealObb.Info again = UnrealObb.scan(c2, "again", null);
                check(again.textures == expected.size() && again.failed == 0, "rebuilt OBB scans: " + again.description());
                UnrealPak np = again.paks.get(0).pak, op = info.paks.get(0).pak;
                check(outObb.length() == obb.length(), "rebuilt OBB is exactly the original size (Android checks it)");
                check(np.indexOffset == op.indexOffset && np.indexSize == op.indexSize && again.paks.get(0).size == info.paks.get(0).size, "pak size and index position kept");
                for (UnrealPak.Entry e : op.entries) {
                    UnrealPak.Entry ne = np.byName.get(e.name);
                    byte[] stored = new byte[(int) ne.size];
                    again.paks.get(0).pak.src.read(ne.dataStart, stored, 0, stored.length);
                    check(java.util.Arrays.equals(UnrealPak.sha1(stored, 0, stored.length), ne.hash), "entry hash of " + e.name);
                    boolean edited = changed.get(0).containsKey(e.name);
                    if (!edited) {
                        check(java.util.Arrays.equals(np.read(ne), op.read(e)), "unchanged " + e.name);
                    } else check(java.util.Arrays.equals(np.read(ne), changed.get(0).get(e.name)), "new content of " + e.name);
                }
                for (UnrealObb.PackageInfo p : again.packages) for (UnrealAssets.Texture t : p.textures) {
                    int[] px = UnrealObb.decode(again, p, t);
                    if (edits.containsKey(t.name)) check(java.util.Arrays.equals(px, edits.get(t.name)), "edit of " + t.name + " read back");
                    else for (String[] e : expected) if (e[2].equals(t.name)) check(pixelSha(t, px).equals(e[7]), "untouched " + t.name);
                }
                // smaller mips were re-encoded too and still decode at their sizes
                for (UnrealObb.PackageInfo p : again.packages) for (UnrealAssets.Texture t : p.textures) for (UnrealAssets.Mip m : t.mips) {
                    byte[] d = np.read(UnrealObb.entry(again, p), m.pos, m.size);
                    check(UnityTextures.decode(d, t.format, m.width, m.height, false).length == m.width * m.height, "mip " + m.width + "x" + m.height + " of " + t.name);
                }
            }
            // zip level: same entries in the same order, only the pak changed
            try (java.util.zip.ZipFile a = new java.util.zip.ZipFile(obb); java.util.zip.ZipFile b = new java.util.zip.ZipFile(outObb)) {
                List<? extends java.util.zip.ZipEntry> ea = java.util.Collections.list(a.entries()), eb = java.util.Collections.list(b.entries());
                check(ea.size() == eb.size(), "zip entry count");
                for (int i = 0; i < ea.size(); i++) {
                    check(ea.get(i).getName().equals(eb.get(i).getName()) && eb.get(i).getMethod() == java.util.zip.ZipEntry.STORED, "zip entry " + i);
                    if (!ea.get(i).getName().endsWith(".pak")) check(ea.get(i).getCrc() == eb.get(i).getCrc(), "unchanged " + ea.get(i).getName());
                }
            }

            // byte level: outside the pak only its CRC-32 differs (local header + central directory), and the CRC is right
            {
                byte[] a = Files.readAllBytes(obb.toPath()), b2 = Files.readAllBytes(outObb.toPath());
                UnityObb.Entry z = null;
                for (UnityObb.Entry ze : info.zipEntries) if (ze.name.endsWith(".pak")) z = ze;
                int diffOutside = 0;
                for (int i = 0; i < a.length; i++) {
                    boolean inPak = i >= z.dataOffset && i < z.dataOffset + z.csize;
                    boolean crcField = (i >= z.localOffset + 14 && i < z.localOffset + 18) || (i >= z.directoryRecord + 16 && i < z.directoryRecord + 20);
                    if (!inPak && !crcField && a[i] != b2[i]) diffOutside++;
                }
                check(diffOutside == 0, "zip headers byte-identical (" + diffOutside + " bytes differ)");
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(b2, (int) z.dataOffset, (int) z.csize);
                long cd = (b2[(int) z.directoryRecord + 16] & 255L) | (b2[(int) z.directoryRecord + 17] & 255L) << 8 | (b2[(int) z.directoryRecord + 18] & 255L) << 16 | (b2[(int) z.directoryRecord + 19] & 255L) << 24;
                long lh = (b2[(int) z.localOffset + 14] & 255L) | (b2[(int) z.localOffset + 15] & 255L) << 8 | (b2[(int) z.localOffset + 16] & 255L) << 16 | (b2[(int) z.localOffset + 17] & 255L) << 24;
                check(crc.getValue() == cd && cd == lh, "pak CRC-32 updated in both zip headers");
                try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new FileInputStream(outObb))) {   // verifies every CRC
                    byte[] sink = new byte[65536];
                    while (zi.getNextEntry() != null) while (zi.read(sink) > 0) { }
                }
                checks++;
            }

            // a change that compresses worse than the original: the files after it are recompressed and moved up
            {
                UnrealPak op = info.paks.get(0).pak;
                Map<String, byte[]> worse = new LinkedHashMap<>();
                byte[] noise = op.read(op.byName.get("Game/Content/Tex/T_Bgra.uasset"));
                byte[] rnd = new byte[96]; new java.util.Random(11).nextBytes(rnd);
                System.arraycopy(rnd, 0, noise, noise.length / 2, rnd.length);
                worse.put("Game/Content/Tex/T_Bgra.uasset", noise);
                List<UnrealPak.Patch> patches = op.buildInPlace(worse);
                UnityObb.Content c = op.patched(patches);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (java.io.InputStream is = c.open()) { byte[] buf = new byte[4096]; int n; while ((n = is.read(buf)) > 0) bo.write(buf, 0, n); }
                byte[] np = bo.toByteArray();
                check(np.length == info.paks.get(0).size, "same pak size after a worse-compressing change");
                UnrealPak rp = UnrealPak.open(UnityBundle.of(np));
                check(java.util.Arrays.equals(rp.read(rp.byName.get("Game/Content/Tex/T_Bgra.uasset")), noise), "incompressible change stored");
                int moved = 0;
                for (UnrealPak.Entry e : op.entries) {
                    UnrealPak.Entry ne = rp.byName.get(e.name);
                    check(java.util.Arrays.equals(rp.read(ne), op.read(e)) || e.name.endsWith("T_Bgra.uasset"), "content kept: " + e.name);
                    byte[] st = new byte[(int) ne.size]; UnityBundle.of(np).read(ne.dataStart, st, 0, st.length);
                    check(java.util.Arrays.equals(UnrealPak.sha1(st, 0, st.length), ne.hash), "hash of moved/kept " + e.name);
                    if (ne.offset != e.offset) moved++;
                }
                check(moved >= 1, "following files moved up (" + moved + ")");
                // the last file can't borrow room from anything: clear error, nothing written
                Map<String, byte[]> tooBig = new LinkedHashMap<>();
                byte[] junk2 = new byte[(int) op.byName.get("Game/Config/Game.ini").usize];
                new java.util.Random(12).nextBytes(junk2);
                tooBig.put("Game/Config/Game.ini", junk2);
                try { op.buildInPlace(tooBig); check(false, "impossible change accepted"); }
                catch (java.io.IOException expectedFailure) { check(expectedFailure.getMessage().contains("doesn't compress as small"), "clear message when it can't fit"); }
            }

            // a loose pak works too
            File loose = File.createTempFile("loose", ".pak"); loose.deleteOnExit();
            UnrealObb.PakInfo pi = info.paks.get(0);
            byte[] pakBytes = new byte[(int) pi.size];
            UnityBundle.of(ch, pi.offset, pi.size).read(0, pakBytes, 0, pakBytes.length);
            Files.write(loose.toPath(), pakBytes);
            try (FileInputStream li = new FileInputStream(loose); FileChannel lc = li.getChannel()) {
                check(UnrealObb.isUnreal(lc), "loose pak detected");
                UnrealObb.Info single = UnrealObb.scan(lc, "loose.pak", null);
                check(single.single && single.textures == expected.size(), "loose pak scan");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                UnrealObb.rebuild(lc, single, changed, bo, null);
                byte[] rebuilt = bo.toByteArray();
                check(rebuilt.length == pakBytes.length, "loose pak keeps its size");
                UnrealPak rp = UnrealPak.open(UnityBundle.of(rebuilt));
                check(java.util.Arrays.equals(rp.read(rp.byName.get("Game/Content/Tex/T_Etc.uasset")), changed.get(0).get("Game/Content/Tex/T_Etc.uasset")), "loose pak rebuilt");
            }

            // damage: a flipped byte in the index fails the SHA-1 check; random bytes aren't a pak
            byte[] bad = pakBytes.clone();
            bad[(int) info.paks.get(0).pak.indexOffset + 10] ^= 1;
            try { UnrealPak.open(UnityBundle.of(bad)); check(false, "damaged index accepted"); }
            catch (java.io.IOException expectedFailure) { check(expectedFailure.getMessage().contains("checksum"), "index checksum reported"); }
            byte[] junk = new byte[2048]; new java.util.Random(3).nextBytes(junk);
            check(!UnrealPak.isPak(UnityBundle.of(junk)), "junk isn't a pak");
            try { UnrealAssets.parse(junk); check(false, "junk package accepted"); } catch (java.io.IOException expectedFailure) { checks++; }
        }
        models(obb);
        areas();
        objRoundTrip(obb);
        System.out.println("UnrealTest OK (" + checks + " checks)");
    }
}
