# v0.7: PS3 guest RSX triangle frames (not a generic GPU animation)

Unlike the separate v0.6 native Vulkan colour test, the displayed graphics
in this build originate in actual synthetic PPU guest code running through
the full upstream boot loader and LV2/HLE runtime on Android ARM64.

The synthetic guest writes a float4 position + float4 colour vertex buffer
into mapped guest IO RAM, then submits **NV4097** vertex-array configuration,
BEGIN_END and DRAW_ARRAYS methods via the real RSX FIFO. Upstream
`cellGcmSys.c` drains that FIFO, `rsx_commands.c` interprets the command
stream, and `rsx_null_backend.c` rasterizes the guest triangle into a
640x360 framebuffer. The upstream-supported `NULLSW_DUMP=1` option captures
three BMP frames on guest flips. The Android UI validates and displays
those actual guest-generated framebuffer pixels. Frames differ in triangle
colour (gold, cyan and pink) so the results can be compared.

**Honest limitations:** software RSX rasterization is not yet a true RSX
shader-to-Vulkan renderer, and these are synthetic triangles rather than
inFAMOUS models, textures or shaders. It does not run the commercial game.

The old v0.5 synthetic boot test and v0.6 native Vulkan test remain intact.

## Device test
Select "DRAW PS3 RSX TRIANGLES (GUEST CODE)". The test must show three
frames, three distinct centre colours, [smoke] PASS, and
"PS3 RSX TRIANGLE DRAW: PASS". If it instead shows a timeout or missing
frame, inspect the log, and do not claim that 3 passes happened.

This demonstrates that PPU guest code can produce rasterized geometry
through the actual RSX command path on Android, independently of the
still-missing production inFAMOUS renderer.
