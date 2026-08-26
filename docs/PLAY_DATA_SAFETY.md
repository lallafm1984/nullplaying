# NULL PLAYING Google Play Data Safety 대응 문서

- 작성 기준일: 2026년 8월 26일
- 대상 앱: NULL PLAYING(구 AlarmQuest)
- 패키지: `com.alarmquest`
- 검토한 앱 버전: `0.4.0` (`versionCode 9`)
- 검토한 광고 SDK: GMA Next-Gen SDK `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.4.0`
- 검토한 동의 SDK: Google UMP `com.google.android.ump:user-messaging-platform:4.0.0`
- 공개 개인정보처리방침 URL: `https://nullplaying.4ltree.com/privacy`
- 공개 데이터 삭제 요청 URL: `https://nullplaying.4ltree.com/data-deletion`

이 문서는 Play Console의 **앱 콘텐츠 > Data Safety** 입력을 위한 내부 작성안입니다. Play Console에 제출했다는 증거가 아니며, 앱 코드·SDK·배포 지역이 바뀔 때 다시 검토해야 합니다.

## 1. 현재 구현 기준 핵심 답변

| Play Console 질문 | 현재 답변 | 근거·주의사항 |
| --- | --- | --- |
| 앱이 필수 사용자 데이터 유형을 수집하거나 공유합니까? | **예** | Supabase 전송 및 GMA Next-Gen SDK 자동 수집·공유가 있음 |
| 수집되는 모든 사용자 데이터가 전송 중 암호화됩니까? | **예** | Supabase URL은 HTTPS만 활성화되며 GMA는 TLS 전송을 공식 명시함 |
| 이용자가 데이터 삭제를 요청할 방법을 제공합니까? | **예 — 웹 요청 경로 제공** | 공개 데이터 삭제 페이지에서 이메일 요청을 시작할 수 있고, 앱 설정에서 요청 대상 확인용 데이터 식별 ID를 확인·복사할 수 있음. 실제 운영 삭제 절차는 아래 차단 항목대로 검증 필요 |
| 앱에서 계정을 만들 수 있습니까? | **아니요 — 현재 구조 기준** | Supabase 익명 인증은 설치 시 내부적으로 자동 생성되지만 로그인·비밀번호·복구·기기 간 이용을 제공하는 사용자 대면 앱 계정이 아님. 향후 계정 기능을 추가하면 재검토 |
| 독립 보안 심사를 받았습니까? | **아니요** | MASA 등 독립 검증 증거 없음 |
| Families 정책 준수를 약속합니까? | **선택하지 않음** | 타깃 연령과 아동 대상 여부를 Play Console에서 먼저 확정해야 함 |

## 2. 신고할 데이터 유형

아래의 “공유”는 Google Play의 정의를 따릅니다. Supabase는 개발자 지시에 따라 처리하는 서비스 제공자로 보고 공유에서 제외했지만, Google은 GMA Next-Gen SDK가 아래 광고 데이터를 자동으로 **수집 및 공유**한다고 공식 안내합니다.

| Play 데이터 유형 | 수집 | 공유 | 필수/선택 | 목적 | 앱의 실제 항목과 근거 |
| --- | --- | --- | --- | --- | --- |
| **위치 > 대략적인 위치** | 예 | 예 | 필수 | 분석, 광고 또는 마케팅, 사기 방지·보안·규정 준수 | GMA가 IP 주소를 수집해 대략적 위치를 추정. 앱도 시스템 로캘의 국가 코드를 Supabase 프로필에 자동 저장 |
| **개인정보 > 이름** | 예 | 아니요 | 필수 | 앱 기능 | 이용자가 입력한 캐릭터 이름/별명을 Lv.20부터 랭킹에 자동 업로드하고 다른 이용자에게 공개 |
| **개인정보 > 사용자 ID** | 예 | 아니요 | 필수 | 앱 기능, 계정 관리, 사기 방지·보안·규정 준수 | 설치 단위 Supabase 익명 사용자 UUID와 캐릭터 UUID |
| **앱 활동 > 앱 상호작용** | 예 | 예 | 필수 | 분석, 광고 또는 마케팅, 사기 방지·보안·규정 준수 | GMA의 앱 실행·탭·동영상 조회. 앱의 포그라운드/백그라운드 세션 시각 |
| **앱 활동 > 기타 사용자 생성 콘텐츠** | 예 | 아니요 | 필수 | 앱 기능 | 캐릭터 이름/별명을 보수적으로 이 유형에도 포함. Play Console이 “이름”만으로 충분하다고 명확히 판단되면 중복 선택 여부 재검토 |
| **앱 활동 > 기타 활동** | 예 | 아니요 | 필수 | 앱 기능, 분석, 사기 방지·보안·규정 준수 | 슬롯, 직업, 레벨, 전투력, 누적 막·처치 수, 세션 종료 사유와 시각 |
| **앱 정보 및 성능 > 진단** | 예 | 예 | 필수 | 분석, 광고 또는 마케팅, 사기 방지·보안·규정 준수 | GMA의 앱 시작 시간·멈춤 비율·에너지 사용량. Supabase 프로필의 앱/OS 버전과 기기 모델은 운영 분석에 연결됨 |
| **기기 또는 기타 ID > 기기 또는 기타 ID** | 예 | 예 | 필수 | 앱 기능, 분석, 광고 또는 마케팅, 사기 방지·보안·규정 준수 | GMA의 광고 ID·앱 세트 ID·적용 가능한 계정 식별자. 설치 단위 식별자는 Supabase 인증에도 사용 |

