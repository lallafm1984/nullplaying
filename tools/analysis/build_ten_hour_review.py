#!/usr/bin/env python3
"""Prepare reviewed, local-only chart evidence and a reproducible companion."""
import contextlib
from datetime import datetime
import io
import json
import uuid
import pandas as pd
from stat_bonus_balance import OUT, ROOT


def main():
    summary=json.loads((OUT/'ten_hour_validation.json').read_text())
    selection=json.loads((OUT/'ten_hour_selection.json').read_text())
    p=summary['selected_parameters']
    gaps=pd.read_csv(OUT/'ten_hour_gaps.csv')
    names={'previous_12h':'기존 12시간','linear_10h':'10시간 비례식','recommended_10h':'10시간 권장식'}
    plotted=gaps[(gaps.cohort=='independent')&(gaps.gap_h==12)&(gaps.session_min==12)&(gaps.level%10==0)].copy()
    rows=[dict(level=int(r.level),plan=names[r.variant],gap_pct=round(r.progress_gap_pct,4)) for _,r in plotted.iterrows()]
    shared_caveats=[
        '직업당 8개 독립 성장 경로이며 전체 난수나 실제 이용자 집단의 결과를 보장하지 않습니다.',
        '전투 경로는 순수 엔진 재생, 접속 간격 효과는 레벨별 시간저장량 환산입니다.',
        'HP 곡선만 비교하고 MP 30%, 탐색 최소 4초, 판매 보너스 최대 20%는 유지했습니다.'
    ]
    hybrid=summary.get('cleric_stat_pair')=='WIS/CHA'
    if hybrid:
        shared_caveats.append('성직자는 WIS/CHA로 변경해 다시 재생했고, 변경 없는 다른 직업의 동일 난수 결과는 재사용했습니다.')
    cap_summary=' · '.join(f"{name} 특화 {summary['cap_checks'][field]['specialist_at_cap']}/{summary['cap_checks'][field]['specialist_count']}" for field,name in [('cap_h','HP'),('raw_proc_pct','MP'),('search_s','DEX'),('sale_bonus_pct','CHA')])
    source=dict(label='로컬 엔진 성장 시뮬레이션',
        files=[{'label':'ten_hour_independent_dense.csv'},{'label':'ten_hour_balance.py'}],
        filters=['직업: 6종','독립 난수 경로: 직업당 8개','접속 간격: 12시간','접속 시 충전: 12분'],
        metricDefinitions=[{'name':'성장속도 격차','definition':'같은 목표 레벨까지의 직업별 평균 소요시간을 비교한 성장속도 격차입니다.',
            'formula':'(가장 빠른 직업의 성장속도 / 가장 느린 직업의 성장속도 - 1) × 100'}],
        caveats=shared_caveats)
    chart=dict(schemaVersion=1,id='ten-hour-growth-balance',title='12시간 간격 접속: 레벨별 직업 성장 격차',
        description='12시간 간격 접속 · 매번 12분 충전 · 낮을수록 균등',
        chart=dict(type='line',x='level',y='gap_pct',series='plan',xLabel='도달 레벨',yLabel='성장속도 격차 (%)',showXAxisLabel=True),
        rows=rows,source=source,height=330,theme='codex-classic',generatedAt=datetime.now().astimezone().isoformat())
    (OUT/'ten_hour_chart_input.json').write_text(json.dumps(chart,ensure_ascii=False,indent=2)+'\n')
    effects=[]
    labels={'WARRIOR':'전사','ROGUE':'도적','RANGER':'레인저','MAGE':'마법사','CLERIC':'성직자','PALADIN':'팔라딘'}
    for cls in labels:
        e=summary['level100_effects'][cls]
        effects.append({'class':labels[cls],**e})
    gap_rows=[dict(plan=names[r['variant']],gap_h=r['gap_h'],growth_gap_pct=round(r['progress_gap_pct'],4)) for r in summary['level100_gaps']]
    receipt=dict(schemaVersion=1,items=[
        dict(id='ten-hour-growth-review',title='10시간 상한과 성장속도 격차',queries=[
            dict(id='independent-growth-gap',source={**source,
                'filters':['직업: 6종','독립 난수 경로: 직업당 8개','목표: 100레벨','접속 간격: 8·12·24시간','접속 시 충전: 12분'],
                'evidenceFlow':[{'kind':'validation','title':'수치 대조','detail':'시간저장량 환산의 두 구현이 동일한 결과를 냈으며, 100레벨 적분 상하한 차이는 최대 0.266%입니다.'}]},
                rows=gap_rows,columns=[{'field':'plan','label':'설정'},{'field':'gap_h','label':'접속 간격 (시간)'},{'field':'growth_gap_pct','label':'성장속도 격차 (%)'}],
                preview={'kind':'aggregate','note':'직업별 평균 소요시간으로 계산한 100레벨 비교','totalRows':9}),
            dict(id='hp-curve-candidates',source={'label':'HP 후보 곡선 비교','files':[{'label':'ten_hour_candidate_grid.csv'},{'label':'ten_hour_selection.json'}],
                'metricDefinitions':[{'name':'후보 선택','definition':'기본 8시간과 최대 10시간을 유지하는 96개 HP 곡선 중, 개성 기준을 만족한 15개를 비교했습니다.',
                    'calculationSummary':'최저 격차와 0.25%p 이내인 후보에서는 기존 HP 기준 6,000을 우선합니다.'}],
                'filters':['보정 경로: 직업당 4개','목표 레벨: 20~100','HP 기준: 5,000~6,250','곡선 지수: 0.5~2.0'],
                'caveats':['전체 가능한 공식의 전역 최적값이 아니라 지정한 후보 범위의 비교입니다.',
                    '비특화 HP 추가시간 30~75분을 보정 기준으로 삼았으나, 독립 표본 1개는 76.23분으로 1.23분 초과했습니다.']},
                rows=[{'choice':'탐색 최저 격차','hp_threshold':6250,'hp_power':.8,'growth_gap_pct':selection['best']['max_growth_gap_pct']},
                      {'choice':'권장값','hp_threshold':6000,'hp_power':.8,'growth_gap_pct':selection['selected']['max_growth_gap_pct']}],
                columns=['choice','hp_threshold','hp_power','growth_gap_pct'])]),
        dict(id='ten-hour-class-identity',title='100레벨 직업별 보너스',queries=[
            dict(id='independent-class-effects',source={'label':'100레벨 직업별 효과','files':[{'label':'ten_hour_class_effects.csv'}],
                'metricDefinitions':[{'name':'HP 보유시간','definition':'HP 보유시간은 기본 8시간에 최대 2시간이 더해집니다.',
                    'formula':'8 + 2 × min(HP / 6000, 1)^0.8 시간'},
                    {'name':'MP 확률','definition':'기존 기본 발동 확률에 MP 보너스가 최대 10%p 더해지며, 총 기본 확률은 30%가 상한입니다.',
                    'formula':'min(30, 기존 확률 + 10 × min(MP / 6000, 1)) %'},
                    {'name':'DEX 탐색','definition':'탐색 시간은 DEX에 따라 5초에서 최소 4초까지 줄고, 발견 연출 2초는 유지됩니다.',
                    'formula':'5 - min(DEX / 150, 1) 초'},
                    {'name':'CHA 판매','definition':'아이템 판매 보너스만 CHA에 따라 최대 20% 증가하며 퀘스트 보상과 드롭 골드에는 붙지 않습니다.',
                    'formula':'20 × min(CHA / 150, 1) %'}],
                'filters':['직업: 6종','독립 난수 경로: 직업당 8개','목표: 100레벨'],
                'caveats':['표는 각 성장 경로의 효과를 계산한 후 평균한 값입니다.',
                    cap_summary+' 경로가 상한에 도달했습니다. 비특화 직업의 상한 도달은 없었습니다.',
                    '일부 낮은 성장 경로는 100레벨에도 상한 직전입니다. 모든 난수에서의 도달이나 직업 잠금을 보장하지 않습니다.',
                    '연속 일반공격 후 확정 발동 규칙을 유지하므로 기본 발동 확률과 실제 시전 비율은 다릅니다.']},
                rows=effects,columns=[{'field':'class','label':'직업'},{'field':'cap_h','label':'보유시간 (시간)'},{'field':'raw_proc_pct','label':'기본 발동 확률 (%)'},
                    {'field':'search_s','label':'탐색 시간 (초)'},{'field':'sale_bonus_pct','label':'판매 보너스 (%)'}],
                preview={'kind':'aggregate','note':'직업별 8개 성장 경로의 평균 효과','totalRows':6})])])
    (OUT/'ten_hour_sources_input.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
    build_notebook(summary)
    print('Prepared chart, Sources receipt, and sequentially checked notebook.')


def build_notebook(summary):
    cells=[]
    def md(s):cells.append(dict(cell_type='markdown',metadata={},source=s.splitlines(True)))
    def code(s):cells.append(dict(cell_type='code',metadata={},source=s.splitlines(True),execution_count=None,outputs=[]))
    md('## tl;dr\n\nHP는 기본 8시간, 최대 10시간. 추천 HP 지수는 0.8, 기준은 6,000이다. 독립 48개 경로에서 12시간 접속 간격의 직업 성장속도 격차는 5.5062%로, 이전 12시간 상한안 10.6188%보다 작았다. 운영 적용은 하지 않았다.\n')
    md('## Context & Methods\n\n### Key Assumptions\n\nMP·DEX·CHA는 기존 상한 곡선을 유지한다. HP만 96개 곡선을 보정 경로 seed 2–5에서 비교하고, seed 6–13은 별도로 검증한다. 상대 성장속도는 같은 목표 레벨까지 평균 소요시간의 역수다. 접속 주기는 엔진 전투 경로에 시간저장량을 환산한 근사이며 실제 플레이어 로그가 아니다.\n\nJupyter 패키지가 없어 코드 셀은 일반 Python으로 순서대로 실행했다. Jupyter 커널 자체의 실행은 미검증이다. 재실행하려면 pandas/numpy 및 nbconvert/ipykernel이 있는 환경에서 `python -m jupyter nbconvert --execute --to notebook --inplace ten_hour_review.ipynb`를 실행한다.\n')
    md('## Data\n\n### 1. Load verified local trajectories\n')
    relative=OUT.relative_to(ROOT).as_posix()
    code("from pathlib import Path\nimport sys,json,itertools\nimport numpy as np\nimport pandas as pd\nanalysis_root=next(p for p in [Path.cwd(),*Path.cwd().parents] if (p/'tools/analysis/ten_hour_balance.py').exists())\nsys.path.insert(0,str(analysis_root/'tools/analysis'))\nimport ten_hour_balance as balance\nbalance.OUT=analysis_root/"+repr(relative)+"\nfrom ten_hour_balance import load,calendar,GAPS,SESSIONS,CLASSES,OUT\nparameters=json.loads((OUT/'ten_hour_selected_parameters.json').read_text())\ntrajectories=load('ten_hour_independent_dense.csv',list(range(6,14)))\nprint('Independent games:',trajectories.groupby(['class','seed_index']).ngroups,'Rows:',len(trajectories))\n")
    md('## Results\n\n### 2. Recompute the equal-level growth comparison\n')
    code("comparison=[]\nfor name,threshold,power,amplitude in [('previous_12h',6000,1,4),('linear_10h',6000,1,2),('recommended_10h',6000,.8,2)]:\n    result=calendar(trajectories,threshold,power,amplitude)\n    for gap in [8.,12.]:\n        index=list(itertools.product(GAPS,SESSIONS)).index((gap,12.))\n        comparison.append(dict(variant=name,gap_h=gap,growth_gap_pct=result['progress_gap'][-1,index]))\ncomparison=pd.DataFrame(comparison)\nassert np.isclose(comparison[(comparison.variant=='recommended_10h')&(comparison.gap_h==12)].growth_gap_pct.iloc[0],5.506151326265263)\nprint(comparison.round(4).to_string(index=False))\n")
    md('### 3. Inspect the effects and cap exceptions\n')
    code("review=json.loads((OUT/'ten_hour_validation.json').read_text())\nprint(pd.DataFrame.from_dict(review['level100_effects'],orient='index').round(4).to_string())\nprint(json.dumps(review['cap_checks'],indent=2))\nassert review['app_source_hash_unchanged']\nassert review['calendar_independent_implementation_match']\n")
    roles=('성직자는 WIS/CHA로 바뀌어 스킬·판매 혼합형이 되었다. 성직자 12개 경로를 다시 재생했고 다른 직업의 검증된 경로는 재사용했다.'
        if summary.get('cleric_stat_pair')=='WIS/CHA' else '메이지/클래릭은 스탯 보너스가 유사하므로 스킬 구성이 역할 차이를 담당해야 한다.')
    md('## Takeaways\n\n전사는 장기 방치, DEX 직업은 잦은 사냥, 마법사는 스킬 빈도, CHA 직업은 판매 가치로 구분한다. '+roles+' HP 후보 탐색 최저값은 기준 6,250/지수 0.8이지만, 0.1%p 미만 차이에 기준을 추가로 바꾸지 않고 6,000을 권장한다. 보정 기준의 비특화 추가시간 75분을 독립 표본 1개가 1.23분 초과했으며, DEX/CHA 저성장 경로도 상한 직전인 예외가 있었다. 전체 난수 공간이나 실제 이용자 집단의 전역 최적값은 아니다.\n')
    env={};count=0
    for cell in cells:
        cell['id']=uuid.uuid4().hex[:8]
        if cell['cell_type']=='code':
            count+=1;buf=io.StringIO()
            with contextlib.redirect_stdout(buf):exec(compile(''.join(cell['source']),cell['id'],'exec'),env)
            cell['execution_count']=count
            cell['outputs']=[dict(output_type='stream',name='stdout',text=buf.getvalue().splitlines(True))]
    notebook=dict(nbformat=4,nbformat_minor=5,cells=cells,metadata={
        'kernelspec':{'display_name':'Python 3','language':'python','name':'python3'},
        'language_info':{'name':'python'},
        'validation':{'mode':'plain-python-sequential','code_cells_passed':count,'jupyter_kernel_executed':False}})
    (OUT/'ten_hour_review.ipynb').write_text(json.dumps(notebook,ensure_ascii=False,indent=2)+'\n')
    assert count==3


if __name__=='__main__':main()
