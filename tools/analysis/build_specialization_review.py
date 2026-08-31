#!/usr/bin/env python3
"""Package the latest user-selected specialization simulation and paired comparison."""
from datetime import datetime
import contextlib
import io
import json
import uuid
import pandas as pd
from final_class_bonus_review import CLASSES,LABELS
from con_mental_review import OUT as ROOT

OUT=ROOT/'specialization-dex35-cha15'
BASE=ROOT/'specialization-cha15'
# Update the already-created inline comparison surfaces to the newest request.
VISUAL=ROOT/'time-gap-review'

def dump(path,data):path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')

def comparison():
    old=pd.concat([pd.read_csv(BASE/n) for n in ['matched.csv','heldout.csv']],ignore_index=True)
    new=pd.concat([pd.read_csv(OUT/n) for n in ['matched.csv','heldout.csv']],ignore_index=True)
    keys=['class','seed_index','level']
    pair=old[keys+['seconds','sale_gold','equipment_power']].merge(new[keys+['seconds','sale_gold','equipment_power']],on=keys,suffixes=('_old','_new'),validate='one_to_one')
    assert len(pair)==14400 and len(old)==len(new)==len(pair)
    z=pair.groupby(['class','level'])[['seconds_old','seconds_new','sale_gold_old','sale_gold_new']].mean()
    z['completion_time_reduction_pct']=100*(1-z.seconds_new/z.seconds_old)
    z['sale_total_change_pct']=100*(z.sale_gold_new/z.sale_gold_old-1)
    return z

