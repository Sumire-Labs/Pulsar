$ErrorActionPreference='Stop'
$root='E:\Github\pulsar-handoff-20261010'
$py='C:\Users\s12kuma01\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$env:PYTHONUTF8='1'
$guard=[IO.File]::Open("$root\runner.lock",[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
function Trial($run,$engine,$waitMode,$variant,$command) {
    if(Test-Path "$root\runs\$run\status.json") {
        & $py "$root\tools\validate.py" "$root\runs\$run"
        if($LASTEXITCODE -ne 0){throw "Invalid existing trial $run"};return
    }
    @{status='measuring';next=$run;updated=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content "$root\progress.json"
    $waitMode|Set-Content "$root\selected-wait.txt";$variant|Set-Content "$root\selected-variant.txt"
    & "$root\run.ps1" -Engine $engine -Run $run -Command $command
    & $py "$root\tools\validate.py" "$root\runs\$run"
    if($LASTEXITCODE -ne 0){throw "Trial failed $run"}
}
try {
    foreach($mode in @('caller','gated','gatedpoll','future')) { Trial "pilot-$mode" 'pulsar' $mode 'off' 'lightbench blockupdates 200 100' }
    $cases=@('pulsar-legacy','pulsar-future','pulsar-gated','pulsar-gatedpoll','pulsar-caller','starlight-legacy')
    $orders=@(@(0,1,2,3,4,5),@(5,4,3,2,1,0),@(2,0,5,1,4,3),@(3,4,1,5,0,2))
    foreach($family in @('blocks','sky')) {
        $command=if($family -eq 'blocks'){'lightbench blockupdates 5000 10000'}else{'lightbench updates 2000 1000'}
        for($rep=1;$rep -le 4;$rep++) {
            foreach($i in $orders[$rep-1]) {
                $parts=$cases[$i].Split('-')
                Trial "formal-$family-r$rep-$($parts[0])-$($parts[1])" $parts[0] $parts[1] 'off' $command
            }
        }
    }
    foreach($family in @('blocks','sky')) {
        $command=if($family -eq 'blocks'){'lightbench blockupdates 5000 10000'}else{'lightbench updates 2000 1000'}
        for($rep=1;$rep -le 2;$rep++) {
            $modes=if($rep -eq 1){@('gated','gatedpoll','caller')}else{@('caller','gatedpoll','gated')}
            foreach($mode in $modes) { Trial "sampled-$family-r$rep-pulsar-$mode" 'pulsar' $mode 'on' $command }
        }
    }
    @{status='measurements-complete';updated=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content "$root\progress.json"
} catch {
    @{status='needs-investigation';reason=$_.ToString();updated=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content "$root\progress.json";throw
} finally {$guard.Dispose()}
