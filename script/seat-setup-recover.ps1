param(
    [Parameter(Mandatory)][long]$ScheduleId,
    [Parameter(Mandatory)][string]$RequestId,
    [Parameter(Mandatory)][long]$ConfigVersion,
    [Parameter(Mandatory)][string]$Operator,
    [Parameter(Mandatory)][string]$Reason,
    [string]$Container = 'ticket-performance-service-1',
    [string]$AuditDirectory = (Join-Path $env:LOCALAPPDATA 'ticket/seat-setup-recovery')
)

# 서비스의 기존 환경 설정을 사용한다. DB 비밀번호·토큰을 읽거나 인수로 전달하지 않는다.
$ErrorActionPreference = 'Stop'
# 별도 JVM 출력은 docker logs에 남지 않으므로 실행 호스트에 명령별 기록을 보존한다.
New-Item -ItemType Directory -Path $AuditDirectory -Force | Out-Null
$auditPath = Join-Path $AuditDirectory ("{0}-{1}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), [guid]::NewGuid())
Start-Transcript -LiteralPath $auditPath -ErrorAction Stop | Out-Null
try {
    [ordered]@{
        scheduleId = $ScheduleId; requestId = $RequestId; configVersion = $ConfigVersion
        operator = $Operator; reason = $Reason; container = $Container
    } | ConvertTo-Json -Compress | Write-Host
    docker exec $Container java -Xms64m -Xmx256m -jar /app/app.jar --seat-setup-recover `
        "--schedule-id=$ScheduleId" "--request-id=$RequestId" "--config-version=$ConfigVersion" `
        "--operator=$Operator" "--reason=$Reason"
    $commandExitCode = $LASTEXITCODE
    Write-Host "exitCode=$commandExitCode"
    if ($commandExitCode -ne 0) { throw '좌석 준비 복구 접수에 실패했습니다.' }
} finally {
    Stop-Transcript | Out-Null
    Write-Host "운영 기록: $auditPath"
}
