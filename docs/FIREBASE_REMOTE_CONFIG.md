# 오프라인 모험 Remote Config

Firebase 프로젝트: `nullplaying-2d131` / 게임 Android 앱: `com.nullplaying`.
관리자 앱 `com.nullplaying.admin`과 별도로 등록하며 Supabase 로그인은 변경하지 않습니다.

콘솔: https://console.firebase.google.com/u/1/project/nullplaying-2d131/config/env/firebase

## 조절할 값

| 매개변수 | 기본값 | 단위 / 허용 범위 | 의미 |
| --- | ---: | --- | --- |
| `offline_adventure_capacity_minutes` | 480 | 정수 분, 1–4320 | CON 보너스 전 기본 저장 시간 (8시간) |
| `offline_adventure_charge_minutes` | 20 | 정수 분, 1–1440 | 0%에서 100%까지 필요한 전경 접속 시간 |
| `app_session_logs_enabled` | true | 부울 true / false | 세션 로그 저장·전송 ON/OFF. 랭킹과 별도 |

직업별 최대 저장 시간은 `원격 기본 시간 × (1 + 0.25 × CON 성장 계수)`입니다. CON 성장 계수는 `min(CON/150,1)^1.2 × (0.15+0.85×clamp((L-1)/99,0,1)^1.2)`입니다.
예: 기본 8시간이면 최대 10시간, 기본 12시간이면 최대 15시간, 기본 24시간이면 최대 30시간입니다. 현재 선택한 기본값은 8시간이지만 산식에 8시간을 강제하거나 고정 2시간을 더하지 않습니다. 기존 키와 정상 캐시를 그대로 사용합니다.
두 값을 함께 검토하고 **변경사항 게시**를 눌러 반영합니다. 앱은 어느 하나라도 잘못된 값이면 두 값 모두 마지막 정상 설정을 유지합니다. 0은 비활성화 의미가 아니며 허용하지 않습니다.

## 적용 방식

- 2026-09-01 사용자가 로그 ON / 기본 8시간 / 완충 20분을 확정했습니다. 앱 기본값은 true / 480분 / 20분이며, 기존 설치에서는 마지막으로 적용한 정상 캐시가 새 원격값을 활성화할 때까지 유지됩니다.
- 앱 시작 및 복귀 시 조회합니다. 조회 간격은 릴리스 1시간, 디버그 60초이며 실시간 업데이트도 구독합니다.
- 설정을 가져오는 동안 게임 시작을 기다리게 하지 않습니다. 활성화된 값은 저장 데이터 로딩과 복귀 정산을 마친 뒤 전경에서 적용합니다.
- 변경 전 경과 시간은 기존 규칙으로 정산합니다. 비활성 캐릭터 슬롯도 함께 처리합니다.
- 최대값이 변해도 이미 충전된 잔여 시간은 보존합니다. 새 최대값을 넘는 기존 시간은 소진될 때까지 유지하고 추가 충전하지 않습니다. 레벨 상승으로 최대값이 늘어나도 무료 시간을 지급하지 않습니다.
- 보상 광고는 새 최대값까지 충전하며 중복 보상은 계속 차단합니다. 광고 표시 중 설정 변경은 복귀 또는 다음 주기까지 미룹니다.
- 저장 시간, 충전 속도, 퍼센트 표시, 보상 광고는 같은 직업별 상한을 사용합니다. 소진 알림은 실제 잔여 시간에 따릅니다. 밀리초 미만 충전분은 별도 분수 버전을 붙여 저장하며, 이전 분모(충전 분)를 새 분모(충전 밀리초)로 한 번만 변환합니다. Room 구조나 원격 DB 마이그레이션은 없습니다.
- Firebase Auth / Analytics SDK는 추가하지 않습니다. Firebase Installations는 Remote Config의 필수 의존성입니다.
- 디버그·migrationTest 빌드의 Supabase 접속은 차단합니다. Remote Config 검증을 위해 운영 DB에 테스트 사용자·프로필·로그를 만들지 않습니다.

## 충전 시간 검증

- 증가량: `전경 경과 밀리초 × 직업별 최대 저장 밀리초 / 원격 완충 밀리초`. 밀리초 미만의 나머지는 다음 충전으로 이월합니다.
- 현재 480분 / 20분 설정에서 CON 보너스 전 상한은 8시간, 최대 보너스 상한은 10시간입니다. 1분 접속 시 각각 24분 / 30분을 충전하고 20분 접속 시 완충합니다. 충전 중 레벨·능력치가 고정된 빈 저장량 기준입니다.
- 직업별 상한에 대해 5분 접속은 25%, 12분은 60%, 20분은 100% 충전입니다.
- 기본 1/480/720/1440/4320분 × 완충 1/6/12/20/24/1440분 × CON 0/50/150의 90조합에서 반충전·완충·보상 충전·상한을 순수 JVM으로 검증했습니다.
- 시간·충전 설정 변경 전 경과분을 먼저 정산하고, 변경 후에는 새 속도로 충전합니다. 설정 변경 자체로 잔여 시간을 추가하지 않습니다.

## 세션 로그 스위치

