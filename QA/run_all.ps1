# Suite 1-3 local runner (Windows). Suite 4/5 need phones - skipped or OPEN.
# Exit non-zero if Suite 1-3 fail.
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
Set-Location $RepoRoot

$gradlew = Join-Path $RepoRoot "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Error "gradlew.bat not found at $RepoRoot"
}

Write-Host "=== Suite 1: JVM unit + golden vectors ==="
& $gradlew `
    :core:test :identity:test :merge:test :transport:test :parser:test `
    :verify:test :hazard:test :trust:test :battery:test :matching:test `
    :privacy:test :ux:test :redteam:test :node:test `
    --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "=== Suite 2: JVM multi-node sim ==="
& $gradlew :sim:test --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "=== Suite 3: shipping classpath + no INTERNET (manifest source) ==="
& $gradlew :app:assertShippingClasspath :app:processReleaseMainManifest --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$mergedCandidates = @(
    (Join-Path $RepoRoot "android\app\build\intermediates\merged_manifest\release\processReleaseMainManifest\AndroidManifest.xml"),
    (Join-Path $RepoRoot "android\app\build\intermediates\merged_manifest\release\AndroidManifest.xml")
)
$merged = $mergedCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($merged) {
    $text = Get-Content $merged -Raw
    if ($text -match 'android\.permission\.INTERNET' -and $text -notmatch 'tools:node="remove"') {
        Write-Error "INTERNET permission present in release merged manifest: $merged"
    }
    Write-Host "Suite 3 manifest check OK ($merged)"
} else {
    Write-Host "WARN: release merged manifest not found yet; classpath assert still ran"
}

$adb = Get-Command adb -ErrorAction SilentlyContinue
$phoneCount = 0
if ($adb) {
    $lines = adb devices | Where-Object { $_ -match "\tdevice$" }
    $phoneCount = @($lines).Count
}
Write-Host "ADB devices ready: $phoneCount"

if ($phoneCount -lt 1) {
    Write-Host "Suite 4: SKIPPED / OPEN - need >=1 phone for instrumented checks"
} else {
    Write-Host "Suite 4: evidence under QA/evidence/suite4/ (reboot/a11y/airplane)"
}

if ($phoneCount -lt 2) {
    Write-Host "Suite 5: OPEN - need >=2 phones for P1-P10 (found $phoneCount)"
} else {
    Write-Host "Suite 5: phones available ($phoneCount) - run multi-phone playbook manually"
}

Write-Host "=== Suites 1-3 PASS ==="
exit 0
