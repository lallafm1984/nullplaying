#!/usr/bin/env python3
"""Verify compact guides from native screenshots and their UI hierarchies."""
import base64
import hashlib
import json
import re
import xml.etree.ElementTree as ET

from run_stat_bonus_probe import ROOT

OUT = ROOT / 'docs/audits/2026-09-01-stat-guide'
SHOTS = OUT / 'screenshots'


def bounds(node):
    return list(map(int, re.findall(r'\d+', node.get('bounds'))))


def main():
    source = (ROOT / 'app/src/simple/java/com/nullplaying/ui/StatGuideContent.kt').read_text()
    entries = re.findall(r'StatGuideEntry\("[^"]+", "([^"]+)", "([^"]+)"\)', source)
    constants = dict(re.findall(r'const val (\w+) = "([^"]+)"', source))
    korean = [constants['title'], constants['introduction']]
    korean += [value for entry in entries for value in entry]
    korean += [constants['classBenefit'], '닫기']
    assert len(entries) == 8
    translations = {'ko': {value: value for value in korean}}
    for language in ['en', 'ja']:
        rows = (ROOT / f'app/src/simple/res/raw/localization_{language}.tsv').read_text().splitlines()
        translations[language] = {
            base64.b64decode(parts[1]).decode(): base64.b64decode(parts[2]).decode()
            for row in rows if row.startswith('E\t') for parts in [row.split('\t')]
        }
    checked = []
    for name, language in [
        ('creation-guide-ko-compact', 'ko'), ('adventurer-guide-ko-compact', 'ko'),
        ('adventurer-guide-en-compact', 'en'), ('adventurer-guide-ja-compact', 'ja'),
    ]:
        nodes = [node for node in ET.parse(SHOTS / f'{name}.xml').iter('node') if node.get('text')]
        values = [node.get('text') for node in nodes]
        assert values == [translations[language][value] for value in korean], (name, values)
        assert not any(character.isdigit() for value in values for character in value)
        close_top = bounds(nodes[-1])[1]
        for node in nodes[:-1]:
            left, top, right, bottom = bounds(node)
            assert 0 <= left < right <= 1080 and 0 <= top < bottom < close_top < 2340, (name, node.attrib)
        checked.append(name)
    for name in ['creation-ko-badge', 'adventurer-ko-badge']:
        tree = ET.parse(SHOTS / f'{name}.xml')
        targets = [node for node in tree.iter('node') if node.get('clickable') == 'true' and
            any(child.get('text') == constants['title'] for child in node.iter('node'))]
        assert len(targets) == 1, (name, len(targets))
        # Compose may expose the role as a virtual child of the clickable node.
        assert any(node.get('class') == 'android.widget.Button' for node in targets[0].iter('node')), (name, 'button accessibility role')
        left, top, right, bottom = bounds(targets[0])
        assert bottom - top >= 126, (name, '48dp touch target at 420dpi', targets[0].attrib)
        if name.startswith('creation'):
            total = next(node for node in tree.iter('node') if node.get('text', '').startswith('합계 '))
            grid_right = max(bounds(node)[2] for node in tree.iter('node')
                if node.get('content-desc', '').split(',')[0] in
                ['STR', 'CON', 'DEX', 'INT', 'WIS', 'CHA', 'HP MAX', 'MP MAX'])
            total_left, _, total_right, _ = bounds(total)
            right_inset = grid_right - total_right
            assert total_left > right and 4 <= right_inset <= 20, (name, 'trailing total inset', right_inset)
        checked.append(name)
    result = dict(
        all_eight_stats_and_footer_visible_without_scrolling=['ko', 'en', 'ja'],
        creation_and_adventurer_copy_identical=True,
        badge_touch_target_dp_at_least=48,
        creation_total_right_aligned=True,
        screen_px=[1080, 2340], density_dpi=420, system_font_scale=1.0,
        apk_sha256=hashlib.sha256((ROOT / 'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest(),
        english_japanese_capture_apk_sha256='e164af052a8b74f84a6d70fefed7c2a1082bed549a2cc26d8140846afa023a10',
        english_japanese_capture_scope='Before heading-only total alignment; popup and translations unchanged.',
        screenshots={name + '.png': hashlib.sha256((SHOTS / f'{name}.png').read_bytes()).hexdigest()
            for name in checked},
    )
    (OUT / 'compact-ui-verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
