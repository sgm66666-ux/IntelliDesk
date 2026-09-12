# HTTP Contract Test
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

function json-val2($json, $key1, $key2) {
    $obj = ($json | ConvertFrom-Json).$key1
    if ($null -eq $obj) { return "" }
    return $obj.$key2
}

function db-query($sql) {
    $result = ($sql | docker exec -i intellidesk-postgres psql -U postgres -d intellidesk -t -A 2>&1)
    return ($result -join "`n").Trim()
}

# Register users
Write-Host "=== Setup ==="
$userA = "http_test_$suffix"
$body = @{username=$userA; password="Test123456"; email="$userA@test.com"} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/auth/register" $body
$tokenA = json-val2 $r.body "data" "accessToken"

# Create WS + KB
$body = @{name="HTTP Test $suffix"} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces" $body $tokenA
$wsId = json-val2 $r.body "data" "id"

$body = @{name="HTTP Test KB $suffix"; chunkStrategy="RECURSIVE"; chunkSize=500; chunkOverlap=50} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces/$wsId/knowledge-bases" $body $tokenA
$kbId = json-val2 $r.body "data" "id"

# Create conv
$body = @{title="HTTP Test Conv $suffix"} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces/$wsId/conversations" $body $tokenA
$convId = json-val2 $r.body "data" "id"

# Test 1: 409 CHAT_CONVERSATION_BUSY
Write-Host ""
Write-Host "=== Test 1: 409 CHAT_CONVERSATION_BUSY ==="
$maxSeq = db-query "SELECT COALESCE(MAX(sequence_no), 0) FROM chat_message WHERE conversation_id = $convId"
$nextSeq = [int]$maxSeq + 1
db-query "INSERT INTO chat_message (conversation_id, role, content, status, sequence_no, created_at) VALUES ($convId, 'ASSISTANT', '', 'GENERATING', $nextSeq, NOW())"
$reqBody = @{query="test busy"; knowledgeBaseIds=@($kbId); rewriteEnabled=$false} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces/$wsId/conversations/$convId/messages/stream" $reqBody $tokenA
Write-Host "HTTP: $($r.code)"
Write-Host "Body: $($r.body)"
if ($r.code -eq 409) { Write-Host "PASS: 409 CONFLICT" -ForegroundColor Green }
else { Write-Host "FAIL: Expected 409, got $($r.code)" -ForegroundColor Red }
db-query "DELETE FROM chat_message WHERE conversation_id = $convId AND status = 'GENERATING'"

# Test 2: 404 Cross-workspace KB
Write-Host ""
Write-Host "=== Test 2: 404 Cross-workspace KB ==="
$body = @{name="HTTP Test WS2 $suffix"} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces" $body $tokenA
$ws2Id = json-val2 $r.body "data" "id"
$body = @{name="HTTP Test KB2 $suffix"; chunkStrategy="RECURSIVE"; chunkSize=500; chunkOverlap=50} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces/$ws2Id/knowledge-bases" $body $tokenA
$kb2Id = json-val2 $r.body "data" "id"
$reqBody = @{query="test"; knowledgeBaseIds=@($kb2Id); rewriteEnabled=$false} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces/$wsId/conversations/$convId/messages/stream" $reqBody $tokenA
Write-Host "HTTP: $($r.code)"
Write-Host "Body: $($r.body)"
if ($r.code -eq 404) { Write-Host "PASS: 404 NOT_FOUND" -ForegroundColor Green }
else { Write-Host "FAIL: Expected 404, got $($r.code)" -ForegroundColor Red }

Write-Host ""
Write-Host "=== Done ==="