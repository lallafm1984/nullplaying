# 광고 동의·로드 복구 설계와 적용 절차

## 1. 결론

설정에 `광고 개인정보 선택` 버튼만 추가해서는 사용자가 제보한 “광고가 작동하지 않음”을 해결할 수 없다. 다음 네 경로를 분리해야 한다.

1. UMP 동의 정보 갱신·양식 로드 실패
2. Google Mobile Ads SDK 초기화 실패
3. 보상형 전면 광고 로드 실패
4. 로드된 광고의 노출 실패

정상적인 동의·거부·부분 선택은 실패가 아니다. `canRequestAds` 또는 앱 계정에 저장한 단일 Boolean으로 이 선택을 다시 분류하지 않는다. 광고는 오프라인 모험 충전의 필수 요건이 아니며, 앱을 켜 둔 동안의 자동 충전이 기본 경로임을 UI에서 명확히 알린다.

## 2. 현재 구현에서 확인한 빈틈

- 현재 의존성은 GMA Next-Gen `ads-mobile-sdk:1.4.0`, UMP `user-messaging-platform:4.0.0`이다. 이 설계는 현재 버전을 기준으로 하며, 적용 중 SDK 업그레이드를 함께 수행하지 않는다.
- `requestConsentInfoUpdate()`는 `MainActivity.onCreate()`에서 한 번 호출된다. 같은 Activity 실행 중 실패를 재시도하는 사용자 경로가 없다.
- 갱신 또는 양식 로드가 실패해도 오류는 Logcat에만 남고 UI 상태로 보존되지 않는다.
- `privacyOptionsRequirementStatus == REQUIRED`만 Boolean으로 노출한다. 갱신 실패 후 `UNKNOWN`이면 복구 버튼도 개인정보 선택 버튼도 안 보일 수 있다.
- Mobile Ads 상태가 `mobileAdsReady: Boolean`으로만 전달된다. 동의 대기, SDK 초기화 중, SDK 초기화 실패가 UI에서 모두 `false`로 보인다.
- 보상 광고는 로드 실패 후 30초마다 재시도하지만, 다이얼로그는 `WAITING`, `LOADING`, `FAILED`를 모두 `광고 준비 중`으로 표시한다. 사용자는 실패와 준비 중을 구분하지 못하고 즉시 재시도도 할 수 없다.
- 오프라인 모험이 가득 차면 보상 광고를 미리 로드하지 않는 것은 의도된 현재 정책으로 유지한다.

## 3. 상태 모델

SDK 값을 UI에 직접 노출하지 말고, 다음 앱 도메인 상태로 변환한다.

```kotlin
enum class ConsentRefreshStatus {
    NOT_STARTED,
    CHECKING,
    COMPLETE,
    RETRYABLE_ERROR,
}

enum class PrivacyOptionsStatus {
    UNKNOWN,
    NOT_REQUIRED,
    REQUIRED,
}

enum class ConsentFailureStage {
    INFO_UPDATE,
    REQUIRED_FORM,
    PRIVACY_OPTIONS_FORM,
}

data class AdsConsentState(
    val refreshStatus: ConsentRefreshStatus,
    val canRequestAds: Boolean,
    val privacyOptionsStatus: PrivacyOptionsStatus,
    val failureStage: ConsentFailureStage? = null,
    val diagnosticMessage: String? = null,
)
```

`diagnosticMessage`는 로그·QA용이며 사용자 UI에 SDK 문구를 그대로 표시하지 않는다. UI 규한은 다음 순수 함수로 결정한다.

```kotlin
data class ConsentActions(
    val showRecovery: Boolean,
    val showPrivacyOptions: Boolean,
    val recoveryInProgress: Boolean,
)

fun AdsConsentState.actions() = ConsentActions(
    showRecovery = !canRequestAds && (
        refreshStatus == COMPLETE || refreshStatus == RETRYABLE_ERROR
    ),
    showPrivacyOptions = privacyOptionsStatus == PrivacyOptionsStatus.REQUIRED &&
        refreshStatus != NOT_STARTED && refreshStatus != CHECKING,
    recoveryInProgress = refreshStatus == CHECKING,
)
```

중요 규칙:

