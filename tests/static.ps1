[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $repoRoot 'scripts\lib\Common.ps1')
$failures = [Collections.Generic.List[string]]::new()

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        $failures.Add($Message)
    }
}

$stableJson = ConvertTo-StableJson -InputObject ([ordered]@{
    outer = [ordered]@{ value = 1 }
    empty = @()
    text = 'a:b,c'
})
$expectedStableJson = "{`n  `"outer`": {`n    `"value`": 1`n  },`n  `"empty`": [],`n  `"text`": `"a:b,c`"`n}"
Assert-True ($stableJson -ceq $expectedStableJson) 'Stable JSON formatter output changed.'

$powerShellFiles = Get-ChildItem -LiteralPath $repoRoot -Recurse -Filter '*.ps1' |
    Where-Object { $_.FullName -notmatch '[\\/]\.build[\\/]|[\\/]\.state[\\/]' }
foreach ($file in $powerShellFiles) {
    $tokens = $null
    $errors = $null
    [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    foreach ($error in $errors) {
        $failures.Add("PowerShell parse error in $($file.FullName): $($error.Message)")
    }
}

$jsonFiles = @(
    Get-Item -LiteralPath (Join-Path $repoRoot 'runtime.lock.json')
    Get-ChildItem -LiteralPath (Join-Path $repoRoot 'compatibility') -Filter '*.json'
    Get-ChildItem -LiteralPath (Join-Path $repoRoot 'docs\workstation') -Filter '*.json'
)
foreach ($file in $jsonFiles) {
    try {
        $parsed = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
        if ($file.Directory.Name -eq 'compatibility') {
            $repositoryVersion = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim()
            Assert-True ([string]$parsed.patchVersion -eq $repositoryVersion) "Compatibility patch version differs from VERSION in $($file.FullName)"
            foreach ($jar in @($parsed.jars.runtime, $parsed.jars.chat, $parsed.jars.frontend)) {
                Assert-True ([string]$jar.patchedSha256 -match '^[A-Fa-f0-9]{64}$') "Patched SHA-256 is missing in $($file.FullName): $($jar.path)"
            }
        } elseif ($file.Name -eq 'runtime.lock.json') {
            foreach ($asset in @($parsed.codex.assets.PSObject.Properties.Value) + @($parsed.node.assets.PSObject.Properties.Value)) {
                Assert-True ([string]$asset.sha256 -match '^[A-Fa-f0-9]{64}$') "Runtime asset SHA-256 is invalid: $($asset.name)"
            }
            Assert-True ([string]$parsed.acp.rateLimitBridge.cleanSha256 -match '^[A-Fa-f0-9]{64}$') 'ACP bridge clean SHA-256 is missing.'
            Assert-True ([string]$parsed.acp.rateLimitBridge.patchedSha256 -match '^[A-Fa-f0-9]{64}$') 'ACP bridge patched SHA-256 is missing.'
            Assert-True ([string]$parsed.acp.rateLimitBridge.backupSuffix -eq '.last-good') 'ACP bridge backup suffix must remain .last-good.'
            Assert-True ([int]$parsed.acp.rateLimitBridge.refreshSeconds -eq 20) 'ACP bridge refresh interval must remain 20 seconds.'
        } elseif ($file.Name -eq 'current-baseline.json') {
            $repositoryVersion = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim()
            $compatibilityPath = Join-Path $repoRoot "compatibility\$([string]$parsed.patch.jetBrainsAiVersion).json"
            Assert-True ([string]$parsed.patch.version -eq $repositoryVersion) 'Workstation baseline patch version differs from VERSION.'
            Assert-True (Test-Path -LiteralPath $compatibilityPath -PathType Leaf) 'Workstation baseline JetBrains AI version has no compatibility manifest.'
            Assert-True ([string]$parsed.patch.tag -eq "jbai-$([string]$parsed.patch.jetBrainsAiVersion)-patch-$repositoryVersion") 'Workstation baseline release tag is inconsistent.'
            Assert-True (@($parsed.pluginDirectories).Count -eq @($parsed.pluginDirectories | Select-Object -Unique).Count) 'Workstation baseline contains duplicate plugin directories.'
            Assert-True (@($parsed.disabledPluginIds).Count -eq @($parsed.disabledPluginIds | Select-Object -Unique).Count) 'Workstation baseline contains duplicate disabled plugin IDs.'
        }
    }
    catch {
        $failures.Add("Invalid JSON in $($file.FullName): $($_.Exception.Message)")
    }
}

$sourceFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $repoRoot 'scripts') -Recurse -File
    Get-ChildItem -LiteralPath (Join-Path $repoRoot 'src') -Recurse -File
)
$sourceText = ($sourceFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
foreach ($forbidden in @('4erk', 'Ubuntu', 'IntelliJIdea2026.1')) {
    Assert-True ($sourceText.IndexOf($forbidden, [StringComparison]::OrdinalIgnoreCase) -lt 0) "Hardcoded workstation value found in source: $forbidden"
}
foreach ($legacy in @(
    'AcpClientReusePatchSupport',
    'AcpSessionResumePatchSupport',
    'AcpPromptFailurePatchSupport',
    'AcpModelPatchSupport',
    'CodexInstallerPatchSupport'
)) {
    Assert-True ($sourceText.IndexOf($legacy, [StringComparison]::Ordinal) -lt 0) "Legacy patch helper is still referenced: $legacy"
}
Assert-True ($sourceText.IndexOf('return if (', [StringComparison]::OrdinalIgnoreCase) -lt 0) 'Invalid PowerShell return-if expression found.'
Assert-True ($sourceText.IndexOf('logs_2.sqlite', [StringComparison]::OrdinalIgnoreCase) -lt 0) 'SQLite telemetry scraping is still referenced by source.'
Assert-True ($sourceText.IndexOf('account/rateLimits/read', [StringComparison]::Ordinal) -ge 0) 'App-server rate-limit RPC is not referenced by source.'
Assert-True ($sourceText.IndexOf('SessionHistoryCheckpointPatchSupport', [StringComparison]::Ordinal) -ge 0) 'Session history checkpoint helper is not referenced by source.'

$version = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim()
Assert-True ($version -match '^\d+\.\d+\.\d+$') "VERSION is not semantic: $version"
Assert-True (Test-Path -LiteralPath (Join-Path $repoRoot 'AGENTS.md')) 'AGENTS.md is missing.'
Assert-True (Test-Path -LiteralPath (Join-Path $repoRoot 'README.md')) 'README.md is missing.'
Assert-True (Test-Path -LiteralPath (Join-Path $repoRoot 'LICENSE')) 'LICENSE is missing.'

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    throw "$($failures.Count) static test(s) failed."
}
Write-Output "Static tests passed ($($powerShellFiles.Count) PowerShell files, $($jsonFiles.Count) JSON files)."
