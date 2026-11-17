#!/usr/bin/env python3
"""Builds the synthetic Unreal Engine 4 fixtures used by UnrealTest.java. No game or engine content involved.

  unreal_fixture.obb  an OBB-style stored zip holding Game/Content/Paks/pakchunk0-Android_ETC2.pak (pak version 3,
                      the format of Life is Strange's Android port) and a dummy movie. The pak has:
                        Game/Content/Tex/T_Grey.uasset    G8 8x4, 2 mips, stored (no compression)
                        Game/Content/Tex/T_Etc.uasset     ETC2_RGB 16x8, 3 mips, zlib in 512-byte blocks (several)
                        Game/Content/Tex/T_Bgra.uasset    B8G8R8A8 4x4, 1 mip, zlib
                        Game/Content/Maps/Level.umap      two textures: ETC2_RGBA 8x8 (2 mips) + G8 4x4, zlib
                        Game/Config/Game.ini              plain text, zlib
                      Packages follow the cooked, unversioned UE 4.14/4.15 layout (summary, names, imports, 96-byte
                      export entries, tagged properties, cooked platform data with inline mips).
  expected.tsv        package, export, name, pixel format, width, height, mips, sha1 of the decoded top mip
                      (RGBA bytes; one byte per pixel for G8), decoded with texture2ddecoder.

  pip install etcpak texture2ddecoder numpy Pillow
  python3 gen_unreal_fixture.py [path/to/repakcheck]   # optional: re-read the pak with repak as a cross-check
"""
import hashlib, io, os, random, struct, subprocess, sys, zipfile, zlib

import etcpak, texture2ddecoder
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
TAG = 0x9E2A83C1

def fstr(s):
    if not s: return struct.pack('<i', 0)
    b = s.encode('latin1') + b'\0'
    return struct.pack('<i', len(b)) + b