- ON: 홈 이동/앱 전체 백그라운드는 `background`, 정상 종료는 `exit`로 로컬에 먼저 저장한 뒤 전송을 시도합니다. 5분 미만 복귀도 기록합니다.
- 전체 화면 광고 등 앱 내부 Activity 간 이동, 화면 회전, 중복 stop/destroy는 로그를 중복 생성하지 않습니다.
- OFF: 새 세션 로그를 생성하지 않고 대기 로그 전송도 중단합니다. 기존 대기 로그는 보존하며 ON 이후 재전송합니다. OFF 기간의 이벤트는 나중에 소급 생성하지 않습니다.
- 랭킹 동기화·인증·프로필 갱신은 이 스위치와 별개입니다. QA 빌드는 ON이어도 Supabase에 접속하지 않습니다.
- 캐시/인앱 기본값으로 시작하며 Remote Config fetch 또는 실시간 업데이트 활성화 이후 반영됩니다. 이미 전송 중인 HTTP 요청을 소급 취소하거나 이미 저장된 서버 로그를 지우지는 않습니다.
- 기존 대기열 최대 100건 정책 유지. 로컬 저장/네트워크 실패와 강제 종료 시 모든 상황의 즉시 서버 전달을 보장하지 않습니다. 콜백 전에 프로세스가 강제 종료되면 해당 종료 이벤트 자체를 기록하지 못할 수 있습니다.
- 이 스위치를 읽는 앱 업데이트가 설치된 이후에만 제어됩니다.

## 빌드·운영

`app/google-services.json`은 게임용 등록에서 받은 파일입니다. 서비스 계정 개인키를 앱에 넣지 않습니다.
실제 클라이언트 설정 파일은 공개 Git 저장소에서 제외하고 로컬에 보존합니다. 새 체크아웃에서는
Firebase 콘솔의 **프로젝트 설정 → 내 앱 → `com.nullplaying` → google-services.json 다운로드**로
받은 파일을 `app/google-services.json`에 두세요. `app/google-services.example.json`은 구조 설명용이며
실제 다운로드 파일을 대체하지 않습니다. 관리자 앱 전용 파일이나 서비스 계정 JSON을 사용하지 않습니다.
현재 배포된 v13에는 이 연동 코드가 없으므로 원격 설정이 동작하려면 이 코드가 포함된 앱 업데이트가 필요합니다. 이 작업은 Play 출시를 자동 수행하지 않습니다.

검증 명령:

```sh
python3 tools/analysis/run_final_balance_tests.py --tag after
./gradlew --offline :app:assembleDebug :app:compileDebugUnitTestKotlin
```

원격 값은 사용자에게 노출될 수 있으므로 비밀번호·서비스 키 등을 저장하지 않습니다. 공개 개인정보처리방침과 Play Data Safety에도 Remote Config / Installations 수집 항목을 반영한 후 출시해야 합니다.

현재 검증에서는 앱/기기를 실행하지 않습니다. 디버그의 Supabase 차단만으로 Firebase Installations까지 차단되는 것은 아닙니다. 이번 사용자가 승인한 운영 설정 변경은 위 세 매개변수뿐이며 기기 실행·배포는 수행하지 않습니다.

공식 참고: [Android 연동](https://firebase.google.com/docs/remote-config/android/get-started), [로드 전략](https://firebase.google.com/docs/remote-config/loading), [SDK 데이터 공개](https://firebase.google.com/docs/android/play-data-disclosure).

## 2026-08-31 검증 결과

- Firebase 콘솔 구성 버전 1 게시: `720` / `12`, 두 매개변수 모두 숫자형.
- 에뮬레이터 `alarmquest-qa` (Android 15): APK 데이터 유지 설치와 실행 성공.
- SDK 로그 `capacitySource=2, chargeSource=2` 확인. 인앱 기본값(1)이 아닌 원격값(2).
- 앱 내부 활성 템플릿 버전 1에서 두 키와 값 확인.
- 엔진 110개, 설정 4개, 저장소 22개: 총 136개 테스트 통과.
- debug APK 빌드 및 lint 성공 (오류 0, 경고 37).
- release 의존성 그래프: `firebase-config:23.0.0`, `firebase-installations:19.0.0`; Auth/Analytics SDK 미포함.
- 증거: `tmp/remote-config-qa/verification.log`, `remote-config.log`, `activated-template.json`.
- 실물 기기는 ADB/mDNS에서 발견되지 않아 설치하지 않음. Play 출시, 공개 정책 페이지 배포는 수행하지 않음.

## 2026-09-01 최종 설정 게시

- 사용자 승인 후 Firebase Remote Config **버전 2 게시 완료**. 새 부울 `app_session_logs_enabled=true`, 기존 숫자형 `offline_adventure_capacity_minutes=480`, `offline_adventure_charge_minutes=20`.
- 게시 확인창의 변경 목록이 위 세 키뿐임을 확인한 뒤 게시했다. 게시 후 버전2와 각 값, 초안 표시 없음까지 브라우저에서 확인했다. 확인 시각: 2026-09-01 02:36 KST.
- 동일 앱 기본값으로 순수 JVM 177개 테스트, debug APK 빌드, debug unit-test Kotlin 컴파일, release Kotlin 컴파일 성공. 최종 480/20 실제 엔진 24경로·2,400행과 90개 충전 설정 조합 검증.
- 이번 검증에서 Android 앱/에뮬레이터/실기기와 DB 클라이언트를 실행하지 않았다. 운영 DB 테스트 사용자·기기·세션 로그 생성, APK 설치, Play 출시 없음. 콘솔 설정 게시와 앱 배포는 별개다.
- 상세 근거: [최종 검토 기록](audits/2026-09-01-final-stat-bonus-implementation/REVIEW.md), [게시 증거](audits/2026-09-01-final-stat-bonus-implementation/remote-config-published.json).
