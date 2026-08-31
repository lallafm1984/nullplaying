#!/usr/bin/env python3
"""Package reviewed local evidence into one report and a notebook companion."""
from pathlib import Path
import contextlib
import io
import json
import sys
import pandas as pd
from stat_bonus_balance import ROOT, OUT

NAMES={'WARRIOR':'전사','ROGUE':'도적','RANGER':'레인저','MAGE':'마법사','CLERIC':'성직자','PALADIN':'팔라딘'}


def rows(d): return json.loads(d.to_json(orient='records',force_ascii=False))


def main():
    v=json.loads((OUT/'validation_summary.json').read_text())
    screen=json.loads((OUT/'screening_summary.json').read_text())
    assert v['engine_control_exact_match'] is True
    effects=pd.read_csv(OUT/'recommended_effects.csv')
    pair=pd.read_csv(OUT/'validated_class_comparison.csv')
    gaps=pd.read_csv(OUT/'validated_class_gaps.csv')
    bands=pd.read_csv(OUT/'hp_mp_sample_ranges.csv')
    e100=effects[effects.level==100].copy()
    e100['직업']=e100['class'].map(NAMES)
    e100['오프라인 한도']=e100.cap_h.map(lambda h:f'{int(h)}시간 {round((h%1)*60):02d}분')
    e100['스킬 확률']=e100.raw_proc_pct.map(lambda p:f'{p:.2f}%')
    e100['탐색 시간']=e100.search_s.map(lambda s:f'{s:.2f}초')
    e100['판매 보너스']=e100.sale_bonus_pct.map(lambda p:f'+{p:.2f}%')
    line=effects[effects['class'].isin(['WARRIOR','MAGE'])].copy()
    line['직업']=line['class'].map(NAMES)
    lift=pair[(pair.level==100)&(pair.session_min==12)&pair.gap_h.isin([8,12])].copy()
    lift['직업']=lift.hero_class.map(NAMES)
    lift['접속 간격']=lift.gap_h.map(lambda h:f'{h:.0f}시간')
    lift['progress_uplift']=lift.progress_uplift_pct/100
    g=gaps[(gaps.session_min==12)&gaps.level.isin([20,50,100])&gaps.gap_h.isin([8,12])&gaps.variant.isin(['baseline8','selected'])].copy()
    g['설정']=g.variant.map({'baseline8':'8시간·보너스 없음','selected':'추천안'})
    g['성장 격차']=g.progress_gap_pct.map(lambda n:f'{n:.2f}%')
    g['판매 격차']=g.sale_gap_pct.map(lambda n:f'{n:.2f}%')
    narrow=bands[(bands['class'].isin(['WARRIOR','MAGE']))&bands.level.isin([1,10,20,50,100])].copy()
    narrow['직업']=narrow['class'].map(NAMES)
    narrow['HP 최저/평균/최고']=narrow.apply(lambda r:f'{r.hp_min:.0f} / {r.hp_mean:.1f} / {r.hp_max:.0f}',axis=1)
    narrow['MP 최저/평균/최고']=narrow.apply(lambda r:f'{r.mp_min:.0f} / {r.mp_mean:.1f} / {r.mp_max:.0f}',axis=1)
    formulas=[
        {'순서':1,'스탯':'HP','공식':'8 + 2 × HP / (HP + 350) 시간','상한':'10시간','범위':'오프라인 보유 한도만; HP MAX 사용'},
        {'순서':2,'스탯':'MP','공식':'기존 확률 + 8 × MP / (MP + 750) %p','상한':'추가 8%p / 합계 28%','범위':'15회 연속 기본공격 보장 유지; MP MAX 사용'},
        {'순서':3,'스탯':'DEX','공식':'5 × [1 − 0.15 × DEX / (DEX + 35)] 초','상한':'최소 4.25초','범위':'탐색 5초만 감소; 발견 연출 2초는 유지'},
        {'순서':4,'스탯':'CHA','공식':'기존 판매가 × [1 + 0.15 × CHA / (CHA + 20)]','상한':'판매가 +15%','범위':'아이템 판매만; 퀘스트·드롭 골드 제외'},
    ]
    at100=g[(g.level==100)&(g.variant=='selected')]
    max8=at100[at100.gap_h==8].progress_gap_pct.iloc[0]
    max12=at100[at100.gap_h==12].progress_gap_pct.iloc[0]
    old100=g[(g.level==100)&(g.variant=='baseline8')].progress_gap_pct.iloc[0]
    gains8=lift[lift.gap_h==8].progress_uplift_pct
    gains12=lift[lift.gap_h==12].progress_uplift_pct
    maxbound=v['level100_integration_bound_pct']
    sections=[
        ('title','# 8시간 기준 스탯 보너스 권장안'),
        ('summary',f'## Executive Summary\n\n기본 오프라인 한도를 8시간으로 줄인다는 해석에서, **HP 최대 10시간·MP 추가 최대 8%p·DEX/CHA 최대 15%**를 잠정 권장합니다. {screen["candidates"]:,}개 후보의 근사모형 비교 후, 탐색에 쓰지 않은 시드 4개 × 6직업 × 기존/추천안 = 48개 성장 경로를 현재 순수 엔진으로 재현했습니다.\n\n레벨 1→100 완료 속도의 직업 격차는 추천안에서 8시간 간격 {max8:.2f}%, 12시간 간격 {max12:.2f}%입니다. 다만 기존 8시간·무보너스의 {old100:.2f}%보다 커졌으므로 **기존보다 더 평준화됐다는 결론은 아닙니다.** 보너스의 의미를 유지하면서 격차를 제한한 후보입니다. 운영 DB·디바이스·앱을 건드리지 않았으며, 실제 게임에는 적용하지 않았습니다.'),
        ('formulas','## 성장할수록 효과가 완만해지는 공식\n\nHP·MP는 최대값을 사용합니다. 현재 남은 자원을 소모하는 구조가 아닙니다. MP의 %p는 퍼센트포인트이므로 기존 20%에 +6%p이면 26%입니다. 8% 증가(21.6%)와 다릅니다. 모든 공식은 스탯 증가 시 효과가 감소하지 않으며 유한한 상한으로 수렴합니다.'),
        ('effects','## 레벨 100에서는 직업 차이를 작게 유지합니다\n\n아래는 추천안을 켠 순수 엔진 4개 시드의 직업별 평균입니다. 보너스는 개별 캐릭터별로 계산한 뒤 평균했으며, 평균 스탯을 공식에 넣은 값과 미세하게 다를 수 있습니다. 스킬 확률은 보장 발동을 제외한 원래 판정 확률입니다.'),
        ('capacity','## HP 보너스는 접속 간격에 따라 가치가 달라집니다\n\n8시간 이내 복귀하고 한도를 충분히 충전했다면 HP 보너스 때문에 더 사냥하지는 않습니다. 12·24시간 부재에서는 추가 보유 시간이 사냥 중단 시간을 줄입니다. 전사는 높은 HP로 더 긴 보유 시간을 얻지만, 기본값을 8시간으로 낮추는 취지를 살리기 위해 최종 한도는 10시간으로 제한합니다.'),
        ('growth',f'## 성장 속도와 경제 효과를 구분합니다\n\n레벨 1→100의 동일 목표에 도달하는 속도는 8시간 간격에서 기존 8시간·무보너스 대비 **+{gains8.min():.1f}~{gains8.max():.1f}%**, 12시간 간격에서 **+{gains12.min():.1f}~{gains12.max():.1f}%**입니다. 8시간 간격 값은 엔진의 누적 플레이 시간 비교이며, 12시간 간격은 그 경로를 보유 시간 모델로 환산한 추정치입니다. 그래프는 시간 단축률이 아니라 완료 속도 증가율입니다.\n\n판매 수입은 퀘스트 골드와 분리했습니다. CHA 판매 단가 보너스 외에 사냥 속도와 장비 자동 구매 피드백도 실제 엔진 재생에 포함되어 있습니다. 따라서 전체 판매 수입 증가율을 CHA 단가 증가율로 읽으면 안 됩니다.'),
        ('balance','## 모든 구간을 완전히 같은 효율로 만들지는 못합니다\n\n성장 격차 = 가장 빠른 직업의 평균 완료 속도 / 가장 느린 직업의 평균 완료 속도 − 1. 판매 격차 = 해당 목표까지 판매 골드/완료 일수의 직업 최고/최저 − 1. 직업마다 시드 수는 동일합니다. 아래는 공통 목표 레벨과 충분한 충전 조건의 결과입니다. HP 편의성, MP 전투, DEX 탐색, CHA 경제는 서로 다른 효용이어서 하나의 계수로 모든 접속 패턴을 같게 할 수 없습니다.'),
        ('ranges','## 전사·마법사 HP/MP: 실제 성장 경로의 표본 범위\n\n현재 엔진의 이야기 완료 성장까지 포함한 직업당 6개 시드입니다. 최저·평균·최고는 **이 표본 안의 값**이지, 가능한 전체 캐릭터의 이론적 하한·기댓값·상한이 아닙니다. 초기 주사위와 성장은 현재 엔진 RNG를 그대로 사용했습니다. 과거의 독립 주사위 가정 표와 혼용하지 않습니다.'),
        ('methods',f'## 방법과 검증 범위\n\n탐색은 시드 0·1의 12개 실제 성장 경로에서 얻은 스탯·전투/마을 소요시간을 이용한 근사모형입니다. 레벨 10~100, 부재 4·8·12·24시간에 대해 직업 최대 성장 격차 + 0.5×최대 판매 격차 + 0.25×평균 성장 격차를 최소화했습니다. 레벨 100에서 모든 직업에 최소 +1.5시간·+2%p·탐색/판매 8% 효과를 요구해 영(0) 보너스가 승자가 되지 않게 했습니다. 이 최소 체감 기준과 가중치는 분석을 위한 기획 가정입니다.\n\n검증은 별도 시드 2~5의 48개 엔진 경로, 총 {v["holdout_kills"]:,}회 처치입니다. 스킬 숙련·선택·15회 보장·이야기 보상·인벤토리·판매·장비 구매를 포함했습니다. 운영 소스는 수정하지 않고 분석용 사본에만 보너스를 삽입했습니다. 보너스 비활성 사본이 원본 경로와 일치하는지도 확인했습니다.\n\n오프라인 일수 환산은 전경 1·5·12분과 부재 4·8·12·24시간의 반복을 가정합니다. 기본 충전 완료 시간은 기존의 12분으로 유지했습니다. 레벨별 HP 한도로 적분하며 레벨 100 완료 일수의 상·하한 간 차이는 최대 {maxbound:.2f}%입니다. 실제 로그인 이벤트를 리플레이한 결과는 아닙니다. 캐릭터별 실사용 분포나 리롤 선호를 DB에서 조회하지 않았습니다.'),
        ('limits','## 해석의 한계와 적용 전 조건\n\n이 결과는 조사한 후보군과 명시한 목적함수 안의 잠정 추천입니다. 1위와 인접 후보의 점수 차이는 매우 작아 350·750·35·20이 유일한 정답이라는 근거는 없습니다. 독립 검증 시드는 직업당 4개이며 실제 유저 대표 표본이나 라이브 A/B 테스트가 아닙니다. 100레벨 이후는 보너스 상한·단조성만 10¹⁸ 스탯까지 점검했고 장기 성장 균형은 검증하지 않았습니다.\n\n**기존 12시간과 비교하면 별개입니다.** 추천안의 한도는 최대 10시간이므로 8시간 초과 부재 유저에게 종전 12시간을 보전하지 않습니다. 8시간이 기본 한도가 아니라 절대 상한이라는 뜻이면 HP 설계 자체를 다시 정해야 합니다.\n\n실제 적용 때는 MP를 0.01%p 단위로 판정해 정수 반올림 계단을 피하고, 탐색 절약분을 UI와 오프라인 재생에 똑같이 반영해야 합니다. HP 한도 상승은 잔여 시간을 즉시 채우거나 과거 부재를 소급 보상하지 않도록 합니다. 한도 감소 시 기존 잔여 시간 처리와 광고 충전 정책은 별도로 확정해야 합니다. 1분 접속만으로는 기본 40분(8시간/12)의 오프라인 시간만 충전된다는 점도 안내해야 합니다.'),
        ('sources','## 재현과 안전\n\n순수 Kotlin 엔진/모델만 기존 로컬 컴파일러로 빌드했습니다. Gradle·Android 앱·에뮬레이터·인증·Supabase·장치 등록 코드는 실행하지 않았습니다. 시뮬레이션 프로세스는 OS에서 모든 네트워크와 .db/.sqlite/.sqlite3 파일 접근을 차단했습니다. 8·9·10시간 한도의 1분/12분 충전, 24시간 차감, 잔여 시간 0 이후 사냥 중단도 엔진에서 확인했습니다.\n\n기준 파일: `app/src/simple/java/com/nullplaying/engine/SimpleGameEngine.kt`, `OfflineAdventureConfig.kt`, `SkillCatalog.kt`, `model/SimpleGameModels.kt`. 분석 코드와 입력 해시는 이 보고서 폴더 및 `tools/analysis/`에 있습니다. 재현 노트북은 일반 Python으로 코드 셀을 순서대로 실행·검산했습니다. nbformat/nbclient/ipykernel이 없어 Jupyter 커널 자체 검증은 미실행이며, 정확한 재실행 명령은 README에 기록했습니다.'),
    ]
    md=dict(sections)
    source={'id':'local-study','label':'로컬 순수 엔진 시뮬레이션 · 2026-08-31',
        'path':'tools/analysis/validate_stat_bonus_balance.py','query':{
            'language':'python','engine':'Local Kotlin/JVM and Python/NumPy',
            'description':'현재 소스의 실제 성장 경로, 후보 탐색, 독립 시드 검증 및 접속 간격 환산',
            'sql':(ROOT/'tools/analysis/validate_stat_bonus_balance.py').read_text(),
            'tables_used':['docs/audits/2026-08-31-stat-bonus-balance/holdout_baseline.csv','docs/audits/2026-08-31-stat-bonus-balance/holdout_selected_dense.csv'],
            'filters':['Six classes; seeds 2..5 held out; checkpoints 1..100; no live user data'],
            'metric_definitions':{'progress_uplift':'baseline mean completion days / selected mean completion days - 1; same level goal',
                'progress_gap':'max class completion rate / min class completion rate - 1',
                'sale_gold_day':'mean cumulative item sale gold / mean calendar completion days; excludes quest and drop gold',
                'cap_h':'mean of 8 + 2*individual maxHP/(individual maxHP+350), hours'}}}
    charts=[
        {'id':'capacity-chart','title':'레벨별 전사·마법사 오프라인 한도','type':'line','intent':'trend',
         'question':'HP 성장에 따라 보유 시간이 얼마나 늘어나는가?',
         'rationale':'레벨별 연속 성장과 두 직업의 한도 차이를 같은 시간 축으로 비교합니다.',
         'dataset':'capacity','sourceId':'local-study','encodings':{'x':{'field':'level','label':'레벨'},'y':{'field':'cap_h','label':'보유 한도 (시간)'},'color':{'field':'직업'}},
         'legend':{'position':'bottom'},'valueFormat':'number'},
        {'id':'uplift-chart','title':'레벨 1→100 완료 속도 증가율','type':'bar','intent':'comparison',
         'question':'접속 간격에 따라 각 직업의 성장 속도 효과가 얼마나 달라지는가?',
         'rationale':'동일한 목표 레벨의 직업별 상승률을 두 접속 조건에서 나란히 비교합니다.',
         'dataset':'uplift','sourceId':'local-study','encodings':{'x':{'field':'직업'},'y':{'field':'progress_uplift','label':'기존 8시간 대비 증가율'},'color':{'field':'접속 간격'}},
         'options':{'orientation':'vertical','grouping':'grouped'},'legend':{'position':'bottom'},'valueFormat':'percent'},
    ]
    def table(id,title,dataset,columns,sort):
        return {'id':id,'title':title,'dataset':dataset,'sourceId':'local-study',
            'columns':[{'field':f,'label':label} for f,label in columns],
            'defaultSort':{'field':sort,'direction':'asc'}}
    tables=[
        table('formulas-table','권장 공식','formulas',[(c,c) for c in ['순서','스탯','공식','상한','범위']],'순서'),
        table('effects-table','레벨 100 직업별 평균 효과','effects',[(c,c) for c in ['직업','오프라인 한도','스킬 확률','탐색 시간','판매 보너스']],'직업'),
        table('gaps-table','목표 레벨별 직업 격차','gaps',[('level','목표 레벨'),('gap_h','부재 시간'),('설정','설정'),('성장 격차','성장 격차'),('판매 격차','판매 격차')],'level'),
        table('bands-table','표본 HP/MP 범위','bands',[('level','레벨'),('직업','직업'),('HP 최저/평균/최고','HP 최저/평균/최고'),('MP 최저/평균/최고','MP 최저/평균/최고'),('n','시드 수')],'level'),
    ]
    blocks=[]
    after={'formulas':('table','formulas-table'),'effects':('table','effects-table'),
           'capacity':('chart','capacity-chart'),'growth':('chart','uplift-chart'),
           'balance':('table','gaps-table'),'ranges':('table','bands-table')}
    for id,body in sections:
        blocks.append(dict(id=id,type='markdown',body=body,**({'sourceId':'local-study'} if id!='title' else {})))
        if id in after:
            kind,asset=after[id]
            blocks.append({'id':id+'-evidence','type':kind,kind+'Id':asset})
    artifact={'surface':'report','manifest':{'version':1,'surface':'report','title':'8시간 기준 스탯 보너스 권장안',
        'description':'운영 DB와 분리한 잠정 게임 밸런스 분석','blocks':blocks,'charts':charts,'tables':tables,'sources':[source]},
        'snapshot':{'version':1,'status':'ready','datasets':{'formulas':formulas,'effects':rows(e100),
            'capacity':rows(line),'uplift':rows(lift),'gaps':rows(g),'bands':rows(narrow)}},'sources':[source]}
    (OUT/'artifact.json').write_text(json.dumps(artifact,ensure_ascii=False,indent=2)+'\n')
    # Text companion is the same reviewed narrative, not a separate HTML reader.
    report=[]
    def markdown_table(frame, columns):
        labels=[label for _,label in columns]
        return '\n'.join(['| '+' | '.join(labels)+' |','|'+'|'.join(['---']*len(labels))+'|']+
            ['| '+' | '.join(str(r[field]) for field,_ in columns)+' |' for r in rows(frame)])
    for id,body in sections:
        report.append(body.replace('그래프는','아래 표는'))
        if id=='formulas':
            report.append('\n'.join(['| 스탯 | 공식 | 상한 |','|---|---|---|']+
                [f'| {r["스탯"]} | {r["공식"]} | {r["상한"]} |' for r in formulas]))
        if id=='effects':
            report.append('\n'.join(['| 직업 | 오프라인 한도 | 스킬 확률 | 탐색 시간 | 판매 보너스 |','|---|---|---|---|---|']+
                ['| '+' | '.join(str(r[c]) for c in ['직업','오프라인 한도','스킬 확률','탐색 시간','판매 보너스'])+' |' for r in rows(e100)]))
        if id=='capacity':
            cap_table=line[line.level.isin([1,10,20,50,100])].copy()
            cap_table['한도(시간)']=cap_table.cap_h.round(3)
            report.append(markdown_table(cap_table,[('level','레벨'),('직업','직업'),('한도(시간)','한도(시간)')]))
        if id=='growth':
            lift_table=lift.copy()
            lift_table['속도 증가율']=lift_table.progress_uplift_pct.map(lambda x:f'+{x:.2f}%')
            lift_table['기간 단축률']=lift_table.days_saved_pct.map(lambda x:f'{x:.2f}%')
            report.append(markdown_table(lift_table,[('직업','직업'),('접속 간격','부재 시간'),('속도 증가율','완료 속도 증가'),('기간 단축률','완료 기간 단축')]))
        if id=='balance':
            report.append(markdown_table(g,[('level','목표 레벨'),('gap_h','부재 시간'),('설정','설정'),('성장 격차','성장 격차'),('판매 격차','판매 격차')]))
        if id=='ranges':
            report.append(markdown_table(narrow,[('level','레벨'),('직업','직업'),('HP 최저/평균/최고','HP 최저/평균/최고'),('MP 최저/평균/최고','MP 최저/평균/최고'),('n','시드 수')]))
    report.append('## 제공 형식\n\n분석 스킬의 보고서 뷰어는 SQL 출처만 허용해 이 순수 Kotlin·Python 분석을 렌더링하지 못했습니다. 차트 대신 동일 집계값의 표를 이 로컬 보고서에 포함했습니다. `artifact.json`은 렌더링되지 않은 입력 초안이며 검증된 차트 결과물이 아닙니다. 분석 계산 검증과 보고서 뷰어의 형식 검증은 별개입니다.')
    (OUT/'REPORT.md').write_text('\n\n'.join(report)+'\n')
    make_notebook(v)
    print(json.dumps({'artifact_bytes':(OUT/'artifact.json').stat().st_size,'datasets':{k:len(v) for k,v in artifact['snapshot']['datasets'].items()},'notebook_cells':'executed sequentially in plain Python; Jupyter kernel not installed'},ensure_ascii=False))


