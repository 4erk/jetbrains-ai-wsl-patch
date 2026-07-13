[CmdletBinding()]
param(
    [string]$IdeHome,
    [string]$Profile,
    [string]$WslDistribution,
    [string]$WslUser,
    [switch]$SkipWsl,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\Common.ps1')

function Invoke-Version {
    param(
        [string]$Command,
        [string[]]$Arguments = @()
    )
    if (-not $Command -or -not (Test-Path -LiteralPath $Command -PathType Leaf)) {
        return $null
    }
    try {
        return (& $Command @Arguments 2>$null | Select-Object -Last 1).Trim()
    }
    catch {
        return $null
    }
}

function Invoke-WslVersion {
    param(
        [Parameter(Mandatory)]$Wsl,
        [string]$Command,
        [string[]]$Arguments = @()
    )
    if (-not $Command) {
        return $null
    }
    try {
        return (& wsl.exe -d $Wsl.Distribution -u $Wsl.User -- $Command @Arguments 2>$null | Select-Object -Last 1).Trim()
    }
    catch {
        return $null
    }
}

$repoRoot = Get-RepositoryRoot
$context = Resolve-JetBrainsContext -IdeHome $IdeHome -Profile $Profile
$lock = Get-Content -LiteralPath (Join-Path $repoRoot 'runtime.lock.json') -Raw | ConvertFrom-Json
$manifests = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'compatibility') -Filter '*.json' |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })

$pluginTarget = Resolve-JetBrainsPluginTarget -Context $context
$targetKind = $pluginTarget.Mode
$target = $pluginTarget.Path
$jarSpecs = [ordered]@{
    runtime = [ordered]@{
        path = 'lib/ml-llm.jar'
        marker = 'com/intellij/ml/llm/agents/acp/process/CodexRuntimePatchSupport.class'
    }
    chat = [ordered]@{
        path = 'lib/modules/intellij.ml.llm.chat.jar'
        marker = 'com/intellij/ml/llm/core/chat/ui/chat/CodexUsageLimitPatchSupport.class'
    }
    frontend = [ordered]@{
        path = 'lib/modules/intellij.ml.llm.agents.frontend.jar'
        marker = 'com/intellij/ml/llm/agents/frontend/compose/ui/components/utils/MarkdownWslLinkPatchSupport.class'
    }
}

