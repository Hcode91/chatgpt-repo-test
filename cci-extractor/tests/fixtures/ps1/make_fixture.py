#!/usr/bin/env python3
"""Builds the PS1 test disc used by tests/Ps1Test.java (no game data).

The ISO 9660 image is written by pycdlib (pip install pycdlib), an independent implementation, and then
wrapped into raw 2352-byte Mode 2 sectors. TIM images are encoded from known RGBA source images, so the
expected decode is the source. Writes test-disc.bin.gz, test-disc.iso.gz and expected.txt.
"""
import gzip, io, math, random, struct, zlib
import pycdlib

random.seed(1996)

def c15(r, g, b): return (r >> 3) | (g >> 3) << 5 | (b >> 3) << 10
def argb_of15(c):
    if c == 0: return 0
    r, g, b = c & 31, (c >> 5) & 31, (c >> 10) & 31
    return 0xff000000 | (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2)
def crc_argb(px): return '%08x' % zlib.crc32(b''.join(struct.pack('>I', p) for p in px))

def tim(depth, w, h, palettes=None, pixels=None):
    """depth 0/1: pixels = palette indices, palettes = list of colour lists. depth 2: 15-bit colours. depth 3: (r,g,b)."""
    out = struct.pack('<II', 0x10, depth | (8 if palettes else 0))
    if palettes:
        n = len(palettes[0])
        out += struct.pack('<IHHHH', 12 + n * len(palettes) * 2, 0, 480, n, len(palettes))
        out += b''.join(struct.pack('<H', c) for pal in palettes for c in pal)
    if depth == 0:
        data = bytes(pixels[i] | pixels[i + 1] << 4 for i in range(0, len(pixels), 2)); units = w // 4
    elif depth == 1:
        data = bytes(pixels); units = w // 2
    elif depth == 2:
        data = b''.join(struct.pack('<H', c) for c in pixels); units = w
    else:
        data = b''.join(bytes(p) for p in pixels); units = w * 3 // 2
    out += struct.pack('<IHHHH', 12 + len(data), 320, 0, units, h) + data
    return out

expected = []
files = {}

# 4bpp, two palettes (colour 0 of palette 0 is transparent black)
pals = [[0] + [c15(random.randrange(8, 256), random.randrange(8, 256), random.randrange(8, 256)) for _ in range(15)] for _ in range(2)]
idx = [random.randrange(16) for _ in range(32 * 16)]
files['/GRAPH/FONT.TIM'] = tim(0, 32, 16, pals, idx)
for p in range(2): expected.append('tim GRAPH/FONT.TIM clut%d 32x16 %s' % (p, crc_argb([argb_of15(pals[p][i]) for i in idx])))
# 8bpp
pal = [c15(random.randrange(256), random.randrange(256), random.randrange(256)) | 0x8000 for _ in range(256)]
idx = [random.randrange(256) for _ in range(24 * 10)]
files['/GRAPH/TITLE.TIM'] = tim(1, 24, 10, [pal], idx)
expected.append('tim GRAPH/TITLE.TIM clut0 24x10 %s' % crc_argb([argb_of15(pal[i]) for i in idx]))
# 16bpp
px = [random.randrange(0x10000) for _ in range(20 * 7)]
files['/GRAPH/BG.TIM'] = tim(2, 20, 7, None, px)
expected.append('tim GRAPH/BG.TIM clut0 20x7 %s' % crc_argb([argb_of15(c) for c in px]))
# 24bpp
px = [(random.randrange(256), random.randrange(256), random.randrange(256)) for _ in range(6 * 5)]
files['/GRAPH/PHOTO.TIM'] = tim(3, 6, 5, None, px)
expected.append('tim GRAPH/PHOTO.TIM clut0 6x5 %s' % crc_argb([0xff000000 | r << 16 | g << 8 | b for r, g, b in px]))
# A game archive with two TIMs embedded at 4-byte aligned offsets among junk
pack = bytes(random.randrange(256) for _ in range(300))
pack += files['/GRAPH/TITLE.TIM'] + bytes(random.randrange(256) for _ in range(128)) + files['/GRAPH/BG.TIM'] + b'\x10\0\0\0' + bytes(64)
files['/DATA/PACK.BIN'] = pack
expected.append('scan DATA/PACK.BIN %d %d' % (300, 300 + len(files['/GRAPH/TITLE.TIM']) + 128))