- `canRequestAds == true`는 동의 의사 표시가 아니라 SDK 초기화·광고 요청 가능 여부로만 사용한다.
- `privacyOptionsStatus == REQUIRED`는 거부 여부가 아니라 선택 변경·철회 진입점 필요 여부다.
- `COMPLETE && canRequestAds == false`는 SDK 오류가 없더라도 광고 요청이 불가능한 미해결 상태이므로 같은 실행 중 재연결 경로를 제공한다.
- `RETRYABLE_ERROR && canRequestAds == true`이면 이전 세션의 유효한 상태로 광고를 진행하고, 사용자를 차단하는 복구 버튼은 노출하지 않는다. 실패 진단만 남긴다.
- 앱 계정이나 Supabase에 `consented`, `declined` 같은 별도 Boolean을 저장하지 않는다.

Mobile Ads SDK도 Boolean 대신 별도 상태를 사용한다.

```kotlin
enum class MobileAdsRuntimeState {
    WAITING_FOR_CONSENT,
    INITIALIZING,
    READY,
    RETRYABLE_ERROR,
}
```

보상 광고는 다음처럼 구분한다.

```kotlin
enum class RewardedAdState {
    WAITING_FOR_CONSENT,
    INITIALIZING_SDK,
    LOADING,
    READY,
    SHOWING,
    LOAD_FAILED,
    SHOW_FAILED,
}
```

## 4. UI 노출 규칙

### 설정 화면

| 상태 | 노출 | 동작 |
|---|---|---|
| `(COMPLETE 또는 RETRYABLE_ERROR) && !canRequestAds` | `광고 설정 다시 연결` | 동의 정보 갱신부터 재시도 |
| `privacyOptionsStatus == REQUIRED` | `광고 개인정보 선택` | UMP Privacy Options Form 열기 |
| 둘 다 해당 | 두 버튼 모두 노출 | 재연결과 선택 변경을 별도 행위로 유지 |
| `NOT_REQUIRED` | 버튼 미노출 | 정상 |
| 정상 동의·거부·부분 선택 | `REQUIRED`이면 개인정보 선택만 노출 | 오류로 표시하지 않음 |

버튼은 48dp 이상 터치 영역, 진행 중 중복 클릭 차단, 접근성 라벨을 제공한다.

### 오프라인 모험 충전 다이얼로그

| 상태 | 본문 | 확인 버튼 |
|---|---|---|
| 기본 안내 | `오프라인 모험 시간은 앱을 켜 둔 동안 자동으로 충전됩니다. 광고 시청은 선택 사항입니다. 광고를 끝까지 보면 즉시 가득 충전됩니다.` | 상태에 따라 변경 |
| `WAITING_FOR_CONSENT` + 복구 필요 | `광고 설정을 불러오지 못했습니다. 자동 충전은 계속됩니다.` | `광고 설정 다시 연결` |
| `INITIALIZING_SDK` / `LOADING` | `광고를 준비하고 있습니다. 자동 충전은 계속됩니다.` | 비활성 `준비 중` |
| `LOAD_FAILED` / `SHOW_FAILED` | `광고를 불러오지 못했습니다. 자동 충전은 계속되며, 원하면 다시 시도할 수 있습니다.` | `다시 시도` |
| `READY` | `광고를 끝까지 보면 오프라인 모험이 모두 충전됩니다.` | `광고 보고 모두 충전` |

오프라인 저장 한도가 제시될 때는 `능력치에 따라 늘어나며 최소 8시간`이라는 현재 게임 규칙과 일치하게 표현한다. 사용자가 광고를 보지 않으면 진행할 수 없다는 인상을 주는 `바로 충전`만 단독으로 강조하지 않는다.

UI 문구는 한국어·영어·일본어 모두 `localized()` 경로를 사용하고 번역 누락 테스트를 추가한다.

## 5. 재시도 정책

### UMP

- 앱 시작 시 기존처럼 한 번 갱신한다.
- 실패하고 `canRequestAds == false`이면 다이얼로그와 설정 화면에서 수동 재시도를 제공한다.
- 수동 재시도는 `AtomicBoolean`으로 단일 실행을 보장하고, 진행 중에는 버튼을 비활성화한다.
- 사용자가 정상적으로 거부한 후에는 자동·반복적으로 동의 양식을 다시 열지 않는다.
- 따로 저장한 동의 값을 `reset()`하지 않는다. UMP `reset()`은 별도 패키지인 `eeaQa` 빌드에서만 사용한다.