$pluginVersion = $null
$installedPatchVersion = $null
$metadata = $null
$jarStatus = [ordered]@{}
if ($targetKind -eq 'pending-update') {
    $pluginVersion = Get-PluginVersionFromZip -ZipPath $target
    $metadata = Get-ZipNestedJarEntryText -ZipPath $target -JarSuffix $jarSpecs.runtime.path -Entry 'META-INF/jetbrains-ai-wsl-patch.properties'
    foreach ($name in $jarSpecs.Keys) {
        $spec = $jarSpecs[$name]
        $jarStatus[$name] = [ordered]@{
            path = $spec.path
            sha256 = Get-ZipEntrySha256 -ZipPath $target -EntrySuffix $spec.path
            patched = Test-ZipNestedJarContainsEntry -ZipPath $target -JarSuffix $spec.path -Entry $spec.marker
        }
    }
}
else {
    $runtimeJar = Join-Path $target $jarSpecs.runtime.path
    if (Test-Path -LiteralPath $runtimeJar -PathType Leaf) {
        $pluginVersion = Get-PluginVersionFromJar -JarPath $runtimeJar
        $metadata = Get-JarEntryText -JarPath $runtimeJar -Entry 'META-INF/jetbrains-ai-wsl-patch.properties'
    }
    foreach ($name in $jarSpecs.Keys) {
        $spec = $jarSpecs[$name]
        $jarPath = Join-Path $target $spec.path
        $jarStatus[$name] = [ordered]@{
            path = $spec.path
            sha256 = if (Test-Path -LiteralPath $jarPath -PathType Leaf) { (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash } else { $null }
            patched = if (Test-Path -LiteralPath $jarPath -PathType Leaf) { Test-JarContainsEntry -JarPath $jarPath -Entry $spec.marker } else { $false }
        }
    }
}

if ($metadata) {
    $match = [regex]::Match($metadata, '(?m)^patchVersion=(.+)$')
    if ($match.Success) {
        $installedPatchVersion = $match.Groups[1].Value.Trim()
    }
}

$compat = $manifests | Where-Object { [string]$_.plugin.version -eq $pluginVersion } | Select-Object -First 1
$integrity = $null -ne $compat -and [string]$compat.patchVersion -eq $installedPatchVersion
foreach ($name in $jarSpecs.Keys) {
    $expected = if ($compat) { [string]$compat.jars.$name.patchedSha256 } else { $null }
    $matches = [bool]($expected -and $jarStatus[$name].sha256 -eq $expected)
    $jarStatus[$name]['expectedPatchedSha256'] = $expected
    $jarStatus[$name]['integrity'] = $matches
    $integrity = $integrity -and $matches
}
$manifestPath = Join-Path $context.LocalCodexHome 'jetbrains-ai-wsl-patch.env'
$runtime = Read-KeyValueFile -Path $manifestPath
$windowsNode = [string]$runtime['WINDOWS_NODE']
$windowsAcp = [string]$runtime['WINDOWS_ACP_ENTRY']
$windowsCodex = [string]$runtime['WINDOWS_CODEX']
$windowsStatus = [ordered]@{
    node = Invoke-Version -Command $windowsNode -Arguments @('--version')
    acp = Invoke-Version -Command $windowsNode -Arguments @($windowsAcp, '--version')
    codex = Invoke-Version -Command $windowsCodex -Arguments @('--version')
    codexHome = [string]$runtime['WINDOWS_CODEX_HOME']
}

$wslStatus = $null
$wsl = if ($SkipWsl) { $null } else { Resolve-WslTarget -Distribution $WslDistribution -User $WslUser }
if ($wsl) {
    $prefix = "WSL_$($wsl.Key)_"
    $wslNode = [string]$runtime["${prefix}NODE"]
    $wslAcp = [string]$runtime["${prefix}ACP_ENTRY"]
    $wslCodex = [string]$runtime["${prefix}CODEX"]
    $wslStatus = [ordered]@{
        distribution = $wsl.Distribution
        user = $wsl.User
        node = Invoke-WslVersion -Wsl $wsl -Command $wslNode -Arguments @('--version')
        acp = Invoke-WslVersion -Wsl $wsl -Command $wslNode -Arguments @($wslAcp, '--version')
        codex = Invoke-WslVersion -Wsl $wsl -Command $wslCodex -Arguments @('--version')
        codexHome = [string]$runtime["${prefix}CODEX_HOME"]
    }
}

$allPatched = @($jarStatus.Values | Where-Object { -not $_.patched }).Count -eq 0 -and $integrity
$result = [ordered]@{
    patchVersion = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim()
    ide = [ordered]@{
        home = $context.IdeHome
        profile = $context.Profile
        build = $context.IdeBuild
    }
    plugin = [ordered]@{
        targetKind = $targetKind
        target = $target
        version = $pluginVersion
        installedVersion = $pluginTarget.InstalledVersion
        pendingVersion = $pluginTarget.PendingVersion
        patchVersion = $installedPatchVersion
        supported = $null -ne $compat
        patched = $allPatched
        integrity = $integrity
        jars = $jarStatus
    }
    expectedRuntime = [ordered]@{
        codex = [string]$lock.codex.version
        acp = [string]$lock.acp.version
    }
    runtimeManifest = $manifestPath
    windows = $windowsStatus
    wsl = $wslStatus
}

if ($Json) {
    Write-Output ($result | ConvertTo-Json -Depth 12 -Compress)
}
else {
    $result | ConvertTo-Json -Depth 12
}
