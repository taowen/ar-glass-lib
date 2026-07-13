#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_TYPE="${1:-debug}"

case "${BUILD_TYPE}" in
  debug)
    GRADLE_TASK=assembleDebug
    ;;
  release)
    GRADLE_TASK=assembleRelease
    ;;
  *)
    echo "Usage: $0 [debug|release]" >&2
    exit 2
    ;;
esac

cd "${ROOT_DIR}"
./gradlew ":app:${GRADLE_TASK}"

SOURCE_APK="app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk"
OUTPUT_DIR="${ROOT_DIR}/dist"
OUTPUT_APK="${OUTPUT_DIR}/ar-glass-check-${BUILD_TYPE}.apk"

if [[ ! -f "${SOURCE_APK}" ]]; then
  echo "APK was not produced at ${SOURCE_APK}" >&2
  exit 1
fi

mkdir -p "${OUTPUT_DIR}"
install -m 0644 "${SOURCE_APK}" "${OUTPUT_APK}"
echo "APK: ${OUTPUT_APK}"
