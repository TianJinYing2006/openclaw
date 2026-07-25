param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHome = 'D:\YOUKD\jdk-21.0.11'
$mavenHome = 'D:\YOUKD\maven\apache-maven-3.9.9'
$java = Join-Path $javaHome 'bin\java.exe'
$maven = Join-Path $mavenHome 'bin\mvn.cmd'
$jar = Join-Path $projectRoot 'target\ykd-summer-0.0.1-SNAPSHOT.jar'
$stdout = Join-Path $projectRoot 'target\runtime-bot.out.log'
$stderr = Join-Path $projectRoot 'target\runtime-bot.err.log'

foreach ($required in @($java, $maven)) {
    if (-not (Test-Path $required)) {
        throw "Required executable was not found: $required"
    }
}

# Only stop Java processes, so another local service on 8080 is never killed by this script.
$listeners = @(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
foreach ($listener in $listeners) {
    $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -ieq 'java') {
        Stop-Process -Id $process.Id -Force
        Write-Host "Stopped Java process on port 8080 (PID $($process.Id))."
    } elseif ($process) {
        throw "Port 8080 is owned by $($process.ProcessName) (PID $($process.Id)); it was not stopped."
    }
}

if (-not $SkipBuild) {
    Write-Host 'Building the current project...'
    $previousJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $javaHome
    try {
        & $maven '-DskipTests' 'package'
        if ($LASTEXITCODE -ne 0) {
            throw "Maven package failed with exit code $LASTEXITCODE."
        }
    } finally {
        $env:JAVA_HOME = $previousJavaHome
    }
}

if (-not (Test-Path $jar)) {
    throw "Runnable JAR was not found: $jar"
}

# Command-line properties have the highest Spring precedence. The API key remains only in
# application-local.properties, which is Git-ignored and packaged from this local machine.
$arguments = @(
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '--add-opens', 'java.base/java.util=ALL-UNNAMED',
    '--add-opens', 'java.base/java.io=ALL-UNNAMED',
    '--add-opens', 'java.base/java.util.zip=ALL-UNNAMED',
    '--add-opens', 'java.base/java.lang.reflect=ALL-UNNAMED',
    '--add-opens', 'java.desktop/java.awt.font=ALL-UNNAMED',
    '-jar', $jar,
    '--ilink.enabled=true',
    '--spring.ai.openai.base-url=https://kittyapi.xyz/v1',
    '--spring.ai.openai.chat.options.model=gpt-5.6-terra',
    '--app.ai.model=gpt-5.6-terra',
    '--app.ai.reasoning-effort=xhigh'
)

$process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $projectRoot `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru

$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener -and $listener.OwningProcess -eq $process.Id) {
        Write-Host "Bot started on http://127.0.0.1:8080 (PID $($process.Id))."
        Write-Host "Model: Kitty gpt-5.6-terra, reasoning effort: xhigh."
        Write-Host "Logs: $stdout"
        exit 0
    }
    Start-Sleep -Seconds 1
}

throw "Bot did not listen on port 8080 within 90 seconds. See $stdout and $stderr."
