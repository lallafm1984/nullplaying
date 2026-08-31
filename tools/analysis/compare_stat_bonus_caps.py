#!/usr/bin/env python3
"""Paired pure-engine comparison of the user's higher stat bonus caps."""
from pathlib import Path
import contextlib
import hashlib
import io
import json
import sys
import numpy as np
import pandas as pd
from stat_bonus_balance import ROOT, OUT, CLASSES, bonuses
from validate_stat_bonus_balance import calendar_scenarios

LABELS={'WARRIOR':'전사','ROGUE':'도적','RANGER':'레인저','MAGE':'마법사','CLERIC':'성직자','PALADIN':'팔라딘'}


def markdown_table(d, fields):
    return '\n'.join(['| '+' | '.join(label for _,label in fields)+' |',
        '|'+'|'.join(['---']*len(fields))+'|']+
        ['| '+' | '.join(str(r[f]) for f,_ in fields)+' |' for r in d.to_dict('records')])


def analyze():
    p_old=json.loads((OUT/'selected_parameters.json').read_text())
    p_new=json.loads((OUT/'upper_caps_parameters.json').read_text())
    old=pd.read_csv(OUT/'holdout_selected_dense.csv')
    new=pd.read_csv(OUT/'upper_caps_dense.csv')
    base=pd.read_csv(OUT/'holdout_baseline.csv')
    keys=['class','seed_index','level']
    for d in [old,new]:
        assert len(d)==2400 and not d.isna().any().any()
        assert not d.duplicated(keys).any()
        assert set(d['class'])==set(CLASSES) and set(d.seed_index)=={2,3,4,5}
        assert (d.groupby(['class','seed_index'])['level'].count()==100).all()
    assert old[keys].equals(new[keys])
    assert old.tales.equals(new.tales)
    hashes=json.loads((OUT/'engine_source_sha256.json').read_text())
    for name,digest in hashes.items():
        if name.startswith('app/'): assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest
    effects=[]
    all_scenarios=[]
    for variant,d,p in [('prior',old,p_old),('proposed',new,p_new)]:
        x=d.copy()
        x['cap_h'],x['raw_proc'],x['search_reduction'],x['sale_bonus']=bonuses(x,p)
        x['proc_bonus_pp']=(x.raw_proc-x.proc_pct/100)*100
        x['raw_proc_pct']=x.raw_proc*100
        x['search_s']=5*(1-x.search_reduction)
        x['search_reduction_pct']=100*x.search_reduction
        x['sale_bonus_pct']=100*x.sale_bonus
        x['capacity_bonus_h']=x.cap_h-8
        g=x.groupby(['level','class'])[['hp','mp','dex','cha','cap_h','capacity_bonus_h','proc_bonus_pp','raw_proc_pct','search_s','search_reduction_pct','sale_bonus_pct']].mean().reset_index()
        g['variant']=variant
        effects.append(g)
        c=calendar_scenarios(d,p,'selected')
        c['variant']=variant
        all_scenarios.append(c)
    baseline=calendar_scenarios(base,p_old,'baseline8')
    c=pd.concat([*all_scenarios,baseline],ignore_index=True)
    c.to_csv(OUT/'upper_caps_calendar.csv',index=False)
    effects=pd.concat(effects,ignore_index=True)
    effects.to_csv(OUT/'upper_caps_effects.csv',index=False)
    ag=c.groupby(['variant','hero_class','level','gap_h','session_min']).agg(
        days=('calendar_days','mean'),sale_gold=('sale_gold','mean'),n=('seed_index','size')).reset_index()
    ag['rate']=1/ag.days
    ag['sale_rate']=ag.sale_gold/ag.days
    paired=ag[ag.variant=='prior'].merge(ag[ag.variant=='proposed'],
        on=['hero_class','level','gap_h','session_min'],suffixes=('_old','_new'),validate='one_to_one')
    paired['progress_uplift_pct']=(paired.days_old/paired.days_new-1)*100
    paired['sale_uplift_pct']=(paired.sale_rate_new/paired.sale_rate_old-1)*100
    paired.to_csv(OUT/'upper_caps_comparison.csv',index=False)
    gaps=[]
    for key,d in ag.groupby(['variant','level','gap_h','session_min']):
        gaps.append(dict(zip(['variant','level','gap_h','session_min'],key),
            progress_gap_pct=(d.rate.max()/d.rate.min()-1)*100,
            sale_gap_pct=(d.sale_rate.max()/d.sale_rate.min()-1)*100))
    gaps=pd.DataFrame(gaps)
    gaps.to_csv(OUT/'upper_caps_gaps.csv',index=False)
    # Retained off-stat benefit as a share of the class with the largest benefit.
    cross=[]
    for level,d in effects[effects.variant=='proposed'].groupby('level'):
        for field in ['capacity_bonus_h','proc_bonus_pp','search_reduction_pct','sale_bonus_pct']:
            for _,r in d.iterrows():
                cross.append(dict(level=int(level),hero_class=r['class'],effect=field,
                    value=r[field],maximum=d[field].max(),share_of_max=r[field]/d[field].max()))
    pd.DataFrame(cross).to_csv(OUT/'upper_caps_cross_stat.csv',index=False)
    # One million is not required for monotonic/range proof of these rational
    # curves; test a logarithmic grid plus denominator anchor values instead.
    a=np.unique(np.r_[0,1,20,35,350,750,np.geomspace(1,1e18,2000)])
    extremes=pd.DataFrame(dict(hp=a,mp=a,dex=a,cha=a,proc_pct=np.full(len(a),20)))
    cap,prob,dex,cha=bonuses(extremes,p_new)
    # At ~1e18, adjacent floating-point values may vary by a few ULPs.
    eps=1e-12
    assert np.all(np.diff(cap)>=-eps) and cap.min()==8 and cap.max()<=12+eps
    assert np.all(np.diff(prob)>=-eps) and prob.min()==.2 and prob.max()<=.3+eps
    assert np.all(np.diff(dex)>=-eps) and dex.max()<=.2+eps and (5*(1-dex)).min()>=4-eps
    assert np.all(np.diff(cha)>=-eps) and cha.max()<=.2+eps
    subset=c[(c.level==100)&(c.variant=='proposed')]
    summary={'parameters':p_new,'proposed_games':24,'paired_seeds':[2,3,4,5],
        'proposed_kills':int(new[new.level==100].kills.sum()),
        'level100_calendar_bound_pct':float(((subset.calendar_days_high/subset.calendar_days_low-1)*100).max()),
        'checks':['game source hashes unchanged','paired seed/level coverage','null/duplicate checks','tale growth counts equal',
                  'monotone capped effects','separate raw proc and pity','same denominators, higher caps only'],
        'status':'conditional design recommendation; no production mutation'}
    (OUT/'upper_caps_validation.json').write_text(json.dumps(summary,indent=2)+'\n')
    return effects,paired,gaps,summary


