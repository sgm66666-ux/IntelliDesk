param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [Parameter(Mandatory = $true)]
    [string]$StopFile,

    [int]$IntervalMilliseconds = 500,

    [int]$RerankerProcessId = 0
)

$ErrorActionPreference = 'Stop'
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$resolvedStop = [System.IO.Path]::GetFullPath($StopFile)
$outputDirectory = [System.IO.Path]::GetDirectoryName($resolvedOutput)
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$writer = [System.IO.StreamWriter]::new($resolvedOutput, $false, [System.Text.UTF8Encoding]::new($false))
$logicalProcessors = [Environment]::ProcessorCount

try {
    while (-not [System.IO.File]::Exists($resolvedStop)) {
        $observedAt = [DateTimeOffset]::UtcNow.ToString('O')
        $os = Get-CimInstance Win32_OperatingSystem
        $hostRecord = [ordered]@{
            observed_at = $observedAt
            record_type = 'host'
            logical_processors = $logicalProcessors
            free_physical_memory_bytes = [int64]$os.FreePhysicalMemory * 1024
            total_visible_memory_bytes = [int64]$os.TotalVisibleMemorySize * 1024
        }
        $writer.WriteLine(($hostRecord | ConvertTo-Json -Compress))

        $processes = Get-Process -Name java, python, ollama -ErrorAction SilentlyContinue
        foreach ($process in $processes) {
            $role = if ($RerankerProcessId -gt 0 -and $process.Id -eq $RerankerProcessId) {
                'reranker'
            } elseif ($process.ProcessName -eq 'ollama') {
                'ollama'
            } elseif ($process.ProcessName -eq 'java') {
                'java'
            } else {
                'other-python'
            }
            $processStartTime = $null
            $totalProcessorTimeMs = $null
            $workingSetBytes = $null
            $privateMemoryBytes = $null
            $threadCount = $null
            $handleCount = $null
            try { $processStartTime = $process.StartTime.ToUniversalTime().ToString('O') } catch {}
            try { $totalProcessorTimeMs = $process.TotalProcessorTime.TotalMilliseconds } catch {}
            try { $workingSetBytes = $process.WorkingSet64 } catch {}
            try { $privateMemoryBytes = $process.PrivateMemorySize64 } catch {}
            try { $threadCount = $process.Threads.Count } catch {}
            try { $handleCount = $process.HandleCount } catch {}
            $record = [ordered]@{
                observed_at = $observedAt
                record_type = 'process'
                role = $role
                process_id = $process.Id
                process_name = $process.ProcessName
                start_time = $processStartTime
                total_processor_time_ms = $totalProcessorTimeMs
                working_set_bytes = $workingSetBytes
                private_memory_bytes = $privateMemoryBytes
                thread_count = $threadCount
                handle_count = $handleCount
            }
            $writer.WriteLine(($record | ConvertTo-Json -Compress))
        }
        $writer.Flush()
        Start-Sleep -Milliseconds $IntervalMilliseconds
    }
} finally {
    $writer.Dispose()
}
