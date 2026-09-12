param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [Parameter(Mandatory = $true)]
    [string]$StopFile,

    [int]$IntervalMilliseconds = 1000
)

$ErrorActionPreference = 'Continue'
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$resolvedStop = [System.IO.Path]::GetFullPath($StopFile)
$outputDirectory = [System.IO.Path]::GetDirectoryName($resolvedOutput)
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$writer = [System.IO.StreamWriter]::new($resolvedOutput, $false, [System.Text.UTF8Encoding]::new($false))
$logicalProcessors = [Environment]::ProcessorCount
$metricPattern = '^(process_cpu_usage|process_uptime_seconds|jvm_memory_used_bytes|jvm_memory_committed_bytes|jvm_memory_max_bytes|jvm_gc_pause_seconds_(count|sum|max)|jvm_threads_(live_threads|daemon_threads|peak_threads)|hikaricp_connections(_active|_idle|_pending|_max|_min)?|tomcat_threads_(busy_threads|current_threads|max_threads)|executor_(active|pool_size|queued|max_pool_size|core_pool_size))'

try {
    while (-not [System.IO.File]::Exists($resolvedStop)) {
        $observedAt = [DateTimeOffset]::UtcNow.ToString('O')

        try {
            $os = Get-CimInstance Win32_OperatingSystem
            $cpu = Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average
            $hostRecord = [ordered]@{
                observed_at = $observedAt
                record_type = 'host'
                logical_processors = $logicalProcessors
                cpu_load_percent = $cpu.Average
                free_physical_memory_bytes = [int64]$os.FreePhysicalMemory * 1024
                total_visible_memory_bytes = [int64]$os.TotalVisibleMemorySize * 1024
            }
            $writer.WriteLine(($hostRecord | ConvertTo-Json -Compress))
        } catch {}

        $processes = Get-Process -Name k6 -ErrorAction SilentlyContinue
        foreach ($process in $processes) {
            try {
                $processRecord = [ordered]@{
                    observed_at = $observedAt
                    record_type = 'client_process'
                    process_id = $process.Id
                    process_name = $process.ProcessName
                    total_processor_time_ms = $process.TotalProcessorTime.TotalMilliseconds
                    working_set_bytes = $process.WorkingSet64
                    private_memory_bytes = $process.PrivateMemorySize64
                    thread_count = $process.Threads.Count
                    handle_count = $process.HandleCount
                }
                $writer.WriteLine(($processRecord | ConvertTo-Json -Compress))
            } catch {}
        }

        try {
            $statsLines = & docker stats --no-stream --format '{{json .}}' intellidesk-backend intellidesk-web 2>$null
            foreach ($statsLine in $statsLines) {
                if (-not [string]::IsNullOrWhiteSpace($statsLine)) {
                    $stats = $statsLine | ConvertFrom-Json
                    $dockerRecord = [ordered]@{
                        observed_at = $observedAt
                        record_type = 'docker_stats'
                        name = $stats.Name
                        cpu_percent = $stats.CPUPerc
                        memory_percent = $stats.MemPerc
                        memory_usage = $stats.MemUsage
                        network_io = $stats.NetIO
                        block_io = $stats.BlockIO
                        pids = $stats.PIDs
                    }
                    $writer.WriteLine(($dockerRecord | ConvertTo-Json -Compress))
                }
            }
        } catch {}

        try {
            $actuatorText = & docker exec intellidesk-backend wget -qO- http://localhost:8080/actuator/prometheus 2>$null
            $selectedLines = @()
            foreach ($line in ($actuatorText -split "`n")) {
                $trimmed = $line.Trim()
                if ($trimmed -match $metricPattern) {
                    $selectedLines += $trimmed
                }
            }
            $metricsRecord = [ordered]@{
                observed_at = $observedAt
                record_type = 'jvm_metrics'
                metrics = $selectedLines
            }
            $writer.WriteLine(($metricsRecord | ConvertTo-Json -Compress -Depth 4))
        } catch {}

        $writer.Flush()
        Start-Sleep -Milliseconds $IntervalMilliseconds
    }
} finally {
    $writer.Dispose()
}
