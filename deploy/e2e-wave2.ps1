$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$username = "e2euser_$ts"
$password = 'Password123'

function InvokeApi($method, $path, $body = $null, $token = $null) {
    $uri = "$base$path"
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{ Uri = $uri; Method = $method }
    if ($headers.Count -gt 0) { $params['Headers'] = $headers }
    if ($body) { $params['ContentType'] = 'application/json'; $params['Body'] = ($body | ConvertTo-Json -Depth 5) }
    try {
        return Invoke-RestMethod @params
    } catch {
        $err = $_
        $msg = $err.ErrorDetails.Message
        if (-not $msg) { $msg = $err.Exception.Message }
        try { return $msg | ConvertFrom-Json } catch { return @{ code = -1; message = $msg } }
    }
}

function UploadFile($path, $token, $wsId, $kbId) {
    $uri = "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents"
    $raw = curl.exe -s -H "Authorization: Bearer $token" -F "file=@$path" -w "`n%{http_code}" $uri
    $lines = $raw -split "`n"
    $http = $lines[-1]
    $json = ($lines[0..($lines.Length - 2)] -join "`n").Trim()
    try {
        $obj = $json | ConvertFrom-Json
    } catch {
        $obj = @{ code = -1; message = $json }
    }
    return @{ http = $http; response = $obj }
}

function Assert($label, $condition, $detail) {
    if ($condition) { Write-Host "[PASS] $label" -ForegroundColor Green }
    else { Write-Host "[FAIL] $label : $detail" -ForegroundColor Red; throw "E2E failed: $label" }
}

# 1. Register & login
$reg = InvokeApi 'POST' '/api/auth/register' @{ username = $username; password = $password; email = "$username@example.com"; nickname = 'E2E User' }
Assert 'Register' ($reg.code -eq 0) "code=$($reg.code)"
$token = $reg.data.accessToken
$userId = $reg.data.userId

# 2. Create workspace
$ws = InvokeApi 'POST' '/api/workspaces' @{ name = "E2E WS $ts"; description = 'wave2' } $token
Assert 'Create workspace' ($ws.code -eq 0) "code=$($ws.code)"
$wsId = $ws.data.id

# 3. Create knowledge base
$kb = InvokeApi 'POST' "/api/workspaces/$wsId/knowledge-bases" @{ name = "E2E KB $ts"; description = 'wave2'; chunkStrategy = 'RECURSIVE'; chunkSize = 500; chunkOverlap = 50 } $token
Assert 'Create knowledge base' ($kb.code -eq 0) "code=$($kb.code)"
$kbId = $kb.data.id

# 4. Prepare files
$txtPath = "$env:TEMP\sample.txt"
$mdPath = "$env:TEMP\sample.md"
$emptyPath = "$env:TEMP\empty.txt"
$exePath = "$env:TEMP\setup.exe"
'IntelliDesk Wave 2 upload test content with unicode 🚀' | Out-File -FilePath $txtPath -Encoding utf8 -Force
'# Markdown heading' | Out-File -FilePath $mdPath -Encoding utf8 -Force
if (Test-Path $emptyPath) { Remove-Item $emptyPath -Force }; $null = New-Item -Path $emptyPath -ItemType File
[IO.File]::WriteAllBytes($exePath, [byte[]]@(0x4D, 0x5A, 0x00, 0x00))

# 5. Upload TXT
$upload = UploadFile $txtPath $token $wsId $kbId
Assert 'Upload TXT' ($upload.http -in 200,202 -and $upload.response.code -eq 0 -and $upload.response.data.status -eq 'PENDING') "http=$($upload.http), code=$($upload.response.code), status=$($upload.response.data.status)"
$docId = $upload.response.data.documentId
$objectKeyPattern = "workspaces/$wsId/knowledge-bases/$kbId/documents/$docId/source.txt"

# 6. Upload MD
$uploadMd = UploadFile $mdPath $token $wsId $kbId
Assert 'Upload MD' ($uploadMd.http -in 200,202 -and $uploadMd.response.code -eq 0 -and $uploadMd.response.data.status -eq 'PENDING') "http=$($uploadMd.http), code=$($uploadMd.response.code)"

# 7. List documents
$list = InvokeApi 'GET' "/api/workspaces/$wsId/knowledge-bases/$kbId/documents?page=1&size=10" $null $token
Assert 'List documents' ($list.code -eq 0 -and $list.data.items.Count -eq 2) "code=$($list.code), count=$($list.data.items.Count)"

# 8. Detail document
$detail = InvokeApi 'GET' "/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$docId" $null $token
Assert 'Detail document' ($detail.code -eq 0 -and $detail.data.id -eq $docId -and $detail.data.status -eq 'PENDING') "code=$($detail.code)"

# 9. Duplicate checksum
$dup = UploadFile $txtPath $token $wsId $kbId
Assert 'Duplicate checksum rejected' ($dup.response.code -eq 4005) "http=$($dup.http), code=$($dup.response.code)"

# 10. Unsupported type
$bad = UploadFile $exePath $token $wsId $kbId
Assert 'Unsupported type rejected' ($bad.response.code -eq 4002) "http=$($bad.http), code=$($bad.response.code)"

# 11. Empty file
$empty = UploadFile $emptyPath $token $wsId $kbId
Assert 'Empty file rejected' ($empty.response.code -eq 4003) "http=$($empty.http), code=$($empty.response.code)"

# 12. Verify MinIO object
$minioCheck = docker exec intellidesk-minio sh -c "ls /data/intellidesk-documents/$objectKeyPattern 2>/dev/null || echo NOT_FOUND"
Assert 'MinIO object exists' ($minioCheck -ne 'NOT_FOUND') "objectKey=$objectKeyPattern, output=$minioCheck"

# 13. Verify DB document state
$pgDoc = docker exec -e PGPASSWORD=change_me intellidesk-postgres psql -U postgres -d intellidesk -t -A -c "SELECT id,status,object_key FROM document WHERE id=$docId"
Assert 'DB document PENDING' ($pgDoc -like "*$docId*PENDING*") "row=$pgDoc"

# 14. Verify DB task state
$pgTask = docker exec -e PGPASSWORD=change_me intellidesk-postgres psql -U postgres -d intellidesk -t -A -c "SELECT document_id,status,task_type FROM document_index_task WHERE document_id=$docId"
Assert 'DB task PENDING' ($pgTask -like "*$docId*PENDING*PARSE_AND_CHUNK*") "row=$pgTask"

Write-Host "`nWave 2 E2E completed successfully." -ForegroundColor Cyan
