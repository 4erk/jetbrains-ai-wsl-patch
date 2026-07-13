[CmdletBinding()]
param(
    [string]$IdeHome,
    [string]$Profile,
    [string]$WslDistribution,
    [string]$WslUser,
    [switch]$SkipWsl
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\Common.ps1')

$repoRoot = Get-RepositoryRoot
$context = Resolve-JetBrainsContext -IdeHome $IdeHome -Profile $Profile
$lock = Get-Content -LiteralPath (Join-Path $repoRoot 'runtime.lock.json') -Raw | ConvertFrom-Json
$wsl = if ($SkipWsl) { $null } else { Resolve-WslTarget -Distribution $WslDistribution -User $WslUser }
$runtimeRoot = Join-Path $context.LocalCodexHome 'runtime'

$windowsArch = switch ([Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
    'Arm64' { 'arm64' }
    'X64' { 'x64' }
    default { throw "Unsupported Windows architecture: $([Runtime.InteropServices.RuntimeInformation]::OSArchitecture)" }
}

$windowsNode = Get-WindowsManagedNode
if ($windowsNode) {
    $managedWindowsNodeVersion = (& $windowsNode --version).Trim().TrimStart('v')
    if ([version]$managedWindowsNodeVersion -lt [version]"$($lock.node.minimumMajor).0") {
        $windowsNode = $null
    }
}
if (-not $windowsNode) {
    $nodeAsset = $lock.node.assets."windows-$windowsArch"
    $nodeRoot = Join-Path $runtimeRoot "node\$($lock.node.preferredVersion)"
    $windowsNode = Join-Path $nodeRoot 'node.exe'
    if (-not (Test-Path -LiteralPath $windowsNode -PathType Leaf)) {
        $downloadRoot = Join-Path $repoRoot '.build\downloads'
        $archive = Join-Path $downloadRoot ([string]$nodeAsset.name)
        $extract = Join-Path $downloadRoot "node-windows-$windowsArch"
        $partial = "$archive.part"
        New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
        Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
        $url = "$($lock.node.baseUrl)/$($nodeAsset.name)"
        & curl.exe --silent --show-error -fL --retry 3 --retry-all-errors --connect-timeout 20 -o $partial $url
        if ($LASTEXITCODE -ne 0) {
            throw "Node download failed with exit code ${LASTEXITCODE}: $url"
        }
        Move-Item -LiteralPath $partial -Destination $archive -Force
        Test-Sha256 -Path $archive -Expected ([string]$nodeAsset.sha256)
        Remove-Item -LiteralPath $extract, $nodeRoot -Recurse -Force -ErrorAction SilentlyContinue
        Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
        $sourceRoot = Get-ChildItem -LiteralPath $extract -Directory | Select-Object -First 1
        if (-not $sourceRoot) {
            throw "Node archive layout is invalid: $archive"
        }
        New-Item -ItemType Directory -Path $nodeRoot -Force | Out-Null
        Copy-Item -Path (Join-Path $sourceRoot.FullName '*') -Destination $nodeRoot -Recurse -Force
    }
}
$windowsNodeVersion = (& $windowsNode --version).Trim().TrimStart('v')
if ([version]$windowsNodeVersion -lt [version]"$($lock.node.minimumMajor).0") {
    throw "Windows Node $windowsNodeVersion is too old; Node $($lock.node.minimumMajor)+ is required."
}

$acpRoot = Join-Path $runtimeRoot "acp\$($lock.acp.version)"
$acpPackageJson = Join-Path $acpRoot 'node_modules\@agentclientprotocol\codex-acp\package.json'
$acpEntry = Join-Path $acpRoot 'node_modules\@agentclientprotocol\codex-acp\dist\index.js'
$npm = Join-Path (Split-Path -Parent $windowsNode) 'npm.cmd'
if (-not (Test-Path -LiteralPath $npm -PathType Leaf)) {
    throw "npm is missing next to the JetBrains Node runtime: $npm"
}

$installedAcpVersion = $null
if (Test-Path -LiteralPath $acpPackageJson -PathType Leaf) {
    $installedAcpVersion = [string](Get-Content -LiteralPath $acpPackageJson -Raw | ConvertFrom-Json).version
}
if ($installedAcpVersion -ne [string]$lock.acp.version) {
    New-Item -ItemType Directory -Path $acpRoot -Force | Out-Null
    & $npm install --prefix $acpRoot --omit=dev --ignore-scripts "$($lock.acp.package)@$($lock.acp.version)" | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Windows ACP installation failed with exit code $LASTEXITCODE"
    }
}
if (-not (Test-Path -LiteralPath $acpEntry -PathType Leaf)) {
    throw "Windows ACP entry point is missing: $acpEntry"
}

$codexAsset = $lock.codex.assets."windows-$windowsArch"
$codexRoot = Join-Path $runtimeRoot "codex\$($lock.codex.version)"
$windowsCodex = Join-Path $codexRoot 'codex.exe'
$currentWindowsCodex = $null
if (Test-Path -LiteralPath $windowsCodex) {
    try {
        $currentWindowsCodex = (& $windowsCodex --version 2>$null)
    }
    catch {
        $currentWindowsCodex = $null
    }
}
if ($currentWindowsCodex -notmatch [regex]::Escape([string]$lock.codex.version)) {
    $downloadRoot = Join-Path $repoRoot '.build\downloads'
    $archive = Join-Path $downloadRoot ([string]$codexAsset.name)
    $extract = Join-Path $downloadRoot "codex-windows-$windowsArch"
    New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
    $url = "https://github.com/openai/codex/releases/download/$($lock.codex.tag)/$($codexAsset.name)"
    $partial = "$archive.part"
    Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
    & curl.exe --silent --show-error -fL --retry 3 --retry-all-errors --connect-timeout 20 -o $partial $url
    if ($LASTEXITCODE -ne 0) {
        throw "Codex download failed with exit code ${LASTEXITCODE}: $url"
    }
    Move-Item -LiteralPath $partial -Destination $archive -Force
    Test-Sha256 -Path $archive -Expected ([string]$codexAsset.sha256)
    Remove-Item -LiteralPath $extract -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
    $executable = Get-ChildItem -LiteralPath $extract -Recurse -Filter '*.exe' |
        Sort-Object Length -Descending |
        Select-Object -First 1
    if (-not $executable) {
        throw "Codex executable was not found in $archive"
    }
    New-Item -ItemType Directory -Path $codexRoot -Force | Out-Null
    Copy-Item -LiteralPath $executable.FullName -Destination $windowsCodex -Force
}

$userBin = Join-Path $env:USERPROFILE '.local\bin'
New-Item -ItemType Directory -Path $userBin -Force | Out-Null
Copy-Item -LiteralPath $windowsCodex -Destination (Join-Path $userBin 'codex.exe') -Force
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not (($userPath -split ';') -contains $userBin)) {
    [Environment]::SetEnvironmentVariable('Path', "$userBin;$userPath".TrimEnd(';'), 'User')
}

