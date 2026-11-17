# Game Asset Extractor (Android v0.21.0)

## New in v0.21: 3D models and painting for Unreal Engine 4 games

Unreal OBBs and paks now get the same 3D viewer, UV view and projection painting as Unity games. After opening one, **Browse models** lists every model, with characters first, and has a search box (e.g. "Max", "Chloe"). Tapping any package also offers **3D models…**. In the viewer, each part shows its real texture. Paint works as before: pick a design, place it, then tap Apply. **Save… → New OBB with painted textures** writes the paint back in the original texture format with all its mips. **Edited PNGs → rebuild** includes painted textures too, and a painted texture wins over a PNG of the same texture.

How it works:
- **Meshes:** LOD 0 of cooked SkeletalMesh (characters) and StaticMesh (props, scenery).
  - Skinned vertices: packed normal, position, half-float UV.
  - Static meshes: separate position buffer, then normals and UVs in packed or high-precision form.
  - Unreal is Z-up and left-handed, so positions are turned so that **Front** shows the character's face. Texture rows run top-down, so V is flipped for the viewer.
- **Materials:** a mesh's material slot leads to a material instance, whose Diffusemap/BaseColor parameter gives the texture. The app follows its parent materials until it finds one. A base material falls back to its *_D texture import.
  - The blend mode, read from the base material or an instance override, decides whether alpha is see-through. Masked and translucent parts get cutouts; on opaque parts, alpha is ignored because it's usually a mask for something else.
  - Cooked static meshes leave the material out of their property list. It's read from the native slot list at the end of the mesh data instead.

**How it was checked**, on Life is Strange (original):
- All 1,978 models (130 skinned, 1,848 static) decode with every index inside its vertex buffer.
- 2,532 of 2,665 parts find their texture. The rest really have none: eye corneas, black planes, engine helper meshes.
- An independent Python decoder gives the same vertices (positions, normals and UVs) as the app for Chloe. Software renders of Max and Chloe with their textures look right, including reading text on Max's shirt the right way round.
- A design painted from the Front view onto Chloe's top, through the same Java code the app runs, was rebuilt into the pak. repak read all 27,835 files back, and only that one texture had changed (same size). An independent render from the repak copy shows the design exactly where it was placed and nothing on her back.
- The CI test adds a synthetic skinned mesh, static mesh, material instance and materials to the Unreal fixture. It checks the axis and UV conversion, the material and blend-mode choice, the static-mesh native slots, that a front paint leaves the back untouched, and that the painted texture survives a rebuild.

**Not yet checked:** painted OBBs haven't been run in the game on a phone yet. Only LOD 0 is shown and painted. Distant LODs use their own lower-resolution textures (e.g. `_LOD2` materials), so those may still show the old look from far away.


## v0.20: Unreal Engine 4 games (OBB with .pak inside, or a loose .pak)

Open an Unreal OBB (e.g. `main.<version>.<package>.obb` holding `…/Content/Paks/pakchunk0-Android_ETC2.pak`) or a `.pak` with **Open ROM**. Every package with textures is listed. **Extract all textures** (or tap a package) writes `Unreal_Textures_<time>/`, mirroring the game's folders, with one PNG per texture and `unreal_textures.tsv`. Edit the PNGs, open the original again, then **Edited PNGs → rebuild Unreal OBB / pak** and pick the folder. Each changed texture is re-encoded in its original format, with every stored mip regenerated at its size. A PNG of another size is scaled back. Then copy the new OBB over the original in `Android/obb/<package>/` with the same name. No signing is needed. For OBBs that live inside an APK, the done screen offers **Put into APK…**.

How it works:
- **Paks:** versions 3–7 with an unencrypted index (UE 4.13–4.19 era); files stored or zlib-compressed in blocks, read with random access. An encrypted index is reported, not guessed at.
- **Packages:** cooked, unversioned UE 4.14/4.15 layout. Every step is checked: the summary must end where the name table starts, and each texture must parse to exactly its stored size.
- **Textures:** cooked platform data with inline mips, in PF_ETC2_RGB, PF_ETC2_RGBA, PF_ETC1, PF_G8, PF_A8, PF_B8G8R8A8 and PF_R8G8B8A8. Unreal stores rows top-down.
- **Rebuilding:** the edited package is patched in place (every mip re-encoded to exactly its old size). Its new copy is appended to the pak with a fresh index and footer, which also carry the SHA-1s Unreal checks. Every original byte before the old index is kept as is, and in the OBB only the pak entry changes.

