# Runs the in-game end-to-end self test in the dev client and prints the outcome.
# Usage: powershell -File tools\selftest.ps1 [-Scenario basic]
param([string]$Scenario = "basic")

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$log = Join-Path $env:TEMP "flashback_reforged-selftest.log"
Remove-Item "run\flashback_reforged-selftest.json" -ErrorAction SilentlyContinue
& .\gradlew.bat runClient "-Pselftest=$Scenario" --console=plain --no-daemon *> $log
"gradle exit $LASTEXITCODE"
if (Test-Path "run\flashback_reforged-selftest.json") { Get-Content "run\flashback_reforged-selftest.json" } else { "NO RESULT FILE" }
Select-String -Path $log -Pattern "error:|\[selftest\] (FAILED|RESULT)|Skipping replay packet|Snapshot could not|MixinApplyError|InvalidInjection|InvalidMixin|Exception in|BUILD " |
    Select-Object -First 40 |
    ForEach-Object { $_.Line.Substring(0, [Math]::Min(240, $_.Line.Length)) }