def main():
    s=json.loads((OUT/'SUMMARY.json').read_text());old=json.loads((BASE/'SUMMARY.json').read_text())
    z=comparison();z.to_csv(OUT/'dex-four-to-three-half-comparison.csv')
    at100=z.xs(100,level='level');growth=max(r['max_growth_spread_pct'] for r in s['cohorts']);sales=max(r['max_sale_spread_pct'] for r in s['cohorts'])
    result=dict(latest=s,baseline=old,paired_level100=at100.to_dict('index'))
    dump(OUT/'FINAL_COMPARISON.json',result)
    lines=['# 직업 특화 강화: CON / 정신력50:50 / DEX3.5초 / CHA15%','',
        '기획 후보에만 반영했다. 앱 코드·DB·기기 등록 변경 없음. 균등 효율보다 직업 개성을 우선한다는 사용자의 최신 방향을 따른다.','',
        '## 확정해서 시험한 조건','',
        '- CON모험시간: `8+2×min(CON/150,1)^1.2×(0.15+0.85×x^1.2)`시간.',
        '- 정신력: `S=(INT+WIS)/2`. 스킬확률:`min(직업상한,P0+15×min(S/180,1)^1.2)`%. 마법사35%,나머지30%.',
        '- DEX탐색시간: `5−1.5×min(DEX/150,0.12+0.88×x^1.4)`초. 최소3.5초.',
        '- CHA판매추가: `15×min(CHA/150,0.12+0.88×x^1.4)`%.',
        '- `x=clamp((L−1)/99,0,1)`. STR가방·능력치성장·P0직업적성식유지. 성직자WIS/CHA유지.',
        '- 직업특화강화는CON지수1.2후보로해석해적용했다. 35%외에시간/탐색/판매는직업별하드락이아니다.','',
        '## 100레벨 평균 — 직업당24경로','',
        '| 직업 | 모험시간(h) | 스킬(%) | 탐색(초) | 판매추가(%) | 가방(칸) |',
        '|---|---:|---:|---:|---:|---:|']
    for c in CLASSES:lines.append('| '+LABELS[c]+' | '+' | '.join(f"{s['level100'][c][f]:.4f}" for f in ['cap_h','raw_proc_pct','search_s','sale_bonus_pct','bag'])+' |')
    lines+=['','## DEX4秒안 대비 탐색3.5秒 변경 영향','',
        '동일한CON강화·CHA15%·정신력50:50에서DEX만바꾼144쌍의실제전투·상점재생비교다. 아래도달시간은엔진내누적진행시간이다. 8시간완충조건의캘린더시간도같은비율이다.','',
        '| 직업 | 100레벨 도달시간 감소(%) | 목표레벨까지 누적판매금액 변화(%) |','|---|---:|---:|']
    for c in CLASSES:lines.append(f"|{LABELS[c]}|{at100.loc[c,'completion_time_reduction_pct']:.4f}|{at100.loc[c,'sale_total_change_pct']:.4f}|")
    lines+=['','## 종합 균형','',
        f'- 20~100레벨,8종접속간격×3종접속시간,두표본집단을별도평가한성장최대격차{growth:.4f}%,판매수입최대격차{sales:.4f}%.',
        '- 판정: DEX특화를강화하려는현재기획방향의후보로적합하다. 두표본모두100레벨까지8시간완충조건은레인저1위,12시간완충과부분충전은전사1위,판매수입은성직자1위다.',
        '- 8시간완충의직업성장격차는4초안최대4.2544%에서3.5초안최대5.3233%로증가한다. 모든접속조건중최대값이거의같더라도이조건에서레인저우위가커진다는점을숨기지않는다.',
        '- 팔라딘은장기접속간격/부분충전에서성장하위권이다. 판매·가방특화의실제체감은추가확인대상이다.',
        '- 기존6%성장격차기준을통과하는지와직업개성강화를우선할지는구분한다. 새로운수치가드레일을사용자가승인한것은아니다.',
        '- DEX3.5초는탐색구간12.5%감소이지전체성장속도12.5%증가가아니다. 전투·이동·판매등은별도로소요된다.',
        '- CHA보너스20→15%는최대판매가격배율1.20→1.15,즉그조건에서가격4.17%감소다. 판매총수입25%감소가아니다.',
        '- 판매금액은장비구매에도영향을주므로금액만0.75배하지않고엔진을재생했다.',
        '- 시간상한은8시간완충접속에서는차별화효과가없다. 긴부재시간과부분충전에서전사특화가나타난다.',
        '- 스킬표는일반발동확률이며연속일반공격후확정발동을제외한다.','',
        '## 검증 범위와 재현','',
        '- 4초안144게임과3.5초안144게임을각각1~100레벨재생. 두안의6능력치·HP/MP·P0·가방·설화수14,400행동일확인.',
        f"- {s['boundary']}. Kotlin훅/Python공식14,400행대조통과.",
        '- 네트워크·DB파일접근OS차단. 원본앱소스해시확인. Android앱·실제기기실행없음.',
        '- 합성경로결과이며실제사용자실험아님. 캘린더효율은반복충전근사이고101레벨이후장기경제·성장은미검증.',
        '- 이전AI기획검토는이3.5초조합을검토한것이아니므로신규팀승인으로표현하지않는다.',
        '- run_con_mental_probe.py --profile specialization-dex35-cha15 → specialization_review.py --dex-floor 3.5 → build_specialization_review.py.',
        '- 노트북코드셀일반Python순차실행검증. nbformat/nbclient/ipykernel미설치로Jupyter커널미실행.',
        '- 커널환경에서 python -m jupyter nbconvert --execute --to notebook --inplace specialization-review.ipynb.']
    (OUT/'REVIEW.md').write_text('\n'.join(lines)+'\n')
    common=dict(label='직업 특화 강화·DEX3.5초 비교',files=[dict(label=n) for n in ['4초안 matched.csv','4초안 heldout.csv','3.5초안 matched.csv','3.5초안 heldout.csv','SpecializationDexBonusHooks.kt','specialization_review.py']],
        filters=['직업: 6종','동일시드비교: 직업당24쌍','기본8시간·최대10시간','CON지수: 1.2','CHA판매상한: 15%','INT/WIS비중: 50/50'],
        caveats=['합성엔진재생이며실제사용자실험이아니다.','캘린더효율은반복충전근사이다.','앱·DB·기기등록에반영하지않았다.'])
    chart_rows=[dict(level=l,series=LABELS[c],reduction_pct=round(float(z.loc[(c,l),'completion_time_reduction_pct']),5)) for c in CLASSES for l in range(20,101,5)]
    dump(OUT/'chart-input.json',dict(schemaVersion=1,id='dex-specialization-growth',title='탐색 4초 → 3.5초: 레벨 도달시간 감소',
        chart=dict(type='line',x='level',y='reduction_pct',series='series',xLabel='목표 레벨',yLabel='누적 진행시간 감소 (%)',showXAxisLabel=True),
        rows=chart_rows,height=300,theme='codex-classic',generatedAt=datetime.now().astimezone().isoformat(),
        source={**common,'metricDefinitions':[dict(name='도달시간 감소',definition='DEX4초안대비3.5초안의동일목표레벨까지누적진행시간감소율. 직업별24경로평균끼리비교한다.',formula='100×(1−3.5초안평균진행시간/4초안평균진행시간)')]}))
    benefits=[dict(hero_class=LABELS[c],**s['level100'][c]) for c in CLASSES]
    source_rows=[dict(cohort=r['cohort'],growth_pct=r['max_growth_spread_pct'],sale_pct=r['max_sale_spread_pct']) for r in s['cohorts']]
    dump(OUT/'sources-input.json',dict(schemaVersion=1,items=[
        dict(id='specialization-benefits',title='최신 직업별 보너스',queries=[dict(id='level100',source=common,rows=benefits,
            preview=dict(kind='aggregate',note='각직업100레벨24경로평균'),columns=[dict(field=f,label=l) for f,l in [('hero_class','직업'),('cap_h','모험시간(h)'),('raw_proc_pct','스킬(%)'),('search_s','탐색(초)'),('sale_bonus_pct','판매추가(%)'),('bag','가방(칸)')]])]),
        dict(id='specialization-balance',title='시간 특화와 성장 격차 비교',queries=[dict(id='balance',source={**common,
            'filters':common['filters']+['평가레벨: 20~100','접속간격: 4/6/8/9/10/12/16/24시간','접속시간: 1/5/12분'],
            'metricDefinitions':[dict(name='최대격차',definition='각접속조건·목표레벨에서최고효율직업평균대비최저효율직업평균의격차중최대값. 판매는아이템판매수입/환산기간이며퀘스트골드와보유재산을제외한다.')]},rows=source_rows,
            columns=[dict(field='cohort',label='표본집단'),dict(field='growth_pct',label='성장최대격차(%)'),dict(field='sale_pct',label='판매최대격차(%)')]),
            dict(id='dex-paired',source={**common,'metricDefinitions':[dict(name='도달시간 감소',definition='100레벨까지의진행시간을DEX4초안과비교.판매누적총액변화는시간당수입변화와다르다.')]},
                rows=[dict(hero_class=LABELS[c],reduction_pct=float(at100.loc[c,'completion_time_reduction_pct'])) for c in CLASSES],
                columns=[dict(field='hero_class',label='직업'),dict(field='reduction_pct',label='도달시간감소(%)')])])]))
    notebook()
    print(json.dumps(dict(level100=s['level100'],paired=at100.to_dict('index'),max_growth=growth,max_sale=sales),ensure_ascii=False,indent=2))