def main():
    effects,paired,gaps,summary=analyze()
    e=effects[(effects.variant=='proposed')&(effects.level==100)].copy()
    e['직업']=e['class'].map(LABELS)
    e['한도']=e.cap_h.map(lambda h:f'{int(h)}시간 {round((h%1)*60):02d}분')
    e['확률']=e.raw_proc_pct.map(lambda p:f'{p:.2f}%')
    e['탐색']=e.search_s.map(lambda s:f'{s:.2f}초')
    e['판매']=e.sale_bonus_pct.map(lambda p:f'+{p:.2f}%')
    s=gaps[(gaps.level.isin([20,50,100]))&(gaps.session_min==12)&gaps.gap_h.isin([8,12])].copy()
    s['설정']=s.variant.map({'baseline8':'보너스 없음','prior':'직전안','proposed':'이번 제안'})
    s['성장']=s.progress_gap_pct.map(lambda p:f'{p:.2f}%')
    s['판매']=s.sale_gap_pct.map(lambda p:f'{p:.2f}%')
    end=paired[(paired.level==100)&(paired.session_min==12)]
    gain8=end[end.gap_h==8].progress_uplift_pct
    gain12=end[end.gap_h==12].progress_uplift_pct
    g8=s[(s.level==100)&(s.gap_h==8)&(s.variant=='proposed')].progress_gap_pct.iloc[0]
    g12=s[(s.level==100)&(s.gap_h==12)&(s.variant=='proposed')].progress_gap_pct.iloc[0]
    get=lambda cls,field:float(e[e['class']==cls][field].iloc[0])
    cross=[
        ['HP 추가 시간','마법사 / 전사',get('MAGE','capacity_bonus_h')/get('WARRIOR','capacity_bonus_h')*100],
        ['MP 추가 확률','전사 / 마법사',get('WARRIOR','proc_bonus_pp')/get('MAGE','proc_bonus_pp')*100],
        ['DEX 탐색 감소','전사 / 레인저',get('WARRIOR','search_reduction_pct')/get('RANGER','search_reduction_pct')*100],
        ['CHA 판매 보너스','전사 / 팔라딘',get('WARRIOR','sale_bonus_pct')/get('PALADIN','sale_bonus_pct')*100],
    ]
    cross_df=pd.DataFrame(cross,columns=['효과','비교','수혜 비율'])
    cross_df['수혜 비율']=cross_df['수혜 비율'].map(lambda p:f'{p:.1f}%')
    text=[
        '# 스탯 상한 확대와 직업 개성',
        '## Executive Summary',
        f'**제안한 상한은 직업 개성을 살리는 목적에 충분히 검토할 만합니다.** 기존 곡선을 유지하고 HP 12시간·MP 총 30%·탐색 최소 4초·판매 +20%로 높여 6직업×4개 시드의 성장 경로를 다시 재생했습니다. 레벨 1→100 성장 속도 격차는 8시간 부재에서 {g8:.2f}%, 12시간 부재에서 {g12:.2f}%로 측정됐습니다. 운영 DB·앱·기기를 사용하거나 게임을 변경하지 않았습니다.',
        f'**주능력치 외 스탯도 충분히 유효하다는 설명은 수치로 확인됩니다.** 단, 상한을 올리는 것은 전 직업의 보너스를 함께 키웁니다. 직전안 대비 레벨100 완료 속도 증가는 8시간 부재 +{gain8.min():.1f}~{gain8.max():.1f}%, 12시간 부재 +{gain12.min():.1f}~{gain12.max():.1f}%입니다. 격차와 전체 성장 가속은 다른 지표입니다.',
        '## 레벨 100에서는 모든 직업이 네 보너스를 받습니다',
        '다음은 직업당 4개 시드의 평균입니다. HP·MP 최대값을 사용합니다. 스킬 확률은 15회 기본공격 보장에 따른 추가 발동을 제외한 raw 판정입니다. 30%는 즉시 고정 확률이 아니라 상한이며, 기본 오프라인 한도는 계속 8시간입니다.',
        markdown_table(e,[('직업','직업'),('한도','오프라인 한도'),('확률','스킬 확률'),('탐색','탐색 시간'),('판매','판매 보너스')]),
        '## 비주력 스탯도 주요 직업 보너스의 상당 부분을 제공합니다',
        '아래는 기본값을 뺀 **보너스 부분**의 비율입니다. 전체 HP·MP 수치나 전체 수입 비율이 아닙니다. 각 직업에 보너스를 독점시키지 않으면서 높은 스탯에 추가 보상을 주는 방향입니다.',
        markdown_table(cross_df,[(c,c) for c in cross_df.columns]),
        '## 직업 격차는 접속 패턴과 성장 구간별로 다릅니다',
        '격차는 같은 목표 레벨에 도달하는 직업별 평균 속도의 최고/최저−1입니다. 판매 격차는 해당 목표까지의 아이템 판매 골드/완료 일수이며 퀘스트·드롭 골드는 제외합니다. 12분 전경 체류로 완충하고 8시간 또는 12시간 부재하는 반복 조건입니다. 12시간 결과는 엔진 플레이 시간을 보유 시간 모델로 환산한 값이지 실제 로그인 리플레이가 아닙니다.',
        markdown_table(s,[('level','목표 레벨'),('gap_h','부재 시간'),('설정','설정'),('성장','성장 속도 격차'),('판매','판매 수입 격차')]),
        '## 상한은 찬성할 만하지만, 개성의 크기는 도달 곡선이 결정합니다',
        f'현재 곡선의 레벨100에서는 전사와 마법사의 보유 시간 차이가 약 {(get("WARRIOR","cap_h")-get("MAGE","cap_h"))*60:.0f}분, 스킬 확률 차이가 약 {get("MAGE","raw_proc_pct")-get("WARRIOR","raw_proc_pct"):.2f}%p입니다. 상한에 가까워질수록 차이가 좁아집니다. 따라서 상한만 넓힌 이번 안은 개성 강화의 출발점이지 강한 직업 분화를 보장하는 수치는 아닙니다.',
        '전사는 보유 시간, 마법사·성직자는 스킬 빈도, 도적·레인저는 탐색, 팔라딘은 판매에서 강점이 있습니다. 단, 마법사와 성직자는 INT/WIS 조합을 공유하므로 이 네 보너스만으로 서로 크게 달라지지 않습니다. 자주 접속하는 유저에게 HP 한도 증가는 유효 사냥 시간을 늘리지 않을 수 있다는 점도 남습니다.',
        '## 권장 방향과 다음 결정',
        '제안한 **12시간 / 30% / 4초 / +20% 상한을 기획 기준으로 채택하는 방향을 권장**합니다. 아직 실제 게임에 적용한 것은 아닙니다. 네 효과를 직업 전용으로 막지 않고 공통 공식으로 유지하면 비주력 스탯도 유효합니다. 더 선명한 개성을 원한다면 다음에는 상한을 더 높이기보다 HP·MP가 상한에 접근하는 속도를 늦추는 곡선을 별도로 비교하는 편이 맞습니다. 이번 분석에서는 곡선을 바꾸지 않았으므로 그 효과까지 검증됐다고 보지 않습니다.',
        '## 가정과 검증 한계',
        '직전안 대비 상한만 변경했습니다: HP는 8+4×HP/(HP+350)시간, MP는 기존 확률+10×MP/(MP+750)%p, 탐색은 5×(1−0.20×DEX/(DEX+35))초, 판매는 기존가×(1+0.20×CHA/(CHA+20)). 발견 연출2초·공격 간격·15회 보장은 유지합니다. 최대 충전 시간은 기존12분으로 가정했습니다.',
        f'직업당 같은 4개 시드의 짝 비교이며 실제 유저 표본이나 새 독립 홀드아웃이 아닙니다. 실제 엔진의 이야기 성장·숙련·자동 장비 구매를 포함합니다. 레벨100 달력 일수 환산 상·하한 간 차이는 최대 {summary["level100_calendar_bound_pct"]:.2f}%입니다. 100레벨 이후 장기 진행은 재생하지 않았고, 스탯10¹⁸까지 공식의 단조성·상한만 점검했습니다.',
    ]
    (OUT/'UPPER_CAPS_COMPARISON.md').write_text('\n\n'.join(text)+'\n')
    create_notebook()
    print(e[['직업','한도','확률','탐색','판매']].to_string(index=False))
    print(s[s.level==100][['variant','gap_h','progress_gap_pct','sale_gap_pct']].round(3).to_string(index=False))
    print('Extra progress speed over prior: 8h',gain8.min(),gain8.max(),'12h',gain12.min(),gain12.max())
    print(cross_df.to_string(index=False))
    print(json.dumps(summary))


