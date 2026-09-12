[CmdletBinding()]
<# 
    TG-05 Independent acceptance: negative business / security paths.

    Scenarios (all against real backend + real fixtures):
      A. unauthenticated access               -> HTTP 401
      B. duplicate KB (same name, same ws)    -> 3003 / 409
      C. KB not found                         -> 3001 / 404
      D. wrong workspace scope (KB in WS-A accessed via WS-A2 path) -> 3001 / 404 (fail closed)
      E. document not found                   -> 4001 / 404
      F. wrong KB scope (doc in KB-A accessed via KB-A2 path) -> 4001 / 404
      G. invalid upload (invalid.pdf)         -> 4002 / 415
      H. retry not allowed (COMPLETED)        -> 4010 / 409
      I. 4008 chunks-not-available (PENDING via RabbitMQ gate) -> 4008 / 409
      J. retry not allowed (PENDING)          -> 4010 / 409
      K. non-empty KB delete                  -> 3004 / 409
      L. cross-user IDOR (User B -> User A resources) -> fail closed, no data leak

    Deterministic PENDING window for (I)/(J): stop RabbitMQ -> consumer gated -> upload
    stays PENDING -> chunks request returns 4008, retry returns 4010. Then restore RabbitMQ
    and let the document COMPLETE. Cleanup restores all dependencies.
#>
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$script:scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { (Split-Path -Parent $MyInvocation.MyCommand.Path) }
$script:runId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
$script:fixtureRoot = Join-Path $script:scriptRoot '..\backend\src\test\resources\fixtures\document'

$script:tokenA = $null; $script:tokenB = $null
$script:wsA = $null; $script:wsA2 = $null; $script:wsB = $null
$script:kbA = $null; $script:kbA2 = $null; $script:kbB = $null
$script:docId = $null; $script:docId2 = $null

