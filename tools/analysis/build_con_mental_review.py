#!/usr/bin/env python3
"""Package the equal-weight planning result without modifying the game."""
from datetime import datetime
import contextlib
import io
import json
import uuid
import pandas as pd
from con_mental_review import OUT,CLASSES,LABELS,FIELDS,effects,verify,dump

def main():
    s=json.loads((OUT/'FINAL_SUMMARY.json').read_text())
    lines=['# CON 모험시간 / INT·WIS 50:50 기획 검토','',
        '판정: 후반 직업 특화와 교차 능력치 혜택을 함께 살리는 조건부 권고안이다. 절대적인 최적해나 실제 이용자 검증 결과는 아니다. 앱 코드·DB·기기에는 적용하지 않았다.','',
        '## 변경 범위','',
        '- HP 대신 CON을 모험시간 보너스에 연결한다. HP 자체의 전투 역할과 성장식은 유지한다.',
        '- MP 대신 정신력 S = 0.5×INT + 0.5×WIS를 스킬 추가 보너스에 연결한다. MP 자체의 역할과 성장식은 유지한다.',
        '- 50:50은 추가 보너스 계산용 가중치다. 능력치 1당 0.5%p가 아니며, 기존 직업별 기본 확률 P0 전체를 대체하지 않는다.',
        '- 같은 정신력은 직업에 관계없이 같은 추가 보너스를 받는다. 다만 최종 상한은 마법사35%, 다른직업30%다.',
        '- 성직자 WIS/CHA, 기존 STR 가방, DEX 탐색과 CHA 판매의 후반 성장 방향은 유지한다.','',
        '## 권고 공식','',
        '`x = clamp((L−1)/99, 0, 1)`', '',
        '`모험시간 = 8 + 2 × min(CON/150,1)^0.6 × (0.15+0.85×x^1.2)` 시간', '',
        '`S = (INT+WIS)/2`', '',
        '`스킬 발동률 = min(직업상한, P0 + 15×min(S/180,1)^1.2)` %', '',
        '`P0 = min(20, 8+floor(floor((7×주력+3×보조)/10)/6))` %', '',
        '`탐색시간 = 5−min(DEX/150, 0.12+0.88×x^1.4)` 초', '',
        '`아이템 판매보너스 = 20×min(CHA/150, 0.12+0.88×x^1.4)` %', '',
        '`B=15+floor((L−1)×3/5); 가방=B+min(floor(STR/2),B−1)` 칸', '',
        '능력치는 유효한 비음수 범위다. 스킬 P0는 기존 직업 적성식을 그대로 유지한다. 과거 MP안의 비마법사 추가계수10을 그대로 쓰지 않고 모든 직업에 계수15를 공유하는 것도 이번 권고안의 변경점이다.','',
        '## 100레벨 평균 — 직업당24개 합성 성장 경로','',
        '| 직업 | CON | 정신력 | 모험시간(h) | 스킬(%) | 탐색(초) | 판매추가(%) | 가방(칸) |',
        '|---|---:|---:|---:|---:|---:|---:|---:|']
    for c in CLASSES:
        r=s['level100'][c]
        lines.append('| '+LABELS[c]+' | '+' | '.join(f'{r[f]:.4f}' for f in ['con','mental','cap_h','raw_proc_pct','search_s','sale_bonus_pct','bag'])+' |')
    lines += ['', '## 레벨별 전사 시간과 마법사 확률 변화','',
        '아래는 관측 평균의 체크포인트 간 변화다. 레벨별 자동 보장 상한이나 모든 캐릭터의 증가량이 아니다. 1→10은9레벨, 이후는10레벨 구간이다.','',
        '| Lv | 전사 추가시간(분) | 이전구간 대비(분) | 마법사 확률(%) | 이전구간 대비(%p) |',
        '|---|---:|---:|---:|---:|']
    checkpoints=[]
    for l in [1,*range(10,101,10)]:
        w=next(r for r in s['checkpoints'] if r['hero_class']=='WARRIOR' and r['level']==l)
        m=next(r for r in s['checkpoints'] if r['hero_class']=='MAGE' and r['level']==l)
        r=dict(level=l,warrior_bonus_min=w['time_bonus_min'],warrior_delta_min=w['time_delta_min'],mage_proc_pct=m['raw_proc_pct'],mage_delta_pp=m['proc_delta_pp'])
        checkpoints.append(r)
        lines.append('| '+str(l)+' | '+' | '.join('—' if r[f] is None else f'{r[f]:.4f}' for f in ['warrior_bonus_min','warrior_delta_min','mage_proc_pct','mage_delta_pp'])+' |')
    lines += ['', '## 밸런스 검증','',
        '- 주 비교:6직업×16경로, Lv1~100. 추가 검증:6직업×8경로, Lv1~120. 총144게임,15,360레벨 스냅샷.',
        '- 두 집단을 분리해 20~100레벨, 접속간격4/6/8/9/10/12/16/24시간, 접속1/5/12분을 비교했다. 12분이면 완충하는 시간 저장 모델이다.',
        f"- 주 비교 성장 최대격차{s['matched']['max_growth_spread_pct']:.4f}%, 아이템 판매수입 최대격차{s['matched']['max_sale_spread_pct']:.4f}%.",
        f"- 추가 검증 성장 최대격차{s['heldout']['max_growth_spread_pct']:.4f}%, 아이템 판매수입 최대격차{s['heldout']['max_sale_spread_pct']:.4f}%.",
        '- 8시간 간격·완충: 주 비교에서는 마법사, 추가 집단에서는 레인저가 가장 빠르다. 안정적인 단일1위라고 결론내리지 않는다.',
        '- 12시간 간격 또는 부분충전: 전사가 성장 우위. 아이템 판매수입은 성직자가 우위다.',
        '- 시험 가드레일 성장6%·판매12% 이내이나, 운영 데이터로 입증된 허용 기준이나 최적화 목표는 아니다.',
        '- CON min형 대안의 성장 최대격차는 주 비교4.9593%, 추가5.3095%로 더 작지만 초반 능력치 차이를 숨긴다. 선택한 곱셈식은 초반 CON 롤과 비주력 혜택을 보존한다. 이 비교는 같은 전투결과에 대한 시간모델 후처리다.','',
        '## 상한과 성장 난도','',
        '- 마법사35%는 P0=20과 S≥180이 필요하다. Lv100에서24개 경로 모두35% 미만. 120까지 재생한 신규8개는106~112레벨에 최초 도달했다.',
        '- 따라서 35%는 100레벨 이후 완성 목표이지 거의 불가능한 희귀 상한이 아니다. 관측 경로 수는 실제 이용자의 달성확률이 아니다.',
        '- 전사10시간은 Lv≥100과 CON≥150을 동시에 요구한다. Lv100에서22/24가 도달했다. 나머지직업은 이 표본에서 미도달이다.',
        '- CON·DEX·CHA는 직업제한이 아니다. 비주력 능력치가 충분하면 다른직업도 상한에 도달 가능하다. 절대적인 직업전용 상한을 원하면 별도 직업조건이 필요하다.',
        '- 같은 레벨에서 CON150 이후의 CON 추가분은 시간 혜택을 더 늘리지 않는다. 능력치 지수0.6은 비주력 혜택을 보호하는 체감형이며, 레벨 배율이 후반 보상을 보강한다. 모든 실제 레벨 구간의 증가량이 엄격히 커지지는 않는다.',
        '- P0 포화와 성장운 때문에 마법사 최종 확률 증가량도 매 레벨 엄격히 가속되지는 않는다. S에 의한 추가분은 지수1.2로 후반 포인트당 효과가 커진다.',
        '- 스킬 확률은 일반 판정 확률이다. 연속 일반공격15회 뒤 확정 발동은 별도로 존재한다.','',
        '## 검증·안전·한계','',
        '- Kotlin 훅과 Python 공식15,360행 대조, 경계3,840검사 통과. 기존동일seed9,600행의6능력치·HP/MP·P0·가방·설화수가 정확히 일치했다.',
        '- 원본 앱 소스 해시가 이전 기준과 동일함을 확인했다. 분석 사본만 계측했다. 앱 실행·기기 등록·DB 접속 없음. 실행 프로세스에 OS 네트워크 및 DB파일 접근 거부 적용.',
        '- 캘린더 결과는 실제 전투 재생에 반복충전 환산을 결합한 근사이며, 실제 사용자 접속 로그나 동적시간충전 앱통합 실험이 아니다.',
        '- 판매수입은 아이템 누적 판매금액/환산기간이며 퀘스트골드·지출·보유재산은 제외한다.',
        '- AI 기획·밸런스 검토는 사람이 참여한 기획팀 승인과 다르다. 기존 저장 성직자의 INT 재분배나 데이터 마이그레이션은 범위 밖이다.',
        '- 두 AI 검토 모두 조건부 권고에 동의했다. 팔라딘은 성장 하단에 있어 가방·판매 역할의 체감은 별도 확인이 필요하다.',
        '- 추가8시드의 기존 능력치 분포도 공식 선정에 참고했으므로 사전 미공개 독립 홀드아웃으로 해석하지 않는다. 101~120레벨 전체 밸런스 검증이 아니라 상한도달 추적을 추가한 것이다.',
        '- .6/.4 가중치와의 독립 전투 A/B 비교가 아니라 최종요청 .5/.5안의 적합성 검증이다.','',
        '## 재현','',
        '`run_con_mental_probe.py` → `con_mental_review.py` → `build_con_mental_review.py`.',
        '전체 레벨 통계는 all-level-effects.csv, 구간 증가량은 level-checkpoints-and-deltas.csv에 있다.',
        '노트북은 코드셀을 일반Python에서 순차 실행해 검증했다. nbformat/nbclient/ipykernel 미설치로 Jupyter커널 실행은 하지 않았다.']
    (OUT/'DESIGN_REVIEW.md').write_text('\n'.join(lines)+'\n')
    common=dict(label='CON·정신력 50:50 오프라인 기획 검증',
        files=[dict(label=n) for n in ['matched.csv','heldout.csv','ConMentalBonusHooks.kt','con_mental_review.py','boundary-checks.log']],
        filters=['합성 성장경로: 직업당24개','주 비교16개, 추가검증8개','평균 혜택: Lv100'],
        metricDefinitions=[dict(name='정신력',definition='스킬 추가 보너스의 INT/WIS 동일 가중 점수. 기존 직업별 P0는 유지한다.',formula='S=0.5×INT+0.5×WIS'),
            dict(name='스킬 확률',definition='연속일반공격 후 확정발동을 제외한 일반 판정 확률.',formula='min(마법사35/그외30, P0+15×min(S/180,1)^1.2)'),
            dict(name='모험시간',definition='CON과 레벨 배율로 결정하는 시간 저장 상한. 실제 접속시간이나 자동 지급 시간은 아니다.',formula='8+2×min(CON/150,1)^0.6×(0.15+0.85×x^1.2), x=clamp((L−1)/99,0,1)')],
        caveats=['운영 데이터가 아닌 로컬 순수엔진 합성 재생. DB와 앱에는 미적용.',
            '캘린더 효율은 시간저장·반복충전 환산 모델이며 실제 사용자 접속실험은 아니다.',
            '24개 경로의 상한달성 건수는 모집단 확률이 아니다.',
            'AI 검토이며 실제 사람 기획팀의 승인은 아니다.'])
    class_rows=[dict(hero_class=LABELS[c],**{f:round(s['level100'][c][f],6) for f in FIELDS}) for c in CLASSES]
    cap_rows=[dict(seed=int(k),first_35pct_level=v) for k,v in b_mage(s).items()]
    dump('sources-input.json',dict(schemaVersion=1,items=[
        dict(id='con-mental-result',title='CON·INT/WIS 50:50 검증 결과',queries=[dict(id='class-benefits',source=common,rows=class_rows,
            preview=dict(kind='aggregate',note='직업별100레벨24경로평균',totalRows=14400),
            columns=[dict(field=f,label=l) for f,l in [('hero_class','직업'),('con','CON'),('mental','정신력'),('cap_h','모험시간(h)'),('raw_proc_pct','스킬확률(%)'),('search_s','탐색(초)'),('sale_bonus_pct','판매추가(%)'),('bag','가방(칸)')]])]),
        dict(id='con-mental-growth',title='레벨별 증가량과 마법사 상한',queries=[dict(id='level-growth',source={**common,
            'filters':['직업: 전사·마법사','직업당24개 합성경로 평균','체크포인트1,10,20,...,100'],
            'caveats':common['caveats']+['평균 관측 혜택과 구간 증가량이며 자동 지급 레벨상한이 아니다.']},rows=checkpoints,
            preview=dict(kind='aggregate',note='전사·마법사24경로의레벨평균과구간증가량'),
            columns=[dict(field=f,label=l) for f,l in [('level','레벨'),('warrior_bonus_min','전사추가시간(분)'),('warrior_delta_min','시간증가(분)'),('mage_proc_pct','마법사확률(%)'),('mage_delta_pp','확률증가(%p)')]]),
            dict(id='mage-cap',source={**common,'filters':['마법사 추가8경로','최대120레벨까지 확인'],
                'metricDefinitions':[dict(name='35% 최초도달',definition='S180 및 P0=20을 만족하는 최초 관측 레벨. 전체 캐릭터 보장 범위가 아니다.')]},
                rows=cap_rows,columns=[dict(field='seed',label='시드'),dict(field='first_35pct_level',label='최초35%레벨')])])]))
    notebook()
    print('Packaged design review, level data, sources receipt input and verified notebook.')