### 필수로 분류한 이유

Play의 “선택”은 모든 이용자가 앱을 사용하면서 해당 수집을 거부하거나 선택적으로 제공할 수 있어야 합니다. 현재 앱은 Supabase 프로필·세션·랭킹 동기화를 끌 수 있는 전역 옵트아웃을 제공하지 않습니다. UMP는 적용 지역의 광고 동의를 처리하지만 모든 지역·모든 데이터 유형의 수집을 선택적으로 바꾸는 전역 옵트아웃은 아니므로, 위 항목은 현재 구현 기준에서 **필수**로 유지합니다.

## 3. 현재 선택하지 않을 데이터 유형

코드와 포함 SDK의 공식 공개 내용에서 다음 항목의 수집 근거는 확인되지 않았습니다.

- 정밀한 위치
- 이메일 주소, 실제 주소, 전화번호, 인종·민족, 정치·종교적 신념, 성적 지향, 기타 개인 정보
- 결제 정보, 구매 내역, 신용 점수, 기타 금융 정보
- 건강 및 피트니스 정보
- 이메일·SMS·MMS·기타 앱 내 메시지
- 사진, 동영상, 오디오, 파일·문서
- 캘린더, 연락처
- 앱 내 검색 기록, 설치된 앱, 웹 탐색 기록
- 앱 자체의 크래시 로그 수집

주의: 광고 SDK가 앱 매니페스트에 `READ_BASIC_PHONE_STATE` 또는 `AD_ID` 권한을 병합합니다. 권한 선언만으로 별도 데이터 유형을 추측하지 않고, GMA 공식 공개에서 확인된 IP, 상호작용, 진단, 기기·계정 식별자를 신고합니다.

## 4. 보유·삭제 답변의 근거

| 데이터 | 현재 보유 정책 |
| --- | --- |
| 로컬 게임 데이터·설정 | 앱 데이터 삭제 또는 앱 삭제 시 제거. Android 백업·기기 이전 비활성화 |
| `app_session_logs` 상세 세션 | 30일 |
| `app_session_daily` 일별 집계 | 90일 |
| Supabase 익명 인증, `user_profiles`, `ranking_entries` | 익명 인증 사용자 삭제 또는 서비스 종료까지. 앱 설정에서 데이터 식별 ID를 확인·복사하고 공개 웹 경로에서 이메일 삭제 요청 가능 |
| 캐릭터 랭킹 | 캐릭터 삭제 후 전체 랭킹 동기화 성공 시 삭제 |
| Google 광고 데이터 | Google 정책 및 이용자 설정에 따름 |

삭제 처리된 익명 인증을 기기의 앱이 다시 사용하려고 하면, 앱은 기존 세션을 검증하고 삭제됨을 확인한 후 이전 원격 전송 대기열·캐시를 폐기합니다. 이후 앱을 계속 사용하면 새 익명 식별자가 생성되고 새로운 이용 데이터가 전송될 수 있습니다. 로컬 게임 진행 정보는 이 원격 삭제 처리로 삭제되지 않습니다.

Supabase 원격 마이그레이션 상태는 2026년 8월 26일에 `202608250001`까지 로컬과 원격이 일치하는 것으로 확인했습니다. 여기에는 30일 상세 로그 및 90일 일별 집계 삭제 작업이 포함됩니다.

