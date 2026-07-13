[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][string]$PluginRoot,
    [string]$OutputPath,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\Common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Get-RepositoryRoot
$pluginRootPath = (Resolve-Path $PluginRoot).Path
$jars = [ordered]@{
    runtime = 'lib/ml-llm.jar'
    chat = 'lib/modules/intellij.ml.llm.chat.jar'
    frontend = 'lib/modules/intellij.ml.llm.agents.frontend.jar'
}
foreach ($path in $jars.Values) {
    if (-not (Test-Path -LiteralPath (Join-Path $pluginRootPath $path) -PathType Leaf)) {
        throw "Plugin jar is missing: $path"
    }
}

$runtimeJar = Join-Path $pluginRootPath $jars.runtime
if (Test-JarContainsEntry -JarPath $runtimeJar -Entry 'com/intellij/ml/llm/agents/acp/process/CodexRuntimePatchSupport.class') {
    throw 'Compatibility must be captured from an unpatched JetBrains AI plugin.'
}

$archive = [IO.Compression.ZipFile]::OpenRead($runtimeJar)
try {
    $entry = $archive.GetEntry('META-INF/plugin.xml')
    if (-not $entry) {
        throw 'META-INF/plugin.xml is missing from ml-llm.jar.'
    }
    $reader = [IO.StreamReader]::new($entry.Open())
    try {
        $descriptor = $reader.ReadToEnd()
    }
    finally {
        $reader.Dispose()
    }
}
finally {
    $archive.Dispose()
}

$versionMatch = [regex]::Match($descriptor, '<version>([^<]+)</version>')
$ideaMatch = [regex]::Match($descriptor, '<idea-version\s+since-build="([^"]+)"\s+until-build="([^"]+)"\s*/>')
if (-not $versionMatch.Success -or -not $ideaMatch.Success) {
    throw 'Unable to read plugin version compatibility from META-INF/plugin.xml.'
}
$version = $versionMatch.Groups[1].Value.Trim()
if (-not $OutputPath) {
    $OutputPath = Join-Path $repoRoot "compatibility\$version.json"
}
if ((Test-Path -LiteralPath $OutputPath) -and -not $Force) {
    throw "Compatibility manifest already exists: $OutputPath. Use -Force to replace it."
}

$manifest = [ordered]@{
    schema = 1
    plugin = [ordered]@{
        id = 'com.intellij.ml.llm'
        version = $version
        sinceBuild = $ideaMatch.Groups[1].Value
        untilBuild = $ideaMatch.Groups[2].Value
    }
    patchVersion = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim()
    jars = [ordered]@{}
    features = @('codex-runtime-routing', 'wsl-file-navigation', 'usage-limits', 'completion-sound')
}
foreach ($name in $jars.Keys) {
    $path = [string]$jars[$name]
    $manifest.jars[$name] = [ordered]@{
        path = $path
        sha256 = (Get-FileHash -LiteralPath (Join-Path $pluginRootPath $path) -Algorithm SHA256).Hash
        patchedSha256 = $null
    }
}

if ($PSCmdlet.ShouldProcess($OutputPath, "Write compatibility manifest for JetBrains AI $version")) {
    Write-Utf8NoBom -Path $OutputPath -Lines @(($manifest | ConvertTo-Json -Depth 8))
}
Write-Output ($manifest | ConvertTo-Json -Depth 8)
