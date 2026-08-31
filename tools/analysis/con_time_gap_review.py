#!/usr/bin/env python3
"""Read-only game-data sensitivity review; outputs are analysis artifacts only."""
import contextlib
from datetime import datetime
import hashlib
import io
import json
import uuid
import numpy as np
import pandas as pd
from con_mental_review import OUT as INPUT,verify
from final_class_bonus_review import ordered,CLASSES,LABELS
from progression_bonus_review import calendar

OUT=INPUT/'time-gap-review'
POWERS={.6:'현재 0.6',1.0:'완만한 강화 1.0',1.2:'강한 특화 1.2'}

def time_effect(x,power):
    t=((x.level-1)/99).clip(0,1)
    return 8+2*(x.con/150).clip(0,1)**power*(.15+.85*t**1.2)

def calculate():
    summaries=[];frames=[]
    for name,seeds in [('matched.csv',list(range(14,30))),('heldout.csv',list(range(30,38)))]:
        p=INPUT/name;receipt=json.loads((INPUT/(name+'.execution.json')).read_text())
        assert hashlib.sha256(p.read_bytes()).hexdigest()==receipt['output_sha256']
        raw=pd.read_csv(p);x=verify(raw,seeds);x=ordered(x[x.level<=100],seeds)
        reference=None
        for power,label in POWERS.items():
            y=x.copy();y['cap_h']=time_effect(x,power)
            assert y.cap_h.between(8,10).all()
            assert (y.groupby(['class','seed_index']).cap_h.diff().dropna()>=0).all()
            if power==.6:assert np.allclose(y.cap_h,x.cap_h,atol=1e-12,rtol=0)
            if power>.6:assert (y.cap_h<=x.cap_h+1e-12).all()
            scenarios,detail=calendar(y)
            full8=detail[(detail.gap_h==8)&(detail.session_min==12)].reset_index(drop=True)
            if reference is None:reference=full8
            else:pd.testing.assert_frame_equal(reference,full8,check_exact=True)
            summaries.append(dict(cohort=name,power=power,label=label,
                max_growth_spread_pct=float(scenarios.growth_spread_pct.max()),
                max_sale_spread_pct=float(scenarios.sale_spread_pct.max())))
            y['power']=power;frames.append(y[['class','seed_index','level','con','cap_h','power']])
    z=pd.concat(frames,ignore_index=True)
    means=z.groupby(['power','level','class']).cap_h.mean()
    comparisons=[]
    for p,label in POWERS.items():
        at100=means.loc[(p,100)];others=at100.drop('WARRIOR')
        comparisons.append(dict(power=p,label=label,warrior_h=float(at100['WARRIOR']),
            other_h_min=float(others.min()),other_h_max=float(others.max()),
            gap_min_minutes=float((at100['WARRIOR']-others.max())*60),
            gap_max_minutes=float((at100['WARRIOR']-others.min())*60),
            max_growth_spread_pct=max(r['max_growth_spread_pct'] for r in summaries if r['power']==p)))
    return summaries,comparisons,means

