# 0.4.1 (14) 프로덕션 전 QA 및 AAB 빌드

2026-09-01. 판정: **수행한 로컬 기능·회귀·산출물 검사 통과, 서명 AAB 생성 완료**.
사용자 요청에 따라 공개 개인정보처리방침 갱신은 이번 범위에서 제외했다. 이 문서는 Play 업로드/심사 승인 또는 실서비스 전체 검증 완료를 의미하지 않는다.

## 산출물

- 패키지 `com.nullplaying`, 버전명 `0.4.1` 유지, 버전코드 **13 → 14**, min SDK 26 / target SDK 36.
- AAB: `app/build/outputs/bundle/release/app-release.aab`
- 크기: **70,196,917 bytes** (약 66.95 MiB).
- SHA-256: `11b01eb9a6febb79d610d51ee370f826408bed1ea20f5d59c3cd092679c9e05b`
- 기존 전용 업로드 인증서 SHA-256: `45:EE:A2:23:C8:3F:2A:C6:F7:98:F2:AC:54:1B:FE:A2:70:E0:DD:74:70:AE:95:D9:FC:2C:4E:AD:4A:73:FD:D2`.
- 저장소의 `tools/build_android_release.zsh` 사용, `BUILD SUCCESSFUL`, JAR 서명 검증 통과. 키/비밀번호는 새로 만들거나 출력하지 않았다.

## 검사 결과

| 검사 | 결과 |
| --- | --- |
| debug / release / migrationTest 단위·Robolectric 검사 | 각각 339개, 총 1,017회 실행, 실패·오류·건너뜀 0 |
| 실제 Android Room 마이그레이션 | 12→15, 13→15, 14→15 데이터 보존 검사 3개 통과 |
| 린트 | release 오류 0 / 경고 34, debug 오류 0 / 경고 35 / 힌트 1 |
| 번들 구조·실제 매니페스트 | bundletool 1.18.1 validate 통과, 패키지·버전·SDK 확인 |
| 릴리스 설정 | 실제 AAB `debuggable=false`, `allowBackup=false` |
| 서명 연속성 | AAB와 추출 APK 모두 기존 업로드 인증서 일치 |
| 네이티브 정렬 | 8개 `.so`의 LOAD 정렬 16,384 및 GNU_RELRO 확인; 64비트 페이지 합동 조건 통과 |
| AAB / APK ZIP | CRC 검사 및 APK `zipalign -c -P 16 4` 통과 |
| 번들 페이지 설정 | `PAGE_ALIGNMENT_16K` 확인 |
| 실제 설치 | AAB에서 생성한 서명 APK로 13→14 덮어쓰기 설치 성공 |
| 설치본 동일성 | 설치 APK SHA-256과 AAB 추출 APK SHA-256 일치 |
| 실행 안정성 | 임시 에뮬레이터 crash buffer 비어 있음, 부팅 이후 ANR 없음 |

버전코드 변경 전과 변경 후에 세 빌드 유형의 전체 검사 및 린트를 재실행했다. 위 수치는 최종 실행 기준이며 동일 테스트를 반복 실행한 수를 고유 테스트 수로 부풀리지 않았다.

## 실제 화면·저장 확인

