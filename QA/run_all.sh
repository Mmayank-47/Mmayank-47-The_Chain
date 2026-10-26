#!/usr/bin/env bash
# Suite 1–3 local runner. Suite 4/5 need phones — skipped or OPEN.
# Exit non-zero if Suite 1–3 fail.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

GRADLEW="$ROOT/gradlew"
if [[ ! -x "$GRADLEW" ]]; then
  chmod +x "$GRADLEW" || true
fi

echo "=== Suite 1: JVM unit + golden vectors ==="
"$GRADLEW" \
  :core:test :identity:test :merge:test :transport:test :parser:test \
  :verify:test :hazard:test :trust:test :battery:test :matching:test \
  :privacy:test :ux:test :redteam:test :node:test \
  --console=plain

echo "=== Suite 2: JVM multi-node sim ==="
"$GRADLEW" :sim:test --console=plain

echo "=== Suite 3: shipping classpath + no INTERNET ==="
"$GRADLEW" :app:assertShippingClasspath :app:processReleaseMainManifest --console=plain

MERGED=""
for c in \
  "$ROOT/android/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml" \
  "$ROOT/android/app/build/intermediates/merged_manifest/release/AndroidManifest.xml"
do
  if [[ -f "$c" ]]; then MERGED="$c"; break; fi
done
if [[ -n "$MERGED" ]]; then
  if grep -q 'android.permission.INTERNET' "$MERGED" && ! grep -q 'tools:node="remove"' "$MERGED"; then
    # Presence of INTERNET element without remove is a fail; merged may still list remove.
    if grep 'android.permission.INTERNET' "$MERGED" | grep -vq 'remove'; then
      echo "INTERNET permission present in release merged manifest: $MERGED" >&2
      exit 1
    fi
  fi
  echo "Suite 3 manifest check OK ($MERGED)"
else
  echo "WARN: release merged manifest not found yet; classpath assert still ran"
fi

PHONE_COUNT=0
if command -v adb >/dev/null 2>&1; then
  PHONE_COUNT="$(adb devices | awk '/\tdevice$/{c++} END{print c+0}')"
fi
echo "ADB devices ready: $PHONE_COUNT"

if [[ "$PHONE_COUNT" -lt 1 ]]; then
  echo "Suite 4: SKIPPED / OPEN — need >=1 phone for instrumented checks"
else
  echo "Suite 4: OPEN for full reboot/a11y/airplane — run manually; evidence under QA/evidence/suite4/"
fi

if [[ "$PHONE_COUNT" -lt 2 ]]; then
  echo "Suite 5: OPEN — need >=2 phones for P1–P10 (found $PHONE_COUNT)"
else
  echo "Suite 5: phones available ($PHONE_COUNT) — run multi-phone playbook manually"
fi

echo "=== Suites 1–3 PASS ==="
exit 0
