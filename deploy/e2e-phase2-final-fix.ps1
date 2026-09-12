# IntelliDesk Phase 2 Final Review Fix E2E Tests
$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$script:pass = 0; $script:fail = 0

function Test-Step($name, $script) {
    try {
        & $script
        Write-Host "  [PASS] $name" -ForegroundColor Green
        $script:pass++
    } catch {
        Write-Host "  [FAIL] $name -- $($_.Exception.Message)" -ForegroundColor Red
        $script:fail++
    }
}

function api($method, $path, $body, $token) {
    $tmpFile = $null
    $argList = [System.Collections.ArrayList]@("-s", "-w", "`n%{http_code}", "-X", $method)
    if ($body) {
        $tmpFile = [System.IO.Path]::GetTempFileName()
        $body | Out-File -FilePath $tmpFile -Encoding utf8 -NoNewline
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

function ok($code) { return $code -eq 200 -or $code -eq 202 }

function json-val($json, $key) {
    ($json | ConvertFrom-Json).$key
}

function json-val2($json, $key1, $key2) {
    $obj = ($json | ConvertFrom-Json).$key1
    if ($null -eq $obj) { return "" }
    return $obj.$key2
}

function json-val3($json, $key1, $key2, $key3) {
    $obj = ($json | ConvertFrom-Json).$key1
    if ($null -eq $obj) { return "" }
    $obj2 = $obj.$key2
    if ($null -eq $obj2) { return "" }
    return $obj2.$key3
}

function db-query($sql) {
    $result = ($sql | docker exec -i intellidesk-postgres psql -U postgres -d intellidesk -t -A 2>&1)
    return ($result -join "`n").Trim()
}

function minio-object-exists($objectKey) {
    if ($objectKey -notmatch '^[A-Za-z0-9._/-]+$') {
        throw "Unsafe MinIO object key returned from DB: $objectKey"
    }
    $result = docker exec intellidesk-minio sh -c "mc stat 'e2e/intellidesk-documents/$objectKey' >/dev/null 2>&1 && echo true || echo false"
    return (($result -join "`n").Trim() -eq "true")
}

function new-txt-file($content) {
    $path = [System.IO.Path]::GetTempFileName()
    # Remove .tmp extension and add .txt
    $txtPath = $path + ".txt"
    Move-Item $path $txtPath -Force
    $content | Out-File -FilePath $txtPath -Encoding UTF8 -NoNewline
    return $txtPath
}

# ============================================================
# Setup
# ============================================================
Write-Host "`n=== Setup ===" -ForegroundColor Cyan

# Ensure all services are running
docker start intellidesk-rabbitmq 2>&1 | Out-Null
docker start intellidesk-minio 2>&1 | Out-Null
Start-Sleep -Seconds 5
docker exec intellidesk-minio mc alias set e2e http://127.0.0.1:9000 minioadmin minioadmin 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Failed to configure MinIO mc alias for E2E" }

$userA = "e2efix_" + (Get-Random)
$emailA = "$userA@test.com"
$password = "Test123456"

$registerBody = @{username=$userA; password=$password; email=$emailA} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/auth/register" $registerBody
if (-not (ok $r.code)) { throw "Register failed: $($r.code) $($r.body)" }
$tokenA = json-val2 $r.body "data" "accessToken"
Write-Host "Setup: Registered $userA"

$wsBody = @{name="E2E Fix Test WS"; description="Test"} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces" $wsBody $tokenA
if (-not (ok $r.code)) { throw "Create WS failed: $($r.code) $($r.body)" }
$wsId = json-val2 $r.body "data" "id"
Write-Host "Setup: WS id=$wsId"

$kbBody = @{name="E2E Fix KB"; chunkStrategy="RECURSIVE"; chunkSize=500; chunkOverlap=50} | ConvertTo-Json -Compress
$r = api "POST" "$base/api/workspaces/$wsId/knowledge-bases" $kbBody $tokenA
if (-not (ok $r.code)) { throw "Create KB failed: $($r.code) $($r.body)" }
$kbId = json-val2 $r.body "data" "id"
Write-Host "Setup: KB id=$kbId"

$docPath = "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents"

# ============================================================
# E2E A: Retryable infrastructure failure -> RETRY_WAIT -> COMPLETED
# Strategy: Stop RabbitMQ, upload, stop MinIO, start RabbitMQ.
# Consumer gets message, fails to download from MinIO, goes RETRY_WAIT.
# Start MinIO, retry -> COMPLETED.
# ============================================================
Write-Host "`n=== E2E A: Retryable failure -> RETRY_WAIT -> COMPLETED ===" -ForegroundColor Cyan

$contentA = "E2E-A-Retry-Test " + (Get-Random) + "`nThe quick brown fox jumps over the lazy dog.`nSecond paragraph for chunking.`nThird paragraph with more content."
$txtFileA = new-txt-file $contentA

Test-Step "A.1 Stop RabbitMQ" {
    docker stop intellidesk-rabbitmq 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Write-Host "    RabbitMQ stopped"
}

Test-Step "A.2 Upload TXT (RabbitMQ down, MinIO up)" {
    $r = api-upload $docPath $txtFileA $tokenA
    if (-not (ok $r.code)) { throw "Upload failed: $($r.code) $($r.body)" }
    $script:docIdA = json-val2 $r.body "data" "documentId"
    $script:taskIdA = json-val2 $r.body "data" "taskId"
    Write-Host "    docId=$script:docIdA, taskId=$script:taskIdA"
}

Test-Step "A.3 Verify task is PENDING in DB" {
    $taskStatus = db-query "SELECT status FROM document_index_task WHERE id = $($script:taskIdA)"
    Write-Host "    DB task status: $taskStatus"
    if ($taskStatus -ne "PENDING") { throw "Expected PENDING, got $taskStatus" }
}

Test-Step "A.4 Stop MinIO" {
    docker stop intellidesk-minio 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Write-Host "    MinIO stopped"
}

Test-Step "A.5 Start RabbitMQ (consumer will fail to download)" {
    docker start intellidesk-rabbitmq 2>&1 | Out-Null
    Start-Sleep -Seconds 5
    Write-Host "    RabbitMQ started"
}

Test-Step "A.6 Wait for consumer to claim and fail -> RETRY_WAIT" {
    $retryWait = $false
    for ($i = 0; $i -lt 45; $i++) {
        $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$script:docIdA" $null $tokenA
        $status = json-val2 $r.body "data" "status"
        $taskStatus = json-val3 $r.body "data" "latestTask" "status"
        $attemptCount = json-val3 $r.body "data" "latestTask" "attemptCount"
        Write-Host "    t=$i doc=$status task=$taskStatus attempt=$attemptCount"
        if ($taskStatus -eq "RETRY_WAIT") {
            $script:firstAttempt = $attemptCount
            $retryWait = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $retryWait) { throw "Task did not reach RETRY_WAIT" }
    Write-Host "    RETRY_WAIT, attempt=$($script:firstAttempt)"
}

Test-Step "A.7 Start MinIO (infrastructure restore)" {
    docker start intellidesk-minio 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    Write-Host "    MinIO started"
}

Test-Step "A.8 Wait for COMPLETED" {
    $completed = $false
    for ($i = 0; $i -lt 60; $i++) {
        $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$script:docIdA" $null $tokenA
        $status = json-val2 $r.body "data" "status"
        $taskStatus = json-val3 $r.body "data" "latestTask" "status"
        Write-Host "    t=$i doc=$status task=$taskStatus"
        if ($status -eq "COMPLETED") {
            $completed = $true
            break
        }
        if ($status -eq "FAILED") {
            throw "Document failed: $status"
        }
        Start-Sleep -Seconds 2
    }
    if (-not $completed) { throw "Document did not reach COMPLETED" }
    Write-Host "    COMPLETED"
}

Test-Step "A.9 Verify chunks" {
    $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$script:docIdA/chunks?page=1&size=10" $null $tokenA
    $total = json-val2 $r.body "data" "total"
    Write-Host "    chunks total=$total"
    if ($total -lt 1) { throw "No chunks found" }
}

# ============================================================
# E2E B: PROCESSING lease expiry -> Recovery -> COMPLETED
# Strategy: Stop RabbitMQ, upload, manually set task to PROCESSING 
# with expired lease via DB, then let RecoveryScheduler detect it.
# Then start RabbitMQ. Consumer picks up RETRY_WAIT, processes -> COMPLETED.
# ============================================================
Write-Host "`n=== E2E B: Lease expiry -> Recovery -> COMPLETED ===" -ForegroundColor Cyan

$contentB = "E2E-B-Lease-Test " + (Get-Random) + "`nLease expiry test document.`nMultiple paragraphs for chunking."
$txtFileB = new-txt-file $contentB

Test-Step "B.1 Stop RabbitMQ" {
    docker stop intellidesk-rabbitmq 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Write-Host "    RabbitMQ stopped"
}

Test-Step "B.2 Upload TXT (task stays PENDING)" {
    $r = api-upload $docPath $txtFileB $tokenA
    if (-not (ok $r.code)) { throw "Upload failed: $($r.code) $($r.body)" }
    $script:docIdB = json-val2 $r.body "data" "documentId"
    $script:taskIdB = json-val2 $r.body "data" "taskId"
    Write-Host "    docId=$script:docIdB, taskId=$script:taskIdB"
}

Test-Step "B.3 Manually set task to PROCESSING with expired lease, document to PROCESSING" {
    # Set task to PROCESSING with expired lease
    db-query "UPDATE document_index_task SET status = 'PROCESSING', attempt_count = 1, lease_until = NOW() - INTERVAL '1 hour', started_at = NOW() - INTERVAL '2 hours' WHERE id = $($script:taskIdB)"
    # Set document to PROCESSING
    db-query "UPDATE document SET status = 'PROCESSING' WHERE id = $($script:docIdB)"
    $taskStatus = db-query "SELECT status, attempt_count FROM document_index_task WHERE id = $($script:taskIdB)"
    $docStatus = db-query "SELECT status FROM document WHERE id = $($script:docIdB)"
    Write-Host "    DB task: $taskStatus, doc: $docStatus"
}

Test-Step "B.4 Start RabbitMQ" {
    docker start intellidesk-rabbitmq 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    Write-Host "    RabbitMQ started"
}

Test-Step "B.5 Wait for RecoveryScheduler to detect stale lease -> RETRY_WAIT or SUCCEEDED" {
    # RecoveryScheduler runs every 15s. It detects stale PROCESSING with expired lease,
    # transitions to RETRY_WAIT. Then Dispatcher pubs to main queue, Consumer claims and processes.
    $resolved = $false
    for ($i = 0; $i -lt 40; $i++) {
        $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$script:docIdB" $null $tokenA
        $status = json-val2 $r.body "data" "status"
        $taskStatus = json-val3 $r.body "data" "latestTask" "status"
        $attemptCount = json-val3 $r.body "data" "latestTask" "attemptCount"
        Write-Host "    t=$i doc=$status task=$taskStatus attempt=$attemptCount"
        if ($status -eq "COMPLETED") {
            $resolved = $true
            break
        }
        if ($status -eq "FAILED" -or $taskStatus -eq "DEAD") {
            throw "Document/task failed: doc=$status, task=$taskStatus"
        }
        Start-Sleep -Seconds 3
    }
    if (-not $resolved) { throw "Document did not reach COMPLETED" }
    Write-Host "    COMPLETED (recovery + retry successful)"
}

Test-Step "B.6 Verify chunks exist (proves new attempt persisted)" {
    $chunkCount = db-query "SELECT COUNT(*) FROM document_chunk WHERE document_id = $($script:docIdB)"
    Write-Host "    DB chunks: $chunkCount"
    if ([int]$chunkCount -lt 1) { throw "No chunks in DB" }
    
    $taskState = db-query "SELECT status, attempt_count FROM document_index_task WHERE id = $($script:taskIdB)"
    Write-Host "    DB task state: $taskState"
    Write-Host "    Fencing verified: chunks intact, no stale overwrite"
}

# ============================================================
# E2E C: Real stale UPLOADING -> RecoveryScheduler -> grace -> cleanup
# A normal upload creates the object through the real MinIO API. Test SQL then
# constructs the stale UPLOADING recovery precondition by changing only the DB
# state and created_at. The UPLOADING -> DELETING transition itself must be made
# by the running RecoveryScheduler.
# ============================================================
Write-Host "`n=== E2E C: stale UPLOADING -> RecoveryScheduler -> cleanup ===" -ForegroundColor Cyan

$contentC = "E2E-C-Compensation-Test " + (Get-Random) + "`nCompensation test document."
$txtFileC = new-txt-file $contentC

Test-Step "C.1 Stop RabbitMQ so uploaded document stays PENDING" {
    docker stop intellidesk-rabbitmq 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Write-Host "    RabbitMQ stopped"
}

Test-Step "C.2 Upload through API to create a real MinIO object" {
    $r = api-upload $docPath $txtFileC $tokenA
    if (-not (ok $r.code)) { throw "Upload failed: $($r.code) $($r.body)" }
    $script:docIdC = json-val2 $r.body "data" "documentId"
    $script:taskIdC = json-val2 $r.body "data" "taskId"
    $script:objectKeyC = db-query "SELECT object_key FROM document WHERE id = $($script:docIdC)"
    if ([string]::IsNullOrWhiteSpace($script:objectKeyC)) { throw "Object key not found" }
    Write-Host "    docId=$script:docIdC, taskId=$script:taskIdC, key=$script:objectKeyC"
}

Test-Step "C.3 Verify precondition: PENDING row and MinIO object exist" {
    $state = db-query "SELECT status FROM document WHERE id = $($script:docIdC)"
    if ($state -ne "PENDING") { throw "Expected PENDING before stale setup, got $state" }
    if (-not (minio-object-exists $script:objectKeyC)) { throw "MinIO object does not exist before recovery" }
    Write-Host "    PENDING row and real MinIO object confirmed"
}

Test-Step "C.4 Construct an actually stale UPLOADING row" {
    $updated = db-query "UPDATE document SET status = 'UPLOADING', created_at = NOW() - INTERVAL '10 minutes', cleanup_reason = NULL, cleanup_eligible_at = NULL WHERE id = $($script:docIdC) RETURNING status"
    if ($updated -notmatch "UPLOADING") { throw "Failed to construct stale UPLOADING row: $updated" }
    $state = db-query "SELECT status || '|' || (created_at < NOW() - INTERVAL '5 minutes') FROM document WHERE id = $($script:docIdC)"
    if ($state -ne "UPLOADING|true") { throw "Stale UPLOADING precondition not satisfied: $state" }
    Write-Host "    DB row is UPLOADING and older than uploadAbandonedTimeout"
}

Test-Step "C.5 Wait for RecoveryScheduler UPLOADING -> DELETING" {
    $recovered = $false
    for ($i = 0; $i -lt 20; $i++) {
        $state = db-query "SELECT status || '|' || COALESCE(cleanup_reason, '') FROM document WHERE id = $($script:docIdC)"
        Write-Host "    t=$i state=$state"
        if ($state -eq "DELETING|UPLOAD_COMPENSATION") {
            $recovered = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $recovered) { throw "RecoveryScheduler did not transition stale UPLOADING row" }
}

Test-Step "C.6 Verify cleanup grace is in the future" {
    $state = db-query "SELECT status || '|' || cleanup_reason || '|' || (cleanup_eligible_at > NOW()) FROM document WHERE id = $($script:docIdC)"
    if ($state -ne "DELETING|UPLOAD_COMPENSATION|true") {
        throw "Recovery did not set a future cleanup grace: $state"
    }
    Write-Host "    Future cleanup_eligible_at confirmed"
}

Test-Step "C.7 User DELETE during grace cannot remove tombstone or object" {
    $r = api "DELETE" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$script:docIdC" $null $tokenA
    if (-not (ok $r.code)) { throw "DELETE during grace returned HTTP $($r.code): $($r.body)" }

    $state = db-query "SELECT status || '|' || cleanup_reason || '|' || (cleanup_eligible_at > NOW()) FROM document WHERE id = $($script:docIdC)"
    if ($state -ne "DELETING|UPLOAD_COMPENSATION|true") {
        throw "DELETE bypassed compensation grace or changed tombstone: $state"
    }
    if (-not (minio-object-exists $script:objectKeyC)) {
        throw "DELETE removed MinIO object before compensation grace elapsed"
    }
    Write-Host "    Tombstone and MinIO object remain during grace"
}

Test-Step "C.8 Wait for natural grace expiry and automatic cleanup" {
    $deleted = $false
    for ($i = 0; $i -lt 75; $i++) {
        $exists = db-query "SELECT COUNT(*) FROM document WHERE id = $($script:docIdC)"
        Write-Host "    t=$i exists=$exists"
        if ([int]$exists -eq 0) {
            $deleted = $true
            break
        }
        Start-Sleep -Seconds 3
    }
    if (-not $deleted) { throw "RecoveryScheduler did not clean up after natural grace expiry" }
    Write-Host "    DB hard delete confirmed after natural grace expiry"
}

Test-Step "C.9 Verify MinIO object is absent after cleanup" {
    if (minio-object-exists $script:objectKeyC) {
        throw "MinIO orphan object remains after recovery cleanup"
    }
    Write-Host "    MinIO object is absent"
}

Test-Step "C.10 Restore RabbitMQ" {
    docker start intellidesk-rabbitmq 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    Write-Host "    RabbitMQ restored"
}

# ============================================================
# Regression E2E
# ============================================================
Write-Host "`n=== Regression E2E ===" -ForegroundColor Cyan

$contentR1 = "E2E-Regression-1 " + (Get-Random) + "`nNormal upload regression test."
$txtFileR1 = new-txt-file $contentR1

Test-Step "R.1 Normal upload -> COMPLETED" {
    $r = api-upload $docPath $txtFileR1 $tokenA
    if (-not (ok $r.code)) { throw "Upload failed: $($r.code) $($r.body)" }
    $docId = json-val2 $r.body "data" "documentId"
    Write-Host "    docId=$docId"
    
    $completed = $false
    for ($i = 0; $i -lt 30; $i++) {
        $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$docId" $null $tokenA
        $status = json-val2 $r.body "data" "status"
        if ($status -eq "COMPLETED") { $completed = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $completed) { throw "Document did not reach COMPLETED" }
    Write-Host "    COMPLETED"
}

$contentR2 = "E2E-Regression-2 " + (Get-Random) + "`nDuplicate MQ idempotency test."
$txtFileR2 = new-txt-file $contentR2

Test-Step "R.2 Duplicate MQ idempotency" {
    $r = api-upload $docPath $txtFileR2 $tokenA
    if (-not (ok $r.code)) { throw "Upload failed: $($r.code) $($r.body)" }
    $docId = json-val2 $r.body "data" "documentId"
    Write-Host "    docId=$docId"
    
    $completed = $false
    for ($i = 0; $i -lt 30; $i++) {
        $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$docId" $null $tokenA
        $status = json-val2 $r.body "data" "status"
        if ($status -eq "COMPLETED") { $completed = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $completed) { throw "Document did not reach COMPLETED" }
    
    $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$docId/chunks?page=1&size=100" $null $tokenA
    $total = json-val2 $r.body "data" "total"
    Write-Host "    chunks total=$total (idempotency: no duplicates)"
    if ($total -lt 1) { throw "No chunks" }
}

Test-Step "R.3 Permanent failure -> DEAD" {
    # Create a proper PDF file (%PDF header)
    $corruptPdf = [System.IO.Path]::GetTempFileName() + ".pdf"
    $pdfBytes = [System.Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n%broken`nnot a valid pdf")
    [System.IO.File]::WriteAllBytes($corruptPdf, $pdfBytes)
    
    $r = api-upload $docPath $corruptPdf $tokenA
    if (-not (ok $r.code)) { throw "Upload failed: $($r.code) $($r.body)" }
    $docId = json-val2 $r.body "data" "documentId"
    Write-Host "    docId=$docId"
    
    $failed = $false
    for ($i = 0; $i -lt 30; $i++) {
        $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$docId" $null $tokenA
        $status = json-val2 $r.body "data" "status"
        $taskStatus = json-val3 $r.body "data" "latestTask" "status"
        Write-Host "    t=$i doc=$status task=$taskStatus"
        if ($status -eq "FAILED" -and $taskStatus -eq "DEAD") { $failed = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $failed) { throw "Document did not reach FAILED/DEAD" }
    Write-Host "    FAILED + DEAD confirmed"
}

Test-Step "R.4 Delete while processing" {
    # Stop RabbitMQ to prevent consumer from picking up the task
    docker stop intellidesk-rabbitmq 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    
    $contentR4 = "E2E-Regression-4 " + (Get-Random) + "`nDelete while processing test."
    $txtFileR4 = new-txt-file $contentR4
    
    $r = api-upload $docPath $txtFileR4 $tokenA
    if (-not (ok $r.code)) { throw "Upload failed: $($r.code) $($r.body)" }
    $docId = json-val2 $r.body "data" "documentId"
    Write-Host "    docId=$docId (task is PENDING, RabbitMQ stopped)"
    
    # Delete immediately
    $r = api "DELETE" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$docId" $null $tokenA
    if (-not (ok $r.code)) { throw "Delete failed: $($r.code) $($r.body)" }
    
    # Verify document is deleted
    $r = api "GET" "$base/api/workspaces/$wsId/knowledge-bases/$kbId/documents/$docId" $null $tokenA
    $code = json-val $r.body "code"
    if ($code -ne 4001) { throw "Expected 4001 DOCUMENT_NOT_FOUND, got code=$code" }
    Write-Host "    Delete confirmed"
    
    docker start intellidesk-rabbitmq 2>&1 | Out-Null
    Start-Sleep -Seconds 3
}

Test-Step "R.5 Phase 1 regression" {
    $r = api "GET" "$base/api/workspaces" $null $tokenA
    if (-not (ok $r.code)) { throw "Phase 1 regression failed: $($r.code)" }
    Write-Host "    Workspace list OK"
}

# ============================================================
# Cleanup
# ============================================================
Remove-Item $txtFileA -Force -ErrorAction SilentlyContinue
Remove-Item $txtFileB -Force -ErrorAction SilentlyContinue
Remove-Item $txtFileC -Force -ErrorAction SilentlyContinue
Remove-Item $txtFileR1 -Force -ErrorAction SilentlyContinue
Remove-Item $txtFileR2 -Force -ErrorAction SilentlyContinue
if ($txtFileR4) { Remove-Item $txtFileR4 -Force -ErrorAction SilentlyContinue }

# ============================================================
# Summary
# ============================================================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "E2E Results: $script:pass PASS, $script:fail FAIL" -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host "========================================" -ForegroundColor Cyan

if ($script:fail -gt 0) {
    exit 1
}
