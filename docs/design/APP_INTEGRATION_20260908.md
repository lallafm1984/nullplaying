# 앱 사전 통합 병합 — 2026-09-08

> 이 문서는 당시의 역사적 검증 기록입니다. 최신 버전·구현·배포 상태는
> [현재 인수인계](../SESSION_HANDOFF.md)를 우선합니다.

현재 구현 기준은 저장소 루트다. `output/preintegration/adventure-20260906/project`는 이전 검증본이다.
원래 인수인계는 해당 폴더의 `qa/player-network-arena-handoff-20260908.md`에 있다.

## 반영 내용

- 사건 100종, 인연 사건 50종, 모험 특성 40종과 20쌍의 상반 관계, 로컬 저장/복구를 병합했다.
- 공유 플레이어 프로필, 일일 후보, 20명까지의 로컬 보충, 결투장 일일 순위와 배치 기록을 병합했다.
- 최신 아레나 스킬트리와 캐릭터 ID 중복 복구를 보존했다. 서신 UI는 계속 비활성이다.
- release의 세 기능 플래그는 기본 활성이다. `local.properties` 또는 환경 변수에 명시한 false는
  롤아웃 중단 설정으로 유지된다. debug/migrationTest 및 offline QA는 운영 Supabase에 연결하지 않는다.
- 출시 플래그로 결투장 메뉴도 표시하며 저장된 장비/가방 탭은 통합 아이템 탭으로 이동한다.
- Android 후보 수신 시각과 결투장 경과 시각을 `SystemClock.elapsedRealtime()`으로 통일했다.
  절전을 포함하는 같은 시계를 써야 후보의 신뢰 시간과 출전권 회복 시간을 비교할 수 있다.
  [Android SystemClock 문서](https://developer.android.com/reference/android/os/SystemClock)
- 결투장 일일 순위의 영어/일본어 안내, 점수, 전적과 순위 표기를 보완했다.
- 사전 통합 폴더 전용 분석 테스트 출력은 `app/build/reports/adventure-probes`로 옮겼다.
- 실물폰 기존 0.4.4(18) 다음 버전인 0.4.5(19)의 서명된 APK/AAB를 생성했다.

## 병합 감사

`output/app-integration-20260908/comparison.json`은 기준 해시·기존 루트 해시·검증본 해시를 보존한다.
변경 대상 130개는 파일별로 검토/병합했고 루트만 변경된 6개는 그대로 유지했다.
양쪽 변경 11개를 별도 검토했으며 기존 복구 회귀 테스트를 제거하지 않았다.
변경 전 대상 파일은 같은 폴더의 `original/`에 보존했다.

라이브 migration 004~008은 재적용하지 않았다. 동일 내용의 SQL을 루트에 포함하고 원격 이력에서
004~008의 로컬/원격 일치를 읽기 검증했다. 이번 앱 검증용 운영 Auth 사용자나 DB 행은 생성하지 않는다.

## 검증 경계

단위/엔진/저장 검증, 로컬 PostgreSQL 호환 계약 검사, 에뮬레이터 화면, 실물폰 및 릴리스 아티팩트의
증거는 `output/app-integration-20260908/`에 모은다. 마지막 결과는 `FINAL_REVIEW.md`를 확인한다.
배치 완료 화면용 `ArenaRankingQaActivity`는 offlineQa에만 있으며, 실제 RPC 디코더와 디스크 캐시,
실제 `BattleRankingScreen`을 로컬 fixture로 검증한다. 이를 라이브 서버 참가자 화면으로 해석하지 않는다.

앱/Play 업로드는 다음 출시 단계다. 결투장 점수는 클라이언트 집계 명예 기록이라는 서버 계약을 유지한다.

## 최종 상태

전체 단위 테스트 1,224건은 실패/오류 0, 건너뜀 3이다. 마지막 번역 수정 후 관련 69건도 통과했다.
release R8 빌드, lint(오류 0), AAB 검증과 에뮬레이터 실행/같은 버전 재설치 시 저장 유지가 통과했다.
SM-S931N에는 별도 offline QA 앱 0.4.5(19)를 덮어설치하고 APK 해시와 순위 화면을 확인했다.

실물폰의 실제 `com.nullplaying`은 Play 설치본이며 로컬 업로드 키와 서명이 달라 덮어설치가
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`로 거부됐다. 기존 Play 앱과 데이터는 삭제하지 않았다.
**프로덕션 전 남은 출시 검증은 Play 테스트 트랙을 통한 18→19 업데이트와 기존 저장 데이터 유지다.**
배치 완료 후 라이브 참가자가 채워진 순위 화면의 E2E도 로컬 fixture 검증과 구분하여 남겨 둔다.
Play 업로드/배포는 실행하지 않았다.
