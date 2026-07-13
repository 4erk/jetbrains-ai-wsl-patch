[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$failures = [Collections.Generic.List[string]]::new()

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        $failures.Add($Message)
    }
}

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
)
foreach ($file in $jsonFiles) {
    try {
        $parsed = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
        if ($file.Directory.Name -eq 'compatibility') {
            foreach ($jar in @($parsed.jars.runtime, $parsed.jars.chat, $parsed.jars.frontend)) {
                Assert-True ([string]$jar.patchedSha256 -match '^[A-Fa-f0-9]{64}$') "Patched SHA-256 is missing in $($file.FullName): $($jar.path)"
            }
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