**How it was checked**, on Life is Strange (original) for Android (`main.100153.com.squareenix.lis.obb`, pak v3, 27,835 files):
- All 20,765 packages parse.
- All 6,168 textures were found and decode pixel-identically to texture2ddecoder.
- Four edited textures (one per format) were rebuilt into a new OBB. **repak**, an independent Rust pak library, read all 27,835 files from it, and exactly those 4 packages differed. Their textures decoded with texture2ddecoder exactly as the app intended. G8 and BGRA came out lossless; ETC2 re-encodes the way any ETC2 encoder does (43 dB on Max's hair).
- The CI test (`tests/run-unreal-test.sh`) runs the same on a synthetic OBB built by `tests/fixtures/unreal/gen_unreal_fixture.py`: stored and multi-block zlib packages, a map holding two textures, all four formats, a loose pak, and checks that damaged files are rejected. That pak is also re-read by repak.

**Not yet checked:** the rebuilt OBB hasn't been run in the game on a phone yet. Newer Unreal versions (4.16+ split `.uexp` files, IoStore) and encrypted paks aren't supported yet.


## New in v0.19: put a modified file back into an APK and sign it

For games whose data file (e.g. a Unity OBB) sits inside the APK itself. **Convert → Put a file into an APK & sign…**, or tap **Put into APK…** straight after rebuilding an OBB:
1. Pick the game's original APK.
2. Pick the file inside it to replace (listed largest first, with size and whether it's stored or compressed).
3. Pick your modified file, unless you came from the OBB rebuild, where it's already chosen.
4. Choose where to save the new APK.

The new APK has every other file byte-for-byte as before (same compression), the chosen file replaced (stored), and the old signature files removed. It's signed by Google's **apksig** (the library behind the SDK's `apksigner`) with v1 where the app's minimum Android version needs it, plus v2 and v3, and uncompressed files aligned the way Android requires. After saving, the app re-verifies the file with apksig's verifier, which runs the same checks as Android's installer.

The signing key is an RSA-2048 key created on first use inside the Android Keystore. It never leaves the phone. Because it isn't the game's original key, **uninstall the original game first** (back up saves without cloud sync). Later rebuilds are signed with the same key, so they install straight over the modded one, unless this app is uninstalled or its data cleared, which deletes the key. While it works it needs free space for about two extra copies of the APK.

**How it was checked:** `tests/run-apk-repack-test.sh` builds an APK with an old signature, replaces its data file, signs it and verifies it with apksig's `ApkVerifier` (v1 + v2 + v3, our certificate). It also checks that the replaced file is inside, every other file and its compression are unchanged, the old signature files are gone, and every stored file is 4-byte aligned. The test fetches apksig 8.7.3 and checks its SHA-256.

**Not yet checked:** this hasn't been run on a phone yet, including the Android Keystore key.


## v0.18.1: view buttons for painting all the way round

**Front / Left / Back / Right** under the viewer's top bar snap the model to those exact angles, keeping the zoom. In paint mode the design stays where you put it on screen, so you can stamp the front, tap **Left**, stamp, tap **Back**, stamp, and so on, and each stamp lines up the same way. Left and Right are the model's own sides (Left shows its left side). Tips: limit painting to the part with **Part**, overlap stamps slightly, and use 100% opacity where stamps overlap, or the overlap is painted twice and looks stronger. The CI test checks that each button shows the expected side.


## New in v0.18: paint designs straight onto Unity models (projection painting)

In the model viewer, **View → Paint a design onto the model**:
1. **Design…** picks an image (PNG transparency works). It floats over the model, see-through so you can line it up.
2. With **Moving: design**, drag to move it, pinch to resize and twist two fingers to rotate. Switch to **Moving: model** to turn or zoom the model and line it up from any angle. The **Opacity** slider sets how strongly it's painted.
3. **Apply** projects it onto everything under it that faces you, as seen from that view, and writes it into the texture. The 3D view, UV view and texture update at once, and **Undo** steps back (up to 8).
4. **Part** limits painting to one part (e.g. only the outfit, not the hair in front of it). Otherwise every textured part under the design is painted.
5. **Save… → New OBB with painted textures** encodes them to their original format, size and mipmaps and writes a new OBB. **Save… → Texture as PNG** exports one. Painted textures also stay for the session, so the main screen's rebuild button includes them, together with edited PNGs from a folder (the painted version wins if both changed the same texture).

How it works: like Blender's stencil projection paint. Every texel a triangle covers is mapped back to its 3D point and projected with the viewer's own camera. It's painted only if it faces you (surfaces turned more than ~75° away are skipped, so designs don't smear along the sides), isn't hidden behind other geometry (depth buffer; thin strands like a necklace don't punch holes in the design), and falls inside the design. Paint is padded 2 texels into the gaps around UV islands so seams don't show with mipmapping. The texture's own alpha is kept. If a real share of what you painted is also used by another spot on the model (mirrored UVs), the viewer tells you.

**How it was checked:** a design stamped onto Chloe's shirt from the front view (same camera code as the viewer) landed on the shirt front where it was placed, at the chosen angle and opacity. An independent software render of the model with the painted texture showed it there and nowhere on the back. The CI test checks placement, opacity, alpha preservation, back faces, occlusion, seam padding, the mirrored-UV warning and the camera projection on synthetic models.

**Not yet checked:** the paint screen hasn't been run on a device yet.


## New in v0.17: Unity 3D model viewer, with UV view

Open a Unity OBB, APK or bundle, then tap a bundle that lists models → **View 3D models** → pick one (biggest first).
- **3D view:** the model with each part in its material's main texture. Drag to rotate, pinch to zoom, double-tap to reset. **Part** highlights one part and dims the rest. The same menu turns off see-through texture areas (on by default, for hair and eyelashes) if a texture's alpha holds something else and leaves holes.
- **UV view:** the selected part's texture, with that part's triangles outlined in colour and other parts sharing the texture outlined dimly. This shows exactly which area of a texture lands on which part of the model, and which areas the model doesn't use at all. Pinch and drag to move around.
- **Save…:** the model as OBJ (with UVs, one group per part), the UV layout as a PNG (texture + outlines), or the outlines alone as a transparent PNG to use as a guide layer while editing.

How it works: meshes are decoded from their vertex streams in every Unity vertex format (float, half, normalised and integer types), with 16/32-bit indices and per-part baseVertex. Each mesh is matched to its SkinnedMeshRenderer, or to the MeshFilter + MeshRenderer on the same GameObject. Material references are followed through each file's externals table, including into other bundles of the same OBB. The main texture is `_MainTex`, or the nearest equivalent (`_BaseMap`, `_Albedo`…; normal/mask/spec maps are skipped). Not supported yet: compressed meshes (Unity's Mesh Compression setting) and blend shapes / skinning (models show in their bind pose).

