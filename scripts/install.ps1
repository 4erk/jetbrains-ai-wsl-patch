[CmdletBinding()]
param(
    [string]$IdeHome,
    [string]$Profile,
    [string]$WslDistribution,
    [string]$WslUser,
    [switch]$SkipWsl,
    [switch]$SkipRuntime
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\Common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Copy-PatchedJars {
    param(
        [Parameter(Mandatory)][string]$PatchedRoot,
        [Parameter(Mandatory)][string]$PluginRoot
    )
    Get-ChildItem -LiteralPath $PatchedRoot -Recurse -Filter '*.jar' | ForEach-Object {
        $relative = $_.FullName.Substring($PatchedRoot.Length).TrimStart('\')
        $target = Join-Path $PluginRoot $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
    }
}

function Get-PluginPatchState {
    param(
        [Parameter(Mandatory)][string]$PluginRoot,
        [Parameter(Mandatory)][string]$ExpectedPatchVersion
    )

    $targets = @(
        @('lib\ml-llm.jar', 'com/intellij/ml/llm/agents/acp/process/CodexRuntimePatchSupport.class'),
        @('lib\modules\intellij.ml.llm.chat.jar', 'com/intellij/ml/llm/core/chat/ui/chat/CodexUsageLimitPatchSupport.class'),
        @('lib\modules\intellij.ml.llm.agents.frontend.jar', 'com/intellij/ml/llm/agents/frontend/compose/ui/components/utils/MarkdownWslLinkPatchSupport.class')
    )
    $markerCount = 0
    $metadataCount = 0
    $metadata = $null
    foreach ($target in $targets) {
        $jar = Join-Path $PluginRoot $target[0]
        if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
            throw "JetBrains AI jar is missing: $jar"
        }
        if (Test-JarContainsEntry -JarPath $jar -Entry $target[1]) {
            $markerCount++
        }
        $jarMetadata = Get-JarEntryText -JarPath $jar -Entry 'META-INF/jetbrains-ai-wsl-patch.properties'
        if ($jarMetadata) {
            $metadataCount++
            if (-not $metadata) {
                $metadata = $jarMetadata
            } elseif ($metadata -ne $jarMetadata) {
                throw 'Patched JAR metadata is inconsistent.'
            }
        }
    }

    if ($markerCount -eq 0 -and $metadataCount -eq 0) {
        return 'clean'
    }
    if ($markerCount -ne $targets.Count -or $metadataCount -ne $targets.Count) {
        throw 'JetBrains AI is partially or legacy patched. Restore a verified clean backup before installing.'
    }
    $properties = @{}
    foreach ($line in $metadata -split "`r?`n") {
        $parts = $line -split '=', 2
        if ($parts.Count -eq 2) {
            $properties[$parts[0]] = $parts[1]
        }
    }
    if ([string]$properties['patchVersion'] -ne $ExpectedPatchVersion) {
        throw "JetBrains AI contains patch $($properties['patchVersion']); restore clean JARs before installing $ExpectedPatchVersion."
    }
    $pluginVersion = Get-PluginVersionFromJar -JarPath (Join-Path $PluginRoot 'lib\ml-llm.jar')
    if ([string]$properties['pluginVersion'] -ne $pluginVersion) {
        throw "Patch metadata targets $($properties['pluginVersion']), but the plugin is $pluginVersion."
    }
    return 'current'
}

function Assert-PatchedPluginIntegrity {
    param(
        [Parameter(Mandatory)][string]$PluginRoot,
        [Parameter(Mandatory)]$Compatibility
    )

    if ([string]$Compatibility.patchVersion -ne (Get-Content -LiteralPath (Join-Path (Get-RepositoryRoot) 'VERSION') -Raw).Trim()) {
        throw "Compatibility manifest patch version does not match this release."
    }
    foreach ($jar in @($Compatibility.jars.runtime, $Compatibility.jars.chat, $Compatibility.jars.frontend)) {
        $path = Join-Path $PluginRoot ([string]$jar.path)
        $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        if (-not $jar.patchedSha256 -or $actual -ne [string]$jar.patchedSha256) {
            throw "Patched JAR integrity check failed for $($jar.path). Expected $($jar.patchedSha256), got $actual."
        }
    }
}

$repoRoot = Get-RepositoryRoot
$context = Resolve-JetBrainsContext -IdeHome $IdeHome -Profile $Profile
Assert-IdeStopped -Context $context

$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$backupRoot = Join-Path $repoRoot ".state\backups\$timestamp"
$installationRoot = Join-Path $repoRoot '.state\installations'
New-Item -ItemType Directory -Path $backupRoot, $installationRoot -Force | Out-Null
$mode = $null
$targetPath = $null
$sourcePluginRoot = $null
$patchedRoot = Join-Path $repoRoot '.build\patched'
$patchVersion = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim()
$pluginTarget = Resolve-JetBrainsPluginTarget -Context $context

if ($pluginTarget.Mode -eq 'pending-update') {
    $mode = 'pending-update'
    $targetPath = $context.PendingPluginZip
    $stagingRoot = Join-Path $repoRoot '.build\pending-plugin'
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($context.PendingPluginZip, $stagingRoot)
    $sourcePluginRoot = Join-Path $stagingRoot 'ml-llm'
    if (-not (Test-Path -LiteralPath (Join-Path $sourcePluginRoot 'lib\ml-llm.jar'))) {
        $sourcePluginRoot = Get-ChildItem -LiteralPath $stagingRoot -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'lib\ml-llm.jar') } |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $sourcePluginRoot) {
        throw "The pending archive does not contain a JetBrains AI plugin: $($context.PendingPluginZip)"
    }

    $runtimeJar = Join-Path $sourcePluginRoot 'lib\ml-llm.jar'
    $alreadyPatched = (Get-PluginPatchState -PluginRoot $sourcePluginRoot -ExpectedPatchVersion $patchVersion) -eq 'current'
    if ($alreadyPatched) {
        $pluginVersion = Get-PluginVersionFromJar -JarPath $runtimeJar
        $compat = Get-Content -LiteralPath (Join-Path $repoRoot "compatibility\$pluginVersion.json") -Raw | ConvertFrom-Json
        Assert-PatchedPluginIntegrity -PluginRoot $sourcePluginRoot -Compatibility $compat
    }
    if (-not $alreadyPatched) {
        & (Join-Path $PSScriptRoot 'build.ps1') -PluginRoot $sourcePluginRoot -IdeHome $context.IdeHome -OutputRoot $patchedRoot
        Copy-PatchedJars -PatchedRoot $patchedRoot -PluginRoot $sourcePluginRoot
        $zipBackup = Join-Path $backupRoot 'ml-llm.zip'
        Copy-Item -LiteralPath $context.PendingPluginZip -Destination $zipBackup
        $temporaryZip = "$($context.PendingPluginZip).patching"
        Remove-Item -LiteralPath $temporaryZip -Force -ErrorAction SilentlyContinue
        [IO.Compression.ZipFile]::CreateFromDirectory(
            $stagingRoot,
            $temporaryZip,
            [IO.Compression.CompressionLevel]::Optimal,
            $false
        )
        Move-Item -LiteralPath $temporaryZip -Destination $context.PendingPluginZip -Force
    }
}
else {
    $mode = 'installed-plugin'
    $targetPath = $context.PluginRoot
    $sourcePluginRoot = $context.PluginRoot
    if (-not (Test-Path -LiteralPath (Join-Path $sourcePluginRoot 'lib\ml-llm.jar'))) {
        throw "JetBrains AI plugin is not installed under $sourcePluginRoot"
    }

    $runtimeJar = Join-Path $sourcePluginRoot 'lib\ml-llm.jar'
    $alreadyPatched = (Get-PluginPatchState -PluginRoot $sourcePluginRoot -ExpectedPatchVersion $patchVersion) -eq 'current'
    if ($alreadyPatched) {
        $pluginVersion = Get-PluginVersionFromJar -JarPath $runtimeJar
        $compat = Get-Content -LiteralPath (Join-Path $repoRoot "compatibility\$pluginVersion.json") -Raw | ConvertFrom-Json
        Assert-PatchedPluginIntegrity -PluginRoot $sourcePluginRoot -Compatibility $compat
    }
    if (-not $alreadyPatched) {
        & (Join-Path $PSScriptRoot 'build.ps1') -PluginRoot $sourcePluginRoot -IdeHome $context.IdeHome -OutputRoot $patchedRoot
        $compat = Get-Content -LiteralPath (Get-ChildItem (Join-Path $repoRoot 'compatibility') -Filter '*.json' |
            Where-Object {
                $manifest = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
                [string]$manifest.jars.runtime.sha256 -eq (Get-FileHash -LiteralPath $runtimeJar -Algorithm SHA256).Hash
            } |
            Select-Object -First 1 -ExpandProperty FullName) -Raw | ConvertFrom-Json
        foreach ($jar in @($compat.jars.runtime, $compat.jars.chat, $compat.jars.frontend)) {
            $relative = [string]$jar.path
            $source = Join-Path $sourcePluginRoot $relative
            $backup = Join-Path $backupRoot $relative
            New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
            Copy-Item -LiteralPath $source -Destination $backup
        }
        Copy-PatchedJars -PatchedRoot $patchedRoot -PluginRoot $sourcePluginRoot
    }
}

