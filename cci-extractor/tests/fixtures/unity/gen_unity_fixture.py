#!/usr/bin/env python3
"""Builds the synthetic Unity test fixtures used by UnityTest.java. No game data involved.

  etc_vectors.bin   random ETC1 / ETC2 RGB / ETC2 RGBA8 blocks with their decoded pixels from texture2ddecoder
                    (an independent decoder). Random blocks hit every ETC2 mode: individual, differential, T, H, planar.
  unity_fixture.zip an OBB-style stored zip holding:
                      assets/Bundles/a.bytes      UnityFS v8, LZ4HC in 100-byte blocks (so patches cross blocks),
                                                  type trees, textures inline + streamed from a .resS, and a
                                                  2-part mesh (two vertex streams, half-float UVs, baseVertex) drawn
                                                  by a SkinnedMeshRenderer with two materials, one of which points
                                                  at a texture in data.unity3d (a reference into another bundle)
                      assets/bin/Data/data.unity3d  type trees stripped (like a real player build), uncompressed
                                                  blocks, block table at the end of the file
                      unity_obb_guid, a stored text file and a deflated text file
  expected.tsv      name, format, width, height, mips, streamed, sha1 of the decoded top-down pixels
  expected_mesh.txt the mesh as UnityPy decodes it (positions, UVs, triangles per part) and its materials
  type_trees_2021_3.json  Unity 2021.3's Mesh / Material / SkinnedMeshRenderer type-tree layouts

The Texture2D type-tree layout is Unity 2021.3's. The generator checks its own output with UnityPy (a third,
independent reader) before writing anything.

  pip install UnityPy etcpak texture2ddecoder lz4 numpy Pillow
  python3 gen_unity_fixture.py
"""
import hashlib, io, os, random, struct, zipfile

import etcpak, lz4.block, texture2ddecoder
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
rng = random.Random(1234)

# --------------------------------------------------------------------------- ETC vectors
def bgra_to_rgba(b):
    out = bytearray(len(b))
    out[0::4], out[1::4], out[2::4], out[3::4] = b[2::4], b[1::4], b[0::4], b[3::4]
    return bytes(out)

def etc_vectors(n=1200):
    out = io.BytesIO()
    out.write(b'ETCV')
    for kind, size, fn in ((1, 8, texture2ddecoder.decode_etc1), (2, 8, texture2ddecoder.decode_etc2),
                           (3, 16, texture2ddecoder.decode_etc2a8)):
        out.write(struct.pack('<BI', kind, n))
        for _ in range(n):
            blk = bytes(rng.getrandbits(8) for _ in range(size))
            out.write(blk)
            out.write(bgra_to_rgba(fn(blk, 4, 4)))  # 4x4 RGBA, row 0 = first block row
    return out.getvalue()

# --------------------------------------------------------------------------- Unity common strings (subset)
COMMON = {}
def _load_common():
    from UnityPy.helpers.Tpk import get_common_strings
    for off, s in get_common_strings().items():
        COMMON[s] = off