### Mobile Ads SDK

- `canRequestAds == true`가 되면 초기화한다.
- 초기화 실패 시 기존 `MobileAdsInitializationGate.markFailed()`로 다음 시도를 허용한다.
- 사용자가 광고 재시도를 누르면 동의 상태를 먼저 확인하고, 허용되면 SDK 초기화부터 다시 시도한다.

### 보상 광고

- 기존 30초 자동 재시도를 유지한다.
- `LOAD_FAILED` 또는 `SHOW_FAILED`에서는 사용자가 즉시 재시도할 수 있게 한다. 2초 디바운스와 단일 로드 보장으로 중복 요청을 막는다.
- 광고 시청 중에는 두 번 노출하지 않는다. 보상은 `onUserEarnedReward` 호출 시에만, 기존 request ID 멱등성을 유지해 지급한다.
- `NO_FILL`을 동의 실패로 취급하지 않는다.

## 6. 진단 기록

이번 변경에서 운영 DB 스키마나 Supabase 세션 로그를 확장하지 않는다. 먼저 로컬 구조화 로그와 실기기 Ad Inspector로 검증한다.

다음 이벤트와 상태를 서로 다른 태그로 기록한다.

- UMP 정보 갱신 시작·성공·실패
- 필수 양식 로드·종료·실패
- `canRequestAds`, Privacy Options Requirement 상태
- Mobile Ads SDK 초기화 시작·성공·실패
- 보상 광고 로드 시작·성공·실패
- `LoadAdError.code`, `message`, `responseInfo`
- 노출 시작·실패·종료와 보상 지급

사용자의 동의 세부 선택, TCF 문자열, 광고 ID, 계정 ID는 로그에 남기지 않는다. 운영 텔레메트리가 필요하면 새 DB 설계·보존 기간·개인정보 영향을 별도로 검토한 후 추가한다.

## 7. 적용 파일

| 파일 | 변경 |
|---|---|
| `ads/AdsConsentState.kt` | 앱 도메인 상태, UI 행위 도출 함수 |
| `ads/GoogleMobileAdsConsentManager.kt` | 기존 UMP 경계를 유지하며 `StateFlow<AdsConsentState>`, 시작·수동 재시도·Privacy Options 조율 |
| `AlarmQuestApplication.kt` | `MobileAdsRuntimeState`, 초기화 재시도 및 구조화 로그 |
| `MainActivity.kt` | 동의·SDK 상태와 재시도 콜백 전달 |
| `ui/GameSettingsScreen.kt` | 복구 버튼과 개인정보 선택 버튼 분리 |
| `ui/AlarmQuestApp.kt` | 보상 광고 상태·문구·수동 재시도 분리 |
| `localization/GameLocalization.kt` 등 | 한·영·일 신규 문구 |
| `simpleTest/.../ads/*Test.kt` | 상태 전이, 버튼 노출, 중복 요청, 재시도 테스트 |

## 8. 테스트 매트릭스

### 순수 JVM 테스트

1. `UNKNOWN + 갱신 전`은 복구 실패로 표시하지 않는다.
2. 갱신 실패 + `canRequestAds=false`에서만 복구 버튼을 표시한다.
3. 갱신 실패 + 이전 유효 상태 `canRequestAds=true`는 광고를 차단하지 않는다.
4. `REQUIRED`는 동의·거부를 추론하지 않고 개인정보 버튼을 표시한다.
5. `NOT_REQUIRED`는 개인정보 버튼을 숨긴다.
6. 수동 재시도 중 연타해도 UMP 요청은 하나만 실행한다.
7. UMP 성공 후 `canRequestAds=true`이면 Mobile Ads 초기화는 한 번만 시작한다.
8. Mobile Ads 초기화 실패 후에는 재시도가 가능하다.
9. 보상 광고 `LOAD_FAILED`와 `SHOW_FAILED`가 준비 중과 다른 UI를 만든다.
10. 보상은 `onUserEarnedReward`에서 한 번만 지급한다.
11. 신규 문구의 영어·일본어 번역이 누락되지 않는다.

### EEA QA 기기 테스트

