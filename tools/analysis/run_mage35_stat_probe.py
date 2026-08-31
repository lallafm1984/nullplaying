#!/usr/bin/env python3
"""Mage-only sensitivity run; never launch the app or open a database."""
import hashlib
import json
import shutil
import run_stat_bonus_probe as probe

if __name__ == '__main__':
    previous = probe.OUT / 'cleric-wis-cha'
    for name, digest in json.loads((previous / 'engine_source_sha256.json').read_text()).items():
        if name.startswith('app/'):
            assert hashlib.sha256((probe.ROOT / name).read_bytes()).hexdigest() == digest, name
    probe.OUT = probe.OUT / 'mage-35-review'
    probe.OUT.mkdir(exist_ok=True)
    shutil.copy2(previous / 'offline-only.sb', probe.OUT / 'offline-only.sb')
    # The generic harness accepts only MAGE here, so mp_pp=15 cannot affect
    # another class. Other-class paths remain the verified prior baseline.
    import sys
    assert '--classes' in sys.argv and sys.argv[-2:] == ['--classes', 'MAGE']
    probe.main()