def dump(name,data):
    (OUT/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')

def main():
    OUT.mkdir(exist_ok=True)
    cohorts,comparisons,means=calculate()
    dump('summary.json',dict(cohorts=cohorts,comparison=comparisons,
        method='Existing synthetic engine replays; only time-cap calendar postprocessing changed.',
        evaluated_levels='20..100',sessions_min=[1,5,12],gaps_h=[4,6,8,9,10,12,16,24],
        checks=['Input SHA256 verified','Original formula parity','Monotone and bounded time curves','8h/full-charge results exactly unchanged'],
        game_replays_added=0,app_changed=False,db_access=False))
    means.rename('cap_h').reset_index().to_csv(OUT/'level-class-means.csv',index=False)
    source=dict(label='전사 모험시간 차이 민감도 검토',files=[dict(label=f) for f in ['matched.csv','heldout.csv','con_time_gap_review.py']],
        filters=['직업: 6종','합성 성장 경로: 직업당24개','시간 외 스킬·탐색·판매·가방: 기존안 유지'],
        metricDefinitions=[dict(name='모험시간 상한',definition='CON에 따른 시간 저장 상한. 기본8시간을 포함한다.',formula='H=8+2×min(CON/150,1)^q×(0.15+0.85×x^1.2), x=clamp((L−1)/99,0,1)'),
            dict(name='성장 격차',definition='20~100레벨, 접속간격8종과접속시간3종에서 가장 느린 직업평균 도달시간을 가장 빠른 직업평균과 비교한 최대값.',formula='(최대도달시간/최소도달시간−1)×100')],
        caveats=['기존144개 합성 경로의 시간모델만 재계산했으며 새 전투 재생은 하지 않았다.',
            '실제 이용자 접속실험이나 모집단 통계가 아닌 반복충전 캘린더 환산이다.',
            '최대6% 성장격차는 이전 기획검토 기준이다. 강화안1.0/1.2는 이 기준을 넘는다.',
            '8시간마다 완충 상태로 접속하면 모든 안에서 시간상한이8시간 이상이라 저장량 차이의 이득이 없다.',
            '앱·DB·기기 등록에는 변경이 없다.'])
    rows=[dict(con=v,series=label,bonus_min=round(120*min(v/150,1)**p,5)) for p,label in POWERS.items() for v in range(0,181,5)]
    dump('chart-input.json',dict(schemaVersion=1,id='con-time-gap',title='100레벨: CON에 따른 추가 모험시간',
        chart=dict(type='line',x='con',y='bonus_min',series='series',xLabel='CON',yLabel='기본 8시간 외 보너스 (분)',showXAxisLabel=True),
        rows=rows,height=300,theme='codex-classic',generatedAt=datetime.now().astimezone().isoformat(),
        source={**source,'filters':['레벨: 100','CON: 0~180','곡선 지수: 0.6/1.0/1.2'],
            'caveats':['실측 분포가 아니라 후보 공식의 계산 곡선이다.','능력치와 레벨 성장식은 변경하지 않는다.']}))
    dump('sources-input.json',dict(schemaVersion=1,items=[dict(id='con-time-comparison',title='시간 특화와 성장 격차 비교',queries=[
        dict(id='variant-summary',source=source,rows=comparisons,preview=dict(kind='aggregate',note='각직업24개 경로평균 및 두 집단의 최악 시나리오'),
            columns=[dict(field=f,label=l) for f,l in [('label','후보'),('warrior_h','전사(h)'),('other_h_min','타직업최소(h)'),('other_h_max','타직업최대(h)'),('gap_min_minutes','시간차최소(분)'),('gap_max_minutes','시간차최대(분)'),('max_growth_spread_pct','최대성장격차(%)')]])])]))
    lines=['# 전사 모험시간 차이 검토','',
        '판정: 현재0.6은 비주력 혜택을 보호한 곡선이다. 직업 특화 강화가 우선이면1.0을 다음 후보로 권고한다. 그러나 기존 성장격차6% 기준을 넘으므로 기존안보다 무조건 균형이 좋다는 뜻은 아니다. 앱에는 적용하지 않았다.','',
        '기존 식의 CON 지수만0.6→1.0으로 조정한다. 시간최대10h,CON기준150,레벨배율,INT/WIS50:50 및 다른혜택은 그대로다.','',
        '| 후보 | 전사(h) | 다른직업(h) | 차이(분) | 최대성장격차 |','|---|---:|---:|---:|---:|']
    for r in comparisons:lines.append(f"|{r['label']}|{r['warrior_h']:.4f}|{r['other_h_min']:.4f}~{r['other_h_max']:.4f}|{r['gap_min_minutes']:.2f}~{r['gap_max_minutes']:.2f}|{r['max_growth_spread_pct']:.4f}%|")
    lines+=['','- 100레벨 평균의 격차와 20~100누적 성장격차는 다른 지표다. 누적격차로100레벨 이후 장기균형까지 보장하지 않는다.',
        '- 8시간마다 완충 접속하면 cap차이의 효율효과는0이다. 9~12시간 이상 비우거나부분충전할때 CON시간이 실질혜택을 준다.',
        '- 전사를 버프한다기보다 다른직업의 모험시간을 낮춰격차를 만드는 변경이다. 전사도 CON150 미만이면 일부시간감소가 있다.',
        '- 0.6/0.8/1.0/1.1/1.2/1.4/1.6/2.0을 탐색했으며1.0/1.2를 비교대표로 표시했다. 다른레벨배율도 민감도확인했으나 이 권고에는 반영하지 않았다.',
        '- 원래144경로를 재활용한후처리다. 신규전투재생·앱실행·네트워크·DB접근없음.',
        '- 비교노트북 코드셀은 일반Python순차실행. Jupyter커널미실행: nbformat/nbclient/ipykernel 미설치. 커널환경에서는 python -m jupyter nbconvert --execute --to notebook --inplace con-time-gap.ipynb.']
    (OUT/'REVIEW.md').write_text('\n'.join(lines)+'\n')
    notebook()
    print(json.dumps(comparisons,ensure_ascii=False,indent=2))

def notebook():
    cells=[]
    def md(t):cells.append(dict(cell_type='markdown',metadata={},source=t.splitlines(True)))
    def code(t):cells.append(dict(cell_type='code',metadata={},source=t.splitlines(True),execution_count=None,outputs=[]))
    md('## tl;dr\n\n시간특화를강화하려면CON지수1.0이완만한후보다. 단기존6%성장격차기준을넘는다.\n\n## Context & Methods\n\n### Key Assumptions\n\n기존144개합성경로의전투결과는고정. 시간상한과반복충전환산만변경한다. 실제사용자실험이아니다.\n\n## Data\n\nmatched.csv와heldout.csv를SHA256검증해재사용.\n')
    code("from pathlib import Path\nimport sys\nroot=next(p for p in [Path.cwd(),*Path.cwd().parents] if (p/'tools/analysis/con_time_gap_review.py').exists())\nsys.path.insert(0,str(root/'tools/analysis'))\nfrom con_time_gap_review import calculate\ncohorts,comparison,means=calculate()\nprint(comparison)\n")
    md('## Results\n\n8시간완충결과동일성을계산함수내에서검증한다.\n')
    code("assert comparison[0]['max_growth_spread_pct']<6\nassert 7<comparison[1]['max_growth_spread_pct']<7.5\nassert comparison[1]['gap_min_minutes']>75\nassert all(8<=r['warrior_h']<=10 for r in comparison)\nprint('Formula, scenario equality and reported magnitudes: PASS')\n")
    md('## Takeaways\n\n1.0안은직업개성강화를위한절충이며최적해확정이아니다. 8시간완충에서는시간격차확대만으로전사효율문제를해결하지못한다.\n\n일반Python순차검증완료. Jupyter커널미실행(nbformat/nbclient/ipykernel미설치). 커널환경에서 python -m jupyter nbconvert --execute --to notebook --inplace con-time-gap.ipynb로재검증.\n')
    env={};n=0
    for c in cells:
        c['id']=uuid.uuid4().hex[:8]
        if c['cell_type']=='code':
            n+=1;buf=io.StringIO()
            with contextlib.redirect_stdout(buf):exec(''.join(c['source']),env)
            c['execution_count']=n;c['outputs']=[dict(output_type='stream',name='stdout',text=buf.getvalue().splitlines(True))]
    dump('con-time-gap.ipynb',dict(nbformat=4,nbformat_minor=5,cells=cells,metadata=dict(kernelspec=dict(display_name='Python3',language='python',name='python3'),language_info=dict(name='python'),validation=dict(plain_python_cells=n,jupyter_kernel_executed=False))))

if __name__=='__main__':main()
