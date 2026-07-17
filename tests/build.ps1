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
    @('lib\modules\intellij.ml.llm.chat.jar', 'com/intellij/ml/llm/chat/session/SessionHistoryCheckpointPatchSupport.class'),
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

$repoRoot = Get-RepositoryRoot
$javac = Join-Path $IdeHome 'jbr\bin\javac.exe'
$java = Join-Path $IdeHome 'jbr\bin\java.exe'
$testClasses = Join-Path $repoRoot '.build\classes\test'
Remove-Item -LiteralPath $testClasses -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
$testSources = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'tests\java') -Recurse -Filter '*Test.java'
$classpath = @(
    (Join-Path $repoRoot '.build\classes\main')
    (Join-Path $PluginRoot 'lib\*')
    (Join-Path $PluginRoot 'lib\modules\*')
    (Join-Path $IdeHome 'lib\*')
) -join ';'
$testArgs = Join-Path $repoRoot '.build\javac-test.args'
[IO.File]::WriteAllLines($testArgs, @(
    '-encoding', 'UTF-8',
    '-cp', ('"{0}"' -f $classpath.Replace('\', '/')),
    '-d', ('"{0}"' -f $testClasses.Replace('\', '/'))
) + @($testSources.FullName | ForEach-Object { '"{0}"' -f $_.Replace('\', '/') }), [Text.UTF8Encoding]::new($false))
& $javac "@$testArgs"
if ($LASTEXITCODE -ne 0) {
    throw "Usage-limit parser test compilation failed with exit code $LASTEXITCODE"
}
& $java -cp "$testClasses;$classpath" com.intellij.ml.llm.core.chat.ui.chat.CodexUsageLimitPatchSupportTest
if ($LASTEXITCODE -ne 0) {
    throw "Usage-limit parser tests failed with exit code $LASTEXITCODE"
}
& $java -cp "$testClasses;$classpath" com.intellij.ml.llm.chat.session.SessionHistoryCheckpointPatchSupportTest
if ($LASTEXITCODE -ne 0) {
    throw "Session checkpoint policy tests failed with exit code $LASTEXITCODE"
}
Write-Output 'Build test passed.'