## 5. Play Console 제출 전 차단 항목

다음 항목을 끝내기 전에는 이 문서를 그대로 최종 제출 완료로 표시하지 않습니다.

1. **UMP 콘솔 설정·기기 검증**
   - [x] 앱 시작마다 `requestConsentInfoUpdate()` 호출
   - [x] 필요한 동의 폼을 표시한 뒤 `canRequestAds()`가 참일 때만 GMA 초기화와 광고 요청
   - [x] 요구되는 경우 설정 화면에 개인정보 옵션 재진입점 제공
   - [x] AdMob **Privacy & messaging**에서 해당 앱의 European regulations 메시지 작성·게시
   - [ ] EEA 디버그 지역에서 최초 동의, 거부/limited ads, 재진입, 광고 요청 순서를 에뮬레이터·기기에서 검증

   2026년 8월 26일 에뮬레이터 콜드 런칭에서 UMP 상태 갱신, 중복 요청 병합, `canRequestAds()` 게이트 후 GMA 1회 초기화를 확인했습니다. 같은 날 AdMob 콘솔 메시지를 게시했으며, 게시 이후 실제 EEA 동의 화면과 거부/limited ads 동작은 아직 다시 검증하지 않았습니다.
2. **익명 인증 사용자·데이터 삭제 기능**
   - [x] 앱 설정에서 현재 익명 사용자의 데이터 식별 ID 표시·복사
   - [x] `https://nullplaying.4ltree.com/data-deletion`에 데이터 식별 ID를 포함한 이메일 삭제 요청 절차 공개
   - [ ] 운영자가 요청 ID로 `auth.users`를 삭제했을 때 `user_profiles`, `ranking_entries`, `app_session_logs`, `app_session_daily`가 실제로 연쇄 삭제되는지 원격 검증
3. **공개 정책 페이지 배포**
   - [x] `https://nullplaying.4ltree.com/privacy`가 로그인·지역 제한 없이 표준 브라우저에서 열리는지 확인
   - [x] 앱 소스에 동일 URL 입력
   - [ ] Play Console 스토어 등록정보에 동일 URL 입력
4. **Play Console 최종 대조**
   - 타깃 연령, 광고 포함 여부, 광고 ID 선언, Data Safety, 계정 삭제 질문을 현재 AAB 기준으로 다시 대조
   - 제출 전 Play SDK Index와 GMA Next-Gen SDK 공개 페이지 재확인

## 6. 코드·스키마 근거

- `app/build.gradle.kts`: 앱 버전, GMA Next-Gen SDK, 광고·Supabase 빌드 설정
- `app/src/simple/java/com/alarmquest/AlarmQuestApplication.kt`: UMP가 허용한 후의 GMA 단일 초기화 게이트
- `app/src/simple/java/com/alarmquest/ads/GoogleMobileAdsConsentManager.kt`: UMP 동의 갱신, 필수 폼, 개인정보 옵션 재진입
- `app/src/simple/java/com/alarmquest/remote/SupabaseGameService.kt`: 익명 인증, 프로필, 랭킹, 세션 전송 필드
- `app/src/simple/java/com/alarmquest/ui/StandardBannerAd.kt`: 배너 광고 요청
- `app/src/simple/java/com/alarmquest/ui/AlarmQuestApp.kt`: 보상형 광고 요청
- `app/src/main/AndroidManifest.xml` 및 release 병합 매니페스트: 앱 및 SDK 권한
- `supabase/migrations/202608230001_rankings_and_session_logs.sql`: 랭킹·상세 세션 스키마
- `supabase/migrations/202608230002_user_profiles.sql`: 기기·앱 프로필 스키마
- `supabase/migrations/202608240001_compact_ranking_and_session_retention.sql`: 30일/90일 보유와 삭제 작업

## 7. 공식 참고 자료

- [Google Play User Data 정책](https://support.google.com/googleplay/android-developer/answer/10144311?hl=ko)
- [Google Play Data Safety 작성 안내](https://support.google.com/googleplay/android-developer/answer/10787469?hl=ko)
- [Google Play 계정 삭제 요구사항](https://support.google.com/googleplay/android-developer/answer/13327111?hl=ko)
- [Google Mobile Ads Next-Gen SDK 데이터 공개 안내](https://developers.google.com/admob/android/next-gen/privacy/play-data-disclosure?hl=ko)
- [Google UMP Android 가이드](https://developers.google.com/admob/android/privacy?hl=ko)
