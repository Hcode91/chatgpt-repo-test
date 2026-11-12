# Game Asset Extractor (Android v0.8.0)

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
