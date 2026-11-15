#!/usr/bin/env python3
"""Convert pinned RPCS3 GPL-2.0 key-vault definitions into scetool's INI keyset format.

Build-time only. The resulting file is packaged as an APK asset, never uploaded
from the device or checked into the repository. Only APP/NPDRM SELF and the
NPDRM klicensee constants required for executable decryption are exported.
RPCS3's source is GPL-2.0 and must be attributed when distributing this build.
"""
import argparse
from pathlib import Path
import re

ENTRY = re.compile(
    r'sk_(?P<kind>APP|NPDRM)_arr\.emplace_back\(\s*'
    r'0x(?P<start>[0-9A-Fa-f]+),\s*0x(?P<end>[0-9A-Fa-f]+),\s*'
    r'0x(?P<revision>[0-9A-Fa-f]+),\s*KEY_(?P=kind),\s*'
    r'"(?P<erk>[0-9A-Fa-f]*)",\s*"(?P<riv>[0-9A-Fa-f]*)",\s*'
    r'"(?P<pub>[0-9A-Fa-f]*)",\s*"(?P<priv>[0-9A-Fa-f]*)",\s*'
    r'0x(?P<ctype>[0-9A-Fa-f]+)\s*\);'
)

def constant(header: str, name: str) -> str:
    match = re.search(
        r'constexpr\s+u8\s+' + re.escape(name)
        + r'\[0x10\]\s*=\s*\{(?P<body>[^}]+)\}', header
    )
    if not match:
        raise ValueError(f'RPCS3 key constant missing: {name}')
    values = re.findall(r'0x([0-9A-Fa-f]{1,2})', match['body'].split('//')[0])
    if len(values) != 16:
        raise ValueError(f'{name} did not have 16 expected bytes')
    return ''.join(v.zfill(2).upper() for v in values)


def produce(cpp: str, hdr: str) -> str:
    entries = list(ENTRY.finditer(cpp))
    np = [x for x in entries if x['kind'] == 'NPDRM']
    app = [x for x in entries if x['kind'] == 'APP']
    if len(np) < 16 or len(app) < 28:
        raise ValueError(f'RPCS3 key-vault layout changed: APP={len(app)}, NPDRM={len(np)}')
    seen = set()
    lines = [
        '# Build-time translation of GPLv2 RPCS3 key_vault.{cpp,h}',
        '# Source: https://github.com/RPCS3/rpcs3',
        '# For scetool SELF/NPDRM decryption only. No private user licence data.',
        ''
    ]
    for x in entries:
        kind = x['kind']
        revision = x['revision'].upper().zfill(4)
        token = (kind, revision)
        if token in seen:
            raise ValueError(f'Duplicate {kind} key revision {revision}')
        seen.add(token)
        if len(x['erk']) != 64 or len(x['riv']) != 32:
            raise ValueError('Malformed 32-byte ERK or 16-byte RIV')
        fields = [
            f'[{kind}_{revision}]',
            'type=SELF',
            f'revision={revision}',
            f'version={x["end"].upper().zfill(16)}',
            f'self_type={kind}',
            f'erk={x["erk"].upper()}',
            f'riv={x["riv"].upper()}',
        ]
        if x['pub']:
            fields.append('pub=' + x['pub'].upper())
        if x['priv']:
            fields.append('priv=' + x['priv'].upper())
        fields.append('ctype=' + x['ctype'].upper().zfill(2))
        lines.extend(fields + [''])
    for label, name in [('NP_klic_key', 'NP_KLIC_KEY'), ('NP_klic_free', 'NP_KLIC_FREE')]:
        lines.extend([f'[{label}]', 'type=OTHER', f'key={constant(hdr, name)}', ''])
    # Check expected revision used by the user's inFAMOUS US PSN SELF.
    if ('NPDRM', '0001') not in seen:
        raise ValueError('Required NPDRM revision 0001 missing')
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cpp', type=Path, required=True)
    parser.add_argument('--header', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    data = produce(args.cpp.read_text(encoding='utf-8'), args.header.read_text(encoding='utf-8'))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(data, encoding='ascii')
    print('Generated scetool-compatible APP and NPDRM keyset asset from pinned RPCS3 GPL source.')
    # Never print key material in CI logs.

if __name__ == '__main__':
    main()
