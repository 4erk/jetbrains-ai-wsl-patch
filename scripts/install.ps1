[CmdletBinding()]
param(
    [string]$IdeHome,
    [string]$Profile,
    [string]$WslDistribution,
    [string]$WslUser,
    [switch]$SkipWsl,
    [switch]$SkipRuntime,
    [switch]$SkipHistoryCache
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
    $pluginVersion = Get-PluginVersionFromJar -JarPath (Join-Path $PluginRoot 'lib\ml-llm.jar')
    if ([string]$properties['pluginVersion'] -ne $pluginVersion) {
        throw "Patch metadata targets $($properties['pluginVersion']), but the plugin is $pluginVersion."
    }
    if ([string]$properties['patchVersion'] -ne $ExpectedPatchVersion) {
        return 'previous'
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

function Invoke-HistoryCachePreparation {
    param(
        [Parameter(Mandatory)][string]$PluginRoot,
        [Parameter(Mandatory)]$Context
    )

    $historyDirectory = Join-Path $env:APPDATA "JetBrains\$($Context.Profile)\aia-task-history"
    if (-not (Test-Path -LiteralPath $historyDirectory -PathType Container)) {
        return $null
    }
    $java = Join-Path $Context.IdeHome 'jbr\bin\java.exe'
    $classpath = @(
        (Join-Path $PluginRoot 'lib\*')
        (Join-Path $PluginRoot 'lib\modules\*')
        (Join-Path $Context.IdeHome 'lib\*')
        (Join-Path $Context.IdeHome 'lib\intellij.libraries.gson.jar')
    ) -join ';'
    $output = & $java -Xmx512m -cp $classpath `
        com.intellij.ml.llm.chat.session.SessionHistoryUiCachePatchSupport `
        --prepare-all $historyDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "Session history UI cache preparation failed with exit code $LASTEXITCODE"
    }
    return ($output | Select-Object -Last 1)
}

function Find-CleanPluginBackup {
    param(
        [Parameter(Mandatory)]$Compatibility,
        [Parameter(Mandatory)][string]$BackupsRoot
    )

    if (-not (Test-Path -LiteralPath $BackupsRoot -PathType Container)) {
        return $null
    }
    foreach ($directory in Get-ChildItem -LiteralPath $BackupsRoot -Directory | Sort-Object Name -Descending) {
        $matches = $true
        foreach ($jar in @($Compatibility.jars.runtime, $Compatibility.jars.chat, $Compatibility.jars.frontend)) {
            $candidate = Join-Path $directory.FullName ([string]$jar.path)
            if (-not (Test-Path -LiteralPath $candidate -PathType Leaf) -or
                (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash -ne [string]$jar.sha256) {
                $matches = $false
                break
            }
        }
        if ($matches) {
            return $directory.FullName
        }
    }
    return $null
}

function Restore-CleanPatchTargets {
    param(
        [Parameter(Mandatory)][string]$PluginRoot,
        [Parameter(Mandatory)][string]$BackupRoot,
        [Parameter(Mandatory)]$Compatibility
    )

    foreach ($jar in @($Compatibility.jars.runtime, $Compatibility.jars.chat, $Compatibility.jars.frontend)) {
        $relative = [string]$jar.path
        Copy-Item -LiteralPath (Join-Path $BackupRoot $relative) `
            -Destination (Join-Path $PluginRoot $relative) -Force
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
    $patchState = Get-PluginPatchState -PluginRoot $sourcePluginRoot -ExpectedPatchVersion $patchVersion
    $alreadyPatched = $patchState -eq 'current'
    if ($alreadyPatched) {
        $pluginVersion = Get-PluginVersionFromJar -JarPath $runtimeJar
        $compat = Get-Content -LiteralPath (Join-Path $repoRoot "compatibility\$pluginVersion.json") -Raw | ConvertFrom-Json
        Assert-PatchedPluginIntegrity -PluginRoot $sourcePluginRoot -Compatibility $compat
    }
    if ($patchState -eq 'previous') {
        $pluginVersion = Get-PluginVersionFromJar -JarPath $runtimeJar
        $compat = Get-Content -LiteralPath (Join-Path $repoRoot "compatibility\$pluginVersion.json") -Raw | ConvertFrom-Json
        $cleanBackup = Find-CleanPluginBackup -Compatibility $compat -BackupsRoot (Join-Path $repoRoot '.state\backups')
        if (-not $cleanBackup) {
            throw "No verified clean backup is available to upgrade the existing patch. Reinstall JetBrains AI, then run the installer again."
        }
        Restore-CleanPatchTargets -PluginRoot $sourcePluginRoot -BackupRoot $cleanBackup -Compatibility $compat
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
    $installedPluginRoot = $context.PluginRoot
    $sourcePluginRoot = $installedPluginRoot
    if (-not (Test-Path -LiteralPath (Join-Path $sourcePluginRoot 'lib\ml-llm.jar'))) {
        throw "JetBrains AI plugin is not installed under $sourcePluginRoot"
    }

    $runtimeJar = Join-Path $sourcePluginRoot 'lib\ml-llm.jar'
    $patchState = Get-PluginPatchState -PluginRoot $sourcePluginRoot -ExpectedPatchVersion $patchVersion
    $alreadyPatched = $patchState -eq 'current'
    if ($alreadyPatched) {
        $pluginVersion = Get-PluginVersionFromJar -JarPath $runtimeJar
        $compat = Get-Content -LiteralPath (Join-Path $repoRoot "compatibility\$pluginVersion.json") -Raw | ConvertFrom-Json
        Assert-PatchedPluginIntegrity -PluginRoot $sourcePluginRoot -Compatibility $compat
    }
    if ($patchState -eq 'previous') {
        $pluginVersion = Get-PluginVersionFromJar -JarPath $runtimeJar
        $compat = Get-Content -LiteralPath (Join-Path $repoRoot "compatibility\$pluginVersion.json") -Raw | ConvertFrom-Json
        $cleanBackup = Find-CleanPluginBackup -Compatibility $compat -BackupsRoot (Join-Path $repoRoot '.state\backups')
        if (-not $cleanBackup) {
            throw "No verified clean backup is available to upgrade the existing patch. Reinstall JetBrains AI, then run the installer again."
        }
        $stagingRoot = Join-Path $repoRoot '.build\clean-plugin-upgrade'
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
        Copy-Item -Path (Join-Path $installedPluginRoot '*') -Destination $stagingRoot -Recurse -Force
        Restore-CleanPatchTargets -PluginRoot $stagingRoot -BackupRoot $cleanBackup -Compatibility $compat
        $sourcePluginRoot = $stagingRoot
        $runtimeJar = Join-Path $sourcePluginRoot 'lib\ml-llm.jar'
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
            $source = Join-Path $installedPluginRoot $relative
            $backup = Join-Path $backupRoot $relative
            New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
            Copy-Item -LiteralPath $source -Destination $backup
        }
        Copy-PatchedJars -PatchedRoot $patchedRoot -PluginRoot $installedPluginRoot
    }
}

$historyCacheReport = $null
if (-not $SkipHistoryCache) {
    $historyCachePluginRoot = if ($mode -eq 'installed-plugin') { $context.PluginRoot } else { $sourcePluginRoot }
    $historyCacheReport = Invoke-HistoryCachePreparation -PluginRoot $historyCachePluginRoot -Context $context
    if ($historyCacheReport) {
        $historyCacheReport | Out-Host
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
    historyCache = $historyCacheReport
    targetSha256 = if (Test-Path -LiteralPath $targetPath -PathType Leaf) {
        (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
    } else { $null }
    runtime = if ($runtimeReport) { $runtimeReport | ConvertFrom-Json } else { $null }
}
$statePath = Join-Path $installationRoot "$timestamp.json"
$stateJson = $state | ConvertTo-Json -Depth 12
Write-Utf8NoBom -Path $statePath -Lines @($stateJson)

Write-Output $stateJson
