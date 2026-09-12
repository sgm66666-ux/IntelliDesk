[CmdletBinding()]
# TG-03 independent acceptance: verify Markdown + valid PDF chunk CONTENT, and invalid PDF negative.
# Uses real backend, real fixtures, fresh data, authenticated API, cleanup.
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$runId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
$fixtureRoot = Join-Path $PSScriptRoot '..\backend\src\test\resources\fixtures\document'
$token = $null; $workspaceId = $null; $knowledgeBaseId = $null
$documentIds = [System.Collections.Generic.List[long]]::new()

function Assert-Condition([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }

function Invoke-JsonApi([string]$method, [string]$path, [object]$body = $null, [string]$token = $null) {
    $dataFile = $null
    $arguments = @('--silent', '--show-error', '--request', $method, '--write-out', "`n%{http_code}", '--max-time', '30')
    if ($token) { $arguments += @('-H', "Authorization: Bearer $token") }
    if ($null -ne $body) {
        $dataFile = Join-Path $env:TEMP ("intellidesk-tg03-" + [guid]::NewGuid().ToString('N') + '.json')
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

function Invoke-Upload([string]$path, [string]$token) {
    $raw = & curl.exe --silent --show-error --request POST -H "Authorization: Bearer $token" -F "file=@$path" -w "`n%{http_code}" "$BaseUrl/api/workspaces/$workspaceId/knowledge-bases/$knowledgeBaseId/documents" 2>&1
    $lines = @($raw -split "`n")
    $status = [int]$lines[-1].Trim()
    $content = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
    try { $payload = $content | ConvertFrom-Json } catch { $payload = @{ message = $content } }
    return @{ status = $status; body = $payload }
}

function Get-Doc([long]$id) {
    $d = Invoke-JsonApi 'GET' "/api/workspaces/$workspaceId/knowledge-bases/$knowledgeBaseId/documents/$id" $null $token
    Assert-Condition ($d.status -eq 200 -and $d.body.code -eq 0) "Detail failed HTTP $($d.status)"
    return $d.body.data
}

function Wait-Terminal([long]$id, [int]$timeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    $seen = [System.Collections.Generic.List[string]]::new()
    do {
        $d = Get-Doc $id
        if (-not $seen.Contains($d.status)) { $seen.Add($d.status) }
        if ($d.status -eq 'COMPLETED') { Write-Host "[PASS] doc=$id COMPLETED; transitions=$($seen -join ' -> ')"; return $d }
        if ($d.status -eq 'FAILED') { throw "doc=$id FAILED: $($d.failureCode) $($d.failureMessage)" }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Timed out doc=$id; transitions=$($seen -join ' -> ')"
}

function Get-ChunkText([long]$id) {
    $c = Invoke-JsonApi 'GET' "/api/workspaces/$workspaceId/knowledge-bases/$knowledgeBaseId/documents/$id/chunks?page=1&size=50" $null $token
    Assert-Condition ($c.status -eq 200 -and $c.body.code -eq 0) "Chunks failed HTTP $($c.status)"
    $text = ($c.body.data.items | ForEach-Object { $_.content }) -join "`n"
    return $text
}

try {
    # fresh user / workspace / KB
    $u = "tg03-$runId"
    $reg = Invoke-JsonApi 'POST' '/api/auth/register' @{ username = $u; password = 'Test@123456'; email = "$u@example.com"; nickname = 'TG03 Evidence' }
    Assert-Condition ($reg.status -eq 200 -and $reg.body.code -eq 0) "Register failed HTTP $($reg.status)"
    $token = $reg.body.data.accessToken
    $ws = Invoke-JsonApi 'POST' '/api/workspaces' @{ name = "TG03 Evidence $runId" } $token
    Assert-Condition ($ws.status -eq 200 -and $ws.body.code -eq 0) "Workspace failed"
    $workspaceId = [long]$ws.body.data.id
    $kb = Invoke-JsonApi 'POST' "/api/workspaces/$workspaceId/knowledge-bases" @{ name = "tg03-kb-$runId"; chunkStrategy = 'RECURSIVE'; chunkSize = 500; chunkOverlap = 50 } $token
    Assert-Condition ($kb.status -eq 200 -and $kb.body.code -eq 0) "KB failed"
    $knowledgeBaseId = [long]$kb.body.data.id
    Write-Host "[PASS] Fresh data; workspaceId=$workspaceId knowledgeBaseId=$knowledgeBaseId"

    # Markdown positive
    $mdPath = Join-Path $fixtureRoot 'sample.md'
    $mdUp = Invoke-Upload $mdPath $token
    Assert-Condition ($mdUp.status -in @(200, 202) -and $mdUp.body.code -eq 0) "MD upload failed HTTP $($mdUp.status)"
    $mdId = [long]$mdUp.body.data.documentId
    $documentIds.Add($mdId)
    $mdDetail = Wait-Terminal $mdId
    Assert-Condition ($mdDetail.parserMetadata.parserFormat -eq 'MARKDOWN') "Expected MARKDOWN got $($mdDetail.parserMetadata.parserFormat)"
    $mdText = Get-ChunkText $mdId
    # Extract the expected marker from the fixture at RUNTIME (explicit UTF-8), located by an ASCII
    # prefix, so this .ps1 never embeds a Chinese literal that PS 5.1 would ANSI-mis-decode.
    $mdFixtureText = [System.IO.File]::ReadAllText($mdPath, [System.Text.Encoding]::UTF8)
    $mdMarkerLine = ($mdFixtureText -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_.StartsWith('IntelliDesk') } | Select-Object -First 1)
    Assert-Condition ($null -ne $mdMarkerLine -and $mdMarkerLine.Length -ge 14) "Cannot locate IntelliDesk line in sample.md"
    $mdMarker = $mdMarkerLine.Substring(0, 14)
    Assert-Condition ($mdText.Contains($mdMarker)) "Markdown chunk missing expected text '$mdMarker'; got: $mdText"
    Write-Host "[PASS] Markdown parserFormat=MARKDOWN; chunk text contains expected heading/content"

    # Valid PDF positive
    $pdfPath = Join-Path $fixtureRoot 'valid-sample.pdf'
    $pdfUp = Invoke-Upload $pdfPath $token
    Assert-Condition ($pdfUp.status -in @(200, 202) -and $pdfUp.body.code -eq 0) "PDF upload failed HTTP $($pdfUp.status)"
    $pdfId = [long]$pdfUp.body.data.documentId
    $documentIds.Add($pdfId)
    $pdfDetail = Wait-Terminal $pdfId
    Assert-Condition ($pdfDetail.parserMetadata.parserFormat -eq 'PDF') "Expected PDF got $($pdfDetail.parserMetadata.parserFormat)"
    Assert-Condition ([int]$pdfDetail.parserMetadata.pageCount -ge 1) "pageCount invalid: $($pdfDetail.parserMetadata.pageCount)"
    $pdfText = Get-ChunkText $pdfId
    Assert-Condition ($pdfText -match 'IntelliDesk Wave 2 PDF Fixture') "PDF chunk missing fixed text; got: $pdfText"
    Assert-Condition ($pdfText -match 'Document parsing verification') "PDF chunk missing second line; got: $pdfText"
    Write-Host "[PASS] Valid PDF parserFormat=PDF pageCount=$($pdfDetail.parserMetadata.pageCount); chunk text contains both fixed lines"

    # Invalid PDF negative
    $badPath = Join-Path $fixtureRoot 'invalid.pdf'
    $bad = Invoke-Upload $badPath $token
    Write-Host "Invalid PDF -> HTTP $($bad.status) code=$($bad.body.code) message=$($bad.body.message)"
    Assert-Condition ($bad.status -eq 415 -and $bad.body.code -eq 4002) "Expected HTTP 415 code 4002, got HTTP $($bad.status) code $($bad.body.code)"
    Write-Host '[PASS] invalid.pdf rejected HTTP 415 / code 4002'

    Write-Host ''
    Write-Host 'RESULT: TG-03 INDEPENDENT EVIDENCE PASS'
} catch {
    Write-Host "[ERROR] $_"
    exit 1
} finally {
    # cleanup
    foreach ($id in @($documentIds | Sort-Object -Descending -Unique)) {
        $r = Invoke-JsonApi 'DELETE' "/api/workspaces/$workspaceId/knowledge-bases/$knowledgeBaseId/documents/$id" $null $token
        if ($r.status -in @(200, 204)) { Write-Host "[PASS] cleanup document $id" }
    }
    if ($knowledgeBaseId) {
        $r = Invoke-JsonApi 'DELETE' "/api/workspaces/$workspaceId/knowledge-bases/$knowledgeBaseId" $null $token
        if ($r.status -in @(200, 204)) { Write-Host '[PASS] cleanup KB' }
    }
    if ($workspaceId) {
        $r = Invoke-JsonApi 'DELETE' "/api/workspaces/$workspaceId" $null $token
        if ($r.status -in @(200, 204)) { Write-Host '[PASS] cleanup workspace' }
    }
}
