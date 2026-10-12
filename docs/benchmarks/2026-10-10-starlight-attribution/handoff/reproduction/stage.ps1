param([ValidateSet('vanilla','alfheim','pulsar','starlight')][string]$Engine,
      [string]$Run,[string]$Mode='updates',[string]$Command='lightbench updates',
      [ValidateSet('flat','normal')][string]$Template='flat')
$ErrorActionPreference='Stop'
$root='E:\Github\pulsar-handoff-20261010'
$instance='C:\Users\s12kuma01\AppData\Roaming\PrismLauncher\instances\Pulsar-SWMR-20261008'
$game=Join-Path $instance 'minecraft'
if($Run -notmatch '^[a-z0-9-]+$'){throw 'Invalid run name'}
if(Test-Path "$root\manifest-final.json"){
    $frozen=Get-Content "$root\manifest-final.json" -Raw|ConvertFrom-Json
    foreach($inputFile in $frozen.common_mods){
        $path=Join-Path "$root\common-mods" $inputFile.file
        if(-not(Test-Path $path) -or (Get-FileHash -LiteralPath $path).Hash -ne $inputFile.sha256){throw "Frozen common mod changed: $($inputFile.file)"}
    }
}
if(@(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object CommandLine -like '*Pulsar-SWMR-20261008*').Count){throw 'Owned game JVM already running'}
$out=Join-Path $root "runs/$Run"
if(Test-Path $out){throw 'Run already exists'}
New-Item -ItemType Directory -Force $out | Out-Null
if(Test-Path $game){
    $target=[IO.Path]::GetFullPath($game)
    $archive=[IO.Path]::GetFullPath((Join-Path $out 'previous-instance'))
    if($target -ne [IO.Path]::GetFullPath("$instance\minecraft") -or -not $archive.StartsWith([IO.Path]::GetFullPath("$root\runs")+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected move target'}
    Move-Item -LiteralPath $target -Destination $archive
}
New-Item -ItemType Directory -Force $game | Out-Null
Copy-Item -LiteralPath "$root\common-mods" -Destination "$game\mods" -Recurse
Copy-Item -LiteralPath "$root\frozen\config" -Destination "$game\config" -Recurse
# The exact generated default Version2 config is supplied to every engine.
# Other engines ignore it; keeping the file set identical preserves strict fingerprints.
Copy-Item -LiteralPath "$root\frozen\options.txt" -Destination "$game\options.txt"
$variant=(Get-Content "$root\selected-variant.txt" -Raw).Trim()
$rel=Get-Content "$game\config\relauncher.json" -Raw|ConvertFrom-Json
$rel.args += " -Dbenchprobe.enabled="+$(if($variant -eq "on"){"true"}else{"false"})
$waitMode=(Get-Content "$root\selected-wait.txt" -Raw).Trim()
$rel.args += " -Dhandoff.wait=$waitMode"
$rel|ConvertTo-Json|Set-Content "$game\config\relauncher.json" -Encoding UTF8
$variant=(Get-Content "$root\selected-variant.txt" -Raw).Trim()
$pool=(Get-Content "$root\selected-pool.txt" -Raw).Trim()
if($Engine -eq "pulsar" -and $pool -eq "1"){
  $cfg=[IO.File]::ReadAllText("$game\config\pulsar.cfg")
  if([regex]::Matches($cfg,"I:experimentalServerLightThreads=-1").Count -ne 1){throw "Unexpected default worker config"}
  [IO.File]::WriteAllText("$game\config\pulsar.cfg",$cfg.Replace("I:experimentalServerLightThreads=-1","I:experimentalServerLightThreads=1"))
}

$artifact=switch($Engine){starlight{'starlight-0a7d94e-clean.jar'} pulsar{if($variant -eq 'original'){'pulsar-main.jar'}elseif($variant -eq 'on'){'pulsar-sampled.jar'}else{'pulsar-diagnostic.jar'}} default{throw 'Unsupported engine'}}
$hash=$null
if($artifact){Copy-Item -LiteralPath "$root\artifacts\$artifact" -Destination "$game\mods\$artifact";$hash=(Get-FileHash "$game\mods\$artifact").Hash}
if($artifact -and $frozen){
    $expected=@($frozen.artifacts|Where-Object file -eq $artifact)
    if($expected.Count -ne 1 -or $expected[0].sha256 -ne $hash){throw "Frozen engine artifact changed: $artifact"}
}
New-Item -ItemType Directory -Force "$game\saves" | Out-Null
Copy-Item -LiteralPath "$root\templates\$Template" -Destination "$game\saves\Bench Updates" -Recurse
& node "$root\world.cjs" "$game\saves\Bench Updates" $Engine $Mode $Run $Command
if($LASTEXITCODE -ne 0){throw 'World preparation failed'}
[IO.File]::WriteAllText("$game\lightbench-variant.txt",$Engine)
[IO.File]::WriteAllText("$game\four-engine-bench.marker",'disposable release-comparison benchmark')
[IO.File]::WriteAllText("$game\four-engine-mode.txt",$Mode)
if($Mode -eq 'travel'){[IO.File]::WriteAllText("$game\bench-travel-height.txt",$(if($Template -eq 'flat'){'32'}else{'96'}))}
$manifest=@{wait_mode=$waitMode;pool=$pool;variant=$variant;engine=$Engine;run=$Run;mode=$Mode;command=$Command;template=$Template;engine_jar=$artifact;sha256=$hash;created=[DateTime]::UtcNow.ToString('o')}
[IO.File]::WriteAllText("$out\stage.json",($manifest|ConvertTo-Json))
Write-Output "Staged $Run"
