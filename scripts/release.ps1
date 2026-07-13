[CmdletBinding()]
param(
    [string]$Remote = 'origin',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\Common.ps1')

$repoRoot = Get-RepositoryRoot
$version = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim()
$branch = (& git -C $repoRoot branch --show-current).Trim()
if (-not $branch) {
    throw 'Release must be created from a named branch.'
}
if ((& git -C $repoRoot status --porcelain)) {
    throw 'Release requires a clean worktree. Commit the verified changes first.'
}

$manifests = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'compatibility') -Filter '*.json' |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json } |
    Where-Object { [string]$_.patchVersion -eq $version })
if ($manifests.Count -eq 0) {
    throw "No compatibility manifest targets patch $version."
}
$tags = @($manifests | ForEach-Object { "jbai-$([string]$_.plugin.version)-patch-$version" })
$head = (& git -C $repoRoot rev-parse HEAD).Trim()
foreach ($tag in $tags) {
    $localTag = [string](& git -C $repoRoot tag --list $tag)
    $localTag = $localTag.Trim()
    if ($localTag) {
        $tagCommit = (& git -C $repoRoot rev-list -n 1 $tag).Trim()
        if ($tagCommit -ne $head) {
            throw "Release tag $tag already points to $tagCommit instead of HEAD $head."
        }
    }
    if (& git -C $repoRoot ls-remote --exit-code --tags $Remote "refs/tags/$tag" 2>$null) {
        throw "Release tag is already published: $tag"
    }
}

if (-not $SkipTests) {
    & (Join-Path $repoRoot 'scripts\test.ps1') | Out-Host
}

foreach ($tag in $tags) {
    if (-not (& git -C $repoRoot tag --list $tag)) {
        $pluginVersion = ($tag -replace '^jbai-', '') -replace "-patch-$([regex]::Escape($version))$", ''
        & git -C $repoRoot tag -a $tag -m "JetBrains AI $pluginVersion patch $version"
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to create tag $tag."
        }
    }
}

$refs = @($branch) + $tags
& git -C $repoRoot push --atomic $Remote @refs
if ($LASTEXITCODE -ne 0) {
    throw "Atomic release push failed for $branch and tags: $($tags -join ', ')"
}

Write-Output $tags
