#!/usr/bin/env python3
"""Explain existing planning curves from local held-out records only; no DB/app IO."""
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path

import pandas as pd

from final_class_bonus_review import CLASSES, LABELS, OUT as SOURCE, ROOT, SEEDS, effects, ordered

OUT = ROOT / 'docs/audits/2026-08-31-stat-bonus-balance/level-growth-review'
FIELDS = ['hp', 'mp', 'cap_h', 'raw_proc_pct', 'search_s', 'sale_bonus_pct', 'bag']
LEVELS = [1, *range(10, 101, 10)]


def dump(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    OUT.mkdir(exist_ok=True)
    hashes = json.loads((SOURCE / 'engine_source_sha256.json').read_text())
    for name, digest in hashes.items():
        if name.startswith('app/'):
            assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest, name
    x = effects(ordered(pd.read_csv(SOURCE / 'all-heldout.csv'), SEEDS))
    # Scalar formulas cross-check every vectorized row, including nonlinear averaging.
    for r in x.itertuples(index=False):
        assert math.isclose(r.cap_h, 8 + 2 * min(r.hp / 6000, 1) ** .8)
        assert math.isclose(r.search_s, 5 - min(r.dex / 150, 1))
        assert math.isclose(r.sale_bonus_pct, 20 * min(r.cha / 150, 1))
    for field in ['cap_h', 'raw_proc_pct', 'sale_bonus_pct']:
        assert (x.groupby(['class', 'seed_index'])[field].diff().dropna() >= -1e-10).all()
    assert (x.groupby(['class', 'seed_index']).search_s.diff().dropna() <= 1e-10).all()

    stats = x.groupby(['class', 'level'])[FIELDS].agg(['mean', 'min', 'max'])
    stats.columns = [f'{a}_{b}' for a, b in stats.columns]
    stats = stats.reset_index()
    for field in FIELDS:
        stats[field + '_delta_1level'] = stats.groupby('class')[field + '_mean'].diff()
    stats.to_csv(OUT / 'all-level-effects-and-deltas.csv', index=False)
    means = x.groupby(['class', 'level'])[FIELDS].mean()

    cap_conditions = {
        'WARRIOR': x.cap_h >= 10, 'MAGE': x.raw_proc_pct >= 35,
        'ROGUE': x.search_s <= 4, 'RANGER': x.search_s <= 4,
        'CLERIC': x.sale_bonus_pct >= 20, 'PALADIN': x.sale_bonus_pct >= 20,
    }
    hits = []
    for cls in CLASSES:
        first = x[(x['class'] == cls) & cap_conditions[cls]].groupby('seed_index')['level'].min()
        hits.append(dict(hero_class=LABELS[cls], reached=int(len(first)), samples=16,
                         earliest=int(first.min()), latest_among_reached=int(first.max()),
                         median_among_reached=float(first.median())))

    common = dict(
        label='최종 기획안의 레벨별 성장',
        files=[dict(label='all-heldout.csv'), dict(label='final_class_bonus_review.py'),
               dict(label='SimpleGameEngine.kt'), dict(label='SimpleGameModels.kt')],
        filters=['직업: 6종', '난수 seed: 14~29', '직업별 전체 전투 경로: 16개'],
        caveats=['실제 이용자 데이터가 아닌 기존 로컬 엔진 시뮬레이션입니다.',
                 '레벨마다 각 캐릭터의 보너스를 계산한 뒤 직업별 평균을 냈습니다. 표본의 최고값은 이론적 상한이 아닙니다.',
                 '레벨업 외에 모험담 완료로도 성장합니다. 이 경로의 100레벨 모험담 완료는 129회입니다.',
                 '보너스는 기획안이며 운영 앱에 적용된 값이 아닙니다. 이번 계산은 DB·기기·앱 실행 없이 이루어졌습니다.'])
    definitions = [
        dict(name='HP 보유시간', definition='HP에 따른 오프라인 보유시간의 상한은 모든 레벨에서 10시간이며 HP 6000부터 포화됩니다.', formula='8 + 2 × min(HP/6000,1)^0.8 시간'),
        dict(name='MP 스킬 확률', definition='마법사의 기본 스킬 확률은 최대 35%, 다른 직업은 최대 30%입니다. MP 보너스는 6000부터 포화됩니다.', formula='min(20+A, P0 + A × min(MP/6000,1)); A=마법사 15, 나머지 10'),
        dict(name='DEX 탐색', definition='DEX는 탐색 시간을 5초에서 최소 4초까지 줄이며 DEX 150부터 포화됩니다.', formula='5 − min(DEX/150,1) 초'),
        dict(name='CHA 판매', definition='CHA는 아이템 판매 보너스를 최대 20%까지 늘리며 CHA 150부터 포화됩니다.', formula='20 × min(CHA/150,1) %'),
        dict(name='STR 가방 상한', definition='가방의 이론적 상한은 레벨에 따라 증가합니다. 실제 가방은 STR에 따라 이 상한 이하입니다.', formula='B=15+floor((레벨−1)×3/5); 가방=B+min(floor(STR/2),B−1); 상한=2B−1'),
    ]
    now = datetime.now().astimezone().isoformat()
    chart_levels = [1, *range(5, 101, 5)]
    for kind, field, title, ylabel, definition in [
        ('offline', 'cap_h', '레벨별 오프라인 보유시간 · 직업별 평균', '보유시간 (시간)', definitions[0]),
        ('skill', 'raw_proc_pct', '레벨별 스킬 확률 · 직업별 평균', '기본 발동 확률 (%)', definitions[1]),
    ]:
        rows = [dict(level=level, hero_class=LABELS[cls], value=round(float(means.loc[(cls, level), field]), 4))
                for cls in CLASSES for level in chart_levels]
        source = {**common, 'metricDefinitions': [definition],
                  'filters': common['filters'] + ['표시 레벨: 1 및 5~100의 5레벨 간격']}
        if kind == 'skill':
            source['caveats'] = common['caveats'] + ['연속 15회 일반 공격 후 확정 발동은 이 기본 확률에 포함하지 않습니다.']
        dump(f'{kind}-chart-input.json', dict(schemaVersion=1, id=f'level-bonus-{kind}',
             title=title, chart=dict(type='line', x='level', y='value', series='hero_class',
                                    xLabel='레벨', yLabel=ylabel, showXAxisLabel=True),
             rows=rows, source=source, generatedAt=now, height=300, theme='codex-classic'))

    preview = []
    for level in LEVELS:
        previous = LEVELS[max(0, LEVELS.index(level) - 1)]
        b = 15 + (level - 1) * 3 // 5
        item = dict(level=level, previous_level=previous, bag_cap=2 * b - 1)
        for name, cls, field in [('warrior_h', 'WARRIOR', 'cap_h'), ('mage_pct', 'MAGE', 'raw_proc_pct'),
                                 ('rogue_s', 'ROGUE', 'search_s'), ('ranger_s', 'RANGER', 'search_s'),
                                 ('cleric_pct', 'CLERIC', 'sale_bonus_pct'), ('paladin_pct', 'PALADIN', 'sale_bonus_pct')]:
            value = float(means.loc[(cls, level), field])
            item[name] = round(value, 6)
            item[name + '_delta'] = round(value - float(means.loc[(cls, previous), field]), 6) if level > 1 else None
        preview.append(item)
    dump('growth-summary.json', dict(levels=preview, cap_hits=hits, rows=9600,
         samples_per_class=16, app_source_hashes_match=True,
         data_sha256=hashlib.sha256((SOURCE / 'all-heldout.csv').read_bytes()).hexdigest()))
    source = {**common, 'metricDefinitions': definitions,
              'caveats': common['caveats'] + ['증분은 직전 표시 레벨과의 차이이며 매 레벨 일정한 증가량이 아닙니다.'],
              'evidenceFlow': [dict(kind='validation', title='공식 확인',
                                   detail='앱 소스 해시 17개가 기존 검증 시점과 일치했습니다. 9600행의 HP·DEX·CHA 효과를 독립 스칼라식으로 대조했고, 가방·기존 스킬 확률 공식과 단조 성장도 확인했습니다.')]}
    dump('sources-input.json', dict(schemaVersion=1, items=[
        dict(id='level-growth', title='증가 공식과 레벨별 평균 혜택', queries=[dict(
            id='level-growth-records', source=source, rows=preview,
            columns=[dict(field=f, label=l) for f,l in [('level','레벨'),('warrior_h','전사 보유시간 (h)'),
                ('mage_pct','마법사 확률 (%)'),('rogue_s','도적 탐색 (초)'),('ranger_s','레인저 탐색 (초)'),
                ('cleric_pct','성직자 판매 (%)'),('paladin_pct','팔라딘 판매 (%)'),('bag_cap','가방 이론 상한 (칸)')]],
            preview=dict(kind='aggregate', note='직업별 16개 경로의 해당 레벨 평균. 가방 상한만 레벨 공식으로 산출.', totalRows=11))]),
        dict(id='cap-arrival', title='상한 도달 시점과 예외', queries=[dict(id='first-cap-level',
             source={**common, 'metricDefinitions':[dict(name='최초 상한 도달',
                     definition='100레벨까지 특화 혜택의 상한에 도달한 경로에서 최초 도달 레벨을 계산했습니다. 미도달 경로는 도달 시점 범위에서 제외했습니다.')],
                     'caveats':common['caveats']+['HP·DEX·CHA는 직업 잠금이나 100레벨 제한이 없습니다. 표본 결과가 모든 성장운에서의 독점이나 도달을 보장하지 않습니다.']},
             rows=hits, columns=['hero_class','reached','samples','earliest','latest_among_reached','median_among_reached'])]),
    ]))

    lines = ['# 레벨별 증가 공식과 혜택', '',
        '현재 기획안은 HP/MP/DEX/CHA의 효과 상한이 레벨과 무관하게 고정이다. 능력치와 실제 혜택이 성장한다. 가방 상한만 레벨에 따라 증가한다.', '',
        'HP/MP의 6000 및 DEX/CHA의 150은 능력치 자체의 최대치가 아니라 해당 보너스가 더 이상 증가하지 않는 기준이다.', '',
        '## 성장 이벤트', '',
        '- 레벨업 또는 모험담 완료 시 HP += floor(CON/3)+1+U{0,1,2,3}.',
        '- MP += floor(floor((INT+WIS)/2)/3)+1+U{0,1,2,3}.',
        '- 위 HP/MP 계산 후 주/보조 스탯 중 하나 +1, 전체 6개 중 하나 +1. 같은 스탯 중복 가능.',
        '- 두 주/보조 능력치의 이벤트당 기대 증가량은 각각 2/3, 나머지는 각각 1/6. 난수 균등을 가정한 기대치이며 개별 성장 보장이 아니다.',
        '- 100레벨 경로: 레벨업 99 + 모험담 129 = 성장 이벤트 228회.', '',
        '## 혜택 공식', '']
    for definition in definitions:
        lines += [f"- {definition['name']}: `{definition['formula']}`"]
    lines += ['- P0 = clamp(8 + floor(floor((7×주력 + 3×보조)/10)/6), 8, 20) (%).', '',
        '## 해석', '',
        '이하 각 값은 직업별 16개 전체 전투 경로의 효과를 먼저 계산한 뒤 평균한 값이다. 실제 이용자 모집단 평균이나 이론적 최대를 뜻하지 않는다.',
        '증분은 직전 표시 레벨 대비이며 기본 확률 변화 단위는 %p, 시간 변화는 분, 탐색 변화는 초이다.', '',
        '## 특화 혜택 10레벨 간격', '',
        '| 레벨 | 전사 시간(h) | 증가(분) | 마법사 확률(%) | 증가(%p) | 도적/레인저 탐색(초) | 성직자/팔라딘 판매(%) | 가방 상한 |',
        '|---|---:|---:|---:|---:|---:|---:|---:|']
    for r in preview:
        dh = '—' if r['warrior_h_delta'] is None else f"{60*r['warrior_h_delta']:+.2f}"
        dp = '—' if r['mage_pct_delta'] is None else f"{r['mage_pct_delta']:+.2f}"
        lines.append(f"| {r['level']} | {r['warrior_h']:.4f} | {dh} | {r['mage_pct']:.2f} | {dp} | {r['rogue_s']:.4f}/{r['ranger_s']:.4f} | {r['cleric_pct']:.2f}/{r['paladin_pct']:.2f} | {r['bag_cap']} |")
    for cls in CLASSES:
        lines += ['', f'## {LABELS[cls]} 전체 레벨 기록', '',
            '최저·최고는 16개 표본에서 관측된 HP/MP이며 이론적 최저·최대가 아니다. Δ는 직전 1레벨 대비 평균 효과의 차이다.', '',
            '| Lv | HP 최저/평균/최고 | MP 최저/평균/최고 | 보유시간(h) | Δ분 | 확률(%) | Δ%p | 탐색(초) | Δ초 | 판매(%) | Δ%p | 가방 평균/상한 |',
            '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
        for r in stats[stats['class']==cls].itertuples(index=False):
            delta = lambda f,scale=1: '—' if pd.isna(getattr(r,f+'_delta_1level')) else f"{getattr(r,f+'_delta_1level')*scale:+.4f}"
            lines.append(f'| {r.level} | {r.hp_min:.0f}/{r.hp_mean:.2f}/{r.hp_max:.0f} | {r.mp_min:.0f}/{r.mp_mean:.2f}/{r.mp_max:.0f} | {r.cap_h_mean:.4f} | {delta("cap_h",60)} | {r.raw_proc_pct_mean:.4f} | {delta("raw_proc_pct")} | {r.search_s_mean:.4f} | {delta("search_s")} | {r.sale_bonus_pct_mean:.4f} | {delta("sale_bonus_pct")} | {r.bag_mean:.2f}/{2*(15+(r.level-1)*3//5)-1} |')
    (OUT/'LEVEL_GROWTH.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps(dict(preview=preview, first_cap=hits),ensure_ascii=False,indent=2))


if __name__ == '__main__':
    main()
