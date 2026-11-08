# Game Asset Extractor (Android v0.4.1)

A native Android app (API 26+) for extracting **decrypted Nintendo 3DS CCI/NCSD** images and converting STXC `.btex` textures to editable PNGs. No root, keys, firmware or network access required. Original CCI remains untouched.

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

## Title ID folder preservation (new in 0.4.1)

3DS extraction now reads the **game Title ID directly from the CCI/NCCH header**, not the filename. A typical extraction has this structure:

```text
CCI_Extract_YYYYMMDD_HHMMSS/
  0004000000112D00/
    romfs/
      system/
        TitleLogo.btex
        TitleLogo.png
    exefs/
  other_partitions/
    partition_1/
      ...
```

The main game's `partition_0/romfs/` is automatically exported as `<TITLE_ID>/romfs/`, including when you extract one file or one folder. Copy just the **Title ID folder** into Azahar's `load/mods/` destination. Other NCSD partitions remain in `other_partitions` to avoid mixing optional/manual content into the main game's mods. If the title ID is unreadable, the app **does not guess** a folder name and retains the original partition path instead.

**Important:** This is a RomFS mod-ready *directory layout*, not a full working mod containing only changed files. Prefer copying only the textures you edited when making a final mod to reduce needless overrides.

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
3. Choose a folder. The output includes `CCI_Extract_.../<TITLE_ID>/romfs/...` with both `TitleLogo.btex` and `TitleLogo.png`.
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
bash tests/run-export-path-test.sh       # 3DS Title ID export folder regression tests
bash tests/run-nes-smoke-test.sh          # NES CHR roundtrip & Mapper 2 CHR-RAM safety tests
bash tests/run-texture-smoke-test.sh    # pure-Java synthetic 14-format test
# Optional if you have a locally decrypted CCI:
bash tests/run-smoke-test.sh /path/to/your/decrypted.cci
gradle --no-daemon :app:assembleDebug
```

The GitHub Actions workflow on the project branch produces an installable debug APK as the `game-asset-extractor-v0.4-debug-apk` artifact.

## Technical format references

- [3dbrew CGFX texture format table](https://www.3dbrew.org/wiki/CGFX)
- [3dstex texture conversion utility](https://github.com/Cruel/3dstex)
- [Nintendo 3DS RomFS reference](https://www.3dbrew.org/wiki/RomFS)

This code implements the codecs independently in pure Java. It does not embed game files, copyrighted sample textures or third-party codec source code.
