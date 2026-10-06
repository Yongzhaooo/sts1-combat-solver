param([string]$Game = 'D:\SteamLibrary\steamapps\common\SlayTheSpire', [switch]$ResearchRecording, [switch]$BundledWindows)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$root = Split-Path $PSScriptRoot -Parent
$workshop = Join-Path (Split-Path (Split-Path $Game -Parent) -Parent) 'workshop\content\646570'
$mts = Join-Path $workshop '1605060445\ModTheSpire.jar'
$base = Join-Path $workshop '1605833019\BaseMod.jar'
$gameJar = Join-Path $Game 'desktop-1.0.jar'
$gson = Join-Path $root 'engine\lib\gson-2.8.8.jar'
if (-not (Test-Path $gson)) { $gson = $gameJar } # The game bundles Gson.
$out = Join-Path $PSScriptRoot 'build'
$utf8 = [Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Force -Path $out | Out-Null
function Compile-Mod($name, $sourceFiles, $metadata, $classpath) {
    # A fresh staging directory prevents old split-mod metadata/classes leaking in.
    $classes = Join-Path $out ("$name-classes-"+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $list = Join-Path $out "$name-sources.txt"
    [IO.File]::WriteAllLines($list, @($sourceFiles | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }), $utf8)
    & javac '-J-Dfile.encoding=UTF-8' '-J-Duser.language=en' -g -proc:none -encoding UTF-8 --release 8 -cp $classpath -d $classes "@$list"
    if ($LASTEXITCODE -ne 0) { throw "$name compile failed" }
    [IO.File]::WriteAllText((Join-Path $classes 'ModTheSpire.json'), $metadata, $utf8)
    if ($name -eq 'STS1CombatSolver') {
        Copy-Item -Path "$PSScriptRoot\resources\*" -Destination $classes -Recurse -Force
        New-Item -ItemType Directory -Path "$classes/licenses" | Out-Null
        Copy-Item -LiteralPath "$root/references/CommunicationMod/LICENSE" -Destination "$classes/licenses/CommunicationMod.txt"
        Copy-Item -LiteralPath "$root/candidates/sts-ironclad-agent/LICENSE" -Destination "$classes/licenses/sts-ironclad-agent.txt"
        $linuxRoot = '/mnt/' + $root.Substring(0,1).ToLowerInvariant() + $root.Substring(2).Replace('\','/')
        $properties = "python=$linuxRoot/candidates/sts-ironclad-agent/.venv/bin/python`nbackend=$linuxRoot/overlay/backend.py`nlog=$($PSScriptRoot.Replace('\','/'))/runtime/backend.log`n"
        if ($BundledWindows) { $properties = "runtime=windows-bundled`n" }
        $properties += "research_recording=$($ResearchRecording.IsPresent.ToString().ToLowerInvariant())`n"
        $properties += "recorder_build=$([DateTime]::UtcNow.ToString('o'))`n"
        [IO.File]::WriteAllText((Join-Path $classes 'solver.properties'), $properties, $utf8)
    }
    & jar cf (Join-Path $out "$name.jar") -C $classes .
    if ($LASTEXITCODE -ne 0) { throw "$name packaging failed" }
    if ((Get-Item (Join-Path $out "$name.jar")).Length -lt 1000) { throw "$name artifact missing" }
}
$comm = Join-Path $root 'references\CommunicationMod'
$cp = "$gameJar;$mts;$base;$gson"
$export = Join-Path $root 'candidates\sts-ironclad-agent\steam\state_export_mod'
$sources = @(Get-ChildItem "$comm\src\main\java" -Filter *.java -Recurse) + @(Get-ChildItem "$export\src" -Filter *.java -Recurse) + @(Get-ChildItem "$PSScriptRoot\src" -Filter *.java -Recurse)
Compile-Mod 'STS1CombatSolver' $sources (Get-Content -Raw -Encoding UTF8 "$PSScriptRoot\ModTheSpire.json") $cp
Get-Item "$out\STS1CombatSolver.jar" | Select-Object Name,Length