def notebook():
    cells=[]
    def md(t):cells.append(dict(cell_type='markdown',metadata={},source=t.splitlines(True)))
    def code(t):cells.append(dict(cell_type='code',metadata={},source=t.splitlines(True),execution_count=None,outputs=[]))
    md('## tl;dr\n\nCON특화·정신력50:50·CHA15%에서DEX최저3.5초를시험한다.\n\n## Context & Methods\n\n### Key Assumptions\n\nDEX4초대조144게임,3.5초144게임. 같은시드,같은CON·스킬·CHA조건. 실제이용자실험아님.\n\n## Data\n')
    code("from pathlib import Path\nimport sys,json\nroot=next(p for p in [Path.cwd(),*Path.cwd().parents] if (p/'tools/analysis/build_specialization_review.py').exists())\nsys.path.insert(0,str(root/'tools/analysis'))\nfrom build_specialization_review import OUT,comparison\nfrom specialization_review import check\nimport specialization_review as review\nimport pandas as pd\nreview.DEX_FLOOR=3.5\nfor name,seeds in [('matched.csv',list(range(14,30))),('heldout.csv',list(range(30,38)))]:\n check(pd.read_csv(OUT/name),seeds)\nz=comparison()\nprint('Validated paired snapshots:',14400)\n")
    md('## Results\n')
    code("print(z.xs(100,level='level').round(5).to_string())\ns=json.loads((OUT/'SUMMARY.json').read_text())\nprint('Balance:',s['cohorts'])\nassert s['stat_identity_rows']==14400 and not s['app_changed'] and not s['db_access']\nprint(s['boundary'])\n")
    md('## Takeaways\n\n탐색구간의12.5%감소와전체진행시간변화는구분한다. 직업별효과와접속조건별선두를따로평가한다. 기존6%균형기준과직업개성우선의트레이드오프를명시한다.\n\n일반Python순차실행검증. Jupyter커널미실행(nbformat/nbclient/ipykernel미설치). 커널환경에서 python -m jupyter nbconvert --execute --to notebook --inplace specialization-review.ipynb.\n')
    env={};n=0
    for c in cells:
        c['id']=uuid.uuid4().hex[:8]
        if c['cell_type']=='code':
            n+=1;b=io.StringIO()
            with contextlib.redirect_stdout(b):exec(''.join(c['source']),env)
            c['execution_count']=n;c['outputs']=[dict(output_type='stream',name='stdout',text=b.getvalue().splitlines(True))]
    dump(OUT/'specialization-review.ipynb',dict(nbformat=4,nbformat_minor=5,cells=cells,metadata=dict(kernelspec=dict(display_name='Python3',language='python',name='python3'),language_info=dict(name='python'),validation=dict(plain_python_cells=n,jupyter_kernel_executed=False))))

if __name__=='__main__':main()