# Texture2D type tree, Unity 2021.3: (level, type, name, byteSize, metaFlag, typeFlags, version)
TEX2D_TREE = [
    (0, 'Texture2D', 'Base', -1, 32768, 0, 2),
    (1, 'string', 'm_Name', -1, 557057, 0, 1), (2, 'Array', 'Array', -1, 540673, 1, 1),
    (3, 'int', 'size', 4, 524289, 0, 1), (3, 'char', 'data', 1, 524289, 0, 1),
    (1, 'int', 'm_ForcedFallbackFormat', 4, 0, 0, 1), (1, 'bool', 'm_DownscaleFallback', 1, 0, 0, 1),
    (1, 'bool', 'm_IsAlphaChannelOptional', 1, 16384, 0, 1), (1, 'int', 'm_Width', 4, 16, 0, 1),
    (1, 'int', 'm_Height', 4, 16, 0, 1), (1, 'unsigned int', 'm_CompleteImageSize', 4, 16, 0, 1),
    (1, 'int', 'm_MipsStripped', 4, 16, 0, 1), (1, 'int', 'm_TextureFormat', 4, 1, 0, 1),
    (1, 'int', 'm_MipCount', 4, 16, 0, 1), (1, 'bool', 'm_IsReadable', 1, 0, 0, 1),
    (1, 'bool', 'm_IsPreProcessed', 1, 1, 0, 1), (1, 'bool', 'm_IgnoreMasterTextureLimit', 1, 0, 0, 1),
    (1, 'bool', 'm_StreamingMipmaps', 1, 16384, 0, 1), (1, 'int', 'm_StreamingMipmapsPriority', 4, 16384, 0, 1),
    (1, 'int', 'm_ImageCount', 4, 16, 0, 1), (1, 'int', 'm_TextureDimension', 4, 1, 0, 1),
    (1, 'GLTextureSettings', 'm_TextureSettings', 24, 0, 0, 2), (2, 'int', 'm_FilterMode', 4, 0, 0, 1),
    (2, 'int', 'm_Aniso', 4, 0, 0, 1), (2, 'float', 'm_MipBias', 4, 0, 0, 1), (2, 'int', 'm_WrapU', 4, 0, 0, 1),
    (2, 'int', 'm_WrapV', 4, 0, 0, 1), (2, 'int', 'm_WrapW', 4, 0, 0, 1),
    (1, 'int', 'm_LightmapFormat', 4, 0, 0, 1), (1, 'int', 'm_ColorSpace', 4, 0, 0, 1),
    (1, 'vector', 'm_PlatformBlob', -1, 32768, 0, 1), (2, 'Array', 'Array', -1, 16384, 1, 1),
    (3, 'int', 'size', 4, 0, 0, 1), (3, 'UInt8', 'data', 1, 0, 0, 1),
    (1, 'TypelessData', 'image data', -1, 16385, 1, 1), (2, 'int', 'size', 4, 1, 0, 1), (2, 'UInt8', 'data', 1, 1, 0, 1),
    (1, 'StreamingInfo', 'm_StreamData', -1, 32768, 0, 2), (2, 'UInt64', 'offset', 8, 0, 0, 1),
    (2, 'unsigned int', 'size', 4, 0, 0, 1), (2, 'string', 'path', -1, 32768, 0, 1),
    (3, 'Array', 'Array', -1, 16385, 1, 1), (4, 'int', 'size', 4, 1, 0, 1), (4, 'char', 'data', 1, 1, 0, 1),
]

def type_tree_blob(tree=None):
    tree = tree or TEX2D_TREE
    strings = bytearray(); local = {}
    def off(s):
        if s in COMMON: return 0x80000000 | COMMON[s]
        if s not in local:
            local[s] = len(strings); strings.extend(s.encode() + b'\0')
        return local[s]
    nodes = b''
    for i, (lvl, typ, name, size, meta, tflags, ver) in enumerate(tree):
        nodes += struct.pack('<HBBIIiiIQ', ver, lvl, tflags, off(typ), off(name), size, i, meta, 0)
    return struct.pack('<ii', len(tree), len(strings)) + nodes + bytes(strings)

# --------------------------------------------------------------------------- generic type-tree serializer
class Node:
    def __init__(self, t):
        self.level, self.type, self.name, self.size, self.meta, self.flags, self.ver = t
        self.kids = []

def build_tree(rows):
    nodes = [Node(r) for r in rows]; stack = [nodes[0]]
    for n in nodes[1:]:
        while len(stack) > n.level: stack.pop()
        stack[-1].kids.append(n); stack.append(n)
    return nodes[0]

PRIM = {'bool': '<B', 'char': '<B', 'UInt8': '<B', 'SInt8': '<b', 'SInt16': '<h', 'UInt16': '<H', 'short': '<h',
        'unsigned short': '<H', 'int': '<i', 'SInt32': '<i', 'unsigned int': '<I', 'UInt32': '<I', 'float': '<f',
        'SInt64': '<q', 'UInt64': '<Q', 'long long': '<q', 'unsigned long long': '<Q', 'double': '<d', 'Type*': '<i'}

