$ErrorActionPreference='Stop'
$root='E:\Github\pulsar-lucis-inspired-20261014'
$lock=[IO.File]::Open("$root\runner.lock",[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$done=0
function RunCase($variant,$name,$mode,$command){
    if(Test-Path "$root\runs\$name\status.json"){
        $result=Get-Content "$root\runs\$name\status.json" -Raw|ConvertFrom-Json
    }else{
        @{status='measuring';current=$name;completed=$script:done;planned=17}|ConvertTo-Json|Set-Content "$root\progress.json"
        & "$root\run.ps1" -Engine $variant -Run $name -Mode $mode -Command $command -Template flat
        $result=Get-Content "$root\runs\$name\status.json" -Raw|ConvertFrom-Json
    }
    if(-not $result.valid){throw "Correctness validation failed: $name"}
    $script:done++
}
try{
    RunCase candidate pilot-candidate updates 'lightbench updates 1 2'
    $orders=@(@('baseline','candidate'),@('candidate','baseline'),@('candidate','baseline'),@('baseline','candidate'))
    for($rep=1;$rep -le 4;$rep++){
        foreach($variant in $orders[$rep-1]){
            RunCase $variant "updates-r$rep-$variant" updates 'lightbench updates 10000 10000'
        }
    }
    for($rep=1;$rep -le 2;$rep++){
        foreach($variant in $orders[$rep-1]){
            RunCase $variant "continuous-r$rep-$variant" suite 'lightbench tps 128 5'
        }
    }
    foreach($variant in @('baseline','candidate')){
        RunCase $variant "generation-$variant" gen 'lightbench gen 8 1 8'
        RunCase $variant "shapes-$variant" audit 'hotpathaudit'
    }
    @{status='measurements-complete';completed=$done;planned=17}|ConvertTo-Json|Set-Content "$root\progress.json"
}catch{
    @{status='needs-investigation';completed=$done;failure=$_.Exception.Message}|ConvertTo-Json|Set-Content "$root\progress.json"
    throw
}finally{$lock.Dispose()}