별도 `com.nullplaying.eeaqa` 패키지에서 UMP 디버그 지역 설정과 Google 테스트 광고 단위만 사용한다. `reset()`과 EEA 지역 강제는 이 빌드에서만 사용한다.

| 사례 | 기대 결과 |
|---|---|
| EEA 첫 실행, 동의 | 광고 요청 가능, 개인정보 선택 진입점 유지 |
| EEA 첫 실행, 거부 | 정상 선택으로 처리, 복구 오류 미노출, UMP가 허용하는 모드로 광고 요청 |
| EEA 부분 선택 | 거부·동의 Boolean으로 재분류하지 않음 |
| 비규제 지역 | 개인정보 선택 버튼 미노출, 광고 초기화 |
| 첫 실행 UMP 네트워크 실패 | 광고 차단, 복구 버튼 노출, 재시작 없이 수동 복구 |
| 기존 유효 선택 + 갱신 실패 | 캐시된 상태가 허용하면 광고 진행 |
| SDK 초기화 실패 | 동의 오류와 다른 상태로 표시, 재시도 후 복구 |
| 보상 광고 No fill/네트워크 실패 | `다시 시도` 노출, 자동 충전은 계속 |
| 로드 및 시청 성공 | 보상 한 번 지급, 다음 광고 재로드 |
| 설정에서 선택 철회 | 변경된 UMP 상태를 반영, 앱 자체 Boolean 미사용 |

실기기에서는 Ad Inspector로 광고 단위, 요청, 응답 정보, 개인정보 설정을 대조한다. 테스트 기기로 등록하지 않은 기기에서 Ad Inspector를 열지 않는다.

### 빌드·회귀 관문

```bash
./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest \
  :app:testReleaseUnitTest \
  :app:testMigrationTestUnitTest \
  :app:lintDebug \
  :app:lintRelease \
  :app:assembleDebug \
  :app:assembleRelease \
  --continue
```

에뮬레이터 실행 전에 `QaSupabaseIsolationTest`와 디버그·migrationTest의 빈 `SUPABASE_URL`/key를 확인한다. 운영 DB에 테스트 계정·랭킹·세션 로그를 남기지 않는다.

## 9. 검토 후 적용 절차

### 1단계: 설계 승인

- 이 문서의 상태 모델과 UI 문구를 검토한다.
- 광고를 선택 사항으로 알리는 범위와 `최소 8시간` 안내 위치를 확정한다.
- 이 단계에서는 소스·AdMob 콘솔·DB를 변경하지 않는다.

### 2단계: 상태 모델과 단위 테스트 선반영

- `AdsConsentState`, UMP gateway, 상태 전이 테스트를 먼저 추가한다.
- 현재 광고 요청 행동은 바꾸지 않고, 동일 콜백을 새 상태로 매핑한다.
- 순수 JVM 테스트로 선택과 오류가 섞이지 않음을 먼저 증명한다.

### 3단계: 복구 UI 연결

- 설정 화면에 복구·개인정보 선택 버튼을 분리한다.
- 오프라인 모험 다이얼로그에 기본 자동 충전 안내와 상태별 다음 행동을 연결한다.
- 한·영·일 번역과 접근성 세맨틱을 같이 반영한다.

### 4단계: 정적 검토

- 알 수 없는 상태를 동의·거부로 추론하지 않는지 코드 리뷰한다.
- `canRequestAds` 이외의 임의 동의 gate가 광고 요청을 막지 않는지 검색한다.
- 운영 코드에 UMP `reset()`, 디버그 지역 강제, 테스트 기기 ID가 포함되지 않음을 확인한다.
- 디버그는 Google 테스트 광고 ID, 릴리스는 운영 ID를 사용하는지 대조한다.

### 5단계: 자동 검증

- 8절의 JVM, 린트, 디버그·릴리스 빌드 관문을 모두 통과한다.
- 실패하면 기존 정상 광고 경로를 보존한 채 해당 단계에서 중지한다.

### 6단계: 에뮬레이터 QA

- 운영 Supabase 격리를 먼저 증명한다.
- 네트워크 성공, 네트워크 차단, EEA, 비EEA, 동의, 거부, 선택 변경을 매트릭스대로 확인한다.
- 기존 로컬 모험가를 삭제하지 않고 테스트 전용 로컬 상태만 정리한다.

