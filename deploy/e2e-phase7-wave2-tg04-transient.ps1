[CmdletBinding()]
<# 
    TG-04 Evidence: FAILED -> Retry -> COMPLETED via a REAL transient dependency failure.

    Deterministic transient failure mechanism (NO production code change, NO backdoor):
      1. RabbitMQ is stopped so the consumer cannot touch MinIO; uploads still succeed
         (upload MQ publish is best-effort; task waits in PENDING for the Dispatcher).
      2. Upload a VALID document while MinIO is UP -> object is stored in MinIO.
      3. Stop MinIO (dependency outage).
      4. Start RabbitMQ -> Dispatcher + Consumer resume -> consumer downloads from MinIO,
         MinIO is down -> getObject throws retryable StorageException("MinIO network/IO error")
         -> DocumentProcessingService.markRetryableFailure(). After retryDelaySeconds x
         maxAttempts, the task exhausts -> markPermanentFailure -> document = FAILED.
      5. Start MinIO (dependency restored).
      6. POST /retry -> objectExists() now true -> HTTP 200 -> re-PENDING ->
         consumer successfully downloads/parses/chunks -> COMPLETED + chunks.

    Usage:
      powershell -NoProfile -ExecutionPolicy Bypass -File .\e2e-phase7-wave2-tg04-transient.ps1 -Mode run
    Modes: health | run | cleanup
#>
param(
    [ValidateSet('health', 'run', 'cleanup')]
    [string]$Mode = 'run',
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$KeepData
)

$ErrorActionPreference = 'Stop'
$script:scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { (Split-Path -Parent $MyInvocation.MyCommand.Path) }
$script:composeDir = if ($env:INTELLIDESK_COMPOSE_DIR) { $env:INTELLIDESK_COMPOSE_DIR } else { $script:scriptRoot }
$script:runId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
$script:token = $null
$script:workspaceId = $null
$script:knowledgeBaseId = $null
$script:documentId = $null
$script:fixture = Join-Path $script:composeDir '..\backend\src\test\resources\fixtures\document\valid-sample.pdf'
$script:stateFile = Join-Path $env:TEMP 'intellidesk-wave2-tg04-state.json'

