param(
    [string]$BaseUrl = "http://localhost",

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9-]{2,31}$')]
    [string]$RunId,

    [string]$ManifestPath = (Join-Path ([System.IO.Path]::GetTempPath()) "intellidesk-demo-$RunId.json")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$demoPassword = [Environment]::GetEnvironmentVariable("INTELLIDESK_DEMO_PASSWORD")
if ([string]::IsNullOrWhiteSpace($demoPassword) -or $demoPassword.Length -lt 6) {
    throw "INTELLIDESK_DEMO_PASSWORD must be set to a local demo password of at least 6 characters"
}

$fixturePath = Join-Path $PSScriptRoot "..\docs\demo\demo-knowledge.txt"
$fixturePath = [System.IO.Path]::GetFullPath($fixturePath)
if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
    throw "Demo fixture is missing"
}

function Invoke-DemoApi {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body,
        [string]$Token,
        [int[]]$ExpectedStatus = @(200)
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers.Authorization = "Bearer $Token"
    }

    $parameters = @{
        Uri = "$($BaseUrl.TrimEnd('/'))$Path"
        Method = $Method
        Headers = $headers
        SkipHttpErrorCheck = $true
        MaximumRedirection = 0
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }

    $response = Invoke-WebRequest @parameters
    $payload = $null
    if (-not [string]::IsNullOrWhiteSpace($response.Content)) {
        try {
            $payload = $response.Content | ConvertFrom-Json
        } catch {
            throw "API returned non-JSON content for $Method $Path (HTTP $([int]$response.StatusCode))"
        }
    }

    $status = [int]$response.StatusCode
    if ($status -notin $ExpectedStatus) {
        $businessCode = if ($null -ne $payload -and $null -ne $payload.code) { $payload.code } else { "UNKNOWN" }
        throw "API request failed: $Method $Path -> HTTP $status / code $businessCode"
    }

    return [pscustomobject]@{
        Status = $status
        Payload = $payload
        Data = if ($null -ne $payload) { $payload.data } else { $null }
    }
}

function Invoke-DemoUpload {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$Token
    )

    $response = Invoke-WebRequest `
        -Uri "$($BaseUrl.TrimEnd('/'))$Path" `
        -Method Post `
        -Headers @{ Authorization = "Bearer $Token" } `
        -Form @{ file = Get-Item -LiteralPath $FilePath } `
        -SkipHttpErrorCheck `
        -MaximumRedirection 0

    $payload = $null
    if (-not [string]::IsNullOrWhiteSpace($response.Content)) {
        try {
            $payload = $response.Content | ConvertFrom-Json
        } catch {
            throw "Upload returned non-JSON content (HTTP $([int]$response.StatusCode))"
        }
    }

    if ([int]$response.StatusCode -ne 202) {
        $businessCode = if ($null -ne $payload -and $null -ne $payload.code) { $payload.code } else { "UNKNOWN" }
        throw "Upload failed: HTTP $([int]$response.StatusCode) / code $businessCode"
    }

    return $payload.data
}

$username = "portfolio_$RunId"
$token = $null

Write-Host "[1/7] Register isolated demo identity"
$registration = Invoke-DemoApi -Method Post -Path "/api/auth/register" -Body @{
    username = $username
    password = $demoPassword
    nickname = "Portfolio Demo $RunId"
} -ExpectedStatus @(200)
$token = [string]$registration.Data.accessToken
if ([string]::IsNullOrWhiteSpace($token)) {
    throw "Registration succeeded without an access token"
}

Write-Host "[2/7] Create workspace"
$workspace = (Invoke-DemoApi -Method Post -Path "/api/workspaces" -Token $token -Body @{
    name = "IntelliDesk Portfolio $RunId"
    description = "Isolated reproducible portfolio demo"
}).Data
if ($null -eq $workspace.id) { throw "Workspace response has no id" }
$workspaceId = [long]$workspace.id

Write-Host "[3/7] Create knowledge base"
$knowledgeBase = (Invoke-DemoApi -Method Post -Path "/api/workspaces/$workspaceId/knowledge-bases" -Token $token -Body @{
    name = "Engineering Handbook $RunId"
    description = "Controlled synthetic policies for the IntelliDesk demo"
    chunkStrategy = "RECURSIVE"
    chunkSize = 500
    chunkOverlap = 50
}).Data
if ($null -eq $knowledgeBase.id) { throw "Knowledge-base response has no id" }
$knowledgeBaseId = [long]$knowledgeBase.id