def pattern(w, h, seed, alpha=True):
    r = random.Random(seed); im = Image.new('RGBA', (w, h))
    for y in range(h):
        for x in range(w):
            im.putpixel((x, y), (x * 255 // max(1, w - 1), y * 255 // max(1, h - 1), r.randrange(256), r.randrange(256) if alpha else 255))
    return im

def encode(im, pf):
    """One mip, rows top-down (Unreal)."""
    w, h = im.size
    if pf == 'PF_G8': return im.convert('L').tobytes()
    if pf == 'PF_B8G8R8A8': return im.tobytes('raw', 'BGRA')
    pw, ph = (w + 3) // 4 * 4, (h + 3) // 4 * 4
    pad = Image.new('RGBA', (pw, ph)); pad.paste(im, (0, 0))
    raw = pad.tobytes('raw', 'RGBA')
    return etcpak.compress_etc2_rgb(raw, pw, ph) if pf == 'PF_ETC2_RGB' else etcpak.compress_etc2_rgba(raw, pw, ph)

def decode_sha(data, pf, w, h):
    if pf == 'PF_G8': return hashlib.sha1(data[:w * h]).hexdigest()
    if pf == 'PF_B8G8R8A8': return hashlib.sha1(Image.frombytes('RGBA', (w, h), data, 'raw', 'BGRA').tobytes()).hexdigest()
    pw, ph = (w + 3) // 4 * 4, (h + 3) // 4 * 4
    fn = texture2ddecoder.decode_etc2 if pf == 'PF_ETC2_RGB' else texture2ddecoder.decode_etc2a8
    im = Image.frombytes('RGBA', (pw, ph), fn(data, pw, ph), 'raw', 'BGRA').crop((0, 0, w, h))
    return hashlib.sha1(im.tobytes()).hexdigest()

def package(path, textures):
    """textures: list of (name, pf, [mip images]). Returns package bytes (cooked UE 4.15 layout)."""
    names = ['None', '/Script/Engine', '/Script/CoreUObject', 'Class', 'Package', 'Texture2D', 'LODBias', 'IntProperty',
             'ImportedSize', 'StructProperty', 'IntPoint', path]
    for n, pf, _ in textures:
        for x in (n, pf):
            if x not in names: names.append(x)
    N = {n: i for i, n in enumerate(names)}
    fname = lambda n: struct.pack('<ii', N[n], 0)
    def summary(total, no, eo, io_, ec, ic, ar, bulk):
        s = struct.pack('<Iiiiii', TAG, -7, 0, 0, 0, 0)          # tag, legacy, legacyUE3, UE4, licensee, custom versions
        s += struct.pack('<i', total) + fstr('None') + struct.pack('<I', 0x80000000)
        s += struct.pack('<ii', len(names), no) + struct.pack('<ii', 0, 0)
        s += struct.pack('<iiiii', ec, eo, ic, io_, 0) + struct.pack('<iiii', 0, 0, 0, 0)
        s += b'\0' * 16 + struct.pack('<i', 1) + struct.pack('<ii', ec, len(names))
        for _ in range(2): s += struct.pack('<HHHI', 4, 15, 0, 0) + fstr('')
        s += struct.pack('<IiIi', 0, 0, 0x12345678, 0) + struct.pack('<i', ar) + struct.pack('<q', bulk)
        s += struct.pack('<iiii', 0, 0, -1, total)
        return s
    s0 = summary(0, 0, 0, 0, 0, 0, 0, 0)
    no = len(s0)
    nb = b''.join(fstr(n) + b'\0\0\0\0' for n in names)
    io_ = no + len(nb)
    imp = (fname('/Script/CoreUObject') + fname('Class') + struct.pack('<i', -2) + fname('Texture2D') +
           fname('/Script/CoreUObject') + fname('Package') + struct.pack('<i', 0) + fname('/Script/Engine'))
    eo = io_ + len(imp)
    ec = len(textures)
    ar = eo + 96 * ec
    total = ar + 4
    # export bodies (positions are absolute, so lay them out after the header)
    bodies = []; at = total
    for name, pf, mips in textures:
        body = bytearray()
        body += fname('LODBias') + fname('IntProperty') + struct.pack('<ii', 4, 0) + b'\0' + struct.pack('<i', 0)
        body += fname('ImportedSize') + fname('StructProperty') + struct.pack('<ii', 8, 0) + fname('IntPoint') + b'\0' * 16 + b'\0'
        body += struct.pack('<ii', mips[0].size[0], mips[0].size[1])
        body += fname('None')
        body += struct.pack('<i', 0) + bytes([1, 0, 1, 0]) + struct.pack('<i', 1)
        body += fname(pf)
        skip_at = len(body); body += struct.pack('<i', 0)
        body += struct.pack('<iii', mips[0].size[0], mips[0].size[1], 1) + fstr(pf) + struct.pack('<ii', 0, len(mips))
        for im in mips:
            data = encode(im, pf)
            body += struct.pack('<iIii', 1, 0x48, len(data), len(data))
            body += struct.pack('<q', at + len(body) + 8)
            body += data + struct.pack('<ii', im.size[0], im.size[1])
        struct.pack_into('<i', body, skip_at, at + len(body))
        body += fname('None')
        bodies.append((at, bytes(body))); at += len(body)
    exp = b''
    for (name, pf, mips), (off, body) in zip(textures, bodies):
        e = struct.pack('<iiii', -1, 0, 0, 0) + fname(name) + struct.pack('<I', 0x0B) + struct.pack('<ii', len(body), off)
        e += b'\0' * 12 + b'\0' * 16 + struct.pack('<iii', 0, 1, 1) + struct.pack('<i', -1) + b'\0' * 16
        assert len(e) == 96
        exp += e
    head = summary(total, no, eo, io_, ec, 2, ar, at) + nb + imp + exp + struct.pack('<i', 0)
    assert len(head) == total, (len(head), total)
    return head + b''.join(b for _, b in bodies) + struct.pack('<I', TAG)

def pak(files, block=512):
    """files: list of (path, data, compress). Pak version 3."""
    out = bytearray(); index = []
    for path, data, comp in files:
        off = len(out)
        if comp:
            chunks = [zlib.compress(data[i:i + block]) for i in range(0, len(data), block)] or [zlib.compress(b'')]
            hlen = 8 * 3 + 4 + 20 + 4 + 16 * len(chunks) + 1 + 4
            pos = off + hlen; blocks = []
            for c in chunks: blocks.append((pos, pos + len(c))); pos += len(c)
            stored = b''.join(chunks); meth = 1; bs = block
        else:
            stored = data; blocks = []; meth = 0; bs = 0
        h = hashlib.sha1(stored).digest()
        def entry(o):
            e = struct.pack('<qqqi', o, len(stored), len(data), meth) + h
            if meth: e += struct.pack('<i', len(blocks)) + b''.join(struct.pack('<qq', a, b) for a, b in blocks)
            return e + b'\0' + struct.pack('<I', bs)
        out += entry(0) + stored
        index.append((path, entry(off)))
    idx = fstr('../../../') + struct.pack('<i', len(index)) + b''.join(fstr(p) + e for p, e in index)
    io_ = len(out)
    out += idx + struct.pack('<IiQQ', 0x5A6F12E1, 3, io_, len(idx)) + hashlib.sha1(idx).digest()
    return bytes(out)

def main():
    expected = []
    def tex(pkgpath, name, pf, w, h, nmips, seed, alpha=True):
        base = pattern(w, h, seed, alpha)
        mips = [base if i == 0 else base.resize((max(1, w >> i), max(1, h >> i)), Image.BOX) for i in range(nmips)]
        top = encode(mips[0], pf)
        expected.append([pkgpath, None, name, pf, w, h, nmips, decode_sha(top, pf, w, h)])
        return (name, pf, mips)
    files = []
    for path, texs, comp in [
        ('Game/Content/Tex/T_Grey.uasset', [('T_Grey', 'PF_G8', 8, 4, 2, 1, False)], False),
        ('Game/Content/Tex/T_Etc.uasset', [('T_Etc', 'PF_ETC2_RGB', 16, 8, 3, 2, False)], True),
        ('Game/Content/Tex/T_Bgra.uasset', [('T_Bgra', 'PF_B8G8R8A8', 4, 4, 1, 3, True)], True),
        ('Game/Content/Maps/Level.umap', [('T_LevelA', 'PF_ETC2_RGBA', 8, 8, 2, 4, True), ('T_LevelB', 'PF_G8', 4, 4, 1, 5, False)], True)]:
        start = len(expected)
        built = [tex(path, *t) for t in texs]
        for i, e in enumerate(expected[start:]): e[1] = i
        files.append((path, package('/Game/' + path.split('/Content/')[1].rsplit('.', 1)[0], built), comp))
    files.append(('Game/Config/Game.ini', b'[Section]\nKey=Value\n' * 40, True))
    pakbytes = pak(files)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('Game/Content/Movies/Intro.mp4', b'\0\0\0\x18ftypmp42' + b'\0' * 100)
        z.writestr('Game/Content/Paks/pakchunk0-Android_ETC2.pak', pakbytes)
    open(os.path.join(HERE, 'unreal_fixture.obb'), 'wb').write(buf.getvalue())
    with open(os.path.join(HERE, 'expected.tsv'), 'w') as f:
        for e in expected: f.write('\t'.join(str(x) for x in e) + '\n')
    print('wrote', len(buf.getvalue()), 'byte OBB with', len(files), 'files,', len(expected), 'textures')
    if len(sys.argv) > 1:   # independent check: repak must read every file back
        p = os.path.join(HERE, '_check.pak'); open(p, 'wb').write(pakbytes)
        try: print(subprocess.run([sys.argv[1], p, '/dev/null'], capture_output=True, text=True, check=True).stdout.strip())
        finally: os.remove(p)

if __name__ == '__main__':
    main()
