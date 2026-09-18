<#
.SYNOPSIS
  Local one-shot verification: compile + layered tests + summary.
  Does NOT invoke real models or the WeChat link by default.

.DESCRIPTION
  Modes:
    unit         compile + unit tests (mvn test; no external deps; must be green)
    integration  integration tests (mvn test -Pintegration; needs MySQL, Redis for graph checkpoint)
    all          unit + integration
    smoke        context load + graph checkpoint recovery subset (needs MySQL/Redis)

  Exit code: 0 when all pass; non-zero on first failure.

.EXAMPLE
  .\scripts\verify.ps1 -Mode unit
  .\scripts\verify.ps1 -Mode integration
  .\scripts\verify.ps1 -Mode all
  .\scripts\verify.ps1 -Mode smoke
#>
[CmdletBinding()]
param(
    [ValidateSet('unit', 'integration', 'all', 'smoke')]
    [string]$Mode = 'unit'
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$script:SessionStart = Get-Date
Push-Location $repoRoot
try {
    Write-Host "== WeChatBot verify (mode=$Mode) ==" -ForegroundColor Cyan

    # 1) toolchain check (use cmd /c so native stderr is captured as plain text)
    $javaRaw = (cmd /c "java -version 2>&1" | Out-String)
    if ($javaRaw -notmatch 'version "(\d+)') {
        Write-Host "ERROR: JDK not found. Install JDK 21." -ForegroundColor Red
        exit 2
    }
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -lt 21) {
        Write-Host "ERROR: JDK 21 required, found $javaMajor." -ForegroundColor Red
        exit 2
    }
    $mvnRaw = (cmd /c "mvn -v 2>&1" | Select-Object -First 1)
    Write-Host ("java : " + (($javaRaw -split "`n")[0]).Trim())
    Write-Host ("maven: " + $mvnRaw.Trim())

    # 2) env presence check (names only, never values)
    $requiredEnv = @()
    if ($Mode -in @('integration', 'all', 'smoke')) {
        $requiredEnv = @('DASHSCOPE_API_KEY', 'PERSISTENCE_PASSWORD')
    }
    foreach ($name in $requiredEnv) {
        $v = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not $v) { $v = [Environment]::GetEnvironmentVariable($name, 'User') }
        if ($v) {
            Write-Host "env $name : set" -ForegroundColor Green
        } else {
            Write-Host "WARN: env $name is not set (application-local.properties may reference it)." -ForegroundColor Yellow
        }
    }

    # 3) dependency ports (informational)
    if ($Mode -in @('integration', 'all', 'smoke')) {
        foreach ($port in @(3306, 6379)) {
            $up = Test-NetConnection -ComputerName '127.0.0.1' -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
            $label = if ($up) { 'reachable' } else { 'NOT reachable (tests may skip or fail)' }
            Write-Host ("port {0} : {1}" -f $port, $label)
        }
    }

    function Invoke-Mvn([string[]]$mvnArgs, [string]$title) {
        Write-Host ""
        Write-Host "-- $title --" -ForegroundColor Cyan
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Host "FAILED: $title" -ForegroundColor Red
            Show-Summary
            exit $LASTEXITCODE
        }
    }

    function Show-Summary {
        $dir = Join-Path $repoRoot 'target\surefire-reports'
        # only reports produced by this session (avoid stale files from earlier runs)
        $reports = Get-ChildItem -Path $dir -Filter '*.txt' -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -ge $script:SessionStart }
        if (-not $reports) { return }
        $run = 0; $fail = 0; $err = 0; $skip = 0
        foreach ($r in $reports) {
            $m = Select-String -Path $r.FullName -Pattern 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)' |
                 Select-Object -First 1
            if ($m) {
                $run += [int]$m.Matches[0].Groups[1].Value
                $fail += [int]$m.Matches[0].Groups[2].Value
                $err += [int]$m.Matches[0].Groups[3].Value
                $skip += [int]$m.Matches[0].Groups[4].Value
            }
        }
        Write-Host ""
        Write-Host ("== test summary: run={0} failures={1} errors={2} skipped={3} ({4} report files) ==" -f `
            $run, $fail, $err, $skip, $reports.Count) -ForegroundColor Cyan
    }

    switch ($Mode) {
        'unit' {
            Invoke-Mvn @('-B', '-DskipTests', 'test-compile') 'compile'
            Invoke-Mvn @('-B', 'test') 'unit tests'
        }
        'integration' {
            Invoke-Mvn @('-B', 'test', '-Pintegration') 'integration tests'
        }
        'smoke' {
            Invoke-Mvn @('-B', 'test', '-Pintegration',
                '-Dtest=ILinkApplicationContextTest,FashionGraphRedisRecoveryIntegrationTest') 'smoke (context + checkpoint)'
        }
        'all' {
            Invoke-Mvn @('-B', 'test') 'unit tests'
            Invoke-Mvn @('-B', 'test', '-Pintegration') 'integration tests'
        }
    }

    Show-Summary
    Write-Host ""
    Write-Host "ALL PASSED" -ForegroundColor Green
    exit 0
} finally {
    Pop-Location
}
