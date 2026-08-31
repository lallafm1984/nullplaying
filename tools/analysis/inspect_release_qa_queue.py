#!/usr/bin/env python3
"""Inspect only the disposable QA AVD; never print identifiers or auth material."""
import argparse
from collections import Counter
import json
import subprocess
import xml.etree.ElementTree as ET
from run_stat_bonus_probe import ROOT

ADB = '/Users/lim/Library/Android/sdk/platform-tools/adb'
SERIAL = 'emulator-5556'
OUT = ROOT / 'docs/audits/2026-09-01-preproduction-qa'


def adb(*args):
    return subprocess.check_output([ADB, '-s', SERIAL, *args], text=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('label', choices=['before-upgrade', 'after-upgrade', 'after-home', 'after-exit'])
    args = parser.parse_args()
    assert adb('emu', 'avd', 'name').splitlines()[0] == 'np-release-qa-20260901-y5rXXx'
    assert not adb('shell', 'ip', 'route').strip(), 'QA networking must remain disconnected'
    assert adb('shell', 'settings', 'get', 'global', 'airplane_mode_on').strip() == '1'
    contents = adb('shell', 'cat', '/data/user/0/com.nullplaying/shared_prefs/supabase_game.xml')
    prefs = {node.get('name'): node.text for node in ET.fromstring(contents)}
    assert 'auth_session' not in prefs, 'An isolated test must never obtain a remote session'
    logs = json.loads(prefs.get('pending_session_logs', '[]'))
    assert len(logs) <= 100
    ids = [log['eventId'] for log in logs]
    assert len(ids) == len(set(ids)), 'Duplicate queued event IDs'
    receipt = {'label': args.label, 'emulator': SERIAL, 'network_routes': [],
               'airplane_mode_on': True, 'remote_auth_session_present': False,
               'queued_session_logs': len(logs), 'unique_event_ids': True,
               'queued_reasons': dict(Counter(log['reason'] for log in logs)),
               'pending_ranking_present': 'pending_ranking_sync' in prefs,
               'pending_ranking_entry_count': len(json.loads(prefs.get('pending_ranking_sync', '[]')))}
    (OUT / f'queue-{args.label}.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
