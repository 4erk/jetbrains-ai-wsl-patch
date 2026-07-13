[CmdletBinding()]
param(
    [string]$PluginRoot,
    [string]$IdeHome
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot '..\tests\static.ps1')
if ($PluginRoot -or $IdeHome) {
    if (-not $PluginRoot -or -not $IdeHome) {
        throw 'Both -PluginRoot and -IdeHome are required for the build test.'
    }
    & (Join-Path $PSScriptRoot '..\tests\build.ps1') -PluginRoot $PluginRoot -IdeHome $IdeHome
}
