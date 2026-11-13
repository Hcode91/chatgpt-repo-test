#!/usr/bin/env python3
"""Builds the Ren'Py archive fixtures used by tests/RenpyArchiveTest.java (no game data).

Indexes are written with Python's own pickle and zlib modules, in the layouts Ren'Py's loader and unrpa read.
Writes the archives plus expected.txt: one line per file, "<archive> <path> <size> <crc32>".
"""
import pickle, random, struct, zlib

random.seed(7)
def blob(n): return bytes(random.randrange(256) for _ in range(n))
FILES = {
    'images/bg room.png': b'\x89PNG\r\n\x1a\n' + blob(700),
    'audio/theme.ogg': b'OggS' + blob(1200),
    'script.rpyc': b'RENPY RPC2' + blob(300),
    'fonts/été.ttf': blob(90),          # non-ASCII name
    'empty.txt': b'',
}
expected = []

def rpa3(name, header, key, protocol, xor_key_in_header=None, alt=False):
    body = bytearray(b'\0' * 34)
    index = {}
    for path, data in FILES.items():
        body += b"Made with Ren'Py."
        index[path] = [(len(body) ^ key, len(data) ^ key, b'' if protocol >= 3 else '')]
        body += data
    off = len(body)
    body += zlib.compress(pickle.dumps(index, protocol))
    if alt:
        line = b'%s %08x %016x\n' % (header, key ^ 0xDABE8DF0, off)
    else:
        line = b'%s %016x %08x\n' % (header, off, key)
    body[:34] = line.ljust(34, b'\0')[:34] if len(line) <= 34 else line
    if len(line) > 34: raise SystemExit('header too long')
    open(name, 'wb').write(bytes(body))
    for path, data in FILES.items(): expected.append('%s %s %d %08x' % (name, path.replace(' ', '%20'), len(data), zlib.crc32(data)))

rpa3('v3-proto2.rpa', b'RPA-3.0', 0x42424242, 2)
rpa3('v3-proto4.rpa', b'RPA-3.0', 0x1A2B3C4D, 4)
rpa3('v3-proto5.rpa', b'RPA-3.0', 0x7FFFFFFF, 5)
rpa3('v32.rpa', b'RPA-3.2', 0x0BADF00D, 2)
rpa3('v40.rpa', b'RPA-4.0', 0xDEADBEEF, 4)
rpa3('alt.rpa', b'ALT-1.0', 0x13572468, 2, alt=True)

# RPA-2.0, protocol 0 (text opcodes), no key
body = bytearray(b'\0' * 25); index = {}
for path, data in FILES.items():
    index[path] = [(len(body), len(data))]; body += data
off = len(body); body += zlib.compress(pickle.dumps(index, 0))
body[:25] = (b'RPA-2.0 %016x\n' % off)
open('v2-proto0.rpa', 'wb').write(bytes(body))
for path, data in FILES.items(): expected.append('v2-proto0.rpa %s %d %08x' % (path.replace(' ', '%20'), len(data), zlib.crc32(data)))

# RPA-1.0: separate index (.rpi) and data (.rpa)
data_file = bytearray(); index = {}
for path, data in FILES.items():
    index[path] = [(len(data_file), len(data))]; data_file += data
open('v1.rpa', 'wb').write(bytes(data_file))
open('v1.rpi', 'wb').write(zlib.compress(pickle.dumps(index, 2)))
for path, data in FILES.items(): expected.append('v1.rpi %s %d %08x' % (path.replace(' ', '%20'), len(data), zlib.crc32(data)))

# Ren'Py-loader specifics: a file in two pieces, and a piece with a prefix (prefix + length bytes from the archive)
key = 0x42424242
body = bytearray(b'\0' * 34)
part1, part2, prefix = b'first half|', b'second half', b'PRE:'
o1 = len(body); body += part1
o2 = len(body); body += part2
o3 = len(body); body += b'tail data'
index = {'joined.txt': [(o1 ^ key, len(part1) ^ key), (o2 ^ key, len(part2) ^ key)],
         'prefixed.bin': [(o3 ^ key, len(b'tail data') ^ key, prefix)]}
off = len(body); body += zlib.compress(pickle.dumps(index, 2))
body[:34] = b'RPA-3.0 %016x %08x\n' % (off, key)
open('pieces.rpa', 'wb').write(bytes(body))
expected.append('pieces.rpa joined.txt %d %08x' % (len(part1 + part2), zlib.crc32(part1 + part2)))
expected.append('pieces.rpa prefixed.bin %d %08x' % (len(prefix) + 9, zlib.crc32(prefix + b'tail data')))

# Python 2-style index (what Ren'Py 6/7 wrote): str keys as SHORT_BINSTRING, ints as BININT/LONG1, memo puts
def py2_pickle(entries):
    out = bytearray(b'\x80\x02}q\x00(')
    memo = 1
    for path, (o, l) in entries:
        p = path.encode('utf-8')
        out += b'U' + bytes([len(p)]) + p + b'q' + bytes([memo]); memo += 1
        out += b']q' + bytes([memo]); memo += 1
        out += b'(' if False else b''
        out += b'J' + struct.pack('<i', o) if o < 2**31 else b'\x8a\x05' + o.to_bytes(5, 'little')
        out += b'J' + struct.pack('<i', l)
        out += b'U\x00\x87q' + bytes([memo]) + b'a'; memo += 1
    return bytes(out + b'u.')
body = bytearray(b'\0' * 34); entries = []
for path, data in [('legacy/a.txt', b'old archive'), ('legacy/b.bin', blob(64))]:
    body += b"Made with Ren'Py."
    entries.append((path, (len(body) ^ key, len(data) ^ key))); body += data
    expected.append('py2.rpa %s %d %08x' % (path, len(data), zlib.crc32(data)))
pk = py2_pickle(entries)
assert pickle.loads(pk, encoding='bytes')       # Python can read it
off = len(body); body += zlib.compress(pk)
body[:34] = b'RPA-3.0 %016x %08x\n' % (off, key)
open('py2.rpa', 'wb').write(bytes(body))

open('expected.txt', 'w').write('\n'.join(expected) + '\n')
print(len(expected), 'expectations')