def b_mage(s):
    return next(r for r in s['heldout']['cap_hits'] if r['hero_class']=='MAGE')['first_by_seed']

def notebook():
    cells=[]
    def md(t):cells.append(dict(cell_type='markdown',metadata={},source=t.splitlines(True)))
    def code(t):cells.append(dict(cell_type='code',metadata={},source=t.splitlines(True),execution_count=None,outputs=[]))
    md('# CON / INT·WIS 50:50\n\n## tl;dr\n\n추가보너스의 가중치는 INT50%·WIS50%. CON은모험시간. 기획조건부권고이며 앱·DB에는미적용.\n\n## Context & Methods\n\n### Key Assumptions\n\nP0직업적성식유지,최종상한마법사35%/다른직업30%. 합성게임144경로. 주비교96경로와추가48경로를분리해평가. 시간저장효율은캘린더환산모델.\n\n일반Python순차실행검증. nbformat/nbclient/ipykernel미설치로Jupyter커널미실행. 커널환경에서 `python -m jupyter nbconvert --execute --to notebook --inplace con-mental-review.ipynb`로재검증가능.\n')
    md('## Data\n\n### 1. Formula parity\n')
    code("from pathlib import Path\nimport sys,json,pandas as pd,numpy as np\nroot=next(p for p in [Path.cwd(),*Path.cwd().parents] if (p/'tools/analysis/con_mental_review.py').exists())\nsys.path.insert(0,str(root/'tools/analysis'))\nfrom con_mental_review import OUT,effects,verify\na=verify(pd.read_csv(OUT/'matched.csv'),list(range(14,30)))\nb=verify(pd.read_csv(OUT/'heldout.csv'),list(range(30,38)))\nprint('Kotlin/Python rows:',len(a)+len(b))\nassert len(a)+len(b)==15360\n")
    md('## Results\n\n### 2. Level100 and cap difficulty\n')
    code("z=pd.concat([a,b]);v=z[z.level==100]\nprint(v.groupby('class')[['cap_h','raw_proc_pct','search_s','sale_bonus_pct']].mean().round(4).to_string())\nassert (v[v['class']=='MAGE'].raw_proc_pct<35).all()\nfirst=b[(b['class']=='MAGE') & np.isclose(b.raw_proc_pct,35,atol=1e-10,rtol=0)].groupby('seed_index')['level'].min()\nassert len(first)==8 and first.min()==106 and first.max()==112\nprint('Additional Mage paths first cap:',first.to_dict())\n")
    md('### 3. Balance guardrails and safety\n')
    code("s=json.loads((OUT/'FINAL_SUMMARY.json').read_text())\nfor cohort in ['matched','heldout']:\n r=s[cohort]\n assert r['max_growth_spread_pct']<6 and r['max_sale_spread_pct']<12\n print(cohort,r['max_growth_spread_pct'],r['max_sale_spread_pct'])\nassert '3840 PASS' in s['boundary_checks']\nassert s['safety']['app_sources_unchanged'] and not s['safety']['app_start']\nprint(s['safety'])\n")
    md('## Takeaways\n\n100레벨일반판정확률평균은마법사33.25%,성직자·레인저27.9%,물리직23.1%. 8시간완충은마법사/레인저가경쟁,긴간격/부분충전은전사,판매수입은성직자. 실측이용자성과나절대최적해를주장하지않는다.')
    env={};n=0
    for cell in cells:
        cell['id']=uuid.uuid4().hex[:8]
        if cell['cell_type']=='code':
            n+=1;buf=io.StringIO()
            with contextlib.redirect_stdout(buf):exec(compile(''.join(cell['source']),cell['id'],'exec'),env)
            cell['execution_count']=n;cell['outputs']=[dict(output_type='stream',name='stdout',text=buf.getvalue().splitlines(True))]
    dump('con-mental-review.ipynb',dict(nbformat=4,nbformat_minor=5,cells=cells,metadata=dict(
        kernelspec=dict(display_name='Python 3',language='python',name='python3'),language_info=dict(name='python'),
        validation=dict(mode='plain-python-sequential',cells_passed=n,jupyter_kernel_executed=False))))

if __name__=='__main__':main()