def make_notebook(validation):
    cells=[]
    def md(s): cells.append({'cell_type':'markdown','metadata':{},'source':s.splitlines(True)})
    def code(s): cells.append({'cell_type':'code','metadata':{},'source':s.splitlines(True),'execution_count':None,'outputs':[]})
    md('## tl;dr\n8시간 기본 한도 + HP 최대 2시간, MP 최대 +8%p, DEX·CHA 최대 15%가 후보군 내 잠정 추천입니다. 기존보다 모든 직업 격차가 줄어든 것은 아닙니다. REPORT.md를 함께 읽으세요.')
    md('## Context & Methods\n운영 DB·앱·네트워크를 사용하지 않습니다. 실제 Kotlin 엔진 성장 경로를 로컬에서 재현했습니다.\n### Key Assumptions\n8시간은 기본 오프라인 한도, 전경 충전 완료는 12분. 접속 분포 가중치는 없습니다. 후보 탐색 2개 시드, 별도 검증 4개 시드/직업. 노트북 코드 셀은 일반 Python으로 순차 실행했지만 Jupyter 커널 검증은 미실행입니다.')
    code("from pathlib import Path\nimport json, sys\nimport pandas as pd\nroot = next(p for p in [Path.cwd(), *Path.cwd().parents] if (p / 'tools/analysis/stat_bonus_balance.py').exists())\naudit = root / 'docs/audits/2026-08-31-stat-bonus-balance'\nsys.path.insert(0, str(root / 'tools/analysis'))\nprint('Source-backed local artifacts:', audit.name)\n")
    md('## Data\n원시 경로의 단위는 직업×시드×도달 레벨입니다. 아래 10행은 기준 경로의 결정적 미리보기입니다.')
    code("base = pd.read_csv(audit / 'holdout_baseline.csv')\nassert len(base) == 288 and not base.duplicated(['class','seed_index','level']).any()\nprint(base[['class','seed_index','level','hp','mp','tales']].head(10).to_string(index=False))\n")
    md('## Results\n저장된 후보 1위와 추천 파라미터가 일치하는지, 독립 검증과 계산식이 다시 통과하는지 확인합니다.')
    code("grid = pd.read_csv(audit / 'candidate_grid.csv')\nparameters = json.loads((audit / 'selected_parameters.json').read_text())\nassert len(grid) == 75264\nassert all(abs(grid.iloc[0][k]-v)<1e-12 for k,v in parameters.items())\nfrom validate_stat_bonus_balance import main as validate_study\nvalidate_study()\n")
    code("effects = pd.read_csv(audit / 'recommended_effects.csv')\nprint(effects[effects.level == 100][['class','cap_h','raw_proc_pct','search_s','sale_bonus_pct']].round(3).to_string(index=False))\n")
    md('## Takeaways\n공식은 보너스가 존재한다는 제약 하에 선택됐습니다. 실제 사용자 행동, 무한 레벨 밸런스, 운영 정책 검증이 아닙니다. 8시간이 절대 상한이거나 12분 충전 정책을 바꾸면 다시 계산해야 합니다. 코드·CSV·source SHA-256을 함께 보관하세요.')
    namespace={}
    count=0
    for i,c in enumerate(cells):
        c['id']=f'cell-{i+1}'
        if c['cell_type']=='code':
            count+=1
            buf=io.StringIO()
            with contextlib.redirect_stdout(buf): exec(compile(''.join(c['source']),f'notebook-cell-{count}','exec'),namespace)
            c['execution_count']=count
            c['outputs']=[{'output_type':'stream','name':'stdout','text':buf.getvalue().splitlines(True)}]
    nb={'nbformat':4,'nbformat_minor':5,'metadata':{'kernelspec':{'display_name':'Python 3','language':'python','name':'python3'},
        'language_info':{'name':'python','version':sys.version.split()[0]},
        'validation':{'mode':'plain-python-sequential','code_cells_passed':count,'jupyter_kernel_executed':False}},'cells':cells}
    assert len({c['id'] for c in cells})==len(cells)
    assert all(c['cell_type'] in ['code','markdown'] and isinstance(c['source'],list) for c in cells)
    (OUT/'balance_review.ipynb').write_text(json.dumps(nb,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__': main()
