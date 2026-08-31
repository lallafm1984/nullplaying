# 8시간 기준 스탯 보너스 분석

기획 검토용입니다. 앱/운영 코드 수정·배포·DB 조회·DB 기록·디바이스 등록을 하지 않습니다.
8시간을 **기본 오프라인 보유 한도**로 해석했으며, 충전 완료는 기존 12분을 유지한다고 가정했습니다.

## 읽는 순서

후속 비교: [12시간 / 30% / 4초 / +20% 상한 비교](UPPER_CAPS_COMPARISON.md). 재현 노트북은 `upper_caps_review.ipynb`이며 코드 셀 3개를 일반 Python으로 순차 검산했습니다. 기존 REPORT.md와 추천 파라미터 파일은 보존했습니다.

1. `REPORT.md` — 권장 공식, 실제 효과, 한계.
2. `artifact.json` — 차트/표 포함 보고서 입력 초안. 뷰어가 SQL 출처만 지원하여 형식 검증에서 거부되었고 렌더링되지 않았습니다. 최종 제공물은 전체 수치 표가 포함된 `REPORT.md`입니다.
3. `balance_review.ipynb` — 검산 노트북. 일반 Python으로 코드 셀을 순서대로 실행했습니다.
4. `validation_summary.json` — 검증 결과와 별도 시드 결과.
5. `recommended_effects.csv`, `validated_class_comparison.csv`, `hp_mp_sample_ranges.csv` — 상세 수치.

## 재현

저장소 루트에서 실행합니다. 기존 캐시의 Kotlin 2.1.20/JDK17과 로컬 Python NumPy/Pandas를 사용하며 네트워크 설치를 하지 않습니다. Java 힙 상한은 768MB입니다.

```bash
python3 tools/analysis/run_stat_bonus_probe.py --samples 2 --level 100 --output engine_trajectories.csv
/usr/bin/sandbox-exec -f docs/audits/2026-08-31-stat-bonus-balance/offline-only.sb /Users/lim/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 tools/analysis/stat_bonus_balance.py
python3 tools/analysis/run_stat_bonus_probe.py --samples 4 --seed-offset 2 --level 100 --output holdout_baseline.csv --skip-compile
python3 tools/analysis/run_stat_bonus_probe.py --samples 4 --seed-offset 2 --level 100 --output holdout_selected.csv --skip-compile --parameters docs/audits/2026-08-31-stat-bonus-balance/selected_parameters.json
python3 tools/analysis/run_stat_bonus_probe.py --samples 4 --seed-offset 2 --level 100 --dense --output holdout_selected_dense.csv --skip-compile --parameters docs/audits/2026-08-31-stat-bonus-balance/selected_parameters.json
python3 tools/analysis/run_stat_bonus_probe.py --samples 1 --level 20 --output engine_control.csv --skip-compile
/usr/bin/sandbox-exec -f docs/audits/2026-08-31-stat-bonus-balance/offline-only.sb /Users/lim/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 tools/analysis/validate_stat_bonus_balance.py
/usr/bin/sandbox-exec -f docs/audits/2026-08-31-stat-bonus-balance/offline-only.sb /Users/lim/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 tools/analysis/build_stat_bonus_report.py
```

`run_stat_bonus_probe.py`가 Java 컴파일/실행을 OS 네트워크/DB 파일 접근 차단 하에 수행합니다. 분석용 엔진 사본에만 보너스 판정과 집계 계측을 삽입합니다. 앱 소스는 읽기만 합니다. HP는 엔진 경로에 직접 보상 시간을 주지 않고, 별도 보유 시간 모델에서 레벨별로 환산합니다.

시드 0·1은 후보 탐색, 2·3·4·5는 독립 검증입니다. dense 재실행은 같은 검증 시드를 더 촘촘히 기록할 뿐, 독립 표본 수를 늘리지 않습니다. 기준 12시간 대비 결과와 변경 후 기본 8시간 대비 결과를 혼용하지 마세요.

## 노트북 검증 한계

현재 로컬 런타임에 nbformat/nbclient/ipykernel이 없어 Jupyter 커널 자체 검증은 하지 않았습니다. 코드 셀은 동일 Python 환경에서 순차 실행하고 출력과 구조를 확인했습니다. Jupyter가 이미 설치된 별도 로컬 환경에서는 다음과 같이 검증할 수 있습니다.

```bash
python -m jupyter nbconvert --execute --to notebook --inplace docs/audits/2026-08-31-stat-bonus-balance/balance_review.ipynb
```

후속 비교 재생성:

```bash
python3 tools/analysis/run_stat_bonus_probe.py --samples 4 --seed-offset 2 --level 100 --dense --output upper_caps_dense.csv --parameters docs/audits/2026-08-31-stat-bonus-balance/upper_caps_parameters.json
/usr/bin/sandbox-exec -f docs/audits/2026-08-31-stat-bonus-balance/offline-only.sb /Users/lim/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 tools/analysis/compare_stat_bonus_caps.py
python -m jupyter nbconvert --execute --to notebook --inplace docs/audits/2026-08-31-stat-bonus-balance/upper_caps_review.ipynb
```

마지막 Jupyter 명령은 해당 도구가 설치된 로컬 환경에서만 사용합니다. 현재 세션에서는 Jupyter 커널을 실행하지 않았습니다.

## 추정의 한계

- 후보 탐색 모형의 일일 처치량은 성장 속도의 근사 대리값이지 정확한 XP/일이 아닙니다.
- 최종 검증은 실제 엔진의 동일 레벨 도달 시간입니다. MP·DEX·CHA와 장비/숙련 피드백은 재생했으나 실제 사용자 로그인 행동을 수집하지 않았습니다.
- 접속 간격별 일수는 전경 1·5·12분과 오프라인 4·8·12·24시간의 반복을 가정한 환산입니다. 로그인 간격에 전경 체류 시간이 더해집니다.
- HP/MP 최소·평균·최대 표는 직업당 6개 경로의 표본 통계이며 전역 가능 범위가 아닙니다.
- 사용자 분포·리롤·광고 시청률·장기 이탈·100레벨 이후 장기 밸런스는 측정하지 않았습니다.
- 노트북의 source hash 검증이 실패하면 현재 엔진이 변경된 것이므로 전체 경로부터 재생성해야 합니다.
