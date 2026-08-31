#!/usr/bin/env python3
"""Package the reviewed all-class evidence; no source/DB connections."""
import contextlib
from datetime import datetime
import io
import json
import uuid
import pandas as pd
from final_class_bonus_review import OUT,ROOT,LABELS,CLASSES


def main():
    s=json.loads((OUT/'summary.json').read_text())
    d=pd.read_csv(OUT/'scenario-comparison.csv')
    checks=(OUT/'growth-tail-iid-checks.log').read_text()
    regression=(OUT/'unchanged-engine-regression.log').read_text()
    assert 'boundary checks: 624 PASS' in checks and 'OK (113 tests)' in regression
    common=dict(label='전 직업 신규 성장 경로',files=[{'label':'all-heldout.csv'},{'label':'final_class_bonus_review.py'}],
        filters=['직업: 6종','신규 난수 seed: 14~29','직업당 전체 전투 경로: 16개','목표 레벨: 100','접속 시 충전: 12분'],
        caveats=['실제 이용자 로그가 아닌 로컬 엔진 시뮬레이션입니다.',
            '접속 간격 영향은 레벨별 저장시간 환산이며 실제 접속 이벤트 재생이 아닙니다.',
            'HP 기본 8시간·최대 10시간, 마법사 기본 확률 35%, 탐색 최소 4초, 판매 보너스 최대 20%를 적용한 기획안입니다.'])
    charts=[]
    for kind,field,title,label,definition in [
        ('growth','growth_advantage_pct','100레벨까지 성장속도: 접속 간격별','조건별 최저 직업 대비 (%)',
         '같은 목표 레벨까지의 직업별 평균 소요시간을 비교한 성장속도입니다. 각 접속 조건에서 가장 느린 직업을 기준으로 합니다.'),
        ('sales','sale_advantage_pct','100레벨까지 평균 판매수입: 접속 간격별','조건별 최저 직업 대비 (%)',
         '100레벨까지 누적 아이템 판매 골드를 환산 기간으로 나눈 평균 판매수입입니다. 각 접속 조건에서 가장 적은 직업을 기준으로 합니다.'),
    ]:
        z=d[(d.level==100)&(d.session_min==12)&d.gap_h.isin([6,8,9,10,12])]
        rows=[dict(gap_h=float(r.gap_h),hero_class=LABELS[r.hero_class],advantage_pct=round(getattr(r,field),4)) for _,r in z.iterrows()]
        formula=('(해당 조건 최대 평균 소요시간 / 해당 직업 평균 소요시간 − 1) × 100' if kind=='growth' else
                 '(해당 직업 평균 판매수입 / 해당 조건 최소 평균 판매수입 − 1) × 100')
        source={**common,'filters':common['filters']+['접속 간격: 6·8·9·10·12시간'],
            'metricDefinitions':[dict(name=title,definition=definition,formula=formula)]}
        if kind=='sales': source['caveats']=source['caveats']+['퀘스트 보상, 지출 및 보유 골드는 포함하지 않으며 100레벨 고정 상태의 수입이 아닙니다.']
        chart=dict(schemaVersion=1,id='final-class-'+kind,title=title,
            chart=dict(type='line',x='gap_h',y='advantage_pct',series='hero_class',xLabel='접속 간격 (시간)',yLabel=label,showXAxisLabel=True),
            rows=rows,source=source,height=310,theme='codex-classic',generatedAt=datetime.now().astimezone().isoformat())
        (OUT/f'{kind}-chart-input.json').write_text(json.dumps(chart,ensure_ascii=False,indent=2)+'\n')
        charts.append(chart)
    benefits=[dict(hero_class=LABELS[cls],**{key:round(value,6) for key,value in s['level100_effects'][cls].items()}) for cls in CLASSES]
    definitions=[
        dict(name='HP 보유시간',definition='HP는 모든 직업의 오프라인 보유시간을 기본 8시간에서 최대 10시간까지 늘립니다.',formula='8 + 2 × min(HP/6000,1)^0.8 시간'),
        dict(name='MP 기본 확률',definition='MP는 기존 스킬 확률에 보너스를 더하며, 마법사는 최대 +15%p·상한 35%, 다른 직업은 최대 +10%p·상한 30%입니다.',formula='min(직업 상한, 기존 확률 + 직업 보너스 × min(MP/6000,1))'),
        dict(name='DEX 탐색',definition='DEX는 탐색 5초를 최소 4초까지 단축합니다. 발견 연출 2초는 유지합니다.',formula='5 − min(DEX/150,1) 초'),
        dict(name='CHA 판매',definition='CHA는 아이템 판매 금액에만 최대 20% 보너스를 줍니다.',formula='20 × min(CHA/150,1) %'),
        dict(name='STR 가방',definition='STR는 레벨별 기본 가방에 추가 칸을 주며, 같은 레벨의 최대 가방은 기본 용량의 두 배 미만입니다.',formula='B=15+floor((레벨−1)×3/5); 가방=B+min(floor(STR/2),B−1)')]
    caps=[{**r,'hero_class':LABELS[r['hero_class']]} for r in s['cap_by_class']]
    scenario=[{**r,**{k:LABELS[r[k]] for k in ['fastest','slowest','highest_sale','lowest_sale']}}
        for r in s['scenario_summary'] if r['gap_h'] in [8,12] and r['session_min'] in [1,5,12]]
    sources=dict(schemaVersion=1,items=[
        dict(id='all-class-benefits',title='100레벨 종합 혜택',queries=[dict(id='all-class-effects',
            source={**common,'metricDefinitions':definitions,'caveats':common['caveats']+[
                '값은 직업별 16개 캐릭터의 효과를 먼저 계산한 뒤 평균한 값입니다.',
                '스킬 확률은 연속 15회 일반공격 후 확정 발동을 포함하지 않은 기본 확률입니다.',
                '가방 증가율을 성장속도나 골드 증가율에 직접 합산하지 않았습니다.']},
            rows=benefits,columns=[dict(field=k,label=v) for k,v in [('hero_class','직업'),('cap_h','보유시간 (h)'),('raw_proc_pct','기본 확률 (%)'),('search_s','탐색 (초)'),('sale_bonus_pct','판매 보너스 (%)'),('bag','가방 (칸)')]],
            preview=dict(kind='aggregate',note='직업별 16개 신규 경로의 100레벨 평균',totalRows=6))]),
        dict(id='all-class-efficiency',title='성장과 판매수입의 균형',queries=[dict(id='all-class-scenarios',
            source={**common,'filters':['직업: 6종','목표 레벨: 100','접속 간격: 8·12시간','접속 시 충전: 1·5·12분'],
                'metricDefinitions':[dict(name='직업 간 격차',definition='각 조건에서 가장 높은 직업과 낮은 직업의 성장속도 또는 평균 판매수입 비율을 비교합니다.',formula='(최고 효율 / 최저 효율 − 1) × 100')],
                'caveats':common['caveats']+['충전이 1~5분에 그치면 8시간 접속에서도 보유시간·충전량이 중요한 전사가 성장에 유리합니다.',
                    '판매수입은 누적 아이템 판매만 포함하며 퀘스트 골드·지출·총재산을 뜻하지 않습니다.'],
                'evidenceFlow':[dict(kind='validation',title='교차 확인',detail='이전 8개 seed와 신규 16개 seed 모두 완충 시 8시간 간격은 마법사, 12시간 간격은 전사가 성장 1위였습니다. 시간저장량 계산 두 구현은 일치했고, 100레벨 구간 상하한 차이는 최대 0.267%였습니다.')]},
            rows=scenario,columns=['gap_h','session_min','growth_spread_pct','sale_spread_pct','fastest','slowest','highest_sale','lowest_sale'])]),
        dict(id='all-class-caps',title='상한과 검증 범위',queries=[dict(id='all-class-cap-counts',
            source=dict(label='특화 상한 및 경계값 검증',files=[{'label':'summary.json'},{'label':'growth-tail-iid-checks.log'},{'label':'unchanged-engine-regression.log'},{'label':'ClassBonusTailProbe.kt'}],
                filters=['목표 레벨: 100','전체 전투 재생: 직업당 16개'],
                metricDefinitions=[dict(name='상한 도달',definition='상한 도달 수는 신규 전투 경로 16개 중 100레벨에 해당 혜택의 최대치에 도달한 캐릭터 수입니다.')],
                caveats=['특화 직업의 모든 캐릭터가 100레벨에 상한에 닿는 것은 아닙니다.',
                    'HP·DEX·CHA 수식에 직업 잠금이 없으므로 샘플의 비특화 상한 0건이 모든 성장운에서의 배타성을 보장하지는 않습니다.',
                    '추가 60,000개 성장 표본은 성장 이벤트 간 독립 난수를 가정한 민감도 분석이며 실제 전투 경로나 플레이어 확률이 아닙니다.',
                    '게임 코드·DB·기기 등록·배포는 이번 검증에서 변경하지 않았습니다.'],
                evidenceFlow=[dict(kind='validation',title='수치 경계',detail='실제 시뮬레이션 훅의 624개 경계값 검사와, 변경 없는 엔진의 회귀 테스트 113개가 통과했습니다. HP 보유시간의 단조성·상한과 가방·기존 스킬 확률 공식도 독립 계산과 일치했습니다.'),
                    dict(kind='validation',title='보조 표본 제외',detail='성장 함수만 같은 난수 상태로 연속 호출한 초기 자료는 실제 전투의 난수 호출 순서와 달라 제외했습니다. 전체 전투 재생 96개는 영향을 받지 않습니다.')]),
            rows=caps,columns=['hero_class','samples','hp_10h','mage_35','search_4s','sale_20pct','bag_max'])])])
    (OUT/'sources-input.json').write_text(json.dumps(sources,ensure_ascii=False,indent=2)+'\n')
    notes(s)
    notebook(s)
    print('Two reviewed chart inputs, sources, final notes and three checked notebook cells ready.')


