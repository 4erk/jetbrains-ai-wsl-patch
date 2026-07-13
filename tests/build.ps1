[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PluginRoot,
    [Parameter(Mandatory)][string]$IdeHome
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '..\scripts\lib\Common.ps1')
$repoRoot = Get-RepositoryRoot
$output = Join-Path $repoRoot '.build\test-patched'
& (Join-Path $repoRoot 'scripts\build.ps1') -PluginRoot $PluginRoot -IdeHome $IdeHome -OutputRoot $output | Out-Host

$expected = @(
    @('lib\ml-llm.jar', 'com/intellij/ml/llm/agents/acp/process/CodexRuntimePatchSupport.class'),
    @('lib\modules\intellij.ml.llm.chat.jar', 'com/intellij/ml/llm/core/chat/ui/chat/CodexUsageLimitPatchSupport.class'),
    @('lib\modules\intellij.ml.llm.agents.frontend.jar', 'com/intellij/ml/llm/agents/frontend/compose/ui/components/utils/MarkdownWslLinkPatchSupport.class')
)
foreach ($item in $expected) {
    $jar = Join-Path $output $item[0]
    if (-not (Test-JarContainsEntry -JarPath $jar -Entry $item[1])) {
        throw "Patched helper is missing from $($item[0]): $($item[1])"
    }
    if (-not (Test-JarContainsEntry -JarPath $jar -Entry 'META-INF/jetbrains-ai-wsl-patch.properties')) {
        throw "Patch metadata is missing from $($item[0])."
    }
    $sourceHash = (Get-FileHash -LiteralPath (Join-Path $PluginRoot $item[0]) -Algorithm SHA256).Hash
    $patchedHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
    if ($sourceHash -eq $patchedHash) {
        throw "Patcher did not change $($item[0])."
    }
}
Write-Output 'Build test passed.'