### 7단계: 실물 테스트 기기 QA

- 등록된 테스트 기기에 디버그 APK를 설치하고 Google 테스트 광고만 사용한다.
- Ad Inspector로 UMP 상태, 광고 단위, 응답 및 어댑터 상태를 확인한다.
- 한·영·일, 기본 글자 크기와 1.5배 글자 크기에서 문구·버튼·스크롤을 확인한다.

### 8단계: 릴리스 경계

- 서명된 AAB 빌드, Play 업로드, 스토어 배포는 서로 다른 증명으로 취급한다.
- 이 작업의 기본 범위는 코드·테스트·디버그 기기 검증까지다. Play 업로드는 별도 승인 후 수행한다.

### 9단계: 배포 후 운영 확인

- 별도 승인으로 Play 내부 테스트 또는 단계적 배포를 시작한 후에만 수행한다.
- 운영 광고를 개발자가 직접 클릭하지 않는다. 기능 확인은 등록된 테스트 기기·테스트 광고·Ad Inspector로 한다.
- 배포 전후의 대상 국가별 요청, 일치한 요청, 노출, 보상 완료 및 수익을 같은 기간·광고 단위로 비교한다.
- 요청이 없는지, 요청은 있지만 일치하지 않는지, 일치하지만 사용자가 노출하지 않는지를 분리해 판단한다.
- 코드·기기 QA 통과는 `복구 경로 구현 완료`, 배포 후 지표 검증은 `운영 문제 해소 확인`으로 구분해 보고한다.

## 10. 적용 승인 기준

다음을 모두 만족하면 “복구 경로 구현 완료”로 판정한다. 실제 영어권 운영 문제가 해소되었다는 판정은 9단계의 배포 후 지표까지 확인한 후 별도로 내린다.

- 첫 동의 갱신 실패 후 앱을 종료하지 않고 복구할 수 있다.
- 정상적인 거부를 오류로 표시하지 않는다.
- 필요한 지역의 동의 사용자도 설정에서 선택을 철회·변경할 수 있다.
- 비규제 지역에서 불필요한 개인정보 선택 버튼이 보이지 않는다.
- SDK 초기화, 광고 로드, 광고 노출 실패를 서로 구분할 수 있다.
- 보상 광고 로드 실패 후 사용자 재시도와 30초 자동 재시도가 모두 작동한다.
- 광고가 준비되지 않아도 앱을 켜 두는 자동 충전이 계속됨을 사용자가 알 수 있다.
- 테스트에서 운영 광고를 클릭하지 않고, 운영 Supabase에 테스트 데이터를 남기지 않는다.

## 11. 공식 근거

- Google UMP Android 설정: https://developers.google.com/admob/android/next-gen/privacy
- `ConsentInformation`: https://developers.google.com/admob/android/reference/privacy/kotlin/com/google/android/ump/ConsentInformation
- UMP 디버그 지역·상태 초기화: https://developers.google.com/admob/android/privacy
- 보상형 전면 광고 단일 로드: https://developers.google.com/admob/android/next-gen/rewarded-interstitial/single-load
- 광고 로드 오류와 `ResponseInfo`: https://developers.google.com/admob/android/next-gen/ad-load-errors
- Ad Inspector: https://developers.google.com/admob/android/next-gen/ad-inspector

## 12. 1차 적용·검증 결과

### 적용 범위

- 기존 GMA Next-Gen `1.4.0`, UMP `4.0.0`, 운영 광고 ID, AdMob 콘솔 설정은 변경하지 않았다.
- UMP SDK 호출을 새 추상화 계층으로 옮기지 않고, 기존 `GoogleMobileAdsConsentManager`에 상태 게시·단일 실행·동기 예외 복구를 추가했다. 라이브 코드의 작은 변경으로 제한하기 위한 검토 결과다.
- `canRequestAds` 및 Privacy Options Requirement를 서로 다른 판단으로 유지했다.
- UMP 갱신·양식 실패, Mobile Ads SDK 초기화 실패, 보상 광고 로드·노출 실패를 UI 상태에서 분리했다.
- UMP가 다시 광고 요청을 허용하지 않으면 기존 배너 뷰를 폐기하고, 로드 중이던 보상 광고의 오래된 콜백을 무시한다.
- 오프라인 모험 자동 충전과 광고 시청은 선택 사항이라는 최종 안내 문구를 한국어·영어·일본어로 추가했다.

