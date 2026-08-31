#!/usr/bin/env python3
"""Run the changed class only, preserving the previous balance evidence."""
import shutil
import run_stat_bonus_probe as probe

if __name__=='__main__':
    previous=probe.OUT
    probe.OUT=previous/'cleric-wis-cha'
    probe.OUT.mkdir(exist_ok=True)
    shutil.copy2(previous/'offline-only.sb',probe.OUT/'offline-only.sb')
    probe.main()
