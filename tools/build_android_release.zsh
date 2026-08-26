#!/usr/bin/env zsh
set -e
set -u
set -o pipefail

SCRIPT_DIR="${0:A:h}"
PROJECT_ROOT="${SCRIPT_DIR:h}"

KEYSTORE_PATH="${ALARMQUEST_RELEASE_STORE_FILE:-$PROJECT_ROOT/keys/alarmquest-upload.keystore}"
PASSWORD_FILE="$PROJECT_ROOT/keys/alarmquest-upload.pass"
KEY_ALIAS="${ALARMQUEST_RELEASE_KEY_ALIAS:-alarmquest-upload}"
KEYCHAIN_SERVICE="${ALARMQUEST_KEYCHAIN_SERVICE:-com.lim.alarmquest.upload-keystore}"
OUTPUT_PATH="$PROJECT_ROOT/app/build/outputs/bundle/release/app-release.aab"
EXPECTED_CERT_SHA256="45:EE:A2:23:C8:3F:2A:C6:F7:98:F2:AC:54:1B:FE:A2:70:E0:DD:74:70:AE:95:D9:FC:2C:4E:AD:4A:73:FD:D2"

if [[ ! -f "$KEYSTORE_PATH" ]]; then
    print -u2 "릴리스 키를 찾을 수 없습니다: $KEYSTORE_PATH"
    exit 1
fi

if [[ -z "${ALARMQUEST_RELEASE_STORE_PASSWORD:-}" ]]; then
    if ALARMQUEST_RELEASE_STORE_PASSWORD="$(
        security find-generic-password -a "$USER" -s "$KEYCHAIN_SERVICE" -w 2>/dev/null
    )"; then
        :
    elif [[ -f "$PASSWORD_FILE" ]]; then
        ALARMQUEST_RELEASE_STORE_PASSWORD="$(tr -d '\r\n' < "$PASSWORD_FILE")"
    else
        print -u2 "릴리스 키 비밀번호를 찾을 수 없습니다."
        print -u2 "키체인 서비스: $KEYCHAIN_SERVICE"
        print -u2 "로컬 보안 파일: $PASSWORD_FILE"
        exit 1
    fi
fi

export ALARMQUEST_RELEASE_STORE_FILE="$KEYSTORE_PATH"
export ALARMQUEST_RELEASE_STORE_PASSWORD
export ALARMQUEST_RELEASE_KEY_ALIAS="$KEY_ALIAS"
export ALARMQUEST_RELEASE_KEY_PASSWORD="${ALARMQUEST_RELEASE_KEY_PASSWORD:-$ALARMQUEST_RELEASE_STORE_PASSWORD}"

ACTUAL_CERT_SHA256="$(
    keytool \
        -J-Duser.language=en \
        -J-Duser.country=US \
        -list \
        -v \
        -keystore "$KEYSTORE_PATH" \
        -alias "$KEY_ALIAS" \
        -storepass:env ALARMQUEST_RELEASE_STORE_PASSWORD \
        | awk '$1 == "SHA256:" { print $2; exit }'
)"
if [[ "$ACTUAL_CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]]; then
    print -u2 "업로드 인증서 지문이 AlarmQuest 릴리스 키와 일치하지 않습니다."
    print -u2 "예상 지문: $EXPECTED_CERT_SHA256"
    print -u2 "실제 지문: ${ACTUAL_CERT_SHA256:-확인 불가}"
    exit 1
fi

"$PROJECT_ROOT/gradlew" :app:bundleRelease --no-daemon

JARSIGNER_OUTPUT="$(
    jarsigner \
        -J-Duser.language=en \
        -J-Duser.country=US \
        -verify \
        "$OUTPUT_PATH" \
        2>&1
)"
if [[ "$JARSIGNER_OUTPUT" != *"jar verified."* ]]; then
    print -u2 "$JARSIGNER_OUTPUT"
    print -u2 "AAB 서명 검증에 실패했습니다."
    exit 1
fi
shasum -a 256 "$OUTPUT_PATH"