- Android 15 / API 35, arm64-v8a, 1080×2400, 420dpi의 격리된 임시 에뮬레이터에서 **서명 릴리스 앱**으로 수행했다.
- 신규 생성, 자동 전투·전리품·퀘스트 진행, 메인/모험가/장비/가방/퀘스트 5개 메뉴, 설정과 한·영·일 언어 전환을 확인했다.
- 능력치 배지, 생성 합계 우측 정렬, 한·영·일 안내 8개 항목과 마지막 직업 설명·닫기의 한 화면 표시를 원본 이미지와 UI XML로 확인했다.
- 알림 권한 요청과 거절 후 안내, 홈 이동 및 복귀, 잔여 모험시간 감소 후 재충전, 강제 중지 후 저장 모험가 복원을 확인했다.
- 최종 AAB에서 생성한 14번 APK를 설치해 **모험가 ReleaseQ, 진행 상태, 일본어 설정, 기존 대기 로그 2건의 유지**를 확인했다. 이후 한국어 안내 팝업을 다시 열었다.
- 14번 앱에서 홈 이동 후 로컬 로그가 2→3건으로 증가했고 이벤트 ID가 중복되지 않았다. 원격 인증 세션은 생성되지 않았다. Lv.1이므로 랭킹 대기 목록의 실제 엔트리는 0개다.
- 정상 종료의 `exit`, 홈 이동의 `background`, 회전/다른 Activity/중복 stop-destroy/로그 OFF/재시도 정책은 소스 추적과 자동 검사로 확인했다. OS 강제 종료가 Activity 콜백 없이 발생하는 경우 로그가 반드시 남는다고 보장하지 않는다.

전체 메뉴와 영·일 팝업은 버전코드 증가 전 13번 릴리스에서 확인했다. 최종 14번의 제품 변경은 버전코드와 아래 영어 접근성 문구 1건뿐이며, 14번 AAB의 해당 번역 리소스 및 한국어 팝업/업데이트/복원을 별도 확인했다.

## 이번 QA에서 수정한 사항

1. 충전 설정 변경 테스트의 예전 12시간/12분 기대값을 현재 **기존 설정으로 경과시간 정산 → 새 설정 적용** 계약과 CON 동적 상한에 맞췄다. 실제 엔진의 상한/충전/광고/90개 설정 조합 검사는 별도로 유지한다.
2. Robolectric 검사 9개 클래스가 실제 앱 Application 대신 기본 Application을 사용하도록 했다. 단위 검사 중 Firebase/Supabase/Room 앱 초기화가 발생하지 않게 하며, 앱 실제 연결 구성은 별도 릴리스 실행으로 검사했다.
3. QA 격리 검사가 release까지 무조건 DEBUG라고 가정하던 부분을 수정했다. QA는 빈 접속 설정과 세션 미생성을 검사하고, release는 공개 접속 설정의 유효성만 검사하며 인증을 시작하지 않는다.
4. 영어 접근성 문구 `Prologue 1th Scene`을 `Prologue Scene 1` 형태로 수정하고 1~3 장면, 한·영·일 회귀 검사를 추가했다.
5. README의 오래된 시간/충전/능력치 설명을 현재 규칙으로 갱신했다. 이번 QA에서 게임 보너스 산식이나 운영 설정을 추가 변경하지 않았다.

## 린트 경고 검토

- 동기 SharedPreferences `commit()` 4건: 종료 직전 대기열 보존 및 순서 보장을 위한 의도된 처리. 일괄 `apply()` 치환하지 않았다.
- POST_NOTIFICATIONS 상수 사용 경고: `runtimePermissionRequired`는 API 33 이상에서만 true이고 요청 조건도 이를 경유한다. API별 분기 자동 검사가 있다.
- 나머지는 KTX 권장, SDK/도구·의존성 업데이트 알림, 기존 세로 고정/아이콘/스타일 관련 권고다. 이번 기능 오류로 판정되지 않았으며 경고 0건이라고 주장하지 않는다.
- 기존 고정 fontScale=1.0 및 세로 화면 정책은 유지했다. 모든 시스템 글자 확대 설정·태블릿·회전 접근성 검증 완료를 의미하지 않는다.

## 운영 데이터 격리 및 범위

