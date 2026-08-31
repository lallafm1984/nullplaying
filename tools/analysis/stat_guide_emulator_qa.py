#!/usr/bin/env python3
"""UI-only QA against the explicitly selected, network-isolated emulator."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from run_stat_bonus_probe import ROOT

ADB = '/Users/lim/Library/Android/sdk/platform-tools/adb'
SERIAL = 'emulator-5554'
OUT = ROOT / 'docs/audits/2026-09-01-stat-guide/screenshots'


def adb(*args):
    return subprocess.check_output([ADB, '-s', SERIAL, *args], text=True, stderr=subprocess.STDOUT)


def ui():
    for attempt in range(3):
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/stat-guide-ui.xml')
        if 'dumped to:' in result:
            return adb('shell', 'cat', '/sdcard/stat-guide-ui.xml')
        if attempt < 2:
            time.sleep(0.5)
    raise AssertionError('No stable UI hierarchy; never reuse a previous dump')


def describe(xml):
    for node in ET.fromstring(xml).iter('node'):
        label = node.get('text') or node.get('content-desc')
        if label:
            print(f"{node.get('bounds')} {label}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['inspect', 'tap', 'capture'])
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    xml = ui()
    if args.action == 'inspect':
        describe(xml)
    elif args.action == 'tap':
        assert args.value
        for attempt in range(3):
            matches = [n for n in ET.fromstring(xml).iter('node') if
                n.get('text') == args.value or n.get('content-desc') == args.value]
            if matches or attempt == 2:
                break
            # A cold launch can still be completing local-save recovery after Activity start.
            # Re-read a fresh hierarchy; never guess a target or use stale coordinates.
            time.sleep(0.5)
            xml = ui()
        assert len(matches) == 1, f'Expected one visible target, found {len(matches)}: {args.value}'
        bounds = list(map(int, re.findall(r'\d+', matches[0].get('bounds'))))
        x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
        print(f'Tap {args.value}: {x},{y}')
        adb('shell', 'input', 'tap', str(x), str(y))
    else:
        assert args.value and re.fullmatch(r'[a-z0-9-]+', args.value)
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / f'{args.value}.xml').write_text(xml)
        adb('shell', 'screencap', '-p', '/sdcard/stat-guide-current.png')
        subprocess.run([ADB, '-s', SERIAL, 'pull', '/sdcard/stat-guide-current.png',
            str(OUT / f'{args.value}.png')], check=True)
        describe(xml)
        print(OUT / f'{args.value}.png')


if __name__ == '__main__':
    main()
