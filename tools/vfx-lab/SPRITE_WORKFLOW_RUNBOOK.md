# AlarmQuest 대표 스킬 스프라이트 운영 계약

## 현재 범위

- 직업은 전사, 도적, 순찰자, 마법사, 성직자, 성기사 6종이다.
- 각 직업은 검토 완료된 대표 스킬 20개만 보유한다. 전체 카탈로그는 120개다.
- 과거의 티어별 5후보·직업별 100개 기획은 운영 범위가 아니다.
- 첫 스킬은 레벨 1에 보유하고, 이후 레벨 5, 10, …, 95에 하나씩 습득한다.
- `catalogId`와 원본 PNG 경로는 이름을 다듬더라도 유지한다.

## 원본 계약

- 기본: Detailed, 16프레임, 4×4, 셀 361×160, 시트 1444×640, RGBA PNG.
- 재생: 16fps, 비반복.
- `warrior_t03_c02`는 승인된 12프레임 4×3, 1444×480 원본을 그대로 쓴다.
- 브라우저에서 승인된 PNG가 원본이다. Android 파생본은 원본을 대체하지 않는다.
- FrameSmith 상태를 먼저 확인하고 `activeJobId`가 null이 아닐 때는 작업을 중단·교체·재시작하지 않는다.

```bash
curl -sS http://127.0.0.1:4317/api/health
```

## 단일 진실 공급원

- 앱 카탈로그: `app/src/simple/java/com/alarmquest/engine/SkillCatalog.kt`
- 웹·Android 연결 매니페스트: `tools/vfx-lab/data/signature-skills.json`
- 브라우저 목록: `tools/vfx-lab/data/skills.json`
- 원본: `tools/vfx-lab/custom-assets/`
- Android 파생본: `app/src/simple/res/drawable-nodpi/vfx_sheet_*.webp`
- Android 라우팅: `app/src/simple/java/com/alarmquest/ui/DetailedSpriteAssets.kt`

## 생성과 동기화

```bash
node tools/vfx-lab/build_signature_skill_manifest.mjs
python3 tools/vfx-lab/sync_signature_sprites_to_android.py --check-only
python3 tools/vfx-lab/sync_signature_sprites_to_android.py --prune
python3 tools/vfx-lab/prune_android_export_to_signatures.py
python3 tools/vfx-lab/generate_data.py
python3 tools/vfx-lab/verify_android_parity.py
```

Android 변환은 Q85 손실 WebP, `alpha_quality=100`, `method=4`를 사용한다. 모든 파일에서 PNG와 WebP의 알파 바이트가 완전히 같아야 하며, 동기화 스크립트와 parity 검사가 이를 실패-폐쇄 방식으로 확인한다.

## 웹 확인

```bash
cd tools/vfx-lab
python3 serve.py
```

`http://127.0.0.1:4173`에서 직업별 20개, 전체 120개가 표시되어야 한다. 각 항목은 검토된 스프라이트 시트로 재생되어야 하고, 레벨 표시는 `1, 5, 10, …, 95`여야 한다.

### 타격 데이터 편집·앱 전달

- 스킬을 선택한 뒤 `APP HIT DATA`에서 타수와 각 타격 타이밍(ms)을 편집하고 `저장`을 누른다.
- 타수는 앱 카탈로그 계약과 같은 1~12타, 타이밍은 0~900ms의 엄격한 오름차순이어야 한다.
- 저장값은 `data/skill-hit-overrides.json`에 안정적인 `catalogId`를 키로 기록된다.
- `hitWeights`는 앱의 `SkillCatalog.weightsFor` 규칙으로 서버가 계산하므로, 앱 반영 시 `hitCount`, `hitWeights`, `hitTimingsMillis`를 함께 옮긴다.
- 이 파일은 앱 반영 전 검토 데이터다. 저장만으로 Android `SkillCatalog.kt`가 자동 변경되지는 않는다.

## 제거 규칙

- `cleanup_obsolete_skill_assets.py`의 dry-run 결과를 먼저 검토한 뒤 `--apply`한다.
- 최종 120개 원본, 해당 프롬프트·검증 증거, 매니페스트와 동기화 도구만 보존한다.
- `vfx16_*_t??_c??.webp`와 최종 ID가 아닌 `vfx_sheet_*` 파생본은 제거한다.
- 실제 삭제 후 dry-run이 `targets=0`인지 다시 확인한다.

## 출시 게이트

1. `verify_android_parity.py` 통과.
2. 전체 단위 테스트와 lint 통과.
3. clean debug APK 생성.
4. 대상 `SM-S931N`에 `adb install -r` 성공.
5. 설치된 versionCode/versionName, 실행 PID, resumed activity와 foreground focus 확인.