def write_value(n, v, out):
    align = bool(n.meta & 0x4000)
    if n.type == 'string':
        b = (v or '').encode(); out += struct.pack('<i', len(b)) + b
        if n.kids and n.kids[0].meta & 0x4000: align = True
    elif n.type == 'TypelessData':
        b = v or b''; out += struct.pack('<i', len(b)) + b
    elif n.kids and n.kids[0].type == 'Array':
        arr = n.kids[0]; elem = arr.kids[1]
        if arr.meta & 0x4000: align = True
        items = v or []
        if isinstance(items, (bytes, bytearray)): items = list(items)
        out += struct.pack('<i', len(items))
        for it in items: write_value(elem, it, out)
    elif not n.kids:
        out += struct.pack(PRIM[n.type], v or 0)
    else:
        v = v or {}
        for k in n.kids: write_value(k, v.get(k.name), out)
    if align:
        while len(out) % 4: out.append(0)

def serialize(rows, value):
    root = build_tree(rows); out = bytearray()
    for k in root.kids: write_value(k, value.get(k.name), out)
    if root.meta & 0x4000:
        while len(out) % 4: out.append(0)
    return bytes(out)

def align(b, n):
    return b + b'\0' * ((-len(b)) % n)

def tex2d_object(name, fmt, w, h, mips, inline, stream):
    o = struct.pack('<i', len(name)) + name.encode(); o = align(o, 4)
    o += struct.pack('<iBB', 4, 0, 0); o = align(o, 4)
    size = len(inline) if inline else stream[1]
    o += struct.pack('<iiIiii', w, h, size, 0, fmt, mips)
    o += bytes([0, 0, 0, 0]); o = align(o, 4)
    o += struct.pack('<iii', 0, 1, 2)
    o += struct.pack('<iifiii', 1, 1, 0.0, 1, 1, 1)
    o += struct.pack('<ii', 6, 1)
    o += struct.pack('<i', 0)  # platform blob
    o += struct.pack('<i', len(inline)) + inline; o = align(o, 4)
    if stream:
        path = stream[2].encode()
        o += struct.pack('<QI', stream[0], stream[1]) + struct.pack('<i', len(path)) + path; o = align(o, 4)
    else:
        o += struct.pack('<QIi', 0, 0, 0)
    return o

def serialized_file(objects, typetree=True, version_str='2021.3.21f1', types=None, externals=()):
    """objects: list of (path_id, bytes) or (path_id, type_index, bytes). types: list of (class_id, tree nodes).
    Returns a SerializedFile v22."""
    if types is None: types = [(28, TEX2D_TREE)]
    meta = version_str.encode() + b'\0' + struct.pack('<i', 13) + bytes([1 if typetree else 0])
    meta += struct.pack('<i', len(types))
    for cid, tree in types:
        meta += struct.pack('<iBh', cid, 0, -1) + b'\x11' * 16
        if typetree:
            meta += type_tree_blob(tree) + struct.pack('<i', 0)
    objects = [o if len(o) == 3 else (o[0], 0, o[1]) for o in objects]
    meta += struct.pack('<i', len(objects))
    head_len = 48
    data = b''; table = b''
    starts = []
    for pid, ti, ob in objects:
        data = align(data, 8); starts.append(len(data)); data += ob
    for (pid, ti, ob), st in zip(objects, starts):
        while (head_len + len(meta) + len(table)) % 4: table += b'\0'
        table += struct.pack('<qqIi', pid, st, len(ob), ti)
    ext = struct.pack('<i', len(externals))
    for e in externals:
        ext += b'\0' + b'\0' * 16 + struct.pack('<i', 0) + e.encode() + b'\0'
    meta += table + struct.pack('<i', 0) + ext + struct.pack('<i', 0) + b'\0'
    data_offset = head_len + len(meta); data_offset += (-data_offset) % 16
    file_size = data_offset + len(data)
    hdr = struct.pack('>IIII', 0, 0, 22, 0) + bytes([0, 0, 0, 0]) + struct.pack('>IqqQ', len(meta), file_size, data_offset, 0)
    return hdr + meta + b'\0' * (data_offset - head_len - len(meta)) + data

