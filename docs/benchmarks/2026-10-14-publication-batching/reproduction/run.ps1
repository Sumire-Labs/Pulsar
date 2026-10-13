param([string]$Engine,[string]$Run,[string]$Mode='updates',[string]$Command='lightbench updates',[string]$Template='flat')
$ErrorActionPreference='Stop'
$root='E:\Github\pulsar-lucis-inspired-20261014'
[IO.File]::WriteAllText("$root\active-run.json",(@{pid=$PID;run=$Run;engine=$Engine;started=[DateTime]::UtcNow.ToString('o');active=$true}|ConvertTo-Json))
$game='C:\Users\s12kuma01\AppData\Roaming\PrismLauncher\instances\Pulsar-SWMR-20261008\minecraft'
function OwnedProcesses { @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object CommandLine -like '*Pulsar-SWMR-20261008*') }
& "$root\stage.ps1" -Engine $Engine -Run $Run -Mode $Mode -Command $Command -Template $Template
if($LASTEXITCODE -ne 0){throw 'Staging failed'}
Start-Process -FilePath 'C:\Users\s12kuma01\AppData\Local\Programs\PrismLauncher\prismlauncher.exe' -ArgumentList '-l','Pulsar-SWMR-20261008','--offline','Serrecable' -WindowStyle Hidden
$deadline=[DateTime]::UtcNow.AddMinutes(30)
$seen=$false
$samples=[Collections.Generic.List[object]]::new()
while([DateTime]::UtcNow -lt $deadline){
    Start-Sleep -Seconds 2
    $processes=OwnedProcesses
    if($processes.Count){$seen=$true}
    foreach($proc in $processes){
        $p=Get-Process -Id $proc.ProcessId -ErrorAction SilentlyContinue
        if($p){$samples.Add(@{utc=[DateTime]::UtcNow.ToString('o');pid=$p.Id;working_set_bytes=$p.WorkingSet64;private_bytes=$p.PrivateMemorySize64;cpu_seconds=$p.CPU})}
    }
    if($seen -and -not $processes.Count){break}
}
$out="$root\runs\$Run"
[IO.File]::WriteAllText("$out\process-samples.json",($samples|ConvertTo-Json -Compress))
if((OwnedProcesses).Count){throw "Timed out $Run; game retained for diagnosis"}
Copy-Item -LiteralPath "$game\logs\latest.log" -Destination "$out\game.log"
Copy-Item -LiteralPath "$game\config" -Destination "$out\config" -Recurse
if(Test-Path "$game\saves\Bench Updates\lightbench-results"){Copy-Item -LiteralPath "$game\saves\Bench Updates\lightbench-results" -Destination "$out\results" -Recurse}
$valid=Test-Path "$out\results\observer.json"
if($valid){
    $observer=Get-Content "$out\results\observer.json" -Raw|ConvertFrom-Json
    if($observer.failure){$valid=$false}
    foreach($file in Get-ChildItem "$out\results" -Filter '*.json' | Where-Object {$_.Name -Match '-tps-|-spikes-' -and $_.Name -notlike 'warmup-*'}){
        $data=Get-Content $file.FullName -Raw|ConvertFrom-Json
        if($data.benchmark.roof_and_light_verified -ne $true -or $data.benchmark.wall_capped){$valid=$false}
    }
    if($Mode -eq 'travel'){
        if(-not(Test-Path "$out\results\travel.json")){$valid=$false}
        elseif((Get-Content "$out\results\travel.json" -Raw|ConvertFrom-Json).valid -ne $true){$valid=$false}
    }
    if($Mode -eq 'gen'){
        $audits=@(Get-ChildItem "$out\results" -Filter '*-gen-audit-*.json')
        if($audits.Count -ne 1){$valid=$false}
        elseif((Get-Content $audits[0].FullName -Raw|ConvertFrom-Json).valid -ne $true){$valid=$false}
    }
}
if(Select-String "$out\game.log" -Pattern 'lightbench failed|run failed|benchmark failed|Benchmark failed|audit failed|automation failed|Post-run observation failed' -Quiet){$valid=$false}
$status=@{run=$Run;valid=$valid;finished=[DateTime]::UtcNow.ToString('o')}
[IO.File]::WriteAllText("$out\status.json",($status|ConvertTo-Json))
[IO.File]::WriteAllText("$root\active-run.json",(@{pid=$PID;run=$Run;engine=$Engine;finished=$status.finished;active=$false}|ConvertTo-Json))
Write-Output ($status|ConvertTo-Json -Compress)

# Completed reports and sampled metrics are retained; discard only this study's
# already captured disposable previous game after every run except its first backup.
$previous=[IO.Path]::GetFullPath("$out\previous-instance")
$ownedRoot=[IO.Path]::GetFullPath("$root\runs")+'\'
if($Run -ne 'preflight-v9-blocks-pulsar' -and (Test-Path $previous)){
    if(-not $previous.StartsWith($ownedRoot,[StringComparison]::OrdinalIgnoreCase) -or -not(Test-Path "$previous\four-engine-bench.marker")){throw 'Unexpected disposable snapshot target'}
    Remove-Item -LiteralPath $previous -Recurse -Force
}