**How it was checked**, on Life is Strange: Before the Storm:
- All 3,112 meshes in the OBB were decoded, and every vertex position, UV and triangle index matched UnityPy's decoding exactly.
- Chloe's 6-part model resolved to the same materials and textures UnityPy reports.
- A software render made from the app's decoded data (not the app's own screen) shows her correctly textured, which confirms the UV orientation and the left-handed → right-handed mirror the viewer uses.
- The CI test adds a synthetic 2-part mesh (two vertex streams, half-float UVs, baseVertex) with a SkinnedMeshRenderer whose two materials point at a texture in the same bundle and one in another bundle. It's built by `gen_unity_fixture.py` from Unity 2021.3's type-tree layouts and cross-checked with UnityPy. UnityPy ignores baseVertex, so the expected values add it, as Unity does.

**Not yet checked:** the viewer screens (OpenGL ES 3 and the UV view) type-check against the Android 14 framework but haven't been run on a device.


## New in v0.16: Unity games: extract textures, edit, rebuild (OBB / APK / asset bundles)

**Extract:** open a Unity game's OBB (`main.<version>.<package>.obb`), an APK, or a loose asset bundle with **Open ROM**. Every bundle is listed with its texture count. Tap **Extract all textures**, or tap a bundle for just that one. You get a `Unity_Textures_<time>/` folder:
- one folder per bundle, each texture saved as `<name>__<path id>.png`;
- `unity_textures.tsv`: where each PNG came from. Don't edit or delete it;
- textures in formats the app can't convert yet are saved as raw `.bin` data.

**Edit and rebuild:** edit the PNGs in place and keep their names. Then open the original file again and tap **Edited PNGs → rebuild Unity OBB / bundle**, pick the `Unity_Textures_…` folder and save. Only PNGs that changed are used. Each one goes back in its original format, size and mipmap count. A PNG of a different size is scaled back to the original size. Everything else is copied byte-for-byte: other zip entries keep their data and CRCs, and inside a patched bundle only the compressed blocks holding the new pixels change. Those blocks are stored uncompressed, so the bundle grows a little.

Then:
- **OBB:** copy it over the original in `Android/obb/<package>/` with the same file name (keep a backup). No re-signing needed.
- **APK:** it needs signing again before Android will install it, and the original game has to be uninstalled first.

Formats: ETC2 RGBA8 (EAC alpha), ETC2 RGB, ETC1, RGBA32, ARGB32, BGRA32, RGB24, RGB565, ARGB4444, RGBA4444, Alpha8 and R8. Alpha8/R8 are exported as greyscale PNGs (white = full), and brightness is written back. Not supported yet: ASTC, PVRTC, DXT/BC, crunched textures and LZMA-compressed bundles. Bundles must be stored, not deflated, inside the zip, which is how Unity OBBs always are.

How it works: UnityFS bundles v6–8 (Unity 5.3 to 6), LZ4/LZ4HC or uncompressed blocks, with random-access block decompression. SerializedFiles are read through their type trees, so field layouts aren't hard-coded. Files whose type trees were stripped at build time (usually `data.unity3d`) borrow the Texture2D layout from another bundle of the same Unity major.minor version. Every object must then parse to exactly its stored size, or it's rejected. Texture data is found inline or in the bundle's `.resS` stream. ETC2 decoding covers all modes (individual, differential, T, H, planar). The encoder writes ETC1-compatible colour blocks, which are valid ETC2, plus an EAC alpha search. Mipmaps are box-filtered.

**How it was checked**, on Life is Strange: Before the Storm's 1.8 GB OBB (493 bundles, Unity 2021.3):
- All 2,953 textures were found, including 105 in the stripped `data.unity3d`.
- Every decoded texture was pixel-identical to UnityPy + texture2ddecoder (independent tools).
- Six edited textures (one per format, two in `data.unity3d`) were rebuilt into a new OBB. UnityPy read it back with every edit intact, all other textures unchanged, and the zip valid with only the patched bundles changed.
- The CI test (`tests/run-unity-test.sh`) checks 1,200 random ETC1, ETC2 and ETC2+EAC blocks each against texture2ddecoder, encoder round trips with mip chains in every writable format, and a full extract → patch → rebuild on a synthetic OBB. The OBB has a type-tree bundle, a stripped one with its block table at the end, and stored and deflated extra files. It's built by `tests/fixtures/unity/gen_unity_fixture.py` and cross-checked with UnityPy. No game data is in the repo.

**Not yet checked:** the rebuilt OBB hasn't been run in the game on a phone yet, and the new screens haven't been run on a device. Re-encoded ETC textures lose a little quality compared to the originals (typically 38–45 dB PSNR), as any ETC re-encode does.


## New in v0.15: custom PS3 trophies and trophy mods (for RPCS3)

