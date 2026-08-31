#!/usr/bin/env python3
"""Package reviewed Mage sensitivity evidence for inline delivery."""
import contextlib
from datetime import datetime
import io
import json
import uuid
import pandas as pd
from review_mage35 import OUT, ROOT


def main():
    s = json.loads((OUT/'summary.json').read_text())
    d = pd.read_csv(OUT/'growth_comparison.csv')
    source = dict(label='로컬 순수 엔진 비교',
        files=[{'label':'mage35_dense.csv'},{'label':'ten_hour_independent_dense.csv'},{'label':'review_mage35.py'}],
        filters=['난수 seed: 6~13','직업당 성장 경로: 8개','접속 간격: 8시간','접속 시 충전: 12분'],
        metricDefinitions=[dict(name='상대 성장속도',definition='같은 목표 레벨까지의 평균 소요시간으로 비교한 팔라딘 대비 성장속도입니다.',
            formula='(팔라딘 평균 소요시간 / 해당 직업 평균 소요시간 − 1) × 100')],
        caveats=['실제 이용자 로그가 아닌 소규모 엔진 시뮬레이션입니다.',
            '접속 간격의 영향은 시간저장량 환산이며 전체 접속 이벤트를 재생하지 않았습니다.',
            '마법사의 MP 보너스만 최대 +10%p에서 +15%p로 변경했고 나머지 직업의 경로는 재사용했습니다.',
            'HP 기본 8시간·최대 10시간, 탐색 최소 4초, 판매 보너스 최대 20%를 유지했습니다.'])
    rows=[]
    for variant,cls,label in [('mage30','MAGE','마법사 30%'),('mage35','MAGE','마법사 35%'),('mage35','RANGER','레인저')]:
        x=d[(d.variant==variant)&(d.hero_class==cls)&(d.gap_h==8)&(d.session_min==12)]
        rows.extend(dict(level=int(r.level),plan=label,speed_pct=round(r.speed_vs_paladin_pct,4)) for _,r in x.iterrows())
    assert len(rows)==27
    chart=dict(schemaVersion=1,id='mage35-growth',title='8시간 간격 접속: 마법사 35%안의 성장속도',
        chart=dict(type='line',x='level',y='speed_pct',series='plan',xLabel='도달 레벨',yLabel='팔라딘 대비 빠른 정도 (%)',showXAxisLabel=True),
        rows=rows,source=source,height=320,theme='codex-classic',generatedAt=datetime.now().astimezone().isoformat())
    (OUT/'chart-input.json').write_text(json.dumps(chart,ensure_ascii=False,indent=2)+'\n')
    labels={'WARRIOR':'전사','ROGUE':'도적','RANGER':'레인저','MAGE':'마법사','CLERIC':'성직자','PALADIN':'팔라딘'}
    effects=[dict(직업=labels[e['hero_class']],기본확률_pct=e['raw_proc_pct'],판매보너스_pct=e['sale_bonus_pct'])
        for e in s['all_effects'] if e['variant']=='mage35']
    receipt=dict(schemaVersion=1,items=[
        dict(id='mage35-identity',title='마법사 35%와 직업 개성',queries=[dict(id='mage35-level100',
            source=dict(label='100레벨 효과',files=[{'label':'level100_effects.csv'},{'label':'SimpleGameEngine.kt'},{'label':'BalanceSimulationHooks.kt'}],
                filters=['목표 레벨: 100','성장 경로: 직업당 8개','성직자 주·보조 스탯: WIS·CHA'],
                metricDefinitions=[dict(name='스킬 기본 확률',definition='스킬 기본 확률은 기존 스탯 확률에 MP 보너스를 더한 값이며 연속 미발동 보정 이전 확률입니다.',
                    formula='마법사: min(35, 기존 확률 + 15 × min(MP/6000,1)); 다른 직업: min(30, 기존 확률 + 10 × min(MP/6000,1))'),
                    dict(name='확정 발동',definition='일반 공격이 15회 연속되면 다음 공격에 스킬이 확정 발동하므로, 확률이 고정된 장기 평균은 기본 확률보다 조금 높습니다.',formula='p / (1 − (1−p)^16)')],
                caveats=['상한만 35%로 올리면 기존 +10%p 보너스로는 30%에 머뭅니다. 비교안에서는 마법사 MP 보너스도 +15%p로 올렸습니다.',
                    '마법사가 기본 35%에 도달했을 때 확정 발동을 포함한 장기 이론 비율은 약 35.04%입니다.',
                    '기획용 보너스이며 앱 및 DB에 적용하지 않았습니다.']),
            rows=effects,columns=['직업','기본확률_pct','판매보너스_pct'],preview={'kind':'aggregate','note':'100레벨 직업별 8개 경로의 평균','totalRows':6})]),
        dict(id='mage35-efficiency',title='스킬 사용과 성장 효율',queries=[
            dict(id='mage35-lategame',source=dict(label='90→100레벨 구간 카운터',files=[{'label':'mage35_dense.csv'},{'label':'ten_hour_independent_dense.csv'},{'label':'review_mage35.py'}],
                filters=['직업: 마법사','구간: 90→100레벨','난수 seed: 6~13'],
                metricDefinitions=[dict(name='실제 스킬 비율',definition='실제 스킬 비율은 구간 내 총 스킬 사용 횟수를 총 공격 횟수로 나눈 값입니다.'),
                    dict(name='시간당 처치·시전',definition='시간당 처치·시전은 해당 구간의 총 횟수를 실제 엔진 진행 시간으로 나눈 값입니다. 오프라인 대기시간은 포함하지 않습니다.')],
                caveats=['성장 중 확률이 증가하므로 90→100레벨 구간 평균은 100레벨 고정 확률과 다릅니다.',
                    '같은 초기 난수를 사용했으나 스킬 선택에 따라 전투 난수 경로는 달라집니다.']),
                rows=s['late_game'],columns=['variant','pooled_cast_pct','pooled_kills_per_hour','pooled_casts_per_hour']),
            dict(id='mage35-growth',source={**source,'filters':['목표 레벨: 100','난수 seed: 6~13','접속 간격: 8·12시간','접속 시 충전: 12분'],
                'evidenceFlow':[dict(kind='validation',title='독립 계산 대조',detail='행별 시간저장량 환산과 배열 계산의 결과가 일치했고, 다른 직업의 입력 경로 및 앱 소스 해시가 유지되었습니다.')]},
                rows=[r for r in s['comparisons'] if r['variant']!='mage_change'],
                columns=['variant','gap_h','class_growth_spread_pct','fastest','slowest'])])])
    (OUT/'sources-input.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
    notebook()


def notebook():
    cells=[]
    def md(text): cells.append(dict(cell_type='markdown',metadata={},source=text.splitlines(True)))
    def code(text): cells.append(dict(cell_type='code',metadata={},source=text.splitlines(True),execution_count=None,outputs=[]))
    md('## tl;dr\n\n마법사만 MP 보너스를 최대 +15%p, 기본 스킬 확률 상한을 35%로 올리는 안은 직업 개성을 강화한다. 90→100레벨 시간당 처치 +3.51%, 시간당 스킬 사용 +10.41%, 8시간 간격에서 100레벨까지 성장속도 +1.72%였다. 앱 적용은 하지 않았다.\n')
    md('## Context & Methods\n\n### Key Assumptions\n\n성직자는 WIS/CHA. HP 8→10시간, 탐색 최소 4초, 판매 보너스 최대 20%를 유지한다. 마법사 seed 6~13을 재계산하고 나머지 5개 직업의 같은 seed 결과는 재사용한다. 총 스킬 비율, 시간당 시전, 시간당 처치, 100레벨 도달 속도는 다른 지표다. 접속 간격은 저장시간 기반 근사이며 실제 사용자 로그가 아니다.\n\n재현: run_mage35_stat_probe.py, review_mage35.py. 네트워크와 DB 파일을 차단한 샌드박스에서 실행했다. 일반 Python으로 코드 셀을 순차 실행했으며 Jupyter 커널 자체 실행은 미검증이다. nbconvert/ipykernel이 설치된 환경에서는 `python -m jupyter nbconvert --execute --to notebook --inplace mage35-review.ipynb`로 검증할 수 있다.\n')
    md('## Data\n\n### 1. Load local evidence and recheck calculations\n')
    code("from pathlib import Path\nimport sys,json,numpy as np,pandas as pd\nroot=next(p for p in [Path.cwd(),*Path.cwd().parents] if (p/'tools/analysis/review_mage35.py').exists())\nsys.path.insert(0,str(root/'tools/analysis'))\nfrom review_mage35 import review\ncomparison,summary=review()\nprint('Mage paths:',len(summary['input_seeds']))\nassert summary['app_source_unchanged'] and summary['other_class_paths_unchanged'] and summary['calendar_spot_check_passed']\n")
    md('## Results\n\n### 2. Compare actual late-game counters\n')
    code("late=pd.DataFrame(summary['late_game']).set_index('variant')\nprint(late[['pooled_cast_pct','pooled_kills_per_hour','pooled_casts_per_hour']].round(4).to_string())\nfor field in ['pooled_kills_per_hour','pooled_casts_per_hour']:\n    print(field,'gain_pct',100*(late.loc['mage35',field]/late.loc['mage30',field]-1))\nassert np.isclose(late.loc['mage35','pooled_kills_per_hour']/late.loc['mage30','pooled_kills_per_hour']-1,.03514340734526189)\n")
    md('### 3. Separate fixed probability from whole-growth efficiency\n')
    code("print(pd.DataFrame(summary['theoretical_fixed_proc']).round(4).to_string(index=False))\nprint(comparison[(comparison.level==100)&(comparison.gap_h.isin([8,12]))&(comparison.session_min==12)][['variant','gap_h','hero_class','speed_vs_paladin_pct']].round(4).to_string(index=False))\nprint(pd.DataFrame(summary['comparisons']).round(4).to_string(index=False))\nassert np.isclose(summary['theoretical_fixed_proc'][1]['effective_pct'],35.035573199950406)\n")
    md('## Takeaways\n\n35%는 전투·연출의 개성을 강화하지만 전체 성장속도를 16.7% 높이는 것은 아니다. 8시간 간격에서는 마법사가 레인저보다 약 0.93% 빠르고, 12시간 간격에서는 전사가 여전히 가장 빠르다. 초기 성장의 차이는 작고 후반에 효과가 커진다. 판매·가방의 경제적 가치는 성장속도와 동일 점수로 합산하지 않았다. 8개 seed의 결과라 전체 난수, 실제 접속 행동, 장기 경제를 보장하지 않는다.\n')
    env={}; count=0
    for cell in cells:
        cell['id']=uuid.uuid4().hex[:8]
        if cell['cell_type']=='code':
            count+=1; capture=io.StringIO()
            with contextlib.redirect_stdout(capture): exec(compile(''.join(cell['source']),cell['id'],'exec'),env)
            cell['execution_count']=count
            cell['outputs']=[dict(output_type='stream',name='stdout',text=capture.getvalue().splitlines(True))]
    doc=dict(nbformat=4,nbformat_minor=5,cells=cells,metadata=dict(kernelspec=dict(display_name='Python 3',language='python',name='python3'),language_info=dict(name='python'),
        validation=dict(mode='plain-python-sequential',code_cells_passed=count,jupyter_kernel_executed=False)))
    assert count==3
    (OUT/'mage35-review.ipynb').write_text(json.dumps(doc,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__':main()
