[CmdletBinding(SupportsShouldProcess)]
param([switch]$Apply)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\Common.ps1')

function Get-GitHubJson {
    param([Parameter(Mandatory)][string]$Uri)
    return Invoke-RestMethod -Uri $Uri -Headers @{ 'User-Agent' = 'jetbrains-ai-wsl-patch' }
}

function Get-Asset {
    param(
        [Parameter(Mandatory)]$Release,
        [Parameter(Mandatory)][string]$Name
    )
    $asset = $Release.assets | Where-Object name -eq $Name | Select-Object -First 1
    if (-not $asset -or -not ([string]$asset.digest).StartsWith('sha256:')) {
        throw "Release asset or SHA-256 digest is missing: $Name"
    }
    return [ordered]@{
        name = $Name
        sha256 = ([string]$asset.digest).Substring(7)
    }
}

$repoRoot = Get-RepositoryRoot
$path = Join-Path $repoRoot 'runtime.lock.json'
$current = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
$codexRelease = Get-GitHubJson -Uri 'https://api.github.com/repos/openai/codex/releases/latest'
$acpRelease = Get-GitHubJson -Uri 'https://api.github.com/repos/agentclientprotocol/codex-acp/releases/latest'
$acpPackage = Invoke-RestMethod -Uri 'https://registry.npmjs.org/@agentclientprotocol%2Fcodex-acp/latest'

$codexVersion = ([string]$codexRelease.tag_name) -replace '^rust-v', ''
$acpVersion = ([string]$acpRelease.tag_name) -replace '^v', ''
if ($acpVersion -ne [string]$acpPackage.version) {
    throw "ACP GitHub release $acpVersion does not match npm release $($acpPackage.version)."
}

$next = [ordered]@{
    schema = 1
    codex = [ordered]@{
        version = $codexVersion
        tag = [string]$codexRelease.tag_name
        assets = [ordered]@{
            'windows-x64' = Get-Asset -Release $codexRelease -Name 'codex-x86_64-pc-windows-msvc.exe.zip'
            'windows-arm64' = Get-Asset -Release $codexRelease -Name 'codex-aarch64-pc-windows-msvc.exe.zip'
            'linux-x64' = Get-Asset -Release $codexRelease -Name 'codex-x86_64-unknown-linux-musl.tar.gz'
            'linux-arm64' = Get-Asset -Release $codexRelease -Name 'codex-aarch64-unknown-linux-musl.tar.gz'
        }
    }
    acp = [ordered]@{
        package = '@agentclientprotocol/codex-acp'
        version = $acpVersion
        rateLimitBridge = $current.acp.rateLimitBridge
    }
    node = $current.node
}

$changed = ($current | ConvertTo-Json -Depth 12 -Compress) -ne ($next | ConvertTo-Json -Depth 12 -Compress)
$result = [ordered]@{
    changed = $changed
    currentCodex = [string]$current.codex.version
    latestCodex = $codexVersion
    currentAcp = [string]$current.acp.version
    latestAcp = $acpVersion
}
if ($Apply -and $acpVersion -ne [string]$current.acp.version) {
    throw "ACP changed from $($current.acp.version) to $acpVersion. Capture and review new rateLimitBridge clean/patched hashes before applying the lock update."
}
if ($Apply -and $changed -and $PSCmdlet.ShouldProcess($path, 'Update locked Codex and ACP versions')) {
    Write-Utf8NoBom -Path $path -Lines @(ConvertTo-StableJson -InputObject $next -Depth 12)
    $result['applied'] = $true
}
else {
    $result['applied'] = $false
}
$result | ConvertTo-Json -Depth 4
