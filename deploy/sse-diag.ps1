# SSE Diagnostic - targeted smoke test
$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$suffix = (Get-Random)

function api($method, $path, $body, $token) {
    $tmpFile = $null
    $argList = [System.Collections.ArrayList]@("-s", "-w", "`n%{http_code}", "-X", $method)
    if ($body) {
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
    $respBody = ($lines[0..($lines.Count-2)] -join "`n")
    return @{body=$respBody; code=[int]$httpCode}
}

function api-upload($path, $filePath, $token) {
    $result = & curl.exe -s -w "`n%{http_code}" -X POST -H "Authorization: Bearer $token" -F "file=@$filePath" $path 2>&1
    $text = $result -join "`n"
    $lines = $text -split "`n"
    $httpCode = $lines[-1].Trim()
    $respBody = ($lines[0..($lines.Count-2)] -join "`n")
    return @{body=$respBody; code=[int]$httpCode}
}

function json-val2($json, $key1, $key2) {
    $obj = ($json | ConvertFrom-Json).$key1
    if ($null -eq $obj) { return "" }
    return $obj.$key2
}

function db-query($sql) {
    $result = ($sql | docker exec -i intellidesk-postgres psql -U postgres -d intellidesk -t -A 2>&1)
    return ($result -join "`n").Trim()
}

Write-Host "=== 1. Register user ==="
$user = "sse_diag_$suffix"
$body = @{username=$user; password="Test123456"; email="$user@test.com"} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/auth/register" $body
Write-Host "Register: $($r.code)"
$token = json-val2 $r.body "data" "accessToken"
Write-Host "Token: $($token.Substring(0,20))..."

Write-Host ""
Write-Host "=== 2. Create Workspace ==="
$body = @{name="SSE Diag $suffix"} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces" $body $token
$wsId = json-val2 $r.body "data" "id"
Write-Host "WS: $wsId"

Write-Host ""
Write-Host "=== 3. Create KB ==="
$body = @{name="SSE Diag KB $suffix"; chunkStrategy="RECURSIVE"; chunkSize=500; chunkOverlap=50} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces/$wsId/knowledge-bases" $body $token
$kbId = json-val2 $r.body "data" "id"
Write-Host "KB: $kbId"

Write-Host ""
Write-Host "=== 4. Upload document ==="
$docContent = "# Travel Standard`nBeijing: max 600 yuan`nShanghai: max 650 yuan"
$docPath = [System.IO.Path]::GetTempFileName() + ".txt"
[System.IO.File]::WriteAllText($docPath, $docContent, [System.Text.UTF8Encoding]::new($false))
$r = api-upload "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents" $docPath $token
$docId = json-val2 $r.body "data" "documentId"
Write-Host "Doc: $docId"

Write-Host ""
Write-Host "=== 5. Wait for document READY ==="
for ($i = 0; $i -lt 120; $i++) {
    $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$docId" $null $token
    $status = json-val2 $r.body "data" "status"
    if ($status -eq "COMPLETED") { break }
    Write-Host "  doc: $status (${i}s)"
    Start-Sleep -Seconds 1
}
Start-Sleep -Seconds 5
$taskStatus = db-query "SELECT status FROM document_retrieval_task WHERE document_id = $docId"
Write-Host "  retrieval task: $taskStatus"

Write-Host ""
Write-Host "=== 6. Create Conversation ==="
$body = @{title="SSE Diag Conv $suffix"} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces/$wsId/conversations" $body $token
$convId = json-val2 $r.body "data" "id"
Write-Host "Conv: $convId"

Write-Host ""
Write-Host "=== 7. SSE Chat Request (DIAGNOSTIC) ==="
$reqBody = @{query="Beijing accommodation standard"; knowledgeBaseIds=@($kbId); rewriteEnabled=$false} | ConvertTo-Json -Compress
$tmpFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmpFile, $reqBody, [System.Text.UTF8Encoding]::new($false))
$raw = & curl.exe -s -N --max-time 60 -D - -X POST -H "Content-Type: application/json" -H "Authorization: Bearer $token" -H "Accept: text/event-stream" -d "@$tmpFile" "$base/api/workspaces/$wsId/conversations/$convId/messages/stream" 2>&1
Remove-Item $tmpFile -Force -ErrorAction SilentlyContinue
$text = $raw -join "`n"

$headerEnd = $text.IndexOf("`r`n`r`n")
if ($headerEnd -ge 0) {
    $headers = $text.Substring(0, $headerEnd)
    $body = $text.Substring($headerEnd + 4)
} else {
    $headers = ""
    $body = $text
}

Write-Host "--- SSE DIAG ---"
Write-Host "HTTP headers:"
$headers.Split("`r`n") | ForEach-Object { Write-Host "  $_" }
Write-Host "Raw body length: $($body.Length) chars"
$preview = $body.Substring(0, [Math]::Min(500, $body.Length))
Write-Host "Raw body first 500 chars:"
Write-Host $preview
Write-Host "--- END DIAG ---"

Write-Host ""
Write-Host "=== SSE Event Analysis ==="
$startCount = ($body | Select-String -Pattern '"type":"start"' -AllMatches).Matches.Count
$tokenCount = ($body | Select-String -Pattern '"type":"token"' -AllMatches).Matches.Count
$doneCount = ($body | Select-String -Pattern '"type":"done"' -AllMatches).Matches.Count
$errorCount = ($body | Select-String -Pattern '"type":"error"' -AllMatches).Matches.Count
Write-Host "start events: $startCount"
Write-Host "token events: $tokenCount"
Write-Host "done events: $doneCount"
Write-Host "error events: $errorCount"

if ($body -match '"code"') {
    Write-Host ""
    Write-Host "WARNING: Response contains 'code' - likely JSON error, not SSE stream!"
    Write-Host "Full body: $body"
}