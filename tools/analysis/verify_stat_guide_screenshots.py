#!/usr/bin/env python3
"""Check recorded native UI hierarchies; screenshots remain original device PNGs."""
import hashlib
import json
import xml.etree.ElementTree as ET

from run_stat_bonus_probe import ROOT

OUT = ROOT / 'docs/audits/2026-09-01-stat-guide'
SHOTS = OUT / 'screenshots'


def texts(name):
    return [node.get('text') for node in ET.parse(SHOTS / f'{name}.xml').iter('node') if node.get('text')]


def main():
    guide = texts('creation-guide-ko-final')
    assert guide == texts('adventurer-guide-ko-final'), 'Both entry points must show identical copy'
    assert guide[0] == '능력치 안내' and guide[-1] == '닫기'
    assert not any(char.isdigit() for text in guide for char in text)
    for name in ['creation-ko-final', 'adventurer-ko-final']:
        assert '능력치 안내' in texts(name)
    en = set(texts('creation-guide-en') + texts('guide-en-bottom'))
    ja = set(texts('adventurer-guide-ja') + texts('guide-ja-bottom'))
    for language, values in [('en', en), ('ja', ja)]:
        assert not any('\uac00' <= char <= '\ud7a3' for value in values for char in value), language
        for label in ['STR', 'CON', 'DEX', 'INT', 'WIS', 'CHA', 'HP MAX', 'MP MAX']:
            assert any(value.startswith(label + ' ·') for value in values), (language, label)
    initial = ET.parse(SHOTS / 'creation-en.xml')
    restored = ET.parse(SHOTS / 'creation-input-preserved-en.xml')
    def stat_values(tree):
        return [n.get('content-desc') for n in tree.iter('node') if n.get('content-desc', '').split(',')[0]
            in ['STR', 'CON', 'DEX', 'INT', 'WIS', 'CHA', 'HP MAX', 'MP MAX']]
    assert stat_values(initial) == stat_values(restored) and len(stat_values(initial)) == 8
    assert 'GuideQA' in texts('creation-input-preserved-en')
    assert '닫기' in texts('guide-ko-large-font')
    large_bottom = texts('guide-ko-large-font-bottom')
    assert '닫기' in large_bottom
    assert any(text.startswith('MP MAX') for text in large_bottom)
    assert any(text.startswith('직업의 주·보조 능력치') for text in large_bottom)
    result = dict(
        shared_final_korean_popup='PASS', stat_values_and_name_preserved='PASS',
        english_and_japanese_body_coverage='PASS; before button chrome update, body unchanged',
        large_font_dialog_scroll_and_close='PASS',
        apk_sha256='6ac2408d197137bb185a439a6044f6add860184c62eadc89e9194886b017bb2c',
        final_screenshots={p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in SHOTS.glob('*-ko-final.png')},
    )
    (OUT / 'ui-verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