function Assert-Condition([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }

function Exec-Compose([string]$cmd) {
    & docker compose -f "$script:scriptRoot\docker-compose.yml" @($cmd.Split(' '))
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
    $dataFile = $null
    $arguments = @('--silent', '--show-error', '--request', $method, '--write-out', "`n%{http_code}", '--max-time', '30')
    if ($token) { $arguments += @('-H', "Authorization: Bearer $token") }
    if ($null -ne $body) {
        $dataFile = Join-Path $env:TEMP ("intellidesk-tg05-" + [guid]::NewGuid().ToString('N') + '.json')
        $body | ConvertTo-Json -Depth 8 -Compress | Set-Content -Path $dataFile -Encoding utf8 -NoNewline
        $arguments += @('-H', 'Content-Type: application/json')
        $arguments += @('--data-binary', "@$dataFile")
    }
    $raw = & curl.exe @arguments "$BaseUrl$path" 2>&1
    if ($dataFile) { Remove-Item $dataFile -Force -ErrorAction SilentlyContinue }
    $lines = @($raw -split "`n")
    $status = 0; [int]::TryParse(($lines[-1].Trim()), [ref]$status) | Out-Null
    $content = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
    try { $payload = $content | ConvertFrom-Json } catch { $payload = @{ message = $content } }
    return @{ status = $status; body = $payload }
}

function Invoke-Upload([string]$path, [string]$token, [long]$ws, [long]$kb) {
    $raw = & curl.exe --silent --show-error --request POST -H "Authorization: Bearer $token" -F "file=@$path" -w "`n%{http_code}" "$BaseUrl/api/workspaces/$ws/knowledge-bases/$kb/documents" 2>&1
    $lines = @($raw -split "`n")
    $status = [int]$lines[-1].Trim()
    $content = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
    try { $payload = $content | ConvertFrom-Json } catch { $payload = @{ message = $content } }
    return @{ status = $status; body = $payload }
}

function Get-Doc([long]$ws, [long]$kb, [long]$id, [string]$token) {
    $d = Invoke-JsonApi 'GET' "/api/workspaces/$ws/knowledge-bases/$kb/documents/$id" $null $token
    Assert-Condition ($d.status -eq 200 -and $d.body.code -eq 0) "Detail failed HTTP $($d.status)"
    return $d.body.data
}

function Wait-Terminal([long]$ws, [long]$kb, [long]$id, [string]$token, [string]$wanted = 'COMPLETED', [int]$timeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    $seen = [System.Collections.Generic.List[string]]::new()
    do {
        $d = Get-Doc $ws $kb $id $token
        if (-not $seen.Contains($d.status)) { $seen.Add($d.status) }
        if ($d.status -eq $wanted) { Write-Host "[PASS] doc=$id -> $wanted ; transitions=$($seen -join ' -> ')"; return $d }
        if ($wanted -ne 'FAILED' -and $d.status -eq 'FAILED') { throw "doc=$id FAILED: $($d.failureCode) $($d.failureMessage)" }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw "Timed out doc=$id; transitions=$($seen -join ' -> ')"
}

function Expect-Negative([string]$label, [hashtable]$result, [int]$http, [int]$code) {
    $ok = ($result.status -eq $http -and $result.body.code -eq $code)
    $msg = "$label -> HTTP $($result.status) code=$($result.body.code) (expect HTTP $http code $code)"
    if ($ok) { Write-Host "[PASS] $msg" } else { Write-Host "[FAIL] $msg body=$($result.body | ConvertTo-Json -Compress)" }
    Assert-Condition $ok $msg
    return $result
}

try {
    # ===== A. Unauthenticated =====
    $u1 = Invoke-JsonApi 'GET' '/api/workspaces' $null $null
    Expect-Negative 'unauthenticated GET /workspaces' $u1 401 401

    # ===== setup users/workspaces/KBs =====
    $ua = "tg05a-$script:runId"
    $ub = "tg05b-$script:runId"
    $regA = Invoke-JsonApi 'POST' '/api/auth/register' @{ username = $ua; password = 'Test@123456'; email = "$ua@example.com"; nickname = 'UserA' }
    Assert-Condition ($regA.status -eq 200 -and $regA.body.code -eq 0) "Register A failed HTTP $($regA.status)"
    $script:tokenA = $regA.body.data.accessToken
    $regB = Invoke-JsonApi 'POST' '/api/auth/register' @{ username = $ub; password = 'Test@123456'; email = "$ub@example.com"; nickname = 'UserB' }
    Assert-Condition ($regB.status -eq 200 -and $regB.body.code -eq 0) "Register B failed HTTP $($regB.status)"
    $script:tokenB = $regB.body.data.accessToken

    $wsA = Invoke-JsonApi 'POST' '/api/workspaces' @{ name = "tg05-wsa-$script:runId" } $script:tokenA
    Assert-Condition ($wsA.status -eq 200 -and $wsA.body.code -eq 0) 'WS-A failed'
    $script:wsA = [long]$wsA.body.data.id
    $wsA2 = Invoke-JsonApi 'POST' '/api/workspaces' @{ name = "tg05-wsa2-$script:runId" } $script:tokenA
    Assert-Condition ($wsA2.status -eq 200 -and $wsA2.body.code -eq 0) 'WS-A2 failed'
    $script:wsA2 = [long]$wsA2.body.data.id
    $wsB = Invoke-JsonApi 'POST' '/api/workspaces' @{ name = "tg05-wsb-$script:runId" } $script:tokenB
    Assert-Condition ($wsB.status -eq 200 -and $wsB.body.code -eq 0) 'WS-B failed'
    $script:wsB = [long]$wsB.body.data.id

    $kbNameA = "tg05-kb-a-$script:runId"
    $kbA = Invoke-JsonApi 'POST' "/api/workspaces/$script:wsA/knowledge-bases" @{ name = $kbNameA; chunkStrategy = 'RECURSIVE'; chunkSize = 500; chunkOverlap = 50 } $script:tokenA
    Assert-Condition ($kbA.status -eq 200 -and $kbA.body.code -eq 0) 'KB-A failed'
    $script:kbA = [long]$kbA.body.data.id
    $kbA2 = Invoke-JsonApi 'POST' "/api/workspaces/$script:wsA/knowledge-bases" @{ name = "tg05-kb-a2-$script:runId"; chunkStrategy = 'RECURSIVE'; chunkSize = 500; chunkOverlap = 50 } $script:tokenA
    Assert-Condition ($kbA2.status -eq 200 -and $kbA2.body.code -eq 0) 'KB-A2 failed'
    $script:kbA2 = [long]$kbA2.body.data.id
    $kbB = Invoke-JsonApi 'POST' "/api/workspaces/$script:wsB/knowledge-bases" @{ name = "tg05-kb-b-$script:runId"; chunkStrategy = 'RECURSIVE'; chunkSize = 500; chunkOverlap = 50 } $script:tokenB
    Assert-Condition ($kbB.status -eq 200 -and $kbB.body.code -eq 0) 'KB-B failed'
    $script:kbB = [long]$kbB.body.data.id
    Write-Host "[PASS] Setup: wsA=$script:wsA wsA2=$script:wsA2 wsB=$script:wsB kbA=$script:kbA kbA2=$script:kbA2 kbB=$script:kbB"

    # upload doc A (COMPLETED)
    $txtPath = Join-Path $script:fixtureRoot 'sample.txt'
    $up = Invoke-Upload $txtPath $script:tokenA $script:wsA $script:kbA
    Assert-Condition ($up.status -in @(200, 202) -and $up.body.code -eq 0) "Doc upload failed HTTP $($up.status)"
    $script:docId = [long]$up.body.data.documentId
    Wait-Terminal $script:wsA $script:kbA $script:docId $script:tokenA 'COMPLETED' 180 | Out-Null
    Write-Host "[PASS] Doc A COMPLETED; documentId=$script:docId"

    # ===== B. Duplicate KB =====
    $dup = Invoke-JsonApi 'POST' "/api/workspaces/$script:wsA/knowledge-bases" @{ name = $kbNameA; chunkStrategy = 'RECURSIVE'; chunkSize = 500; chunkOverlap = 50 } $script:tokenA
    Expect-Negative 'duplicate KB (3003/409)' $dup 409 3003

    # ===== C. KB not found =====
    $nf = Invoke-JsonApi 'GET' "/api/workspaces/$script:wsA/knowledge-bases/999999999" $null $script:tokenA
    Expect-Negative 'KB not found (3001/404)' $nf 404 3001

    # ===== D. wrong workspace scope =====
    $wws = Invoke-JsonApi 'GET' "/api/workspaces/$script:wsA2/knowledge-bases/$script:kbA" $null $script:tokenA
    Expect-Negative 'wrong workspace scope (3001/404)' $wws 404 3001

    # ===== E. document not found =====
    $dnf = Invoke-JsonApi 'GET' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA/documents/999999999" $null $script:tokenA
    Expect-Negative 'document not found (4001/404)' $dnf 404 4001

    # ===== F. wrong KB scope =====
    $wks = Invoke-JsonApi 'GET' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA2/documents/$script:docId" $null $script:tokenA
    Expect-Negative 'wrong KB scope (4001/404)' $wks 404 4001

    # ===== G. invalid upload =====
    $badPath = Join-Path $script:fixtureRoot 'invalid.pdf'
    $bad = Invoke-Upload $badPath $script:tokenA $script:wsA $script:kbA2
    Expect-Negative 'invalid upload (4002/415)' $bad 415 4002

    # ===== H. retry not allowed (COMPLETED) =====
    $rc = Invoke-JsonApi 'POST' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA/documents/$script:docId/retry" @{} $script:tokenA
    Expect-Negative 'retry not allowed COMPLETED (4010/409)' $rc 409 4010

    # ===== I + J. PENDING window via RabbitMQ gate =====
    Exec-Compose 'stop rabbitmq'
    Set-ContainerState 'intellidesk-rabbitmq' 'exited'
    Write-Host '[STATE] RabbitMQ stopped (consumer gated)'

    $up2 = Invoke-Upload $txtPath $script:tokenA $script:wsA $script:kbA2
    Assert-Condition ($up2.status -in @(200, 202) -and $up2.body.code -eq 0) "Doc2 upload failed HTTP $($up2.status)"
    $script:docId2 = [long]$up2.body.data.documentId
    Start-Sleep -Seconds 3
    $d2 = Get-Doc $script:wsA $script:kbA2 $script:docId2 $script:tokenA
    Assert-Condition ($d2.status -eq 'PENDING') "Expected PENDING while gated, got $($d2.status)"
    Write-Host "[PASS] Doc2 PENDING (gated); documentId2=$script:docId2"

    $chk = Invoke-JsonApi 'GET' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA2/documents/$script:docId2/chunks?page=1&size=20" $null $script:tokenA
    Expect-Negative 'chunks not available in PENDING (4008/409)' $chk 409 4008

    $rp = Invoke-JsonApi 'POST' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA2/documents/$script:docId2/retry" @{} $script:tokenA
    Expect-Negative 'retry not allowed PENDING (4010/409)' $rp 409 4010

    Exec-Compose 'start rabbitmq'
    Set-ContainerState 'intellidesk-rabbitmq' 'running'
    Wait-Healthy 'intellidesk-rabbitmq'
    Write-Host '[STATE] RabbitMQ restored; doc2 will process'
    Wait-Terminal $script:wsA $script:kbA2 $script:docId2 $script:tokenA 'COMPLETED' 180 | Out-Null

    # ===== K. non-empty KB delete =====
    $de = Invoke-JsonApi 'DELETE' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA" $null $script:tokenA
    Expect-Negative 'non-empty KB delete (3004/409)' $de 409 3004

    # ===== L. cross-user IDOR (User B -> User A resources) =====
    $iu1 = Invoke-JsonApi 'GET' "/api/workspaces/$script:wsA" $null $script:tokenB
    Expect-Negative 'IDOR workspace (2002/403)' $iu1 403 2002
    $iu2 = Invoke-JsonApi 'GET' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA" $null $script:tokenB
    Expect-Negative 'IDOR KB (2002/403)' $iu2 403 2002
    $iu3 = Invoke-JsonApi 'GET' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA/documents/$script:docId" $null $script:tokenB
    Expect-Negative 'IDOR document (2002/403)' $iu3 403 2002
    $iu4 = Invoke-JsonApi 'GET' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA/documents/$script:docId/chunks?page=1&size=20" $null $script:tokenB
    Expect-Negative 'IDOR chunks (2002/403)' $iu4 403 2002
    $iu5 = Invoke-JsonApi 'POST' "/api/workspaces/$script:wsA/knowledge-bases/$script:kbA/documents/$script:docId/retry" @{} $script:tokenB
    Expect-Negative 'IDOR retry (2002/403)' $iu5 403 2002

    Write-Host ''
    Write-Host 'RESULT: TG-05 NEGATIVE PATHS INDEPENDENT EVIDENCE PASS'
} catch {
    Write-Host "[ERROR] $_"
    exit 1
} finally {
    # ===== cleanup =====
    foreach ($pair in @(
        @{ ws = $script:wsA; kb = $script:kbA; id = $script:docId; tok = $script:tokenA },
        @{ ws = $script:wsA; kb = $script:kbA2; id = $script:docId2; tok = $script:tokenA }
    )) {
        if ($pair.id) {
            $r = Invoke-JsonApi 'DELETE' "/api/workspaces/$($pair.ws)/knowledge-bases/$($pair.kb)/documents/$($pair.id)" $null $pair.tok
            if ($r.status -in @(200, 204)) { Write-Host "[PASS] cleanup document $($pair.id)" }
        }
    }
    foreach ($pair in @(
        @{ ws = $script:wsA; kb = $script:kbA; tok = $script:tokenA },
        @{ ws = $script:wsA; kb = $script:kbA2; tok = $script:tokenA },
        @{ ws = $script:wsB; kb = $script:kbB; tok = $script:tokenB }
    )) {
        if ($pair.kb) {
            $r = Invoke-JsonApi 'DELETE' "/api/workspaces/$($pair.ws)/knowledge-bases/$($pair.kb)" $null $pair.tok
            if ($r.status -in @(200, 204)) { Write-Host "[PASS] cleanup KB $($pair.kb)" }
        }
    }
    foreach ($pair in @(
        @{ ws = $script:wsA; tok = $script:tokenA },
        @{ ws = $script:wsA2; tok = $script:tokenA },
        @{ ws = $script:wsB; tok = $script:tokenB }
    )) {
        if ($pair.ws) {
            $r = Invoke-JsonApi 'DELETE' "/api/workspaces/$($pair.ws)" $null $pair.tok
            if ($r.status -in @(200, 204)) { Write-Host "[PASS] cleanup workspace $($pair.ws)" }
        }
    }
    Exec-Compose 'start rabbitmq'
    Write-Host '[PASS] RabbitMQ restored to UP'
}