- Gradle/테스트와 임시 릴리스 에뮬레이터는 호스트 OS 샌드박스로 외부 통신을 차단했다. 루프백만 허용하며 외부 소켓 연결은 EPERM(1)로 거절됨을 확인했다.
- 두 에뮬레이터 모두 비행기 모드 ON, Wi-Fi/모바일 데이터 OFF, 라우팅 테이블 비어 있음을 확인했다. debug/migrationTest의 Supabase URL과 키는 빈 값이다.
- 운영 DB로 테스트 사용자·기기·프로필·랭킹·로그를 전송하지 않았다. 서버 변경, 마이그레이션 적용, Remote Config 재게시, Play 업로드도 하지 않았다.
- Remote Config ON / 480분 / 20분은 현재 앱 기본값 및 캐시 적용 경로·자동 검사로 확인했다. 이전 게시 증거는 `../2026-09-01-final-stat-bonus-implementation/remote-config-published.json`이며 **이번 QA에서 라이브 값을 재조회한 것은 아니다**.
- 실제 광고 노출/보상, 지역별 UMP 동의 화면, Firebase 최신 값 수신, Supabase 실서버 왕복 및 Play 수용 여부는 격리 정책 때문에 미검증이다.
- 공개 개인정보처리방침의 Firebase 관련 설명 갱신은 사용자 요청으로 보류했다. 공개 웹사이트에 배포하지 않았다.
- 16KB는 AAB/ELF/APK의 정렬 요건을 확인한 것이다. 실행 기기의 페이지 크기는 4KB이므로 16KB 기기 실동작까지 검증한 것으로 해석하지 않는다. [Android의 16KB 페이지 크기 검증 안내](https://developer.android.com/guide/practices/page-sizes).

## 증거와 재현

- `artifact-qa.json`, `bundle-verification.json`, `aab-manifest.xml`, `aab-config.json`, `aab-validation.txt`, `aab-signature-verification.txt`
- `device-verification.json`, `queue-before-upgrade.json`, `queue-after-upgrade.json`, `queue-after-home.json`
- `screenshots/release-v13-*.png` 및 대응 XML, `screenshots/release-v14-upgraded-roster.png`, `screenshots/release-v14-guide-ko.png`
- `source-sha256.json`: 기존 변경을 보존한 현재 작업 트리의 소스/빌드 입력 281개 해시. 깨끗한 커밋 재현 빌드라는 주장은 하지 않는다.
- `tools/analysis/verify_release_qa_artifacts.py`, `verify_release_bundle.py`, `record_release_source_receipt.py`, `inspect_release_qa_queue.py`
- 단위/린트 명령: 격리 프로필 `gradle-offline.sb` 아래에서 `./gradlew --offline --no-daemon --max-workers=2 :app:testDebugUnitTest :app:testReleaseUnitTest :app:testMigrationTestUnitTest :app:lintRelease :app:lintDebug :app:assembleDebug --continue`. Java 17, IPv4 루프백, Gradle heap 1536m, Kotlin in-process 사용.
- 마이그레이션 명령: `adb -s emulator-5554 shell am instrument -w -r -e class com.nullplaying.data.SimpleDatabaseMigrationTest com.nullplaying.test/androidx.test.runner.AndroidJUnitRunner` — `OK (3 tests)`.

## 정리

검증에 사용한 임시 릴리스 AVD `np-release-qa-20260901-y5rXXx`를 종료한 뒤 정확한 이름으로 삭제했고 `/private/tmp/nullplaying-release-qa.y5rXXx`도 비어 있음을 확인하여 제거했다. 그 안의 테스트 모험가·로컬 DB·대기 로그는 복구용 사본 없이 삭제했다. 원본 스크린샷과 개인 식별값을 제외한 검사 결과만 보관한다.

기존 `emulator-5554`는 유지하고 버전코드 14의 debug 앱으로 복원했다. 빈 모험가 목록과 한국어 생성 화면을 확인했으며 새 모험가는 저장하지 않았다. 마이그레이션 테스트가 만든 `simple-migration-12/13/14` 및 각각의 journal/lock 파일 9개만 제거했고 기존 `alarmquest.db`는 보존했다. 최종 연결 장치는 기존 QA 에뮬레이터 1개뿐이다.

최종 소스 281개 해시와 AAB 해시를 다시 확인했으며 변경 없었다. 앱 코드 커밋·서버 배포·Play 업로드는 하지 않았다.