$manifest = @(
    '# Generated by jetbrains-ai-wsl-patch. Paths use forward slashes intentionally.',
    'SCHEMA=1',
    "PATCH_VERSION=$((Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim())",
    "WINDOWS_NODE=$($windowsNode.Replace('\', '/'))",
    "WINDOWS_ACP_ENTRY=$($acpEntry.Replace('\', '/'))",
    "WINDOWS_CODEX=$($windowsCodex.Replace('\', '/'))",
    "WINDOWS_CODEX_HOME=$($context.LocalCodexHome.Replace('\', '/'))"
)

$wslResult = $null
if ($wsl) {
    $wslArch = (& wsl.exe -d $wsl.Distribution -u $wsl.User -- uname -m).Trim()
    $wslArch = switch ($wslArch) {
        'x86_64' { 'x64' }
        'aarch64' { 'arm64' }
        default { throw "Unsupported WSL architecture: $wslArch" }
    }
    $wslCodexHome = "$($wsl.Home)/.local/share/JetBrains/$($context.Profile)/aia/codex"
    $wslNode = Get-WslManagedNode -Context $context -Wsl $wsl
    if ($wslNode) {
        $managedWslNodeVersion = (& wsl.exe -d $wsl.Distribution -u $wsl.User -- $wslNode --version).Trim().TrimStart('v')
        if ([version]$managedWslNodeVersion -lt [version]"$($lock.node.minimumMajor).0") {
            $wslNode = $null
        }
    }
    if (-not $wslNode) {
        $nodeAsset = $lock.node.assets."linux-$wslArch"
        $wslNodeRoot = "$wslCodexHome/runtime/node/$($lock.node.preferredVersion)"
        $wslNode = "$wslNodeRoot/bin/node"
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- test -x $wslNode
        if ($LASTEXITCODE -ne 0) {
            $wslArchive = "/tmp/jetbrains-ai-wsl-patch-node-$($lock.node.preferredVersion)-$wslArch.tar.xz"
            $url = "$($lock.node.baseUrl)/$($nodeAsset.name)"
            & wsl.exe -d $wsl.Distribution -u $wsl.User -- rm -f $wslArchive
            & wsl.exe -d $wsl.Distribution -u $wsl.User -- rm -rf $wslNodeRoot
            & wsl.exe -d $wsl.Distribution -u $wsl.User -- mkdir -p $wslNodeRoot
            & wsl.exe -d $wsl.Distribution -u $wsl.User -- curl --silent --show-error -fL --retry 3 --retry-all-errors --connect-timeout 20 -o $wslArchive $url
            if ($LASTEXITCODE -ne 0) {
                throw "WSL Node download failed with exit code $LASTEXITCODE"
            }
            $actualNodeHash = ((& wsl.exe -d $wsl.Distribution -u $wsl.User -- sha256sum $wslArchive) -split '\s+')[0]
            if ($actualNodeHash -ne [string]$nodeAsset.sha256) {
                throw "WSL Node SHA-256 mismatch. Expected $($nodeAsset.sha256), got $actualNodeHash"
            }
            & wsl.exe -d $wsl.Distribution -u $wsl.User -- tar -xJf $wslArchive --strip-components=1 -C $wslNodeRoot
            if ($LASTEXITCODE -ne 0) {
                throw "WSL Node extraction failed with exit code $LASTEXITCODE"
            }
            & wsl.exe -d $wsl.Distribution -u $wsl.User -- rm -f $wslArchive
        }
    }
    $wslNodeVersion = (& wsl.exe -d $wsl.Distribution -u $wsl.User -- $wslNode --version).Trim().TrimStart('v')
    if ([version]$wslNodeVersion -lt [version]"$($lock.node.minimumMajor).0") {
        throw "WSL Node $wslNodeVersion is too old; Node $($lock.node.minimumMajor)+ is required."
    }

    $wslAcpRoot = "$wslCodexHome/runtime/acp/$($lock.acp.version)"
    $wslAcpEntry = "$wslAcpRoot/node_modules/@agentclientprotocol/codex-acp/dist/index.js"
    $wslNodeBin = $wslNode.Substring(0, $wslNode.LastIndexOf('/'))
    $wslNpm = "$wslNodeBin/npm"
    $packageSpec = "$($lock.acp.package)@$($lock.acp.version)"
    & wsl.exe -d $wsl.Distribution -u $wsl.User -- mkdir -p $wslAcpRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create the WSL ACP runtime directory."
    }
    $wslPath = "$wslNodeBin`:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    $wslPackageJson = "$wslAcpRoot/node_modules/@agentclientprotocol/codex-acp/package.json"
    $installedWslAcpVersion = $null
    & wsl.exe -d $wsl.Distribution -u $wsl.User -- test -f $wslPackageJson
    if ($LASTEXITCODE -eq 0) {
        $wslPackage = ((& wsl.exe -d $wsl.Distribution -u $wsl.User -- cat $wslPackageJson) -join "`n") | ConvertFrom-Json
        $installedWslAcpVersion = [string]$wslPackage.version
    }
    if ($installedWslAcpVersion -ne [string]$lock.acp.version) {
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- env "PATH=$wslPath" $wslNpm install --prefix $wslAcpRoot --omit=dev --ignore-scripts $packageSpec | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "WSL ACP installation failed with exit code $LASTEXITCODE"
        }
    }

    $linuxAsset = $lock.codex.assets."linux-$wslArch"
    $wslCodexRoot = "$wslCodexHome/runtime/codex/$($lock.codex.version)"
    $wslCodex = "$wslCodexRoot/codex"
    $url = "https://github.com/openai/codex/releases/download/$($lock.codex.tag)/$($linuxAsset.name)"
    $currentWslCodex = $null
    try {
        $currentWslCodex = (& wsl.exe -d $wsl.Distribution -u $wsl.User -- $wslCodex --version 2>$null)
    }
    catch {
        $currentWslCodex = $null
    }
    if ($currentWslCodex -notmatch [regex]::Escape([string]$lock.codex.version)) {
        $wslArchive = "/tmp/jetbrains-ai-wsl-patch-codex-$($lock.codex.version)-$wslArch.tar.gz"
        $wslExtract = "/tmp/jetbrains-ai-wsl-patch-codex-$($lock.codex.version)-$wslArch"
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- rm -f $wslArchive
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- rm -rf $wslExtract
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- mkdir -p $wslExtract $wslCodexRoot
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- curl --silent --show-error -fL --retry 3 --retry-all-errors --connect-timeout 20 -o $wslArchive $url
        if ($LASTEXITCODE -ne 0) {
            throw "WSL Codex download failed with exit code $LASTEXITCODE"
        }
        $actualWslHash = ((& wsl.exe -d $wsl.Distribution -u $wsl.User -- sha256sum $wslArchive) -split '\s+')[0]
        if ($actualWslHash -ne [string]$linuxAsset.sha256) {
            throw "WSL Codex SHA-256 mismatch. Expected $($linuxAsset.sha256), got $actualWslHash"
        }
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- tar -xzf $wslArchive -C $wslExtract
        if ($LASTEXITCODE -ne 0) {
            throw "WSL Codex extraction failed with exit code $LASTEXITCODE"
        }
        $wslExecutable = @(& wsl.exe -d $wsl.Distribution -u $wsl.User -- find $wslExtract -maxdepth 2 -type f -name 'codex*') | Select-Object -First 1
        if (-not $wslExecutable) {
            throw "WSL Codex executable was not found after extraction."
        }
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- install -m 0755 $wslExecutable $wslCodex
        if ($LASTEXITCODE -ne 0) {
            throw "WSL Codex installation failed with exit code $LASTEXITCODE"
        }
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- rm -f $wslArchive
        & wsl.exe -d $wsl.Distribution -u $wsl.User -- rm -rf $wslExtract
    }
    & wsl.exe -d $wsl.Distribution -u $wsl.User -- mkdir -p "$($wsl.Home)/.local/bin"
    & wsl.exe -d $wsl.Distribution -u $wsl.User -- ln -sfn $wslCodex "$($wsl.Home)/.local/bin/codex"

    $manifest += @(
        "WSL_DEFAULT_DISTRIBUTION=$($wsl.Distribution)",
        "WSL_DEFAULT_USER=$($wsl.User)",
        "WSL_$($wsl.Key)_USER=$($wsl.User)",
        "WSL_$($wsl.Key)_HOME=$($wsl.Home)",
        "WSL_$($wsl.Key)_NODE=$wslNode",
        "WSL_$($wsl.Key)_ACP_ENTRY=$wslAcpEntry",
        "WSL_$($wsl.Key)_CODEX=$wslCodex",
        "WSL_$($wsl.Key)_CODEX_HOME=$wslCodexHome"
    )
    $wslResult = [ordered]@{
        distribution = $wsl.Distribution
        user = $wsl.User
        node = (& wsl.exe -d $wsl.Distribution -u $wsl.User -- $wslNode --version).Trim()
        acp = (& wsl.exe -d $wsl.Distribution -u $wsl.User -- $wslNode $wslAcpEntry --version).Trim()
        codex = (& wsl.exe -d $wsl.Distribution -u $wsl.User -- $wslCodex --version).Trim()
    }
}

$manifestPath = Join-Path $context.LocalCodexHome 'jetbrains-ai-wsl-patch.env'
Write-Utf8NoBom -Path $manifestPath -Lines $manifest

$syncArgs = @{
    IdeHome = $context.IdeHome
    Profile = $context.Profile
    WslDistribution = $WslDistribution
    WslUser = $WslUser
    SkipWsl = $SkipWsl
}
& (Join-Path $PSScriptRoot 'sync-codex-state.ps1') @syncArgs | Out-Host

[ordered]@{
    profile = $context.Profile
    manifest = $manifestPath
    windows = [ordered]@{
        node = (& $windowsNode --version).Trim()
        acp = (& $windowsNode $acpEntry --version).Trim()
        codex = (& $windowsCodex --version).Trim()
    }
    wsl = $wslResult
} | ConvertTo-Json -Depth 8 -Compress
