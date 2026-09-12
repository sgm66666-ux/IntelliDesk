[CmdletBinding()]
param(
    [ValidateSet('health', 'setup', 'run', 'cleanup')]
    [string]$Mode = 'run',
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$FrontendUrl = 'http://localhost:5173',
    [string]$ComposeFile = "$PSScriptRoot\docker-compose.yml",
    [switch]$KeepData
)

$ErrorActionPreference = 'Stop'
$script:runId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
$script:token = $null
$script:workspaceId = $null
$script:knowledgeBaseId = $null
$script:documentIds = [System.Collections.Generic.List[long]]::new()
$script:fixtureRoot = Join-Path $PSScriptRoot '..\backend\src\test\resources\fixtures\document'
$script:stateFile = Join-Path $env:TEMP 'intellidesk-wave2-evidence-state.json'

function Assert-Condition([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Invoke-JsonApi([string]$method, [string]$path, [object]$body = $null, [string]$token = $null) {
    $arguments = @('--silent', '--show-error', '--request', $method, '--write-out', "`n%{http_code}", '--max-time', '30')
    if ($token) { $arguments += @('-H', "Authorization: Bearer $token") }
    if ($null -ne $body) {
        $arguments += @('-H', 'Content-Type: application/json')
        $json = $body | ConvertTo-Json -Depth 8 -Compress
        $arguments += @('--data-binary', '@-')
        $raw = $json | & curl.exe @arguments "$BaseUrl$path" 2>&1
    } else {
        $raw = & curl.exe @arguments "$BaseUrl$path" 2>&1
    }
    $lines = @($raw -split "`n")
    $status = 0
    [int]::TryParse(($lines[-1].Trim()), [ref]$status) | Out-Null
    $content = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
    try { $payload = $content | ConvertFrom-Json } catch { $payload = @{ message = $content } }
    return @{ status = $status; body = $payload }
}

function Invoke-Multipart([string]$path, [string]$filePath, [string]$token) {
    $raw = & curl.exe --silent --show-error --request POST -H "Authorization: Bearer $token" -F "file=@$filePath" -w "`n%{http_code}" "$BaseUrl$path" 2>&1
    $lines = @($raw -split "`n")
    $status = [int]$lines[-1].Trim()
    $content = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
    try { $payload = $content | ConvertFrom-Json } catch { $payload = @{ message = $content } }
    return @{ status = $status; body = $payload }
}

function Wait-Http([string]$url, [int]$timeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        $statusText = & curl.exe --silent --output NUL --write-out '%{http_code}' --max-time 3 $url 2>$null
        $status = 0
        [int]::TryParse(($statusText | Select-Object -Last 1), [ref]$status) | Out-Null
        if ($status -ge 100 -and $status -lt 500) { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $url"
}

function Test-ContainerHealth([string]$name) {
    $status = docker inspect --format '{{.State.Health.Status}}' $name 2>$null
    Assert-Condition ($LASTEXITCODE -eq 0) "Container $name is not present"
    Assert-Condition ($status -eq 'healthy') "Container $name is not healthy: $status"
}

function Test-Environment {
    $java = cmd /c 'java -version 2>&1'
    Assert-Condition (($java -join "`n") -match '21') "Java 21 is required; found $($java -join ' ')"
    $null = & mvn -version
    Assert-Condition ($LASTEXITCODE -eq 0) 'Maven is not available on PATH'
    $null = & node --version
    Assert-Condition ($LASTEXITCODE -eq 0) 'Node.js is not available on PATH'
    $null = & npm --version
    Assert-Condition ($LASTEXITCODE -eq 0) 'npm is not available on PATH'
    $null = docker info
    Assert-Condition ($LASTEXITCODE -eq 0) 'Docker daemon is not available'
    Test-ContainerHealth 'intellidesk-postgres'
    Test-ContainerHealth 'intellidesk-redis'
    Test-ContainerHealth 'intellidesk-rabbitmq'
    Test-ContainerHealth 'intellidesk-minio'
    Test-ContainerHealth 'intellidesk-elasticsearch'
    Wait-Http $BaseUrl
    Wait-Http $FrontendUrl
    Write-Host '[PASS] Environment health'
}

function Start-Environment {
    docker compose -f $ComposeFile up -d
    Assert-Condition ($LASTEXITCODE -eq 0) 'docker compose up failed'
    $deadline = (Get-Date).AddSeconds(120)
    do {
        try {
            Test-ContainerHealth 'intellidesk-postgres'
            Test-ContainerHealth 'intellidesk-redis'
            Test-ContainerHealth 'intellidesk-rabbitmq'
            Test-ContainerHealth 'intellidesk-minio'
            Test-ContainerHealth 'intellidesk-elasticsearch'
            break
        } catch {
            if ((Get-Date) -ge $deadline) { throw }
            Start-Sleep -Seconds 3
        }
    } while ($true)
    Write-Host '[PASS] Docker dependency stack started'
}

function Get-Status([long]$documentId) {
    $result = Invoke-JsonApi 'GET' "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId/documents/$documentId" $null $script:token
    Assert-Condition ($result.status -eq 200 -and $result.body.code -eq 0) "Document detail failed: HTTP $($result.status)"
    return $result.body.data
}

function Wait-DocumentTerminal([long]$documentId, [string]$expected = 'COMPLETED', [int]$timeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    $seen = [System.Collections.Generic.List[string]]::new()
    do {
        $detail = Get-Status $documentId
        if (-not $seen.Contains($detail.status)) { $seen.Add($detail.status) }
        if ($detail.status -eq $expected) {
            Write-Host "[PASS] Document $documentId status $expected; transitions=$($seen -join ' -> ')"
            return $detail
        }
        if ($detail.status -eq 'FAILED' -and $expected -ne 'FAILED') {
            throw "Document $documentId failed: $($detail.failureCode) $($detail.failureMessage)"
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for document $documentId to reach $expected; transitions=$($seen -join ' -> ')"
}

function Upload-Fixture([string]$name) {
    $path = Join-Path $script:fixtureRoot $name
    Assert-Condition (Test-Path $path) "Fixture not found: $path"
    $result = Invoke-Multipart "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId/documents" $path $script:token
    if ($name -eq 'invalid.pdf') {
        Assert-Condition ($result.status -in @(400, 415) -and $result.body.code -ne 0) "Invalid PDF was not rejected: HTTP $($result.status)"
        Write-Host "[PASS] Negative upload rejected $name; HTTP $($result.status) code=$($result.body.code)"
        return $null
    }
    Assert-Condition ($result.status -in @(200, 202) -and $result.body.code -eq 0) "Upload $name failed: HTTP $($result.status) message=$($result.body.message)"
    $id = [long]$result.body.data.documentId
    $script:documentIds.Add($id)
    Write-Host "[PASS] Upload $name; documentId=$id"
    return $id
}

function Setup-Data {
    $username = "e2e-wave2-$script:runId"
    $registration = Invoke-JsonApi 'POST' '/api/auth/register' @{ username = $username; password = 'Test@123456'; email = "$username@example.com"; nickname = 'Wave 2 Reviewer' }
    Assert-Condition ($registration.status -eq 200 -and $registration.body.code -eq 0) "Registration failed: HTTP $($registration.status)"
    $script:token = $registration.body.data.accessToken
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$script:token)) 'Registration did not return an access token'
    $workspace = Invoke-JsonApi 'POST' '/api/workspaces' @{ name = "Wave 2 Evidence $script:runId"; description = 'Deterministic Wave 2 evidence run' } $script:token
    Assert-Condition ($workspace.status -eq 200 -and $workspace.body.code -eq 0) "Workspace creation failed: HTTP $($workspace.status)"
    $script:workspaceId = [long]$workspace.body.data.id
    $kb = Invoke-JsonApi 'POST' "/api/workspaces/$script:workspaceId/knowledge-bases" @{ name = "wave2-review-kb-$script:runId"; description = 'Deterministic Wave 2 evidence KB'; chunkStrategy = 'RECURSIVE'; chunkSize = 500; chunkOverlap = 50 } $script:token
    Assert-Condition ($kb.status -eq 200 -and $kb.body.code -eq 0) "Knowledge Base creation failed: HTTP $($kb.status)"
    $script:knowledgeBaseId = [long]$kb.body.data.id
    @{ token = $script:token; workspaceId = $script:workspaceId; knowledgeBaseId = $script:knowledgeBaseId } |
        ConvertTo-Json | Set-Content -Path $script:stateFile -Encoding UTF8
    Write-Host "[PASS] Fresh data; workspaceId=$script:workspaceId knowledgeBaseId=$script:knowledgeBaseId"
}

function Restore-Data {
    Assert-Condition (Test-Path $script:stateFile) "State file not found: $script:stateFile"
    $state = Get-Content $script:stateFile -Raw | ConvertFrom-Json
    $script:token = $state.token
    $script:workspaceId = [long]$state.workspaceId
    $script:knowledgeBaseId = [long]$state.knowledgeBaseId
}

function Run-PositiveFlow {
    $txt = Upload-Fixture 'sample.txt'
    Wait-DocumentTerminal $txt
    $md = Upload-Fixture 'sample.md'
    Wait-DocumentTerminal $md
    $pdf = Upload-Fixture 'valid-sample.pdf'
    Wait-DocumentTerminal $pdf
    foreach ($id in @($txt, $md, $pdf)) {
        $chunks = Invoke-JsonApi 'GET' "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId/documents/$id/chunks?page=1&size=20" $null $script:token
        Assert-Condition ($chunks.status -eq 200 -and $chunks.body.code -eq 0 -and $chunks.body.data.items.Count -gt 0) "Chunks missing for document $id"
        Write-Host "[PASS] Chunks persisted for document $id; count=$($chunks.body.data.items.Count)"
    }
    Upload-Fixture 'invalid.pdf'
    Write-Host '[PASS] Permanent invalid PDF negative case observed; it is not used as FAILED-to-recovery evidence'
}

function Cleanup-Data {
    if ($script:knowledgeBaseId -and $script:workspaceId -and $script:token) {
        foreach ($id in @($script:documentIds | Sort-Object -Descending -Unique)) {
            $result = Invoke-JsonApi 'DELETE' "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId/documents/$id" $null $script:token
            if ($result.status -in @(200, 204)) { Write-Host "[PASS] Document cleanup; documentId=$id" }
            else { Write-Host "[WARN] Document cleanup returned HTTP $($result.status); documentId=$id" }
        }
        $result = Invoke-JsonApi 'DELETE' "/api/workspaces/$script:workspaceId/knowledge-bases/$script:knowledgeBaseId" $null $script:token
        if ($result.status -notin @(200, 204)) { Write-Host "[WARN] KB cleanup returned HTTP $($result.status)" }
        else { Write-Host '[PASS] Knowledge Base cleanup' }
        if ($script:workspaceId) {
            $result = Invoke-JsonApi 'DELETE' "/api/workspaces/$script:workspaceId" $null $script:token
            if ($result.status -in @(200, 204)) { Write-Host '[PASS] Workspace cleanup' }
            else { Write-Host "[WARN] Workspace cleanup returned HTTP $($result.status)" }
        }
    }
    if (Test-Path $script:stateFile) { Remove-Item $script:stateFile -Force }
    if (-not $KeepData) { Write-Host '[PASS] Cleanup attempted through the authenticated API' }
}

try {
    if ($Mode -in @('health', 'setup', 'run')) {
        if ($Mode -eq 'setup') { Start-Environment }
        Test-Environment
        if ($Mode -eq 'health' -or $Mode -eq 'setup') { exit 0 }
    }
    if ($Mode -eq 'cleanup') { Restore-Data; Cleanup-Data; exit 0 }
    Setup-Data
    Run-PositiveFlow
    Write-Host 'Wave 2 evidence run completed. This is implementation-side evidence only.'
} catch {
    Write-Error $_
    exit 1
} finally {
    if ($Mode -eq 'run' -and -not $KeepData) { Cleanup-Data }
}
