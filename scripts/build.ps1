[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$PluginRoot,

    [Parameter(Mandatory)]
    [string]$IdeHome,

    [string]$OutputRoot = (Join-Path $PSScriptRoot '..\.build\patched'),

    [string]$CompatibilityManifest,

    [switch]$UpdatePatchedHashes
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pluginRootPath = (Resolve-Path $PluginRoot).Path
$ideHomePath = (Resolve-Path $IdeHome).Path

if (-not $CompatibilityManifest) {
    $runtimeJar = Join-Path $pluginRootPath 'lib\ml-llm.jar'
    if (-not (Test-Path -LiteralPath $runtimeJar -PathType Leaf)) {
        throw "JetBrains AI runtime jar is missing: $runtimeJar"
    }
    $runtimeHash = (Get-FileHash -LiteralPath $runtimeJar -Algorithm SHA256).Hash
    $CompatibilityManifest = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'compatibility') -Filter '*.json' |
        Where-Object {
            $candidate = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
            [string]$candidate.jars.runtime.sha256 -eq $runtimeHash
        } |
        Select-Object -First 1 -ExpandProperty FullName
}

if (-not $CompatibilityManifest -or -not (Test-Path -LiteralPath $CompatibilityManifest -PathType Leaf)) {
    throw "Unsupported JetBrains AI version. Manifest not found: $CompatibilityManifest"
}

$compat = Get-Content -LiteralPath $CompatibilityManifest -Raw | ConvertFrom-Json
$patchVersion = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim()
if ($compat.patchVersion -and [string]$compat.patchVersion -ne $patchVersion) {
    throw "Compatibility manifest targets patch $($compat.patchVersion), current patch is $patchVersion."
}
foreach ($jar in @($compat.jars.runtime, $compat.jars.chat, $compat.jars.frontend)) {
    $path = Join-Path $pluginRootPath ([string]$jar.path)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Plugin jar is missing: $path"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($actual -ne [string]$jar.sha256) {
        throw "Unsupported or already patched jar: $($jar.path). Expected $($jar.sha256), got $actual"
    }
}

$javac = Join-Path $ideHomePath 'jbr\bin\javac.exe'
$java = Join-Path $ideHomePath 'jbr\bin\java.exe'
$asmJar = Join-Path $ideHomePath 'lib\intellij.libraries.asm.jar'
foreach ($required in @($javac, $java, $asmJar)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required build dependency is missing: $required"
    }
}

$buildRoot = Join-Path $repoRoot '.build'
$mainClasses = Join-Path $buildRoot 'classes\main'
$patcherClasses = Join-Path $buildRoot 'classes\patcher'
$outputRootPath = [IO.Path]::GetFullPath($OutputRoot)
Remove-Item -LiteralPath $mainClasses, $patcherClasses, $outputRootPath -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $mainClasses, $patcherClasses, $outputRootPath -Force | Out-Null

$mainSources = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'src\main\java') -Recurse -Filter '*.java'
$patcherSources = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'src\patcher\java') -Recurse -Filter '*.java'
if (-not $mainSources -or -not $patcherSources) {
    throw 'Java sources are missing.'
}

$pluginClasspath = @(
    Get-ChildItem -LiteralPath (Join-Path $pluginRootPath 'lib') -Recurse -Filter '*.jar'
    Get-ChildItem -LiteralPath (Join-Path $ideHomePath 'lib') -Filter '*.jar'
) | Select-Object -ExpandProperty FullName -Unique
$pluginClasspath = $pluginClasspath -join ';'