def unityfs(files, block=100, compressed=True, info_at_end=False):
    """files: list of (path, bytes). UnityFS v8."""
    stream = b''.join(d for _, d in files)
    blocks = []
    for i in range(0, len(stream), block):
        raw = stream[i:i + block]
        if compressed:
            c = lz4.block.compress(raw, mode='high_compression', store_size=False)
            blocks.append((len(raw), c, 3) if len(c) < len(raw) else (len(raw), raw, 0))
        else:
            blocks.append((len(raw), raw, 0))
    info = b'\0' * 16 + struct.pack('>i', len(blocks))
    for u, c, f in blocks: info += struct.pack('>IIH', u, len(c), f)
    info += struct.pack('>i', len(files)); off = 0
    for path, d in files:
        info += struct.pack('>qqI', off, len(d), 4 if path.endswith('.resS') else 0) + path.encode() + b'\0'; off += len(d)
    cinfo = lz4.block.compress(info, mode='high_compression', store_size=False) if compressed else info
    flags = (2 if compressed else 0) | 0x40 | (0x80 if info_at_end else 0) | 0x200
    def build(total):
        h = b'UnityFS\0' + struct.pack('>I', 8) + b'5.x.x\0' + b'2021.3.21f1\0' + struct.pack('>qIII', total, len(cinfo), len(info), flags)
        h = align(h, 16)
        body = b''.join(c for _, c, _ in blocks)
        if info_at_end:
            return h + body + cinfo
        return align(h + cinfo, 16) + body
    first = build(0)
    return build(len(first))

# --------------------------------------------------------------------------- textures
def pattern(w, h, seed, alpha=True):
    r = random.Random(seed); img = Image.new('RGBA', (w, h))
    for y in range(h):
        for x in range(w):
            img.putpixel((x, y), ((x * 255) // max(1, w - 1), (y * 255) // max(1, h - 1), r.randrange(256),
                                  (r.randrange(256) if alpha else 255)))
    return img

def encode(img, fmt, mips):
    """Unity layout: rows bottom-up, mip chain appended."""
    out = b''; w, h = img.size
    for l in range(mips):
        lw, lh = max(1, w >> l), max(1, h >> l)
        li = (img if l == 0 else img.resize((lw, lh), Image.BOX)).transpose(Image.FLIP_TOP_BOTTOM)
        if fmt == 4: out += li.tobytes('raw', 'RGBA')
        elif fmt == 3: out += li.convert('RGB').tobytes()
        elif fmt == 1: out += li.getchannel('A').tobytes()
        elif fmt == 47:
            pw, ph = (lw + 3) // 4 * 4, (lh + 3) // 4 * 4
            pad = Image.new('RGBA', (pw, ph)); pad.paste(li, (0, 0))
            out += etcpak.compress_etc2_rgba(pad.tobytes('raw', 'RGBA'), pw, ph)
        else: raise ValueError(fmt)
    return out

def decoded_sha(data, fmt, w, h):
    if fmt == 47:
        pw, ph = (w + 3) // 4 * 4, (h + 3) // 4 * 4
        img = Image.frombytes('RGBA', (pw, ph), bgra_to_rgba(texture2ddecoder.decode_etc2a8(data, pw, ph))).crop((0, 0, w, h))
    elif fmt == 4: img = Image.frombytes('RGBA', (w, h), data[:w * h * 4])
    elif fmt == 3: img = Image.frombytes('RGB', (w, h), data[:w * h * 3]).convert('RGBA')
    elif fmt == 1: img = Image.frombytes('L', (w, h), data[:w * h])
    img = img.transpose(Image.FLIP_TOP_BOTTOM)
    return hashlib.sha1(img.tobytes()).hexdigest()