def notes(s):
    lines=['# 전 직업 보너스 최종 검증 기록','',
        '판정: 현재 수치를 기획 최종안으로 유지할 근거가 있다. 단, 모든 특화 캐릭터의 100레벨 확정 상한이나 운영 통합 완료를 뜻하지 않는다.',
        '', '## 공식', '',
        '- HP: `8 + 2 × min(HP/6000,1)^0.8`시간, 최대 10시간.',
        '- MP: 마법사 `min(35, 기존 확률 + 15 × min(MP/6000,1))`%, 다른 직업은 15→10·35→30.',
        '- DEX: `5 − min(DEX/150,1)`초. 발견 연출 2초 유지.',
        '- CHA: 아이템 판매에 `20 × min(CHA/150,1)`% 추가. 퀘스트 골드 제외.',
        '- STR: `B=15+floor((Lv−1)×3/5)`, 가방 `B+min(floor(STR/2),B−1)`. 기존 공식 유지.',
        '', '## 100레벨 신규 16개 경로 평균', '',
        '| 직업 | HP 보유시간(h) | 스킬 기본 확률(%) | 탐색(초) | 판매 보너스(%) | 가방(칸) |',
        '|---|---:|---:|---:|---:|---:|']
    for cls in CLASSES:
        e=s['level100_effects'][cls]
        lines.append(f"| {LABELS[cls]} | {e['cap_h']:.4f} | {e['raw_proc_pct']:.4f} | {e['search_s']:.4f} | {e['sale_bonus_pct']:.4f} | {e['bag']:.4f} |")
    lines += ['', '## 판단', '',
        '전사=보유시간·가방, 도적=탐색·가방, 레인저=탐색·스킬 혼합, 마법사=스킬 빈도, 성직자=스킬·판매 혼합, 팔라딘=가방·판매 조합이다.',
        '완충 12분 조건에서 8시간 접속은 마법사, 12시간 접속은 전사가 성장 1위다. 성직자는 두 조건 모두 아이템 평균 판매수입이 가장 높다. 성장·경제를 한 점수로 합치지 않았다.',
        f"20~100레벨, 접속 간격 4·6·8·9·10·12·16·24시간 및 충전 1·5·12분에서 관측한 성장 최대 격차는 {s['max_growth_spread20_to100_pct']:.4f}%, 판매수입 최대 격차는 {s['max_sale_spread20_to100_pct']:.4f}%다.",
        '특정 직업이 성장과 판매를 모두 독점하지 않는다. 레인저는 마법사와 사냥 효율이 근접하므로 별도 추가 상향 없이 탐색·스킬 혼합 역할을 유지한다.',
        '', '## 실제 검증과 한계', '',
        '- 기존 seed 2~13과 겹치지 않는 seed 14~29를 직업당 16개, 총 96개 전체 전투 재생했다. 9,600개 레벨별 기록을 검사했다.',
        '- 마법사는 최대 +15%p, 다른 5개 직업은 +10%p 파라미터로 별도 실행했다. 상세 실행 시각·파라미터·앱 소스 해시는 이 폴더에 저장했다.',
        '- 순수 엔진 및 모델만 컴파일했다. OS 샌드박스가 네트워크와 DB 파일 접근을 차단했다. 캐릭터는 메모리 객체이며 앱 시작·기기 등록은 없었다.',
        '- 실제 보너스 훅 경계값 624개, 기존 순수 엔진 회귀 테스트 113개 통과. 가방·기존 기본 확률을 별도 수식으로 전 행 대조했고 시간저장량 환산 두 구현도 일치했다.',
        '- 접속 효과는 시간저장량 기반 근사이다. HP에 따라 동적으로 바뀌는 저장시간의 앱 통합은 아직 구현·검증하지 않았다. 100레벨 계산 상하한 차이는 최대 0.267%였다.',
        '- 판매수입은 해당 레벨까지 누적 아이템 판매 / 환산 기간이다. 고정 레벨 수입·퀘스트 골드·지출·총재산과 구분한다.',
        '- 100레벨 신규 경로에서 전사 16/16이 10시간, 마법사 16/16이 35%, 도적·레인저 29/32가 탐색 4초, 성직자·팔라딘 29/32가 판매 +20%에 도달했다. 비특화의 해당 상한 도달은 없었다.',
        '- 추가 성장 민감도는 직업당 10,000개, 총 60,000개다. 성장 이벤트마다 독립 시드를 가정한다. HP 전사 98.88%, DEX 특화 90.88%, CHA 특화 90.72%가 기준에 닿았지만 실제 이용자 도달 확률로 해석하면 안 된다.',
        '- 초기 `growth-tails.csv`는 성장 함수를 동일 난수 상태로 4회씩 연속 호출해 낮은 비트의 주기를 고정하는 모델 오류가 있었다. 실제 전투 호출 순서와 다르므로 전부 제외했다. 보조 자료는 `growth-tails-iid.csv`만 사용한다.',
        '- HP·DEX·CHA는 직업 잠금이 없다. 모든 성장운에서 특화만 상한에 닿도록 강제하려면 직업별 상한 정책을 추가로 정해야 한다. 이번에는 임의로 규칙을 바꾸지 않았다.',
        '- 기존 성직자의 INT→CHA 재분배도 하지 않았다. 배포 전 기존 성직자 보정 정책이 필요하다.',
        '- 재현 노트북은 일반 Python으로 코드 셀 3개를 순차 실행했다. Jupyter 커널 실행은 미검증이다.',
        '', '## 재현', '',
        '`tools/analysis/run_final_class_bonus_probe.py` → `run_class_bonus_tails.py` → `final_class_bonus_review.py` → `build_final_class_bonus_review.py`.',
        '주 입력: `all-heldout.csv`. 출력: `benefits-by-level.csv`, `scenario-comparison.csv`, `summary.json`, `final-class-review.ipynb`.']
    (OUT/'FINAL_REVIEW.md').write_text('\n'.join(lines)+'\n')