# VAG: a tone encoded with a straightforward PS-ADPCM encoder (brute force over filter/shift per frame)
F = [(0, 0), (60, 0), (115, -52), (98, -55), (122, -60)]
def encode_vag(samples, rate):
    h1 = h2 = 0; frames = bytearray(16); decoded = []
    for f in range(0, len(samples), 28):
        block = samples[f:f + 28] + [0] * (28 - len(samples[f:f + 28]))
        best = None
        for filt in range(5):
            for shift in range(13):
                s1, s2, err, nib, dec = h1, h2, 0, [], []
                for x in block:
                    pred = (s1 * F[filt][0] + s2 * F[filt][1] + 32) >> 6
                    n = max(-8, min(7, round((x - pred) * (1 << shift) / 4096)))
                    v = max(-32768, min(32767, ((n << 12) >> shift) + pred))
                    err += (x - v) ** 2; nib.append(n & 15); dec.append(v); s2, s1 = s1, v
                if best is None or err < best[0]: best = (err, filt, shift, nib, dec, s1, s2)
        _, filt, shift, nib, dec, h1, h2 = best
        frames += bytes([filt << 4 | shift, 0]) + bytes(nib[i] | nib[i + 1] << 4 for i in range(0, 28, 2))
        decoded += dec
    frames += bytes([0, 7]) + bytes(14)
    head = b'VAGp' + struct.pack('>IIII', 0x20, 0, len(frames), rate) + bytes(12) + b'TESTTONE'.ljust(16, b'\0')
    return head + bytes(frames), decoded
tone = [int(12000 * math.sin(i * 2 * math.pi * 440 / 22050) + 3000 * math.sin(i * 0.31)) for i in range(28 * 40)]
vag, dec = encode_vag(tone, 22050)
files['/SOUND/BEEP.VAG'] = vag
pcm = [0] * 28 + dec            # leading all-zero frame
expected.append('vag SOUND/BEEP.VAG 22050 %d %08x' % (len(pcm), zlib.crc32(b''.join(struct.pack('<h', s) for s in pcm))))

files['/SYSTEM.CNF'] = b'BOOT = cdrom:\\SLUS_999.99;1\r\nTCB = 4\r\nEVENT = 10\r\nSTACK = 801FFFF0\r\n'
files['/SLUS_999.99'] = b'PS-X EXE' + bytes(2040)
files['/README.TXT'] = b'test disc'
# Form 2 files: the ISO extent is 2 sectors each; real payload lives in the raw sectors (filled below)
files['/MOVIE/INTRO.STR'] = bytes(4096)
files['/MOVIE/VOICE.XA'] = bytes(4096)

iso = pycdlib.PyCdlib()
iso.new(interchange_level=1, sys_ident='PLAYSTATION', vol_ident='TESTDISC', xa=True)
for d in ('/GRAPH', '/DATA', '/SOUND', '/MOVIE'): iso.add_directory(d)
for path, data in files.items(): iso.add_fp(io.BytesIO(data), len(data), path + ';1')
buf = io.BytesIO(); iso.write_fp(buf); image = bytearray(buf.getvalue())

def extent(name):
    rec = iso.get_record(iso_path=name + ';1')
    return rec.extent_location()
str_lba, xa_lba = extent('/MOVIE/INTRO.STR'), extent('/MOVIE/VOICE.XA')
# Mark INTRO.STR as Form 2 in its XA directory record (VOICE.XA is detected from its sector subheaders only)
key = b'INTRO.STR;1'
at = image.find(key); assert at > 0
su = at + len(key) + (1 if len(key) % 2 == 0 else 0)
assert image[su + 6:su + 8] == b'XA', image[su:su + 14]
image[su + 4] |= 0x10
iso.close()

iso2048 = bytes(image)
sectors = len(iso2048) // 2048
raw = bytearray()
form2 = {}
for lba in range(sectors):
    a = lba + 150
    head = bytes([0] + [255] * 10 + [0]) + bytes([(a // 4500) // 10 << 4 | (a // 4500) % 10, (a // 75 % 60) // 10 << 4 | (a // 75 % 60) % 10, (a % 75) // 10 << 4 | (a % 75) % 10, 2])
    if lba in (str_lba, str_lba + 1, xa_lba, xa_lba + 1):
        sub = bytes([1, 1, 0x64 if lba >= xa_lba else 0x62, 0]) * 2      # Form 2 + realtime (+audio for XA)
        payload = bytes(random.randrange(256) for _ in range(2324)) + bytes(4)
        sector = head + sub + payload
        form2.setdefault('MOVIE/VOICE.XA' if lba >= xa_lba else 'MOVIE/INTRO.STR', bytearray()).extend(sector)
    else:
        sector = head + bytes([0, 0, 8, 0] * 2) + iso2048[lba * 2048:(lba + 1) * 2048] + bytes(280)
    assert len(sector) == 2352
    raw += sector
for name, data in sorted(form2.items()): expected.append('form2 %s %d %08x' % (name, len(data), zlib.crc32(data)))
for path, data in sorted(files.items()):
    if path.startswith('/MOVIE/'): continue
    expected.append('file %s %d %08x' % (path[1:], len(data), zlib.crc32(data)))
expected.append('boot SLUS_999.99')
expected.append('volume TESTDISC')

open('test-disc.bin.gz', 'wb').write(gzip.compress(bytes(raw), 9, mtime=0))
open('test-disc.iso.gz', 'wb').write(gzip.compress(iso2048, 9, mtime=0))
open('expected.txt', 'w').write('\n'.join(expected) + '\n')
print(len(raw), 'bytes raw,', sectors, 'sectors;', len(expected), 'expectations')