### 자동 검증

- 변경 전 `:app:testDebugUnitTest` 기준선이 통과했다.
- 변경 후 `testDebugUnitTest`, `testReleaseUnitTest`, `testMigrationTestUnitTest`, `lintDebug`, `lintRelease`, `assembleDebug`, `assembleRelease` 모두 통과했다.
- `QaSupabaseIsolationTest`는 debug·migrationTest 모두 실패 0건으로 통과했다.
- 새 상태·UI·번역 테스트가 동의 실패, 캐시된 선택, Privacy Options 표시, SDK 실패, 광고 로드·노출 실패, 한·영·일 문구를 검증했다.
- 최종 디버그 APK SHA-256: `2df25d5e1993fefd7b3aab5b20c2e83af0f4ccabd0d2f9a1f71a3fdd09334c3a`
- 최종 EEA QA APK SHA-256: `0494ce0cb359eb34a188c27217c7a44393e4802302e62baf749a27a2a35cad75`
- 최종 릴리스 APK SHA-256: `b67f9b2067b083534302d67349765b67a90bd04e0023128459fea1fde9a33e88`
- 최종 서명 AAB SHA-256: `9a203a8b2f532ee64b1c1363a52f3160c46759ba18efa4434ff3923c6141adeb`
- 최종 AAB의 패키지·버전은 `com.nullplaying`, `versionCode=15`, `versionName=0.4.2`이며 JAR 서명, 업로드 인증서, ZIP 무결성을 확인했다.

### 에뮬레이터 QA

- `alarmquest-qa`, Android 15, 1080×2340, 420dpi에 기존 로컬 데이터 유지 설치로 디버그 APK를 검증했다.
- 이 에뮬레이터는 DNS가 차단된 QA 환경이므로 UMP와 Google 테스트 광고가 `NETWORK_ERROR (2)`로 실패했다. 이를 실패 UI 검증에 사용했으며 광고 로드 성공을 증명했다고 표현하지 않는다.
- 캐시된 선택으로 `canRequestAds=true`, `NOT_REQUIRED`인 상태에서 UMP 갱신이 실패해도 SDK 초기화를 차단하지 않았고 설정에 불필요한 복구·개인정보 버튼이 나타나지 않았다.
- UMP 로컬 캐시를 임시 분리한 `canRequestAds=false`, `UNKNOWN` 상태에서 설정과 충전 다이에로그 모두 `광고 설정 다시 연결`을 표시했다.
- 위 버튼을 누르면 같은 프로세스에서 `requestConsentInfoUpdate()`가 다시 호출되고 실패 결과가 UI에 다시 반영됨을 Logcat과 UI hierarchy로 확인했다.
- 보상 광고 로드 실패에서 `다시 시도`를 누르면 즉시 새 로드가 시작되고, 네트워크 실패 후 실패 UI로 돌아오는 것을 확인했다.
- 실패 다이얼로그는 한국어·영어·일본어에서 버튼·본문 잘림 없이 표시되었다.
- QA에 사용한 로컬 모험가를 UI로 영구 삭제했고 `simple_game_state` 행이 0개임을 확인했다. 임시 분리한 UMP 파일은 원본으로 복원했고 임시 백업이 남지 않았다.

### 남은 경계

- 한국 실물 기기 `SM-S931N`에 별도 `eeaQa` APK를 설치하고 EEA 지역을 강제해 실제 UMP 양식을 표시했다. 직접 거부 후 `canRequestAds=true`, Privacy Options Requirement `REQUIRED`를 확인했으며 Google 데모 배너와 보상형 전면 광고의 로드·노출·보상 성공을 확인했다.
- 이 검증은 테스트 광고 단위를 사용하므로 운영 광고의 수익 발생을 증명하지 않는다. 운영 거부 사용자에게 실제 배너 노출·수익이 기록되는지는 배포 후 AdMob 보고서에서 별도로 확인해야 한다.
- 릴리스 v15 APK는 라이브 v14 앱을 덮어쓰지 않도록 실물 기기에 설치하지 않았다. 서명 AAB는 새로 빌드·검증했지만 Google Play 업로드와 배포는 수행하지 않았다.
