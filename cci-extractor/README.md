# Game Asset Extractor (Android v0.14.0)

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