def create_notebook():
    md=lambda s:{'cell_type':'markdown','metadata':{},'source':s.splitlines(True)}
    code=lambda s:{'cell_type':'code','metadata':{},'source':s.splitlines(True),'outputs':[],'execution_count':None}
    cells=[md('## tl;dr\n상한 확대는 전체 보너스를 함께 키웁니다. 직업 격차와 비주력 스탯 수혜를 분리해서 비교합니다.'),
        md('## Context & Methods\n### Key Assumptions\n기본8시간, 완충12분. 상한만12시간/30%/4초/+20%로 변경. 동일 시드2~5를 짝 비교. 운영 DB와 무관한 로컬 엔진 경로입니다.'),
        code("from pathlib import Path\nimport sys\nroot=next(p for p in [Path.cwd(),*Path.cwd().parents] if (p/'tools/analysis/compare_stat_bonus_caps.py').exists())\nsys.path.insert(0,str(root/'tools/analysis'))\nfrom compare_stat_bonus_caps import analyze\neffects,comparison,gaps,validation=analyze()\nprint(validation)\n"),
        md('## Data\n레벨별 효과는 직업당4개 시드의 평균입니다.'),
        code("print(effects[(effects.variant=='proposed')&(effects.level==100)][['class','cap_h','raw_proc_pct','search_s','sale_bonus_pct']].round(3).to_string(index=False))\n"),
        md('## Results\n같은 목표 레벨에서의 직업 최고/최저 격차입니다.'),
        code("print(gaps[(gaps.level==100)&(gaps.session_min==12)&gaps.gap_h.isin([8,12])].round(3).to_string(index=False))\n"),
        md('## Takeaways\n상한과 도달 곡선은 별도 결정입니다. 모든 직업이 비주력 스탯에서도 혜택을 받지만, 후반에 상한으로 모이면서 차이가 좁아집니다. Jupyter 커널은 미실행; 코드 셀은 일반 Python으로 순차 검산했습니다.')]
    env={};count=0
    for i,c in enumerate(cells):
        c['id']=f'upper-cap-cell-{i}'
        if c['cell_type']=='code':
            count+=1;buf=io.StringIO()
            with contextlib.redirect_stdout(buf): exec(compile(''.join(c['source']),c['id'],'exec'),env)
            c['execution_count']=count;c['outputs']=[{'output_type':'stream','name':'stdout','text':buf.getvalue().splitlines(True)}]
    nb={'nbformat':4,'nbformat_minor':5,'cells':cells,'metadata':{
        'kernelspec':{'name':'python3','language':'python','display_name':'Python 3'},
        'validation':{'plain_python_sequential_cells_passed':count,'jupyter_kernel_executed':False}}}
    assert len(set(c['id'] for c in cells))==len(cells)
    (OUT/'upper_caps_review.ipynb').write_text(json.dumps(nb,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__': main()
