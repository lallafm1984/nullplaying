#!/usr/bin/env python3
"""Read-only Android artifact/test validation; writes only a local audit receipt."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/audits/2026-09-01-preproduction-qa'


def elf_headers(data):
    assert data[:4] == b'\x7fELF'
    endian = '<' if data[5] == 1 else '>'
    if data[4] == 2:
        offset = struct.unpack_from(endian + 'Q', data, 32)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 54)
        headers = [struct.unpack_from(endian + 'IIQQQQQQ', data, offset + i * size)
                   for i in range(count)]
        loads = [{'offset': h[2], 'virtual_address': h[3], 'alignment': h[7]}
                 for h in headers if h[0] == 1]
    else:
        assert data[4] == 1
        offset = struct.unpack_from(endian + 'I', data, 28)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 42)
        headers = [struct.unpack_from(endian + 'IIIIIIII', data, offset + i * size)
                   for i in range(count)]
        loads = [{'offset': h[1], 'virtual_address': h[2], 'alignment': h[7]}
                 for h in headers if h[0] == 1]
    assert loads
    return loads, any(h[0] == 0x6474E552 for h in headers)


def inspect_artifact(path):
    native = []
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, 'ZIP CRC failed'
        for name in archive.namelist():
            if not name.endswith('.so'):
                continue
            abi = name.split('/')[-2]
            loads, relro = elf_headers(archive.read(name))
            is_64_bit = abi in ('arm64-v8a', 'x86_64')
            compatible = all(h['alignment'] >= 16384 and
                             (h['virtual_address'] - h['offset']) % 16384 == 0
                             for h in loads) if is_64_bit else None
            assert not is_64_bit or compatible, (name, loads)
            assert relro, f'No GNU_RELRO: {name}'
            native.append({'file': name, 'abi': abi, 'load_segments': loads,
                           'gnu_relro': relro, '64_bit_16kb_compatible': compatible})
    return {'file': str(path.relative_to(ROOT)), 'bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'zip_crc': 'PASS', 'native_libraries': native}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('artifacts', nargs='+', type=Path)
    parser.add_argument('--receipt', default='artifact-qa.json')
    args = parser.parse_args()
    assert Path(args.receipt).name == args.receipt
    receipt = {'artifacts': [inspect_artifact(p.resolve()) for p in args.artifacts]}
    receipt['unit_tests'] = {}
    for variant in ('Debug', 'Release', 'MigrationTest'):
        reports = sorted((ROOT / f'app/build/test-results/test{variant}UnitTest').glob('TEST-*.xml'))
        assert reports, variant
        totals = Counter()
        for report in reports:
            suite = ET.parse(report).getroot()
            for key in ('tests', 'failures', 'errors', 'skipped'):
                totals[key] += int(suite.get(key, 0))
        assert totals['failures'] == totals['errors'] == totals['skipped'] == 0, totals
        receipt['unit_tests'][variant] = dict(totals)
    receipt['lint'] = {}
    for variant in ('debug', 'release'):
        issues = ET.parse(ROOT / f'app/build/reports/lint-results-{variant}.xml').getroot()
        counts = Counter(i.get('severity') for i in issues.findall('issue'))
        assert counts['Error'] == counts['Fatal'] == 0, counts
        receipt['lint'][variant] = {'severity_counts': dict(counts),
            'issue_counts': dict(Counter(i.get('id') for i in issues.findall('issue')))}
    OUT.mkdir(parents=True, exist_ok=True)
    destination = OUT / args.receipt
    destination.write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({**receipt, 'artifacts': [{k: v for k, v in artifact.items()
          if k != 'native_libraries'} | {'native_library_count': len(artifact['native_libraries'])}
          for artifact in receipt['artifacts']]}, indent=2))
    print(destination)


if __name__ == '__main__':
    main()