function Assert-Condition([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Exec-Compose([string]$cmd) {
    # PowerShell 5.1: do NOT use `& docker compose ... 2>&1` or `2>$null` here.
    # Under $ErrorActionPreference='Stop' those redirects turn docker compose's
    # native stderr (the obsolete-`version` warning) into a terminating error.
    # A direct call leaves $LASTEXITCODE reliable; the `$args` param name also
    # collides with the automatic $args variable, so the parameter is $cmd.
    & docker compose -f "$script:composeDir\docker-compose.yml" @($cmd.Split(' '))
    if ($LASTEXITCODE -ne 0) { throw "docker compose $cmd failed (exit=$LASTEXITCODE)" }
}

function Set-ContainerState([string]$container, [string]$want) {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $status = docker inspect --format '{{.State.Status}}' $container 2>$null
        if ($status -eq $want) { return }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Container $container never reached state $want (last=$status)"
}

function Wait-Healthy([string]$container) {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $status = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $container 2>$null
        if ($status -eq 'healthy') { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Container $container not healthy (last=$status)"
}

function Invoke-JsonApi([string]$method, [string]$path, [object]$body = $null, [string]$token = $null) {
    # NOTE: body is written to a temp file and sent via --data-binary '@file'.
    # Piping the JSON string through the pipeline to curl.exe's stdin (@-) proved
    # fragile under `powershell -File` (transmission corrupted -> HTTP 500 on register).
    $dataFile = $null
    $arguments = @('--silent', '--show-error', '--request', $method, '--write-out', "`n%{http_code}", '--max-time', '30')
    if ($token) { $arguments += @('-H', "Authorization: Bearer $token") }
    if ($null -ne $body) {
        $dataFile = Join-Path $env:TEMP ("intellidesk-api-" + [guid]::NewGuid().ToString('N') + '.json')
        $body | ConvertTo-Json -Depth 8 -Compress | Set-Content -Path $dataFile -Encoding utf8 -NoNewline
        $arguments += @('-H', 'Content-Type: application/json')
        $arguments += @('--data-binary', "@$dataFile")
    }
    $raw = & curl.exe @arguments "$BaseUrl$path" 2>&1
    if ($dataFile) { Remove-Item $dataFile -Force -ErrorAction SilentlyContinue }
    $lines = @($raw -split "`n")
    $status = 0
    [int]::TryParse(($lines[-1].Trim()), [ref]$status) | Out-Null
    $content = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
    try { $payload = $content | ConvertFrom-Json } catch { $payload = @{ message = $content } }
    return @{ status = $status; body = $payload }
}

function Invoke-Upload([string]$path, [string]$token) {
    $raw = & curl.exe --silent --show-error --request POST -H "Authorization: Bearer $token" -F "file=@$path" -w "`n%{http_code}" "$BaseUrl/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId/documents" 2>&1
    $lines = @($raw -split "`n")
    $status = [int]$lines[-1].Trim()
    $content = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
    try { $payload = $content | ConvertFrom-Json } catch { $payload = @{ message = $content } }
    return @{ status = $status; body = $payload }
}

function Get-DocumentStatus {
    $d = Invoke-JsonApi 'GET' "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId/documents/$script:documentId" $null $script:token
    Assert-Condition ($d.status -eq 200 -and $d.body.code -eq 0) "Document detail failed HTTP $($d.status)"
    return $d.body.data
}

function Test-ContainerHealthy([string]$name) {
    $status = docker inspect --format '{{.State.Health.Status}}' $name 2>$null
    return ($LASTEXITCODE -eq 0 -and $status -eq 'healthy')
}

function Test-Health {
    Assert-Condition (Test-ContainerHealthy 'intellidesk-postgres') 'PostgreSQL not healthy'
    Assert-Condition (Test-ContainerHealthy 'intellidesk-redis') 'Redis not healthy'
    Assert-Condition (Test-ContainerHealthy 'intellidesk-rabbitmq') 'RabbitMQ not healthy'
    Assert-Condition (Test-ContainerHealthy 'intellidesk-minio') 'MinIO not healthy'
    Assert-Condition (Test-ContainerHealthy 'intellidesk-elasticsearch') 'Elasticsearch not healthy'
    $h = & curl.exe --silent --max-time 5 --output NUL --write-out '%{http_code}' $BaseUrl 2>$null | Select-Object -Last 1
    Assert-Condition ([int]$h -ge 400) "Backend not reachable at $BaseUrl (HTTP $h)"
    Write-Host '[PASS] Dependency stack healthy; backend reachable'
}

function Setup-Data {
    Assert-Condition (Test-Path $script:fixture) "Valid PDF fixture not found: $script:fixture"
    $u = "tg04-$script:runId"
    $reg = Invoke-JsonApi 'POST' '/api/auth/register' @{ username = $u; password = 'Test@123456'; email = "$u@example.com"; nickname = 'TG04 Evidence' }
    if ($reg.status -ne 200 -or $reg.body.code -ne 0) {
        throw "Registration failed HTTP $($reg.status); body=$($reg.body | ConvertTo-Json -Compress); runId=$script:runId"
    }
    $script:token = $reg.body.data.accessToken
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$script:token)) 'No access token returned'
    $ws = Invoke-JsonApi 'POST' '/api/workspaces' @{ name = "TG04 Evidence $script:runId"; description = 'Deterministic transient-failure evidence' } $script:token
    Assert-Condition ($ws.status -eq 200 -and $ws.body.code -eq 0) "Workspace creation failed HTTP $($ws.status)"
    $script:workspaceId = [long]$ws.body.data.id
    $kb = Invoke-JsonApi 'POST' "/api/workspaces/$script:workspaceId/knowledge-bases" @{ name = "tg04-kb-$script:runId"; description = 'TG04 KB'; chunkStrategy = 'RECURSIVE'; chunkSize = 500; chunkOverlap = 50 } $script:token
    Assert-Condition ($kb.status -eq 200 -and $kb.body.code -eq 0) "Knowledge Base creation failed HTTP $($kb.status)"
    $script:knowledgeBaseId = [long]$kb.body.data.id
    @{ token = $script:token; workspaceId = $script:workspaceId; knowledgeBaseId = $script:knowledgeBaseId; documentId = $script:documentId } |
        ConvertTo-Json | Set-Content -Path $script:stateFile -Encoding UTF8
    Write-Host "[PASS] Fresh data; workspaceId=$script:workspaceId knowledgeBaseId=$script:knowledgeBaseId"
}

function Restore-Data {
    Assert-Condition (Test-Path $script:stateFile) 'State file not found'
    $state = Get-Content $script:stateFile -Raw | ConvertFrom-Json
    $script:token = $state.token
    $script:workspaceId = [long]$state.workspaceId
    $script:knowledgeBaseId = [long]$state.knowledgeBaseId
    if ($null -ne $state.documentId) { $script:documentId = [long]$state.documentId }
}

function Wait-ForStatus([string]$wanted, [int]$timeoutSeconds, [string]$label) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    $seen = [System.Collections.Generic.List[string]]::new()
    do {
        $d = Get-DocumentStatus
        if (-not $seen.Contains($d.status)) { $seen.Add($d.status) }
        if ($d.status -eq $wanted) {
            Write-Host "[PASS] $label -> $wanted ; transitions=$($seen -join ' -> ')"
            return $d
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    $last = Get-DocumentStatus
    throw "Timed out waiting for $label -> $wanted ; transitions=$($seen -join ' -> '); last=$($last.status) $($last.failureCode)"
}

function Run-Sequence {
    # 1. Full stack healthy first
    Test-Health
    Setup-Data
    # Ensure both RabbitMQ and MinIO are UP before the critical window
    Exec-Compose 'start rabbitmq'
    Exec-Compose 'start minio'
    Start-Sleep -Seconds 5
    Test-Health

    # 2. Stop RabbitMQ so consumer cannot touch MinIO; queue holds nothing yet.
    Exec-Compose 'stop rabbitmq'
    Set-ContainerState 'intellidesk-rabbitmq' 'exited'
    Write-Host '[STATE] RabbitMQ stopped (consumer gated)'

    # 3. Upload a VALID document while MinIO is UP (object gets stored in MinIO).
    $up = Invoke-Upload $script:fixture $script:token
    Assert-Condition ($up.status -eq 202 -and $up.body.code -eq 0) "Valid PDF upload failed HTTP $($up.status) $($up.body.message)"
    $script:documentId = [long]$up.body.data.documentId
    Write-Host "[STATE] Uploaded valid PDF while MinIO up; documentId=$script:documentId (stored in MinIO, task gated in PENDING)"
    $script:stateFile | Out-Null
    # persist documentId for cleanup robustness
    @{ token = $script:token; workspaceId = $script:workspaceId; knowledgeBaseId = $script:knowledgeBaseId; documentId = $script:documentId } |
        ConvertTo-Json | Set-Content -Path $script:stateFile -Encoding UTF8

    # 4. Bring MinIO DOWN (dependency outage) while consumer is still gated.
    Exec-Compose 'stop minio'
    Set-ContainerState 'intellidesk-minio' 'exited'
    Write-Host '[STATE] MinIO stopped (dependency outage)'

    # 5. Release the gate: start RabbitMQ. Dispatcher publishes PENDING; consumer
    #    downloads from MinIO -> down -> retryable network/IO error -> retry loops -> FAILED.
    Exec-Compose 'start rabbitmq'
    Set-ContainerState 'intellidesk-rabbitmq' 'running'
    Write-Host '[STATE] RabbitMQ resumed; consumer will hit MinIO outage and exhaust retries'

    $failed = Wait-ForStatus 'FAILED' 300 'FAILED degradation'
    Assert-Condition ($failed.failureCode -eq 'STORAGE_UNAVAILABLE') "Expected failureCode STORAGE_UNAVAILABLE, got $($failed.failureCode)"
    Write-Host "[PASS] Document FAILED with failureCode=$($failed.failureCode) message=$($failed.failureMessage)"

    # 6. Restore the dependency.
    Exec-Compose 'start minio'
    Set-ContainerState 'intellidesk-minio' 'running'
    Wait-Healthy 'intellidesk-minio'
    Write-Host '[STATE] MinIO restored (dependency recovery)'

    # 7. Manual Retry over public API -> 200 -> re-PENDING -> COMPLETED + chunks.
    $retry = Invoke-JsonApi 'POST' "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId/documents/$script:documentId/retry" @{} $script:token
    Assert-Condition ($retry.status -eq 200 -and $retry.body.code -eq 0) "Retry failed HTTP $($retry.status) $($retry.body.message)"
    Write-Host "[PASS] Retry HTTP 200; body=$($retry.body | ConvertTo-Json -Compress)"

    $done = Wait-ForStatus 'COMPLETED' 180 'post-retry processing'
    $chunks = Invoke-JsonApi 'GET' "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId/documents/$script:documentId/chunks?page=1&size=20" $null $script:token
    Assert-Condition ($chunks.status -eq 200 -and $chunks.body.code -eq 0 -and $chunks.body.data.items.Count -gt 0) 'Chunks missing after recovery'
    Write-Host "[PASS] Chunks persisted after recovery; count=$($chunks.body.data.items.Count)"

    Write-Host ''
    Write-Host 'RESULT: TG-04 FAILED -> Retry -> COMPLETED EVIDENCE PASS'
}

function Cleanup {
    if ($script:documentId) {
        $r = Invoke-JsonApi 'DELETE' "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId/documents/$script:documentId" $null $script:token
        if ($r.status -in @(200, 204)) { Write-Host '[PASS] Document cleanup' } else { Write-Host "[WARN] Document cleanup HTTP $($r.status)" }
    }
    if ($script:knowledgeBaseId) {
        $r = Invoke-JsonApi 'DELETE' "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId" $null $script:token
        if ($r.status -in @(200, 204)) { Write-Host '[PASS] KB cleanup' } else { Write-Host "[WARN] KB cleanup HTTP $($r.status)" }
    }
    if ($script:workspaceId) {
        $r = Invoke-JsonApi 'DELETE' "/api/workspaces/$script:workspaceId" $null $script:token
        if ($r.status -in @(200, 204)) { Write-Host '[PASS] Workspace cleanup' } else { Write-Host "[WARN] Workspace cleanup HTTP $($r.status)" }
    }
    if (Test-Path $script:stateFile) { Remove-Item $script:stateFile -Force }
    Exec-Compose 'start minio'
    Exec-Compose 'start rabbitmq'
    if (-not $KeepData) { Write-Host '[PASS] Cleanup attempted; dependencies restored to UP' }
}

if ($Mode -eq 'health') { Test-Health; exit 0 }
if ($Mode -eq 'cleanup') { Restore-Data; Cleanup; exit 0 }

# run
try {
    Run-Sequence
    if (-not $KeepData) { Cleanup }
} catch {
    Write-Host "[ERROR] $_"
    exit 1
}