def main():
    _load_common()
    open(os.path.join(HERE, 'etc_vectors.bin'), 'wb').write(etc_vectors())

    expected = []
    # bundle a: type trees; RGBA32 inline, ETC2_RGBA8 streamed with mips, Alpha8 inline
    texs = [('Inline', 4, 8, 8, 1, False, 11), ('Streamed', 47, 22, 10, 5, True, 22), ('Mask', 1, 6, 5, 1, False, 33)]
    objects, ress = [], b''
    cab = 'CAB-0123456789abcdef0123456789abcdef'
    for name, fmt, w, h, mips, streamed, pid in texs:
        data = encode(pattern(w, h, pid), fmt, mips)
        if streamed:
            ress = align(ress, 16); st = (len(ress), len(data), 'archive:/%s/%s.resS' % (cab, cab)); ress += data
            objects.append((pid, tex2d_object(name, fmt, w, h, mips, b'', st)))
        else:
            objects.append((pid, tex2d_object(name, fmt, w, h, mips, data, None)))
        expected.append(('assets/Bundles/a.bytes', pid, name, fmt, w, h, mips, int(streamed), decoded_sha(data, fmt, w, h)))
    # a 2-part mesh: quad (part 1) + triangle using baseVertex (part 2); stream 0 = position + normal (float),
    # stream 1 = UV0 as half floats; drawn by a SkinnedMeshRenderer with two materials
    import json, numpy as np
    trees = json.load(open(os.path.join(HERE, 'type_trees_2021_3.json')))
    pos = [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0), (2, 0.5, 0.25)]
    uvs = [(0, 0), (1, 0), (1, 1), (0, 1), (0.5, 0.75)]
    s0 = b''.join(struct.pack('<6f', *p, 0, 0, 1) for p in pos)
    s1 = b''.join(np.array(u, dtype='<f2').tobytes() for u in uvs)
    vdata = align(s0, 16) + s1
    chans = [dict(stream=0, offset=0, format=0, dimension=3), dict(stream=0, offset=12, format=0, dimension=3)] + \
            [dict()] * 2 + [dict(stream=1, offset=0, format=1, dimension=2)] + [dict()] * 9
    idx = struct.pack('<6H', 0, 1, 2, 0, 2, 3) + struct.pack('<3H', 0, 1, 2)
    mesh = serialize(trees['43'], {
        'm_Name': 'TestQuad', 'm_IndexFormat': 0, 'm_IndexBuffer': list(idx),
        'm_SubMeshes': [dict(firstByte=0, indexCount=6, topology=0, baseVertex=0, firstVertex=0, vertexCount=4),
                        dict(firstByte=12, indexCount=3, topology=0, baseVertex=2, firstVertex=2, vertexCount=3)],
        'm_VertexData': {'m_VertexCount': len(pos), 'm_Channels': chans, 'm_DataSize': vdata}})
    texenv = lambda name, fid, pid: {'first': name, 'second': {'m_Texture': {'m_FileID': fid, 'm_PathID': pid}, 'm_Scale': {'x': 1, 'y': 1}}}
    mat1 = serialize(trees['21'], {'m_Name': 'QuadMat', 'm_SavedProperties': {'m_TexEnvs': [
        texenv('_BumpMap', 0, 33), texenv('_MainTex', 0, 11)]}})
    mat2 = serialize(trees['21'], {'m_Name': 'TriMat', 'm_SavedProperties': {'m_TexEnvs': [texenv('_MainTex', 1, 10)]}})
    smr = serialize(trees['137'], {'m_Mesh': {'m_FileID': 0, 'm_PathID': 50},
                                   'm_Materials': [{'m_FileID': 0, 'm_PathID': 60}, {'m_FileID': 0, 'm_PathID': 61}]})
    objects = [(pid, 0, ob) for pid, ob in objects] + [(50, 1, mesh), (60, 2, mat1), (61, 2, mat2), (70, 3, smr)]
    types = [(28, TEX2D_TREE), (43, trees['43']), (21, trees['21']), (137, trees['137'])]
    sf_a = serialized_file(objects, types=types, externals=['archive:/data/resources.assets'])
    bundle_a = unityfs([(cab, sf_a), (cab + '.resS', ress)])

    # data.unity3d: stripped type trees, uncompressed, info at end; one streamed RGB24 with mips
    d = encode(pattern(12, 12, 44, alpha=False), 3, 3)
    st = (0, len(d), 'resources.assets.resS')
    sf = serialized_file([(10, tex2d_object('Builtin', 3, 12, 12, 3, b'', st))], typetree=False, version_str='2021.3.58f1')
    data_unity3d = unityfs([('resources.assets', sf), ('resources.assets.resS', d)], compressed=False, info_at_end=True)
    expected.append(('assets/bin/Data/data.unity3d', 10, 'Builtin', 3, 12, 12, 3, 1, decoded_sha(d, 3, 12, 12)))

    # independent check of the fixture with UnityPy (type trees from a.bytes are what UnityPy reads;
    # for the stripped file it uses its own database, which also exercises our layout claims)
    import UnityPy
    from UnityPy.helpers.MeshHelper import MeshHandler
    mesh_lines = []
    for blob, label in ((bundle_a, 'a.bytes'), (data_unity3d, 'data.unity3d')):
        env = UnityPy.load(blob)
        n = 0
        for o in env.objects:
            if o.type.name == 'Mesh':
                m = o.read(); h = MeshHandler(m); h.process()
                mesh_lines.append('mesh\t%s\t%d\t%d' % (m.m_Name, h.m_VertexCount, len(m.m_SubMeshes)))
                mesh_lines.append('pos\t' + ' '.join('%g' % x for v in h.m_Vertices for x in v[:3]))
                mesh_lines.append('uv\t' + ' '.join('%g' % x for v in h.m_UV0 for x in v[:2]))
                # UnityPy's get_triangles ignores SubMesh.baseVertex; Unity adds it to every index
                # (SubMeshDescriptor.baseVertex), so it's added here.
                for i, tri in enumerate(h.get_triangles()):
                    bv = m.m_SubMeshes[i].baseVertex or 0
                    mesh_lines.append('part\t%d\t' % i + ' '.join(str(x + bv) for t in tri for x in t))
                assert [tuple(round(x, 4) for x in v[:3]) for v in h.m_Vertices] == [tuple(float(x) for x in p) for p in pos]
                continue
            if o.type.name == 'Material':
                m = o.read()
                env_ = [(k if isinstance(k, str) else k.name, v.m_Texture.m_FileID, v.m_Texture.m_PathID) for k, v in m.m_SavedProperties.m_TexEnvs]
                mesh_lines.append('material\t%s\t%s' % (m.m_Name, env_))
                continue
            if o.type.name == 'SkinnedMeshRenderer':
                r = o.read()
                assert r.m_Mesh.m_PathID == 50 and [p.m_PathID for p in r.m_Materials] == [60, 61]
                continue
            t = o.read()
            exp = [e for e in expected if e[1] == o.path_id and e[0].endswith(label)][0]
            img = t.image
            got = (img.getchannel('A').tobytes() if t.m_TextureFormat == 1 else img.convert('RGBA').tobytes())
            assert hashlib.sha1(got).hexdigest() == exp[8], (label, t.m_Name)
            n += 1
        print('UnityPy reads', label, ':', n, 'texture(s) OK')

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('unity_obb_guid', b'00000000-1111-2222-3333-444444444444')
        z.writestr('assets/bin/Data/data.unity3d', data_unity3d)
        z.writestr('assets/Bundles/a.bytes', bundle_a)
        z.writestr('assets/notes.txt', b'stored text\n')
        z.writestr(zipfile.ZipInfo('assets/deflated.txt'), b'deflated text ' * 50, compress_type=zipfile.ZIP_DEFLATED)
    open(os.path.join(HERE, 'unity_fixture.zip'), 'wb').write(buf.getvalue())
    with open(os.path.join(HERE, 'expected_mesh.txt'), 'w') as f:
        f.write('\n'.join(mesh_lines) + '\n')
    with open(os.path.join(HERE, 'expected.tsv'), 'w') as f:
        for e in expected: f.write('\t'.join(str(x) for x in e) + '\n')
    print('wrote fixtures:', len(buf.getvalue()), 'byte zip,', len(expected), 'textures')

if __name__ == '__main__':
    main()
