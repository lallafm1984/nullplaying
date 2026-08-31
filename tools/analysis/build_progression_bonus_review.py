#!/usr/bin/env python3
"""Package the finalized v2 evidence into concise inline figures and review notes."""
from datetime import datetime
import contextlib
import io
import json
import subprocess
import uuid

import numpy as np
import pandas as pd

from progression_bonus_review import OUT,ROOT,BASELINE,CLASSES,LABELS,FIELDS,effects,caps,review,calendar
from run_progression_bonus_probe import prepare
import run_stat_bonus_probe as base


def dump(name,value):
    (OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')


def main():
    names=['envelope-matched-v2.csv','envelope-heldout-v2.csv','mage-k7500-v2.csv','baseline-identity-v2.csv']
    for name in names: assert (OUT/(name+'.execution.json')).exists(),name
    old=pd.read_csv(BASELINE)
    a=pd.read_csv(OUT/names[0]);b=pd.read_csv(OUT/names[1]);alt=pd.read_csv(OUT/names[2]);identity=pd.read_csv(OUT/names[3])
    keys=['class','seed_index','level']
    expected=old[old.seed_index==14].sort_values(keys).reset_index(drop=True)
    actual=identity.sort_values(keys).reset_index(drop=True)
    pd.testing.assert_frame_equal(expected,actual[old.columns],check_exact=True)
    matched=review(names[0],list(range(14,30)))
    heldout=review(names[1],list(range(30,38)))
    for frame,threshold in [(a,8000),(b,8000),(alt,7500)]:
        x=effects(frame,mage_threshold=threshold)
        for f,g in [('cap_h','bonus_cap_h'),('raw_proc_pct','bonus_proc_pct'),('sale_bonus_pct','bonus_sale_pct')]:
            assert np.allclose(x[f],frame[g],atol=1e-10,rtol=0)
    sandbox,jar,runtime=prepare()
    boundary=subprocess.run(sandbox+[str(base.JAVA),'-Xmx256m','-cp',':'.join([str(OUT/'boundary-checks.jar'),str(jar),*runtime]),
        'com.nullplaying.engine.ProgressionBonusChecks'],capture_output=True,text=True,check=True)
    assert '17958 PASS' in boundary.stdout
    (OUT/'boundary-checks-v2.log').write_text(boundary.stdout+boundary.stderr)
    x=effects(a);v=effects(alt,mage_threshold=7500)
    mean=x.groupby(['class','level'])[FIELDS].mean()
    before=effects(old,'baseline').groupby(['class','level'])[FIELDS].mean()
    first7500=v[v.raw_proc_pct>=35].groupby('seed_index')['level'].min()
    tail=effects(b[b['class']=='MAGE'])
    first8000=tail[tail.raw_proc_pct>=35].groupby('seed_index')['level'].min()
    time_extra=[]
    for seed,lvl in first8000.items():
        z=tail[tail.seed_index==seed].set_index('level')
        time_extra.append(dict(seed=int(seed),first_cap_level=int(lvl),
            extra_active_days=float((z.loc[lvl,'seconds']-z.loc[100,'seconds'])/86400)))
    # Level-cap increments are guaranteed by the formulas, not every character's RNG path.
    levels=[1,*range(10,101,10)]
    level_rows=[]
    for level in levels:
        h,s,c=caps(level)
        level_rows.append(dict(level=level,offline_ceiling_h=float(h),search_floor_s=float(s),sale_ceiling_pct=float(c),
            warrior_mean_h=float(mean.loc[('WARRIOR',level),'cap_h']),
            mage_mean_pct=float(mean.loc[('MAGE',level),'raw_proc_pct'])))
    duration=pd.read_csv(OUT/(names[0]+'.calendar.csv'))
    late=duration[(duration.level==100)&(duration.gap_h==8)&(duration.session_min==12)].set_index('hero_class')
    mage_gap=float((late.loc['MAGE','calendar_days']/late.loc['RANGER','calendar_days']-1)*100)
    summary=dict(selected='level_envelope',mage_threshold=8000,mage_exponent=1.15,
        matched=matched,heldout=heldout,level_rows=level_rows,
        alternative7500=dict(mean100_pct=float(v[v.level==100].raw_proc_pct.mean()),
            reached_by100=int((first7500<=100).sum()),first_min=int(first7500.min()),first_max=int(first7500.max()),samples=16),
        mage_first_cap_heldout=time_extra,mage_vs_ranger_completion_time_pct=mage_gap,
        verification=dict(recommended_games=144,alternative_games=16,identity_games=6,baseline_identical_rows=600,
            baseline_identical_columns=len(old.columns),boundary_checks=17958,hook_comparison_rows=len(a)+len(b)+len(alt),
            application_sources_unchanged=True,database_access=False,network_access=False),
        excluded=['All pre-v2 replay outputs are superseded: floating-point addition order differed at basis-point rounding boundaries.'],
        caveats=['AI design review, not human team approval.',
            'Calendar growth and sale comparisons use a time-bank conversion, not live session experiments.',
            'No player population probabilities follow from 24 Mage seeds.',
            '35% is an attainable post-100 completion goal, not an extremely rare ceiling.',
            'Level ceilings stop increasing at Lv100. Actual stat-driven benefit increments need not increase every level.'])
    dump('FINAL_SUMMARY.json',summary)
    common=dict(label='고레벨 보너스 재설계 검증',files=[dict(label=names[0]),dict(label=names[1]),
        dict(label='ProgressionBonusHooks.kt'),dict(label='progression_bonus_review.py')],
        filters=['직업: 6종','주 비교: 직업당 16개 경로, seed 14~29','추가 검증: 직업당 8개 경로, seed 30~37'],
        caveats=['실제 이용자 로그가 아닌 로컬 순수 엔진 재생입니다.',
                 '추천안의 100레벨은 직업당 16개 주 비교 경로 평균입니다. 신규 8개 경로는 별도로 확인했습니다.',
                 '운영 앱과 DB에는 적용하지 않은 기획 후보입니다.'])
    now=datetime.now().astimezone().isoformat()
    chart_levels=[1,*range(5,101,5)]
    chart_specs=[('hp','전사: 후반 오프라인 보너스 성장','추가 보유시간 (분)',[
        ('기존 전사',[float((before.loc[('WARRIOR',l),'cap_h']-8)*60) for l in chart_levels]),
        ('재설계 전사',[float((mean.loc[('WARRIOR',l),'cap_h']-8)*60) for l in chart_levels])],
        '기본 8시간을 제외한 전사의 추가 오프라인 보유시간 평균입니다.'),
        ('mage','마법사: 100레벨에도 남는 성장 여지','기본 스킬 확률 (%)',[
        ('기존 마법사',[float(before.loc[('MAGE',l),'raw_proc_pct']) for l in chart_levels]),
        ('재설계 마법사',[float(mean.loc[('MAGE',l),'raw_proc_pct']) for l in chart_levels]),
        ('재설계 레인저',[float(mean.loc[('RANGER',l),'raw_proc_pct']) for l in chart_levels]),
        ('재설계 성직자',[float(mean.loc[('CLERIC',l),'raw_proc_pct']) for l in chart_levels])],
        '각 레벨의 기본 스킬 발동 확률 평균입니다. 연속 일반공격 후 확정 발동은 제외합니다.')]
    for kind,title,ylabel,series,definition in chart_specs:
        rows=[dict(level=l,series=label,value=round(value,4)) for label,values in series for l,value in zip(chart_levels,values)]
        source={**common,'filters':common['filters'][:2]+['표시 레벨: 1 및 5~100의 5레벨 간격'],
            'metricDefinitions':[dict(name=title,definition=definition)],
            'files':common['files']+[dict(label='기존 all-heldout.csv')]}
        dump(f'{kind}-chart-input.json',dict(schemaVersion=1,id=f'progression-{kind}',title=title,
            chart=dict(type='line',x='level',y='value',series='series',xLabel='레벨',yLabel=ylabel,showXAxisLabel=True),
            rows=rows,source=source,generatedAt=now,height=300,theme='codex-classic'))
    definitions=[
        dict(name='레벨 진행도',definition='레벨 진행도는 1레벨에서 0, 100레벨 이상에서 1입니다.',formula='x = clamp((레벨−1)/99,0,1)'),
        dict(name='HP',definition='오프라인 보유시간은 능력치 혜택과 레벨별 허용상한 중 작은 값을 적용합니다.',formula='8+2×min(min(HP/6000,1)^0.8, 0.02+0.98×x^1.8) 시간'),
        dict(name='DEX',definition='탐색 단축량은 DEX와 레벨별 허용상한을 모두 만족해야 합니다.',formula='5−min(DEX/150, 0.12+0.88×x^1.4) 초'),
        dict(name='CHA',definition='아이템 판매 보너스는 CHA와 레벨별 허용상한 중 작은 값에 따릅니다.',formula='20×min(CHA/150, 0.12+0.88×x^1.4) %'),
        dict(name='마법사 MP',definition='마법사는 MP 8000과 기존 기본 확률20%를 갖추면 최종35%에 도달합니다.',formula='min(35, P0+15×min(MP/8000,1)^1.15) %')]
    caps_source={**common,'metricDefinitions':definitions,'caveats':common['caveats']+[
        '레벨별 허용상한은 자동 지급되는 혜택이 아닙니다. 능력치가 낮으면 실제 혜택은 더 작습니다.',
        'HP·DEX·CHA 상한은 100레벨부터 완전히 열립니다. 마법사 상한은 레벨 잠금이 아닌 MP 기준입니다.',
        '나머지 직업의 MP, 기존 스킬확률 및 STR 가방 공식은 유지합니다.']}
    benefits=[dict(hero_class=LABELS[c],**{f:round(float(mean.loc[(c,100),f]),6) for f in FIELDS}) for c in CLASSES]
    scenario_rows=[]
    for label,s in [('주 비교 16개',matched),('신규 8개',heldout)]:
        scenario_rows.append(dict(cohort=label,growth_spread_pct=s['max_growth_spread_pct'],sale_spread_pct=s['max_sale_spread_pct']))
    dump('sources-input.json',dict(schemaVersion=1,items=[
        dict(id='new-curves',title='레벨별 허용상한과 증가 공식',queries=[dict(id='curve-definition',source=caps_source,
            rows=level_rows,columns=['level','offline_ceiling_h','search_floor_s','sale_ceiling_pct','warrior_mean_h','mage_mean_pct'])]),
        dict(id='balance-review',title='전 직업 혜택과 AI 기획 검토',queries=[dict(id='class-benefits',source=common,rows=benefits,
            columns=['hero_class','cap_h','raw_proc_pct','search_s','sale_bonus_pct','bag']),
            dict(id='scenario-gaps',source={**common,'metricDefinitions':[dict(name='직업 간 격차',
                definition='20~100레벨과 접속 간격8종·충전시간3종에서 직업 평균 성장속도 또는 아이템 판매수입의 최대 격차입니다.',formula='(최고 효율/최저 효율−1)×100')],
                'caveats':common['caveats']+['판매수입은 목표 레벨까지 누적 아이템 판매/환산 기간이며 퀘스트 골드·지출·보유재산은 제외합니다.',
                    '캘린더 효율은 반복 충전·저장시간 근사입니다. 실제 이용자의 접속 패턴을 재생한 결과가 아닙니다.',
                    '독립 AI 기획·밸런스 검토는 적합 의견이지만 인간 기획팀의 승인이 아닙니다.']},rows=scenario_rows,
                columns=['cohort','growth_spread_pct','sale_spread_pct'])]),
        dict(id='mage-cap',title='마법사 35% 도달 난도',queries=[dict(id='mage-tail',source={**common,
            'filters':['직업: 마법사','신규 경로: 8개, seed 30~37','최대 재생 레벨: 110'],
            'metricDefinitions':[dict(name='35% 최초 도달',definition='MP 8000 기준 신규 마법사 경로가 35%에 처음 도달한 레벨입니다.'),
                dict(name='추가 진행시간',definition='100레벨부터 35% 첫 도달까지 엔진 내 누적 진행시간 차이를 24시간 단위로 환산합니다. 현실의 플레이 소요일 예측은 아닙니다.')],
            'caveats':common['caveats']+['관측 도달 범위는 모든 캐릭터의 도달 보장이 아니며 실제 이용자 달성 확률이 아닙니다.'],
            'evidenceFlow':[dict(kind='validation',title='새 계산의 일치성',detail=f'기존식600행{len(old.columns)}열이 정확히 일치했고 새Kotlin훅과Python효과식도 대조했습니다. 경계17,958개 검사를 통과했습니다.'),
                dict(kind='validation',title='구 결과 제외',detail='초기 재생은 부동소수점 연산순서에 따른 반올림 경계 차이로 제외했습니다. 계산 순서를 맞춘 v2 재생만 최종 판단에 사용했습니다.')]},
            rows=time_extra,columns=['seed','first_cap_level','extra_active_days'])])]))
    notes(summary,mean)
    notebook(summary)
    print(json.dumps({k:summary[k] for k in ['alternative7500','mage_first_cap_heldout','mage_vs_ranger_completion_time_pct','verification']},ensure_ascii=False,indent=2))


def notes(s,mean):
    lines=['# 고레벨 보너스 재설계 — AI 기획 검토', '',
        '권고: 레벨별 허용상한과 능력치 계산값 중 작은 혜택을 적용하고, 마법사만 MP 8000·지수1.15로 변경한다. 기획상 적합하나 운영 적용 승인은 아니다.', '',
        '## 공식', '',
        '`x=clamp((L−1)/99,0,1)`', '',
        '- HP 시간: `8+2×min(min(HP/6000,1)^0.8, .02+.98×x^1.8)`.',
        '- 탐색: `5−min(DEX/150, .12+.88×x^1.4)`초.',
        '- 판매: `20×min(CHA/150, .12+.88×x^1.4)`%. 아이템에만 적용.',
        '- 마법사: `min(35, P0+15×min(MP/8000,1)^1.15)`%.',
        '- P0: `min(20,8+floor(floor((7×주력+3×보조)/10)/6))`%. 나머지 직업 MP 기존 +10×min(MP/6000,1), 최대30%.',
        '- HP/MP/기본6능력치 성장·가방 공식은 변경하지 않는다. 성직자 WIS/CHA 유지.', '',
        '## 후보 판단', '',
        '- 능력치 지수만 상향: 비주력까지 크게 약화하고 동일 stat threshold의 상한 도달 시점을 늦추지 못한다.',
        '- 공통 레벨배율: 초중반의 작은 혜택까지 이중으로 억제한다.',
        '- min형 허용상한: 초반 롤 혜택을 보존하는 시작값으로 보완했다. 100레벨의 비주력 혜택은 유지한다.',
        f"- MP7500 후보 실제16개 재생: Lv100평균{s['alternative7500']['mean100_pct']:.4f}%, 첫35% Lv{s['alternative7500']['first_min']}~{s['alternative7500']['first_max']}. MP8000을 권고한다.", '',
        '## 레벨별 허용상한 — 실제 지급량과 구분', '',
        '| Lv | 시간 상한(h) | 탐색 하한(초) | 판매 상한(%) | 전사 평균(h) | 마법사 평균(%) |',
        '|---|---:|---:|---:|---:|---:|']
    for r in s['level_rows']:lines.append('| '+ ' | '.join([str(r['level']),*[f'{r[f]:.4f}' for f in ['offline_ceiling_h','search_floor_s','sale_ceiling_pct','warrior_mean_h','mage_mean_pct']]])+' |')
    lines += ['', '## 100레벨 직업별 평균', '', '| 직업 | 시간(h) | 기본 확률(%) | 탐색(초) | 판매(%) | 가방 |', '|---|---:|---:|---:|---:|---:|']
    for cls in CLASSES:lines.append('| '+LABELS[cls]+' | '+' | '.join(f'{mean.loc[(cls,100),f]:.4f}' for f in FIELDS[:5])+' |')
    lines += ['', '## 검토 판정', '',
        '- AI 기획 검토: 후반 특화·초반 롤 보존·교차 능력치 혜택 보존에 적합. 실제 사람 기획팀의 승인은 아니다.',
        '- AI 밸런스 검토: MP8000은 달성 가능한 100레벨 이후 완성 목표로 적절하다. 희귀하거나 거의 불가능한 상한이라고 표현하지 않는다.',
        f"- 주 비교96경로: 성장최대격차{s['matched']['max_growth_spread_pct']:.4f}%,판매최대격차{s['matched']['max_sale_spread_pct']:.4f}%.",
        f"- 신규48경로: 성장최대격차{s['heldout']['max_growth_spread_pct']:.4f}%,판매최대격차{s['heldout']['max_sale_spread_pct']:.4f}%.",
        f"- 8시간·12분완충의100레벨까지 성장: 레인저1위,마법사는 완료시간{s['mage_vs_ranger_completion_time_pct']:.4f}%더소요. 12시간간격과부분충전에서는전사우위,판매는성직자우위.",
        '- 목표로 삼은 성장격차6%·판매격차12%는 이번 기획의 가드레일이며 실증적으로 보장된 최적 기준이 아니다.',
        '- 마법사는 중반에 스킬확률/성장순위1위가 아니다. 기본확률20%포화와성장운 때문에 실제 매레벨 증가량의엄격한가속은보장하지않는다.',
        '- 허용상한에 붙은 동안 stat 증가가 즉시 혜택으로 드러나지 않을 수 있다. UI 통합 시 현재값과 레벨상한을 구분해야 한다.',
        '- HP/DEX/CHA레벨상한은100부터고정. 특정직업전용잠금이아니므로충분히높은비주력스탯도최종상한에도달가능.', '',
        '## 마법사 신규8경로의 추가 성장', '', '| seed | 최초35%레벨 | 100레벨부터추가누적진행일(24h환산) |', '|---|---:|---:|']
    for r in s['mage_first_cap_heldout']:lines.append(f"| {r['seed']} | {r['first_cap_level']} | {r['extra_active_days']:.4f} |")
    lines += ['', '## 검증과 한계', '',
        f"- 최종 자료는 v2만 사용. 추천안144회,MP7500대조16회,기존식동일성6회. 기존식600행{s['verification']['baseline_identical_columns']}열exact일치.",
        '- Kotlin 실제훅 경계17,958개와Kotlin/Python효과수치대조통과. 네트워크·DB파일접근OS차단. 앱·기기등록실행없음.',
        '- 초기v1은부동소수점계산순서가달라basis point반올림일부경계에서기존식재현차이발생. 원본증거는보존하고최종결과에서제외.',
        '- 시간저장량의캘린더효과는반복충전환산모델이다. 실제플레이어행동·이탈·체감테스트및동적충전앱통합은미검증.',
        '- 표본의상한도달건수는실제이용자확률이아니다. 기존성직자스탯보정도이번범위밖이다.',
        '- 재현노트북은코드셀을일반Python에서순차실행. nbformat/nbclient/ipykernel미설치로Jupyter커널실행은하지않음.',
        '- 커널검증명령: `python -m jupyter nbconvert --execute --to notebook --inplace progression-review.ipynb`.',
        '', '## 재현', '', '`run_progression_bonus_probe.py` → `progression_bonus_review.py` → `build_progression_bonus_review.py`.']
    (OUT/'DESIGN_REVIEW.md').write_text('\n'.join(lines)+'\n')


def notebook(s):
    cells=[]
    def md(t):cells.append(dict(cell_type='markdown',metadata={},source=t.splitlines(True)))
    def code(t):cells.append(dict(cell_type='code',metadata={},source=t.splitlines(True),execution_count=None,outputs=[]))
    md('## tl;dr\n\n레벨별 허용상한+MP8000의 마법사 곡선을 기획안으로 권고한다. 100레벨의 조기 포화를 막되 35%를 100레벨 이후 달성 가능한 목표로 둔다. 운영 적용은 하지 않았다.\n')
    md('## Context & Methods\n\n### Key Assumptions\n\n기본8시간/최대10시간,마법사35%,다른직업30%,최소탐색4초,판매+20%. 주 비교16개 seed/직업과독립8개/직업을분리한다. 캘린더환산은실제접속재생이아니다.\n\n일반Python순차실행으로검증했다. nbformat/nbclient/ipykernel미설치로Jupyter커널은미실행. 별도커널검증은 `python -m jupyter nbconvert --execute --to notebook --inplace progression-review.ipynb`.\n')
    md('## Data\n\n### 1. Verify final replay evidence\n')
    code("from pathlib import Path\nimport sys,json,pandas as pd,numpy as np\nroot=next(p for p in [Path.cwd(),*Path.cwd().parents] if (p/'tools/analysis/progression_bonus_review.py').exists())\nsys.path.insert(0,str(root/'tools/analysis'))\nfrom progression_bonus_review import OUT,review,effects,caps\nmatched=review('envelope-matched-v2.csv',list(range(14,30)))\nheldout=review('envelope-heldout-v2.csv',list(range(30,38)))\nassert matched['games']==96 and heldout['games']==48\nprint('Verified candidate games:',matched['games']+heldout['games'])\n")
    md('## Results\n\n### 2. Compare balance and cap progression\n')
    code("print(pd.DataFrame(matched['level100']).T.round(4).to_string())\nfor label,result in [('matched',matched),('heldout',heldout)]:\n print(label,result['max_growth_spread_pct'],result['max_sale_spread_pct'])\n assert result['max_growth_spread_pct']<6 and result['max_sale_spread_pct']<12\nprint('Mage cap:',next(x for x in heldout['cap_hits'] if x['hero_class']=='MAGE'))\n")
    md('### 3. Check monotonic level ceilings\n')
    code("levels=np.arange(1,101)\nh,search,sale=caps(levels)\nassert np.all(np.diff(h)>0) and np.all(np.diff(np.diff(h))>0)\nassert np.all(np.diff(search)<0) and np.all(np.diff(sale)>0)\nassert np.all(np.diff(np.diff(sale))>0)\nassert h[-1]==10 and search[-1]==4 and sale[-1]==20\nprint('Increasing, accelerating ceiling curves and final limits: PASS')\n")
    md('## Takeaways\n\n새 안은 조기 포화를 막고 마법사 상한을 달성 가능한 100레벨 이후 목표로 둔다. 독립 AI 검토는 조건부 적합이다. 실제 이용자 경험과 앱 통합은 별도 검증해야 한다.')
    env={};count=0
    for cell in cells:
        cell['id']=uuid.uuid4().hex[:8]
        if cell['cell_type']=='code':
            count+=1;buf=io.StringIO()
            with contextlib.redirect_stdout(buf):exec(compile(''.join(cell['source']),cell['id'],'exec'),env)
            cell['execution_count']=count;cell['outputs']=[dict(output_type='stream',name='stdout',text=buf.getvalue().splitlines(True))]
    dump('progression-review.ipynb',dict(nbformat=4,nbformat_minor=5,cells=cells,
        metadata=dict(kernelspec=dict(display_name='Python 3',language='python',name='python3'),language_info=dict(name='python'),
            validation=dict(mode='plain-python-sequential',cells_passed=count,jupyter_kernel_executed=False))))


if __name__=='__main__':main()
