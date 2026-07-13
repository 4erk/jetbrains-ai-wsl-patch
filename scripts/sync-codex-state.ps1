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

function ConvertTo-WslUncPath {
    param(
        [Parameter(Mandatory)][string]$Distribution,
        [Parameter(Mandatory)][string]$LinuxPath
    )
    return "\\wsl.localhost\$Distribution\$($LinuxPath.TrimStart('/').Replace('/', '\'))"
}

function ConvertTo-WslDrivePath {
    param([Parameter(Mandatory)][string]$WindowsPath)

    $fullPath = [IO.Path]::GetFullPath($WindowsPath)
    $match = [regex]::Match($fullPath, '^([A-Za-z]):\\(.*)$')
    if (-not $match.Success) {
        throw "Only local Windows drive paths can be mapped into WSL: $WindowsPath"
    }
    $drive = $match.Groups[1].Value.ToLowerInvariant()
    $tail = $match.Groups[2].Value.Replace('\', '/')
    return "/mnt/$drive/$tail"
}

function Get-StateFile {
    param(
        [Parameter(Mandatory)][ValidateSet('windows', 'wsl')][string]$Platform,
        [Parameter(Mandatory)][string]$Path,
        [string]$Distribution,
        [string]$User
    )

    if ($Platform -eq 'windows') {
        $item = Get-Item -LiteralPath $Path -ErrorAction SilentlyContinue
        return [pscustomobject]@{
            Platform = $Platform
            Path = $Path
            HostPath = $Path
            Exists = $null -ne $item
            ModifiedUnix = if ($item) { ([DateTimeOffset]$item.LastWriteTimeUtc).ToUnixTimeSeconds() } else { -1L }
        }
    }

    & wsl.exe -d $Distribution -u $User -- test -f $Path
    $exists = $LASTEXITCODE -eq 0
    $modified = if ($exists) {
        [long]((& wsl.exe -d $Distribution -u $User -- stat -c '%Y' $Path).Trim())
    } else { -1L }
    return [pscustomobject]@{
        Platform = $Platform
        Path = $Path
        HostPath = ConvertTo-WslUncPath -Distribution $Distribution -LinuxPath $Path
        Exists = $exists
        ModifiedUnix = $modified
    }
}

function Copy-StateFile {
    param(
        [Parameter(Mandatory)]$Source,
        [Parameter(Mandatory)]$Destination,
        [string]$Distribution,
        [string]$User
    )

    if ($Source.Exists -and $Destination.Exists) {
        $sourceHash = (Get-FileHash -LiteralPath $Source.HostPath -Algorithm SHA256).Hash
        $destinationHash = (Get-FileHash -LiteralPath $Destination.HostPath -Algorithm SHA256).Hash
        if ($sourceHash -eq $destinationHash) {
            return $false
        }
    }

    if ($Destination.Platform -eq 'windows') {
        New-Item -ItemType Directory -Path (Split-Path -Parent $Destination.Path) -Force | Out-Null
        Copy-Item -LiteralPath $Source.HostPath -Destination $Destination.Path -Force
        return $true
    }

    $linuxSource = if ($Source.Platform -eq 'wsl') {
        $Source.Path
    } else {
        ConvertTo-WslDrivePath -WindowsPath $Source.Path
    }
    & wsl.exe -d $Distribution -u $User -- install -D -p -m 0600 $linuxSource $Destination.Path
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to synchronize $($Source.Path) to $($Destination.Path)."
    }
    return $true
}

function Sync-NewestFile {
    param(
        [Parameter(Mandatory)][object[]]$Files,
        [string]$Distribution,
        [string]$User
    )

    $source = $Files | Where-Object Exists | Sort-Object ModifiedUnix -Descending | Select-Object -First 1
    if (-not $source) {
        return [pscustomobject]@{ source = $null; updated = @() }
    }
    $updated = @()
    foreach ($destination in $Files) {
        if ($destination.Path -eq $source.Path -and $destination.Platform -eq $source.Platform) {
            continue
        }
        if (Copy-StateFile -Source $source -Destination $destination -Distribution $Distribution -User $User) {
            $updated += "$($destination.Platform):$($destination.Path)"
        }
    }
    return [pscustomobject]@{
        source = "$($source.Platform):$($source.Path)"
        updated = $updated
    }
}

$context = Resolve-JetBrainsContext -IdeHome $IdeHome -Profile $Profile
$windowsUserHome = Join-Path $env:USERPROFILE '.codex'
$windowsIdeHome = $context.LocalCodexHome
$wsl = if ($SkipWsl) { $null } else { Resolve-WslTarget -Distribution $WslDistribution -User $WslUser }

$windowsConfig = Sync-NewestFile -Files @(
    (Get-StateFile -Platform windows -Path (Join-Path $windowsUserHome 'config.toml')),
    (Get-StateFile -Platform windows -Path (Join-Path $windowsIdeHome 'config.toml'))
)

$wslConfig = $null
$auth = $null
if ($wsl) {
    $wslUserHome = "$($wsl.Home)/.codex"
    $wslIdeHome = "$($wsl.Home)/.local/share/JetBrains/$($context.Profile)/aia/codex"
    $wslConfig = Sync-NewestFile -Distribution $wsl.Distribution -User $wsl.User -Files @(
        (Get-StateFile -Platform wsl -Distribution $wsl.Distribution -User $wsl.User -Path "$wslUserHome/config.toml"),
        (Get-StateFile -Platform wsl -Distribution $wsl.Distribution -User $wsl.User -Path "$wslIdeHome/config.toml")
    )
    $auth = Sync-NewestFile -Distribution $wsl.Distribution -User $wsl.User -Files @(
        (Get-StateFile -Platform windows -Path (Join-Path $windowsUserHome 'auth.json')),
        (Get-StateFile -Platform windows -Path (Join-Path $windowsIdeHome 'auth.json')),
        (Get-StateFile -Platform wsl -Distribution $wsl.Distribution -User $wsl.User -Path "$wslUserHome/auth.json"),
        (Get-StateFile -Platform wsl -Distribution $wsl.Distribution -User $wsl.User -Path "$wslIdeHome/auth.json")
    )
}
else {
    $auth = Sync-NewestFile -Files @(
        (Get-StateFile -Platform windows -Path (Join-Path $windowsUserHome 'auth.json')),
        (Get-StateFile -Platform windows -Path (Join-Path $windowsIdeHome 'auth.json'))
    )
}

[ordered]@{
    profile = $context.Profile
    auth = $auth
    windowsConfig = $windowsConfig
    wslConfig = $wslConfig
} | ConvertTo-Json -Depth 8 -Compress