**Convert → Add custom trophies…**
1. Pick the game's `TROPHY.TRP`.
2. For each trophy enter a name, description, grade (bronze, silver or gold), hidden or not, and optionally an icon (cropped square and resized to 240×240).
3. Save the new `TROPHY.TRP` and put it in the game's `PS3_GAME/TROPDIR/<id>/` folder (keep a backup).
4. Rebuild the progress file: pick your current `TROPUSR.DAT` (in RPCS3's `dev_hdd0/home/00000001/trophy/<id>/`) to keep earned trophies, or make a fresh one. Put the result back there with RPCS3 closed.
5. Optionally save your additions as a **trophy mod** to share.

**Convert → Apply a trophy mod (.zip)…** adds someone else's trophies to your own `TROPHY.TRP`. A mod holds only its creator's names, descriptions and icons (`trophymod.xml` + `icons/`), nothing from the game. It records which trophy set and original trophy list it was made for, and is refused on any other pack.

Limits:
- Custom trophies never count towards the platinum, and a platinum can't be added.
- The game won't award them by itself. That needs a per-game RPCS3 patch (not part of the app) or a manual unlock; the app has no unlock button yet.
- RPCS3 only. A real PS3 rejects edited trophy files. If you use RPCN, be aware RPCS3 syncs unlocked trophies with it.

**How it was checked:** a pack with one added trophy and a rebuilt `TROPUSR.DAT` were loaded in RPCS3, where the trophy appeared with its icon and a manual unlock showed as earned. The editor reproduces both of those files byte-for-byte. The `TROPUSR.DAT` writer also reproduces RPCS3's own 14-trophy file byte-for-byte. The editor screens themselves haven't been run on a device.

## New in v0.14: PS3 trophy packs (TROPHY.TRP), extract and rebuild

**Extract:** open a `TROPHY.TRP` (found in `PS3_GAME/TROPDIR/<NPWR id>/`) with **Open ROM**. The summary shows the game, its trophy-set ID and the trophy count. Files are saved to `TRP_<id>_v<version>_<time>/`:
- `TROPCONF.SFM`: the trophy list (ID, grade, hidden, platinum link).
- `TROP.SFM` / `TROP_xx.SFM`: names and descriptions, per language.
- `ICON0.PNG`: the game's trophy icon.
- `TROPnnn.PNG`: one icon per trophy.

The `.SFM` files are plain XML.

**Rebuild:** **Convert → Folder → TROPHY.TRP** packs the folder back into a pack laid out exactly like Sony's: same file order, flags, 16-byte alignment and padding. Version 2+ packs get the SHA-1 checksum RPCS3 verifies. The version comes from the extracted folder's name.

**Where edited packs work:** RPCS3 does not check the trophy files' signature comment, and reinstalls `TROPHY.TRP` from the game folder every time the game starts, so edits show up there. A real PS3 checks the signatures and rejects edited files.

**How it was checked:** a retail `TROPHY.TRP` (version 1, 17 files) was extracted and rebuilt byte-for-byte identical, even with the files supplied in shuffled order. A version 3 rebuild passes RPCS3's checksum rule, verified independently with Python's `hashlib`. The format and the emulator behaviour were taken from RPCS3's own source (`Loader/TRP.cpp`, `Loader/TROPUSR.cpp`, `Modules/sceNpTrophy.cpp`). CI runs `tests/Ps3TrophyTest.java` on a synthetic pack.

## New in v0.13: Ren'Py archives (.rpa), extract and repack

**Extract:** open a `.rpa` with **Open ROM**. Every file in the archive is listed. **Extract all** saves them with their original paths into `RPA_<name>_<time>/`, or tap a file to save just that one. Large files (videos, music) are streamed, not loaded into memory.

- **Versions:** RPA-3.0 (current Ren'Py), RPA-3.2 and RPA-4.0 (custom variants), ALT-1.0, RPA-2.0, and RPA-1.0. For RPA-1.0, open the `.rpi` index and the app asks for the matching `.rpa`.
- Follows **Ren'Py's own loader**, not unrpa, where they differ. Ren'Py joins files stored in several pieces, and puts a piece's prefix before its data. unrpa reads only the first piece, and counts the prefix inside the length. Ren'Py's own archiver never writes either, so this only matters for archives made by other tools.
- **ZiX-12A/B** archives aren't supported. Their key is hidden in the game's `loader.pyo`, which needs a Python decompiler (unrpa on a computer can do it, given `loader.pyo`).
- The index is read with a built-in **data-only pickle reader**. It never runs code from the archive.

**Repack:** **Convert → Folder → RPA** packs a folder back into an archive. Pick the extracted folder after editing; the suggested name is the original archive's name. The output matches Ren'Py's own archiver: an RPA-3.0 header, the same fixed key and padding, files in path order, and a protocol-2 pickle index. That index works with both Python 2 Ren'Py (6/7) and Python 3 Ren'Py (8).

Tip: you often don't need to repack. Ren'Py loads loose files from the game's `game/` folder ahead of archives, so an edited file copied into `game/` at the same path overrides the archived one.

**How it was checked:** `tests/fixtures/rpa` holds archives whose indexes were written by Python's own `pickle` module, in every supported version and pickle protocols 0, 2, 4 and 5, plus a hand-built Python 2-style index. The Java reader matches all 44 files. unrpa extracts the same fixtures identically, except RPA-1.0, where unrpa reads data from the `.rpi` instead of the `.rpa`, unlike Ren'Py. Archives built by **Folder → RPA** were read back byte-for-byte by unrpa and by Ren'Py's loader logic, including empty files, deep paths and non-ASCII names, and their index uses no pickle opcode newer than protocol 2. Not yet tried in a running Ren'Py game.

## v0.12.1: Metal Gear Solid cutscene audio (DEMO.DAT) too

`DEMO.DAT` (the cutscenes) uses the same container as `VOX.DAT`, with extra animation data in each clip. Open it the same way and you get `demo_NNNN.wav` + `.srt` for each cutscene. A retail `DEMO.DAT` gives 79 cutscenes, about 2.2 hours, mostly stereo, with 1,430 subtitle lines; all of it converts. The animation data itself isn't exported. 22,050 Hz was chosen for the same reason as for VOX: the voices come out at a normal male pitch (median about 112 Hz). One forum post suggests 33,075 Hz for the PC version's files, and at that rate the cutscene animation would run at a round 25 fps, so if the cutscene audio sounds slow or deep, that is the likely cause.

## New in v0.12: Metal Gear Solid voice files (VOX.DAT)

Metal Gear Solid (PS1) keeps its codec calls and cutscene dialogue in `VOX.DAT` (`VOX_1.DAT` / `VOX_2.DAT` on the two discs). Extract it from the disc, then open it with **Open ROM**.

- The list shows every clip with its length and first subtitle line. **Extract all** converts everything; tap a clip for just that one.
- Each clip becomes `vox_NNNN.wav` (22050 Hz; mono, or stereo for the clips that have music), plus `vox_NNNN.srt` subtitles timed to the WAV, so most media players show them together.
- `script.txt` lists every subtitle line, clip by clip, to make finding a scene easy.

How it was checked: the format was worked out from a retail `VOX_1.DAT` (1,107 clips, 4.3 hours, 2,675 subtitle lines), and all of it converts without errors. The sample rate isn't stored in the file. 22,050 Hz was chosen because it puts the voices at normal speaking pitch (median about 125 Hz), and the subtitle timing lines up with the speech. Subtitle timing for the 33 stereo clips is less certain, because music under the dialogue makes it harder to check. Only English text has been seen; other language versions may show garbled subtitles. CI runs `tests/MgsVoxTest.java` on a synthetic file.

## New in v0.11: PlayStation disc images

Open a PS1 `.bin` with **Open ROM** (`.iso` and `.img` work too). The app reads the disc's file system and lists every file; **Extract all** saves the whole disc, or tap a file to save just that one.

- **Disc layouts:** raw `.bin` (2352-byte sectors, Mode 2 or Mode 1), 2336-byte Mode 2 images, and plain 2048-byte `.iso`. Detection is by content, so a PS1 `.bin` is never mistaken for a Mega Drive `.bin`.
- **Multi-track games:** open the first track (the data track, usually `Track 01.bin`). The other tracks are CD audio and have no files on them. You don't need the `.cue`.
- **Output:** `PS1_<boot file>_<time>/`, with the disc's own folder structure and file names.
- **TIM images → PNG.** All four depths (4, 8, 16, 24-bit). A TIM with several palettes gives one PNG per palette (`_clut01`, `_clut02` …, up to 16). Pure black is transparent, as on the console.
- **TIMs inside other files:** many games pack their TIMs into their own archives. Every file up to 16 MB (except the game executable) is scanned, and TIMs found inside are saved to `<file>_tims/0x<offset>.png`. Compressed images can't be found this way.
- **VAG sounds → WAV.**
- **XA audio and STR video** are stored in a special sector format, so they're saved as raw 2352-byte sectors. They aren't converted here; open them with a tool such as jPSXdec.

Not included: 3D models (TMD and game-specific formats), VAB sound banks, SEQ music, decoding XA/STR, and writing files back to the disc.

**How this was checked:** `tests/Ps1Test.java` runs against a test disc (`tests/fixtures/ps1`) whose ISO 9660 file system was written by an independent library (pycdlib) and wrapped into raw Mode 2 sectors, including Form 2 files. The same disc is tested as 2352, 2336 and 2048-byte images. TIM images are encoded from known source pixels, so the expected output is the source image. The VAG expectation comes from a test encoder written alongside the decoder, so that check is weaker. Not yet tried on a retail disc.

## New in v0.10: more 3DS and DS formats, unpacked automatically

Nothing new to tap: when you extract files from a 3DS or DS game, anything in one of these formats is now unpacked or converted **next to the original file**. Originals are always kept, and a file that can't be parsed is simply left alone.

| Format | What you get |
|---|---|
| **3DS archives:** SARC, darc, ZAR, GARC | a `<file>_contents/` folder with the files inside (names kept where the archive stores them; GARC and nameless SARC entries are numbered and given an extension from their contents) |
| **3DS compression:** Yaz0, LZ10/LZ11 | a `<file>.decompressed` copy, which is then examined in turn |
| **3DS textures:** CTPK, BCLIM, BFLIM, and the textures inside CGFX (`.bcres`/`.bcmdl`) and BCH | PNG (`<file>.png`, or `<file>.<texture name>.png` when a file holds several); largest mip level only |
| **3DS audio:** BCWAV, BCSTM | 16-bit WAV (`<file>.wav`); PCM8, PCM16, DSP ADPCM and IMA ADPCM |
| **DS textures:** NSBTX, and the texture block inside NSBMD models | one PNG per texture; all seven texture formats including 4x4-compressed |
| **DS tilemaps:** NSCR | `<name>_screen.png`, the assembled background, using the same-named `.ncgr` (or the nearest one in the folder) and its palette |
| **DS audio:** SDAT sound archives, SWAR wave archives, SWAV, STRM | SDAT → `<file>_contents/` with `SEQ/`, `BANK/`, `WAVEARC/`, `STRM/` (named from the archive's symbol table when it has one); every sample and stream as WAV |

It works through layers, so an LZ-compressed darc of BCLIM images ends up as PNGs. Files over 64 MB are not examined.

**How far this has been checked.** No retail files in these formats were available during development, so:
- The DS readers (NSBTX, SWAV, SWAR, STRM, SDAT) and SARC and Yaz0 were checked against files produced by independent open-source libraries (ndspy, sarc, libyaz0) and decode them correctly.
- darc, ZAR, GARC, CTPK, BCLIM, BFLIM, CGFX, BCH, BCWAV and BCSTM are written from the public documentation (3dbrew) and open-source tools (Kuriimu), and tested only against fixtures built from those same layouts in `tests/AssetFormatsTest.java`. They may need fixes once tried on real games; BCH texture names and BFLIM rotation are the least certain.

Not included: 3D models and animations, sequenced music (SSEQ/SBNK → MIDI), DS sprite cells (NCER/NANR), fonts, and putting edited files back into these containers.

## New in v0.9: Torus PAK asset packs (`packfile.dat`)

Some 3DS games built on Torus Games' engine keep nearly everything in one `packfile.dat` inside RomFS. Extract it from the game as usual, then open the `.dat` itself with **Open ROM**.

- The list shows each bundle ("hunk") in the pack, named from its manifest (`Global`, `HUB`, `Environment01_LOAD_01` …).
- **Extract all** unpacks every bundle; tapping a bundle extracts just that one.
- Output is `PAK_Extract_<time>/<Bundle>/<AssetType>/<AssetName>`, using the names stored in the pack.
- **Textures (`TSETexture`) are saved as PNG.** They are standard 3DS ETC1 / ETC1A4; only the largest mip level is exported.
- **Everything else is saved raw** as `.bin` (models, animations, sounds, scripts, string tables, collision, level data). Those formats are custom and aren't decoded. Each `.bin` holds the asset's records as `u32 size, u16 type, u16 flags, data`.
- Read-only: there is no PNG → PAK import or repacking.

The format was worked out from a single game's pack, so other Torus titles may use texture formats this doesn't know; those textures are saved raw and counted in the summary. Opening a `.cia` on the Extract tab now explains that it needs converting first instead of reporting an unsupported file.

## New in v0.8: encrypted CIA → CCI

**CIA → CCI (.3ds)** on the Convert tab now accepts encrypted CIAs as well as decrypted ones.

- **Encrypted CIA** (title-key or NCCH level): decrypted on the device and rebuilt as a *decrypted* CCI that emulators such as Azahar can load. This uses a bundled native engine: `ctrtool` and `makerom` from [3DSGuy/Project_CTR](https://github.com/3DSGuy/Project_CTR), wrapped by the JNI layer from [cia3ds-android](https://github.com/Hinoaaaaaf212/cia3ds-android) (both MIT, see `native/`).
- **Decrypted CIA**: unchanged. It is still repackaged in pure Java, byte-for-byte.
- **Games and demos only** for encrypted input. DLC, updates, system titles and DSiWare have no cartridge-image form, so they are refused with an explanation and no file is written.
- **No network access.** Seed-encrypted titles use the bundled community `seeddb.bin`; a title whose seed isn't in it can't be decrypted.
- **Free space:** the engine stages the decrypted contents in the app's cache while it works, so allow roughly twice the CIA's size free on internal storage.
- The APK now contains native code for `arm64-v8a` and `armeabi-v7a` only.

The engine is exercised in CI on small synthetic titles (`tests/run-native-engine-test.sh`). Those are generated unencrypted, so the decryption step itself isn't covered by an automated test.

## New in v0.7: Extract and Convert tabs

The app now has two tabs. **Extract** holds everything from earlier versions. **Convert** adds:

**3DS containers**
- **CIA → CCI (.3ds):** repacks a *decrypted* CIA into a cartridge image. Content 0 becomes the game partition, 1 the manual, 2 Download Play. (v0.7 refused encrypted CIAs; from v0.8 they are decrypted first, see above.)
- **CCI → CIA:** builds an unsigned CIA (contents unencrypted, SHA-256 hashes in the TMD). Install it with signature patches (e.g. Luma3DS + FBI). Update partitions are dropped.
- The standard 3DS certificate chain that every CIA needs isn't bundled. **Save certificate chain from a CIA** copies it from any CIA you own (encrypted ones work for this step), and converting a CIA → CCI saves it automatically.

**Textures (batch)**
- Inputs: PNG/JPG/WebP, DDS (uncompressed, BC1/DXT1, BC2, BC3/DXT5, BC4, BC5), KTX 1 (RGBA8, RGB8, ETC1), 3DS BTEX, and DS NCGR (uses an `.nclr` selected in the same batch as its palette). BC6H/BC7 DDS aren't supported yet.
- Outputs: PNG, DDS (RGBA8 / BC1 / BC3), KTX (RGBA8 / ETC1), or a new DS NCGR + NCLR pair (4bpp/8bpp, colours reduced automatically).
- Optional resize: half, 2×/4× sharp pixels, 2× smooth, or pad up to a power of two.


## New in v0.6

**Nintendo DS archives and compression**
- Tap a `.narc`/`.carc` (or any file that turns out to be one) and choose **Open contents** to browse inside it. Unnamed archive files are numbered and given an extension from their contents (`0003.ncgr`, `0004.nclr` …).
- LZ10 and LZ11 compressed files are detected and decompressed automatically.
- **Extract** now unpacks archives into `*_contents` folders, saves a `.decompressed` copy of compressed files and converts every `.ncgr` it finds to PNG. It uses the same-named `.nclr` or, failing that, the nearest palette in the same folder/archive. Graphics without a palette are exported as `_gray.png`.

**DS PNG → NCGR import**
- Tap an `.ncgr` → **Preview / import PNG** shows it with its palette (use **Next palette bank** for multi-bank palettes).
- **Import PNG…** takes your edited PNG (same size), matches each pixel to the nearest palette colour, re-compresses and rebuilds any archives it sits in, and saves a **new** `.nds`. Files that grow are moved to the end of the ROM and the FAT, ROM size and header CRC are updated. The original ROM is never touched.

**Game Boy, Game Boy Color and Game Boy Advance**
- GB/GBC: 2bpp tile pages. GBA: raw 4bpp/8bpp pages, plus **LZ77 compressed blocks** (Tile format → LZ77). This scans the ROM for BIOS-LZ77 data that decompresses to whole tiles.
- Edited LZ77 blocks are re-compressed. If they no longer fit, they're moved to the end of the ROM and every pointer to them is updated.

**Tile viewer, palettes and checksums**
- Tap any NES/SNES/MD/GB/GBA page to open the viewer with ◀ / ▶ paging.
- **Palette** button: grayscale (classic), inverted, Game Boy green, warm/cool ramps, rainbow, or load a `.pal` (JASC/RIFF), `.gpl`, `.act`, `.nclr` or raw BGR555 file. Exports use the palette, and imports map colours back to indexes, so keep the same palette selected when importing.
- PNG → ROM now repairs checksums automatically: SNES (including mirrored sizes), Mega Drive, Game Boy header + global, and the GBA header complement.


A native Android app (API 26+) for extracting **decrypted Nintendo 3DS CCI/NCSD** images and converting STXC `.btex` textures to editable PNGs. No root, keys, firmware or network access required. Original CCI remains untouched.

## New: Nintendo DS, SNES and Mega Drive (v0.5)

**Nintendo DS (`.nds`):** Read-only NitroFS folder browser and file extractor. Files remain in `NDS_Extract_.../nitrofs/...` with original names and directory structure. When an `.ncgr` (RGCN/RAHC) file has a **same-basename `.nclr` (RLCN/TTLP)** palette, supported tiled 4bpp/8bpp 2D graphics are automatically exported to companion PNG previews. The source NCGR/NCLR files remain untouched. This is a limited decoder: no NSBTX, custom compression, NARC recursion, arbitrary palettes or 2D tilemaps yet. PNG-to-NCGR import/DS ROM rebuilding are **not** included in this release.

**SNES (`.sfc`/`.smc`):** Probe LoROM/HiROM headers and the optional 512-byte copier header, then scan raw ROM pages as 2bpp, 3bpp, 4bpp, or 8bpp 8×8 tiles. Choose bit depth, export selected/all pages to indexed grayscale PNGs. PNG → SNES can replace one *known* raw page in a separate ROM copy, with exact grayscale/dimension validation. No automatic decompression, sprite detection, palette mapping or checksum repair. A raw page may contain executable code or compressed data; blindly importing it can break the new ROM.

**Sega Mega Drive / Genesis (`.md`/`.gen`/linear `.bin`):** Verify the SEGA ROM header, scan standard 4bpp packed 8×8 tile pages, export indexed PNG sheets and re-import one *known* page into a **new** ROM copy. `.smd` interleaved files are not supported; convert them to linear ROMs first. Original palettes/compression are not decoded and ROM checksums are not automatically updated.

All consoles use Android's storage picker; original game dumps are **never overwritten**. NES and 3DS features remain in place.

### 3DS Title ID extraction folder (v0.4.1 preserved)

The primary NCCH's program ID is used as the export directory. For Yumi's Odd Odyssey, the folder is `CCI_Extract_.../0004000000112D00/romfs/...`. Other partitions are kept separately. The Title ID is read from the header, never the filename.

## NES support (new in 0.3)

- Opens **iNES 1.0 `.nes` ROMs** (read-only input), detects mapper and CHR-ROM versus CHR-RAM.
- **CHR-ROM:** choose **Export tile PNGs**. Each 4096-byte (256-tile) page becomes a 128×128 grayscale PNG of 16×16 NES tiles. The ROM's four 2-bit graphics indexes map to #000000, #555555, #AAAAAA and #FFFFFF. NES palette colours are separate from tile data.
- **PNG → NES:** after editing an exported CHR page, select its page number and edited PNG. The app validates its dimensions/index colours and saves a **new** `.nes` ROM, replacing only the selected CHR page. Original ROM remains untouched.
- **CHR-RAM:** since graphics may be compressed, generated at runtime or intertwined with program code, the app **only exports experimental PRG candidate tile sheets**. These are *not* confirmed assets and can look like noise. It intentionally disables PNG-to-ROM rebuilding for CHR-RAM games until game-specific graphics handling is implemented.
- Verified header and PRG tile scans using the user's **Caesars Palace (USA)** NES (Mapper 2, 128 KiB PRG, CHR-RAM, 32 PRG candidate sheets). The ROM is **not included** in this source or APK.
- Classic iNES 1.0 supported, NES 2.0 currently rejected explicitly. Other ROM formats not supported.

**To edit CHR-ROM graphics:** Select .nes → Export tile PNGs → edit a sheet without resizing or changing the four grayscale colours → PNG → NES → choose the same page → save a new ROM.

**For Caesars Palace:** Select .nes → Export tile PNGs to inspect 32 PRG candidate sheets. Do *not* patch these raw sheets back into the game, as they may represent executable code. A true mod needs specific graphics data location/decoding or CHR-RAM runtime capture.

## HD 3DS texture upscaling (new in 0.4)

- **3DS HD PNG upscale (2× / 4×)** supports selecting **multiple PNGs** and saving enlarged copies into a chosen folder. Use sharp nearest-neighbour (pixel-art fidelity) or smooth bilinear filtering. Originals remain unchanged.
- Upscaling is **not AI super-resolution**; it increases pixel dimensions without inventing new texture details. Upscaled images consume more GPU memory (2× in each dimension = ~4× texture memory; 4× = ~16×). The app limits the PNG output to 4096 pixels per axis / 12 million pixels per image to reduce memory crashes.
- **In-game HD textures require Azahar custom texture packs**. First enable Azahar's texture dumping in settings, run the game to capture the used textures, and select those **`tex1_...png`** dumped images in the upscale tool. Their original hash-based filenames are kept intact. For a new output folder the app creates a basic `pack.json` if needed.
- Copy the upscaled files and `pack.json` into that game's **custom textures** folder (not RomFS mods). Enable **Custom Textures** in Azahar Graphics settings. The correct title-specific folder can normally be opened using the game's context menu; exact UI depends on Azahar version.
- **Important:** PNGs exported from `.btex` files use BTEX filenames rather than Azahar's hash names. They can be upscaled for viewing, but cannot automatically be recognized as in-game HD replacements without being matched to Azahar texture dumps. Existing PNG → BTEX conversion still requires the original exact dimensions.
- Start with 2× on mobile; 4× has much higher VRAM cost. Raising Azahar's **Internal Resolution** separately can improve 3D rendering without modifying textures.

## Features

- Open `.cci`/`.3ds` images via Android's file picker and browse RomFS/ExeFS partitions.
- Extract a file, folder or complete game with a chosen destination directory.
- **Every supported `.btex` file is automatically converted to a PNG during extraction.** Original `.btex` and companion `.png` are saved side by side, preserving the reference for reverse conversion. For non-BTEX content, original files are copied unchanged.
- **PNG → BTEX:** select your edited PNG, choose the matching original `.btex` (from the extraction), then choose where to save the new BTEX. The template is required to retain the original STXC header, dimensions, format and trailing data.
- Conversion supports Nintendo 3DS texture formats **RGBA8 (0), RGB8 (1), RGBA5551 (2), RGB565 (3), RGBA4 (4), LA8 (5), HILO8 (6), L8 (7), A8 (8), LA4 (9), L4 (10), A4 (11), ETC1 (12), ETC1A4 (13)**, with 8×8 tiled image layout. ETC1 and ETC1A4 are lossy when re-encoded; editing may reduce image quality.
- A failed/unsupported BTEX conversion won't interrupt the overall extraction; the original BTEX remains and the extraction summary reports the number skipped.
- No direct writes to game ROMs. To test a mod, put the newly created `.btex` into Azahar's per-game RomFS mod path.

## How to use

1. Launch the app and open your **decrypted** CCI.
2. Tap **Extract all**, or browse to `partition_0/romfs/system/` and select `TitleLogo.btex`.
3. Choose a folder. The output includes both `TitleLogo.btex` and `TitleLogo.png`.
4. Open the PNG in an image editor on your phone. Keep image dimensions unchanged.
5. Tap **PNG → BTEX**. Choose your edited PNG, then the matching original BTEX extracted in step 3.
6. Choose a new output path for the modified `.btex`.
7. Copy the modified BTEX to the correct relative `romfs/` path in the Azahar game mods folder.

## Notes

- Not every game uses the same `.btex` container. This codec targets the **STXC header** used by Yumi's Odd Odyssey and compatible games. Unknown BTEX variants will be left in original format, not silently corrupted.
- Requires correctly decrypted NCSD/NCCH content. Encrypted files are detected and rejected; no keys or decryption are bundled.
- Some rare multi-mipmap or nonstandard textures are conservatively rejected.
- Conversion preserves original width/height/format and does not resize images. PNGs must match the original BTEX dimensions exactly.
- The ETC encoder is a straightforward low/medium-quality individual-mode encoder. It is functional but may introduce visible compression artefacts, especially on detailed art.
- Large extraction jobs work best while the app is kept in the foreground. Cancelling leaves completed files in the output folder.
- Use only with your own game dumps and modifiable assets. Do not distribute copyrighted game assets.

## Building

JDK 17, Android Gradle Plugin 8.7.3, Gradle 8.9, Android SDK 35.

```sh
bash tests/run-export-path-test.sh         # 3DS Title ID mod folder regression
bash tests/run-nds-smoke-test.sh           # Nintendo DS NitroFS file-tree tests
bash tests/run-nds-graphics-test.sh        # NDS 2D NCGR/NCLR preview tests
bash tests/run-retro-smoke-test.sh         # SNES and Mega Drive tile format roundtrips
bash tests/run-nes-smoke-test.sh          # NES CHR roundtrip & Mapper 2 CHR-RAM safety tests
bash tests/run-texture-smoke-test.sh    # pure-Java synthetic 14-format test
# Optional if you have a locally decrypted CCI:
bash tests/run-smoke-test.sh /path/to/your/decrypted.cci
gradle --no-daemon :app:assembleDebug
```

The GitHub Actions workflow on the project branch produces an installable debug APK as the `game-asset-extractor-v0.5-debug-apk` artifact.

## Technical format references

- [3dbrew CGFX texture format table](https://www.3dbrew.org/wiki/CGFX)
- [3dstex texture conversion utility](https://github.com/Cruel/3dstex)
- [Nintendo 3DS RomFS reference](https://www.3dbrew.org/wiki/RomFS)

This code implements the codecs independently in pure Java. It does not embed game files, copyrighted sample textures or third-party codec source code.
