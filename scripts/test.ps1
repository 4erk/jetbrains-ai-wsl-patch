[CmdletBinding()]
param(
    [string]$PluginRoot,
    [string]$IdeHome,
    [string]$PatchedAcpEntry,
    [string]$NodePath
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot '..\tests\static.ps1')
if ($PluginRoot -or $IdeHome) {
    if (-not $PluginRoot -or -not $IdeHome) {
        throw 'Both -PluginRoot and -IdeHome are required for the build test.'
    }
    & (Join-Path $PSScriptRoot '..\tests\build.ps1') -PluginRoot $PluginRoot -IdeHome $IdeHome
}
if ($PatchedAcpEntry -or $NodePath) {
    if (-not $PatchedAcpEntry -or -not $NodePath) {
        throw 'Both -PatchedAcpEntry and -NodePath are required for the ACP bridge test.'
    }
    & (Join-Path $PSScriptRoot '..\tests\acp-rate-limit-bridge.ps1') `
        -PatchedEntry $PatchedAcpEntry `
        -NodePath $NodePath
}
