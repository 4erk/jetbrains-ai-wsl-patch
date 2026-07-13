[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$IdeHome,
    [string]$Profile,
    [string]$BackupZip
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\Common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Find-PluginRoot {
    param([Parameter(Mandatory)][string]$Root)
    $jar = Get-ChildItem -LiteralPath $Root -Recurse -Filter 'ml-llm.jar' |
        Where-Object { $_.FullName -match '[\\/]lib[\\/]ml-llm\.jar$' } |
        Select-Object -First 1
    if ($jar) {
        return Split-Path -Parent (Split-Path -Parent $jar.FullName)
    }
    return $null
}

$repoRoot = Get-RepositoryRoot
$context = Resolve-JetBrainsContext -IdeHome $IdeHome -Profile $Profile
Assert-IdeStopped -Context $context
$compatibility = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'compatibility') -Filter '*.json' |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })

$pluginTarget = Resolve-JetBrainsPluginTarget -Context $context
$targetKind = $pluginTarget.Mode
$targetVersion = if ($targetKind -eq 'pending-update') {
    $pluginTarget.Version
} else {
    $installedRuntimeJar = Join-Path $context.PluginRoot 'lib\ml-llm.jar'
    if (-not (Test-Path -LiteralPath $installedRuntimeJar -PathType Leaf)) {
        throw "JetBrains AI plugin is not installed under $($context.PluginRoot)."
    }
    Get-PluginVersionFromJar -JarPath $installedRuntimeJar
}

$candidates = if ($BackupZip) {
    @(Get-Item -LiteralPath $BackupZip)
} else {
    @(Get-ChildItem -LiteralPath (Join-Path $repoRoot '.state\backups') -Recurse -Filter 'ml-llm.zip' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending)
}

$selected = $null
$selectedManifest = $null
foreach ($candidate in $candidates) {
    $version = Get-PluginVersionFromZip -ZipPath $candidate.FullName
    if ($version -ne $targetVersion) {
        continue
    }
    $runtimeHash = Get-ZipEntrySha256 -ZipPath $candidate.FullName -EntrySuffix 'lib/ml-llm.jar'
    $manifest = $compatibility | Where-Object {
        [string]$_.plugin.version -eq $version -and [string]$_.jars.runtime.sha256 -eq $runtimeHash
    } | Select-Object -First 1
    if ($manifest) {
        $selected = $candidate
        $selectedManifest = $manifest
        break
    }
}
if (-not $selected) {
    throw "No verified clean backup was found for JetBrains AI $targetVersion."
}

$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$preRollback = Join-Path $repoRoot ".state\rollback-backups\$timestamp"
New-Item -ItemType Directory -Path $preRollback -Force | Out-Null

if ($targetKind -eq 'pending-update') {
    if ($PSCmdlet.ShouldProcess($context.PendingPluginZip, "Restore clean JetBrains AI $targetVersion update archive")) {
        Copy-Item -LiteralPath $context.PendingPluginZip -Destination (Join-Path $preRollback 'ml-llm.zip')
        Copy-Item -LiteralPath $selected.FullName -Destination $context.PendingPluginZip -Force
    }
}
else {
    $staging = Join-Path $repoRoot ".build\rollback\$timestamp"
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($selected.FullName, $staging)
    $cleanRoot = Find-PluginRoot -Root $staging
    if (-not $cleanRoot) {
        throw "The verified backup does not contain the JetBrains AI plugin tree."
    }
    foreach ($jar in @($selectedManifest.jars.runtime, $selectedManifest.jars.chat, $selectedManifest.jars.frontend)) {
        $relative = [string]$jar.path
        $source = Join-Path $cleanRoot $relative
        $target = Join-Path $context.PluginRoot $relative
        $actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        if ($actual -ne [string]$jar.sha256) {
            throw "Verified backup changed while restoring $relative."
        }
        if ($PSCmdlet.ShouldProcess($target, "Restore clean $relative")) {
            $backup = Join-Path $preRollback $relative
            New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
            Copy-Item -LiteralPath $target -Destination $backup
            Copy-Item -LiteralPath $source -Destination $target -Force
        }
    }
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
}

$result = [ordered]@{
    restoredAtUtc = [DateTime]::UtcNow.ToString('o')
    mode = $targetKind
    pluginVersion = $targetVersion
    sourceBackup = $selected.FullName
    preRollbackBackup = $preRollback
}
$result | ConvertTo-Json -Depth 6
