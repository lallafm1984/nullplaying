#!/usr/bin/env python3
"""Fingerprint current source/config inputs, without copying credentials or save data."""
import hashlib
import json
from pathlib import Path
import subprocess
from run_stat_bonus_probe import ROOT

OUT = ROOT / 'docs/audits/2026-09-01-preproduction-qa'
SCOPES = ['app/src', 'app/schemas', 'app/build.gradle.kts', 'app/proguard-rules.pro',
          'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
          'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties',
          'tools/build_android_release.zsh']


def main():
    listed = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard',
                                      '-z', '--', *SCOPES], cwd=ROOT).decode().split('\0')
    files = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
             for name in sorted(set(listed)) if name and (ROOT / name).is_file()}
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    receipt = {'git_head': head, 'source': 'current dirty worktree; existing changes preserved',
               'version_name': '0.4.1', 'version_code': 14, 'files': files,
               'excluded': 'keystore/password, local.properties, environment, local databases, generated build files'}
    destination = OUT / 'source-sha256.json'
    if destination.exists():
        assert json.loads(destination.read_text()) == receipt, 'Source changed during final QA'
        print('Source receipt unchanged:', len(files), 'files')
    else:
        destination.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
        print('Recorded source receipt:', len(files), 'files')


if __name__ == '__main__':
    main()