Write-Host "[4/7] Upload deterministic document"
$upload = Invoke-DemoUpload `
    -Path "/api/workspaces/$workspaceId/knowledge-bases/$knowledgeBaseId/documents" `
    -FilePath $fixturePath `
    -Token $token
if ($null -eq $upload.documentId) { throw "Upload response has no documentId" }
$documentId = [long]$upload.documentId

Write-Host "[5/7] Wait for document COMPLETED and retrieval availability"
$documentReady = $false
$retrievalReady = $false
$documentStatus = "UNKNOWN"
$retrievalProbe = @{
    query = "What is the Beijing daily allowance?"
    knowledgeBaseIds = @($knowledgeBaseId)
    documentIds = @($documentId)
    mode = "HYBRID"
    candidateTopK = 10
    topK = 5
    rerank = $false
}

for ($attempt = 1; $attempt -le 180; $attempt++) {
    $detail = (Invoke-DemoApi -Method Get -Path "/api/workspaces/$workspaceId/knowledge-bases/$knowledgeBaseId/documents/$documentId" -Token $token).Data
    $documentStatus = [string]$detail.status
    if ($documentStatus -eq "FAILED") {
        throw "Document processing failed with code $($detail.failureCode)"
    }
    $documentReady = $documentStatus -eq "COMPLETED"

    if ($documentReady) {
        $search = Invoke-DemoApi -Method Post -Path "/api/workspaces/$workspaceId/retrieval/search" -Token $token -Body $retrievalProbe
        $retrievalReady = [int]$search.Data.totalResults -gt 0
    }

    if ($documentReady -and $retrievalReady) { break }
    Start-Sleep -Seconds 1
}

if (-not $documentReady -or -not $retrievalReady) {
    throw "Demo data did not become ready: document=$documentStatus retrieval=$retrievalReady"
}

$chunks = (Invoke-DemoApi -Method Get -Path "/api/workspaces/$workspaceId/knowledge-bases/$knowledgeBaseId/documents/$documentId/chunks?page=1&size=20" -Token $token).Data
if ([int]$chunks.total -lt 1) { throw "Completed document has no chunks" }

Write-Host "[6/7] Create RAG conversation and read-only API-key metadata"
$conversation = (Invoke-DemoApi -Method Post -Path "/api/workspaces/$workspaceId/conversations" -Token $token -Body @{
    title = "Travel policy Q&A"
}).Data
if ($null -eq $conversation.id) { throw "Conversation response has no id" }
$conversationId = [long]$conversation.id

$apiKey = (Invoke-DemoApi -Method Post -Path "/api/workspaces/$workspaceId/api-keys" -Token $token -Body @{
    name = "Portfolio read-only key"
    scope = "READ"
}).Data
if ($null -eq $apiKey.id -or [string]::IsNullOrWhiteSpace([string]$apiKey.keyPrefix)) {
    throw "API-key response has no metadata"
}
$apiKeyId = [long]$apiKey.id
$apiKeyPrefix = [string]$apiKey.keyPrefix
$apiKey = $null

Write-Host "[7/7] Write redacted manifest"
$manifest = [ordered]@{
    schemaVersion = 1
    runId = $RunId
    baseUrl = $BaseUrl.TrimEnd('/')
    username = $username
    workspaceId = $workspaceId
    knowledgeBaseId = $knowledgeBaseId
    documentId = $documentId
    conversationId = $conversationId
    apiKeyId = $apiKeyId
    apiKeyPrefix = $apiKeyPrefix
    documentStatus = $documentStatus
    retrievalReady = $retrievalReady
    chunkCount = [int]$chunks.total
    fixtureSha256 = (Get-FileHash -LiteralPath $fixturePath -Algorithm SHA256).Hash.ToLowerInvariant()
}

$manifestDirectory = Split-Path -Parent $ManifestPath
if (-not [string]::IsNullOrWhiteSpace($manifestDirectory)) {
    [System.IO.Directory]::CreateDirectory($manifestDirectory) | Out-Null
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ManifestPath -Encoding utf8NoBOM

$token = $null
$demoPassword = $null

Write-Host "PASS: isolated demo data is ready"
Write-Host "Manifest: $ManifestPath"
Write-Host "Login username: $username"
