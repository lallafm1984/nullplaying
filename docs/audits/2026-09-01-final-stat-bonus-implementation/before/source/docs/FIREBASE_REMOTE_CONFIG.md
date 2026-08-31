# 오프라인 모험 Remote Config

Firebase 프로젝트: `nullplaying-2d131` / 게임 Android 앱: `com.nullplaying`.
관리자 앱 `com.nullplaying.admin`과 별도로 등록하며 Supabase 로그인은 변경하지 않습니다.

콘솔: https://console.firebase.google.com/u/1/project/nullplaying-2d131/config/env/firebase

## 조절할 값

| 매개변수 | 기본값 | 단위 / 허용 범위 | 의미 |
| --- | ---: | --- | --- |
| `offline_adventure_capacity_minutes` | 720 | 정수 분, 1–4320 | 오프라인 모험 최대 저장 시간 (기본 12시간) |
| `offline_adventure_charge_minutes` | 12 | 정수 분, 1–1440 | 0%에서 100%까지 필요한 전경 접속 시간 |

예: 최대 24시간, 완충 12분은 `1440 / 12`. 최대 12시간, 완충 6분은 `720 / 6`.
두 값을 함께 검토하고 **변경사항 게시**를 눌러 반영합니다. 앱은 어느 하나라도 잘못된 값이면 두 값 모두 마지막 정상 설정을 유지합니다. 0은 비활성화 의미가 아니며 허용하지 않습니다.

## 적용 방식

- 최초 설치·통신 실패: 720분 / 12분. 이후에는 마지막으로 적용한 정상 값을 로컬에서 사용합니다.
- 앱 시작 및 복귀 시 조회합니다. 조회 간격은 릴리스 1시간, 디버그 60초이며 실시간 업데이트도 구독합니다.
- 설정을 가져오는 동안 게임 시작을 기다리게 하지 않습니다. 활성화된 값은 저장 데이터 로딩과 복귀 정산을 마친 뒤 전경에서 적용합니다.
- 변경 전 경과 시간은 기존 규칙으로 정산합니다. 비활성 캐릭터 슬롯도 함께 처리합니다.
- 최대값 증가 시 현재 잔여 시간은 그대로입니다. 최대값 감소 시 남은 시간을 새 최대값으로 제한합니다.
- 보상 광고는 새 최대값까지 충전하며 중복 보상은 계속 차단합니다. 광고 표시 중 설정 변경은 복귀 또는 다음 주기까지 미룹니다.
- 저장 시간, 충전 속도, 퍼센트 표시, 보상 광고, 오프라인 소진 정산에 같은 설정을 사용합니다. 밀리초 미만 충전분도 저장하여 프레임 간격에 따른 충전 손실을 방지합니다.
- Firebase Auth / Analytics SDK는 추가하지 않습니다. Firebase Installations는 Remote Config의 필수 의존성입니다.
- 디버그·migrationTest 빌드의 Supabase 접속은 차단합니다. Remote Config 검증을 위해 운영 DB에 테스트 사용자·프로필·로그를 만들지 않습니다.

## 빌드·운영

`app/google-services.json`은 게임용 등록에서 받은 파일입니다. 서비스 계정 개인키를 앱에 넣지 않습니다.
현재 배포된 v13에는 이 연동 코드가 없으므로 원격 설정이 동작하려면 이 코드가 포함된 앱 업데이트가 필요합니다. 이 작업은 Play 출시를 자동 수행하지 않습니다.

검증 명령:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.nullplaying.engine.OfflineAdventureConfigTest' --tests 'com.nullplaying.engine.SimpleGameEngineTest' --tests 'com.nullplaying.data.SimpleGameRepositoryTest'
./gradlew :app:assembleDebug
adb logcat -s OfflineAdventureConfig FirebaseRemoteConfig
```

원격 값은 사용자에게 노출될 수 있으므로 비밀번호·서비스 키 등을 저장하지 않습니다. 공개 개인정보처리방침과 Play Data Safety에도 Remote Config / Installations 수집 항목을 반영한 후 출시해야 합니다.

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