def notebook(s):
    cells=[]
    def md(t):cells.append(dict(cell_type='markdown',metadata={},source=t.splitlines(True)))
    def code(t):cells.append(dict(cell_type='code',metadata={},source=t.splitlines(True),execution_count=None,outputs=[]))
    md('## tl;dr\n\n마법사 35%안을 포함한 6개 직업의 보너스 조합을 유지하는 것을 권장한다. 신규 96개 전투 경로에서 성장속도 최대 격차 5.26%, 평균 판매수입 최대 격차 10.79%였다. 특화 직업 전원의 100레벨 확정 상한이나 운영 통합 완료를 의미하지 않는다.\n')
    md('## Context & Methods\n\n### Key Assumptions\n\nHP 8→10시간, 마법사 35%, 다른 직업 최대 30%, 탐색 최소 4초, 아이템 판매 최대 +20%, 기존 STR 가방 공식 유지. 성직자는 WIS/CHA. 성장·판매·편의를 한 점수로 합산하지 않는다. 접속 간격은 실제 접속 이벤트가 아닌 저장시간 기반 환산이다.\n\n일반 Python으로 코드 셀을 순차 실행했다. Jupyter 커널 자체는 미검증이며 nbconvert/ipykernel이 설치된 환경에서 `python -m jupyter nbconvert --execute --to notebook --inplace final-class-review.ipynb`로 검증할 수 있다.\n')
    md('## Data\n\n### 1. Recompute fresh held-out evidence\n')
    code("from pathlib import Path\nimport sys,json,pandas as pd,numpy as np\nroot=next(p for p in [Path.cwd(),*Path.cwd().parents] if (p/'tools/analysis/final_class_bonus_review.py').exists())\nsys.path.insert(0,str(root/'tools/analysis'))\nfrom final_class_bonus_review import review\neffects,comparison,summary=review()\nassert len(effects)==9600 and summary['fresh_games']==96\nassert summary['app_source_unchanged'] and summary['calendar_independent_check']\nprint('Full combat games:',summary['fresh_games'],'Level rows:',len(effects))\n")
    md('## Results\n\n### 2. Compare benefits and separate growth from sales\n')
    code("print(pd.DataFrame.from_dict(summary['level100_effects'],orient='index').round(4).to_string())\nprint(pd.DataFrame(summary['scenario_summary']).query('gap_h in [8,12] and session_min == 12').round(4).to_string(index=False))\nassert np.isclose(summary['max_growth_spread20_to100_pct'],5.260083174027708)\nassert np.isclose(summary['max_sale_spread20_to100_pct'],10.794547454806303)\n")
    md('### 3. Verify caps and record uncertainty\n')
    code("print(pd.DataFrame(summary['cap_by_class']).to_string(index=False))\nprint(pd.DataFrame(summary['stability']).round(4).to_string(index=False))\nprint(pd.DataFrame(summary['iid_growth_tails'])[['hero_class','hp_cap_pct','mp_threshold_pct','dex_cap_pct','cha_cap_pct']].round(4).to_string(index=False))\nprint('Excluded:',summary['excluded'])\nassert summary['bag_formula_match'] and summary['raw_proc_formula_match']\n")
    md('## Takeaways\n\n완충 상태에서는 잦은 접속에 마법사, 긴 간격에 전사, 아이템 판매에 성직자가 유리하다. 1~5분의 부분 충전에서는 전사의 보유량·충전량 이점이 커진다. DEX/CHA 특화 일부는 100레벨에도 상한 직전이다. 추가 60,000개는 독립 성장 이벤트 시드의 보조 민감도 분석이며 실제 플레이어 확률이 아니다. 원시 연속 성장 RNG 자료는 제외했다. 운영 앱·DB 변경은 없으며, 배포 전 동적 충전/저장시간 통합 및 기존 성직자 보정 정책 검증이 남는다.\n')
    env={};count=0
    for cell in cells:
        cell['id']=uuid.uuid4().hex[:8]
        if cell['cell_type']=='code':
            count+=1;buf=io.StringIO()
            with contextlib.redirect_stdout(buf):exec(compile(''.join(cell['source']),cell['id'],'exec'),env)
            cell['execution_count']=count;cell['outputs']=[dict(output_type='stream',name='stdout',text=buf.getvalue().splitlines(True))]
    assert count==3
    doc=dict(nbformat=4,nbformat_minor=5,cells=cells,metadata=dict(kernelspec=dict(display_name='Python 3',language='python',name='python3'),
        language_info=dict(name='python'),validation=dict(mode='plain-python-sequential',code_cells_passed=count,jupyter_kernel_executed=False)))
    (OUT/'final-class-review.ipynb').write_text(json.dumps(doc,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__':main()
