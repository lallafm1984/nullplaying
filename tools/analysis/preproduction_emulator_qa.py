#!/usr/bin/env python3
"""Native UI QA only on this release audit's isolated temporary emulator."""
import stat_guide_emulator_qa as qa
from run_stat_bonus_probe import ROOT

qa.SERIAL = 'emulator-5556'
qa.OUT = ROOT / 'docs/audits/2026-09-01-preproduction-qa/screenshots'

if __name__ == '__main__':
    qa.main()