$runtimeReport = $null
if (-not $SkipRuntime) {
    $runtimeArgs = @{
        IdeHome = $context.IdeHome
        Profile = $context.Profile
        WslDistribution = $WslDistribution
        WslUser = $WslUser
        SkipWsl = $SkipWsl
    }
    $runtimeReport = & (Join-Path $PSScriptRoot 'install-runtime.ps1') @runtimeArgs | Select-Object -Last 1
}

$state = [ordered]@{
    schema = 1
    patchVersion = $patchVersion
    installedAtUtc = [DateTime]::UtcNow.ToString('o')
    mode = $mode
    profile = $context.Profile
    ideHome = $context.IdeHome
    ideBuild = $context.IdeBuild
    target = $targetPath
    backupRoot = $backupRoot
    backupAvailable = $null -ne (Get-ChildItem -LiteralPath $backupRoot -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1)
    targetSha256 = if (Test-Path -LiteralPath $targetPath -PathType Leaf) {
        (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
    } else { $null }
    runtime = if ($runtimeReport) { $runtimeReport | ConvertFrom-Json } else { $null }
}
$statePath = Join-Path $installationRoot "$timestamp.json"
$stateJson = $state | ConvertTo-Json -Depth 12
Write-Utf8NoBom -Path $statePath -Lines @($stateJson)

Write-Output $stateJson