$mainArgs = Join-Path $buildRoot 'javac-main.args'
$mainArgLines = @(
    '-encoding', 'UTF-8',
    '-cp', ('"{0}"' -f $pluginClasspath.Replace('\', '/')),
    '-d', ('"{0}"' -f $mainClasses.Replace('\', '/'))
)
$mainArgLines += $mainSources.FullName | ForEach-Object { '"{0}"' -f $_.Replace('\', '/') }
[IO.File]::WriteAllLines($mainArgs, $mainArgLines, [Text.UTF8Encoding]::new($false))
& $javac "@$mainArgs"
if ($LASTEXITCODE -ne 0) {
    throw "Helper compilation failed with exit code $LASTEXITCODE"
}

$patcherArgs = Join-Path $buildRoot 'javac-patcher.args'
$patcherArgLines = @(
    '-encoding', 'UTF-8',
    '-cp', ('"{0}"' -f $asmJar.Replace('\', '/')),
    '-d', ('"{0}"' -f $patcherClasses.Replace('\', '/'))
)
$patcherArgLines += $patcherSources.FullName | ForEach-Object { '"{0}"' -f $_.Replace('\', '/') }
[IO.File]::WriteAllLines($patcherArgs, $patcherArgLines, [Text.UTF8Encoding]::new($false))
& $javac "@$patcherArgs"
if ($LASTEXITCODE -ne 0) {
    throw "Patcher compilation failed with exit code $LASTEXITCODE"
}

$patcherClasspath = @(
    $patcherClasses,
    $asmJar,
    (Join-Path $pluginRootPath 'lib\*'),
    (Join-Path $pluginRootPath 'lib\modules\*'),
    (Join-Path $ideHomePath 'lib\*')
) -join ';'
$targets = @(
    @{ Class = 'dev.jetbrains.ai.wsl.patch.PatchRuntimeJar'; Jar = $compat.jars.runtime },
    @{ Class = 'dev.jetbrains.ai.wsl.patch.PatchChatJar'; Jar = $compat.jars.chat },
    @{ Class = 'dev.jetbrains.ai.wsl.patch.PatchFrontendJar'; Jar = $compat.jars.frontend }
)

$report = [ordered]@{
    patchVersion = $patchVersion
    pluginVersion = [string]$compat.plugin.version
    builtAtUtc = [DateTime]::UtcNow.ToString('o')
    jars = [ordered]@{}
}
$metadataPath = Join-Path $buildRoot 'jetbrains-ai-wsl-patch.properties'
[IO.File]::WriteAllLines($metadataPath, @(
    'schema=1',
    "patchVersion=$($report.patchVersion)",
    "pluginVersion=$($report.pluginVersion)"
), [Text.UTF8Encoding]::new($false))

foreach ($target in $targets) {
    $relative = [string]$target.Jar.path
    $input = Join-Path $pluginRootPath $relative
    $output = Join-Path $outputRootPath $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force | Out-Null
    & $java -cp $patcherClasspath $target.Class $input $output $mainClasses $metadataPath
    if ($LASTEXITCODE -ne 0) {
        throw "$($target.Class) failed with exit code $LASTEXITCODE"
    }
    $report.jars[$relative] = [ordered]@{
        sourceSha256 = (Get-FileHash -LiteralPath $input -Algorithm SHA256).Hash
        patchedSha256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
    }
    $expectedPatchedHash = [string]$target.Jar.patchedSha256
    if ($expectedPatchedHash -and $expectedPatchedHash -ne $report.jars[$relative].patchedSha256 -and -not $UpdatePatchedHashes) {
        throw "Patched SHA-256 changed for $relative. Expected $expectedPatchedHash, got $($report.jars[$relative].patchedSha256). Use -UpdatePatchedHashes only after review."
    }
}

if ($UpdatePatchedHashes) {
    $compat | Add-Member -NotePropertyName patchVersion -NotePropertyValue $patchVersion -Force
    foreach ($target in $targets) {
        $relative = [string]$target.Jar.path
        $target.Jar | Add-Member -NotePropertyName patchedSha256 -NotePropertyValue $report.jars[$relative].patchedSha256 -Force
    }
    [IO.File]::WriteAllLines($CompatibilityManifest, @(($compat | ConvertTo-Json -Depth 12)), [Text.UTF8Encoding]::new($false))
}

$reportPath = Join-Path $outputRootPath 'patch-report.json'
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Output $reportPath
