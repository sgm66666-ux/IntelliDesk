# Phase 7 Wave 3 - Deterministic E2E seed
# Creates: user -> workspace -> KB -> indexed document -> conversation
# Outputs a JSON manifest consumed by the real-browser E2E.
$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"

function api($method, $path, $body, $token) {
    $tmpFile = $null
    $argList = [System.Collections.ArrayList]@("-s", "-w", "`n%{http_code}", "-X", $method)
    if ($null -ne $body) {
        $tmpFile = [System.IO.Path]::GetTempFileName()
        [System.IO.File]::WriteAllText($tmpFile, $body, [System.Text.UTF8Encoding]::new($false))
        [void]$argList.Add("-d")
        [void]$argList.Add("@$tmpFile")
    }
    [void]$argList.Add("-H")
    [void]$argList.Add("Content-Type: application/json")
    if ($token) {
        [void]$argList.Add("-H")
        [void]$argList.Add("Authorization: Bearer $token")
    }
    [void]$argList.Add($path)
    $result = & curl.exe $argList 2>&1
    if ($tmpFile) { Remove-Item $tmpFile -Force -ErrorAction SilentlyContinue }
    $text = $result -join "`n"
    $lines = $text -split "`n"
    $httpCode = $lines[-1].Trim()
    $respBody = ($lines[0..($lines.Count - 2)] -join "`n")
    return @{ body = $respBody; code = [int]$httpCode }
}

function api-upload($path, $filePath, $token) {
    $result = & curl.exe -s -w "`n%{http_code}" -X POST -H "Authorization: Bearer $token" -F "file=@$filePath" $path 2>&1
    $text = $result -join "`n"
    $lines = $text -split "`n"
    return @{ body = ($lines[0..($lines.Count - 2)] -join "`n"); code = [int]$lines[-1].Trim() }
}

function pick($json, $key) {
    try { return (($json | ConvertFrom-Json).data.$key) } catch { return $null }
}

$suffix = (Get-Random)
$username = "w3seed_$suffix"

Write-Host "== 1. register =="
$r = api "POST" "$base/api/auth/register" ('{"username":"' + $username + '","password":"Test123456","email":"' + $username + '@test.com"}') $null
if ($r.code -ne 200) { throw "register failed: $($r.code) $($r.body)" }
$token = pick $r.body "accessToken"
Write-Host "   token ok"

Write-Host "== 2. workspace =="
$r = api "POST" "$base/api/workspaces" '{"name":"W3 E2E Seed","description":"deterministic"} ' $token
$workspaceId = pick $r.body "id"
Write-Host "   ws=$workspaceId"

Write-Host "== 3. KB =="
$r = api "POST" "$base/api/workspaces/$workspaceId/knowledge-bases" '{"name":"W3 KB","chunkStrategy":"RECURSIVE","chunkSize":500,"chunkOverlap":50}' $token
$kbId = pick $r.body "id"
Write-Host "   kb=$kbId"

Write-Host "== 4. upload doc =="
$docPath = [System.IO.Path]::GetTempFileName() + ".txt"
$docContent = "Beijing travel budget: the daily allowance is capped at 600 yuan. Shanghai travel budget: the daily allowance is capped at 650 yuan. Tokyo travel budget: the daily allowance is capped at 12000 yen."
[System.IO.File]::WriteAllText($docPath, $docContent, [System.Text.UTF8Encoding]::new($false))
$r = api-upload "$base/api/workspaces/$workspaceId/knowledge-bases/$kbId/documents" $docPath $token
Remove-Item $docPath -Force -ErrorAction SilentlyContinue
$docId = pick $r.body "documentId"
Write-Host "   doc=$docId"

Write-Host "== 5. wait READY =="
$status = "PENDING"
for ($i = 0; $i -lt 150; $i++) {
    $r = api "GET" "$base/api/workspaces/$workspaceId/knowledge-bases/$kbId/documents/$docId" $null $token
    $status = pick $r.body "status"
    if ($status -eq "COMPLETED") { break }
    Start-Sleep -Seconds 1
}
if ($status -ne "COMPLETED") { throw "doc not ready: $status" }
Start-Sleep -Seconds 3
Write-Host "   doc COMPLETED"

Write-Host "== 6. conversation =="
$r = api "POST" "$base/api/workspaces/$workspaceId/conversations" '{"title":"W3 E2E Conversation"}' $token
$conversationId = pick $r.body "id"
Write-Host "   conv=$conversationId"

$manifest = @{
    username     = $username
    password     = "Test123456"
    workspaceId  = $workspaceId
    knowledgeBaseId = $kbId
    conversationId = $conversationId
} | ConvertTo-Json -Compress
Write-Host "MANIFEST:$manifest"