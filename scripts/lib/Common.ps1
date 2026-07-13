Set-StrictMode -Version Latest

function Get-RepositoryRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Resolve-JetBrainsContext {
    param(
        [string]$IdeHome,
        [string]$Profile
    )

    if (-not $IdeHome) {
        $candidates = Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'JetBrains') -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                $productInfo = Join-Path $_.FullName 'product-info.json'
                if (Test-Path -LiteralPath $productInfo) {
                    $info = Get-Content -LiteralPath $productInfo -Raw | ConvertFrom-Json
                    [pscustomobject]@{
                        Home = $_.FullName
                        Info = $info
                        Preferred = if ([string]$info.productCode -eq 'IU') { 1 } else { 0 }
                        Modified = $_.LastWriteTimeUtc
                    }
                }
            } |
            Sort-Object Preferred, Modified -Descending
        $selected = $candidates | Select-Object -First 1
        if (-not $selected) {
            throw 'No JetBrains IDE installation was found.'
        }
        $IdeHome = $selected.Home
        $product = $selected.Info
    }
    else {
        $IdeHome = (Resolve-Path $IdeHome).Path
        $productInfo = Join-Path $IdeHome 'product-info.json'
        if (-not (Test-Path -LiteralPath $productInfo -PathType Leaf)) {
            throw "product-info.json is missing under $IdeHome"
        }
        $product = Get-Content -LiteralPath $productInfo -Raw | ConvertFrom-Json
    }

    if (-not $Profile) {
        $Profile = [string]$product.dataDirectoryName
    }
    if (-not $Profile) {
        throw 'Unable to resolve the JetBrains profile name.'
    }

    [pscustomobject]@{
        IdeHome = $IdeHome
        Profile = $Profile
        ProductCode = [string]$product.productCode
        IdeBuild = [string]$product.buildNumber
        PluginRoot = Join-Path $env:APPDATA "JetBrains\$Profile\plugins\ml-llm"
        PendingPluginZip = Join-Path $env:LOCALAPPDATA "JetBrains\$Profile\plugins\ml-llm.zip"
        LocalCodexHome = Join-Path $env:LOCALAPPDATA "JetBrains\$Profile\aia\codex"
        LogPath = Join-Path $env:LOCALAPPDATA "JetBrains\$Profile\log\idea.log"
    }
}

function Assert-IdeStopped {
    param([Parameter(Mandatory)]$Context)

    $prefix = [IO.Path]::GetFullPath($Context.IdeHome).TrimEnd('\')
    $running = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $path = [string]$_.ExecutablePath
            $path -and $path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
        }
    if ($running) {
        throw "JetBrains IDE is running (PID $($running.ProcessId -join ', ')). Close it before installing the patch."
    }
}

function Resolve-WslTarget {
    param(
        [string]$Distribution,
        [string]$User
    )

    if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
        return $null
    }
    if (-not $Distribution) {
        $Distribution = @(wsl.exe --list --quiet 2>$null |
            ForEach-Object { ($_ -replace [string][char]0, '').Trim() } |
            Where-Object { $_ }) | Select-Object -First 1
    }
    if (-not $Distribution) {
        return $null
    }

    if (-not $User) {
        $User = (& wsl.exe -d $Distribution -- sh -lc 'id -un' 2>$null | Select-Object -Last 1).Trim()
        if (-not $User -or $User -eq 'root') {
            $User = @(& wsl.exe -d $Distribution -u root -- getent passwd 2>$null |
                ForEach-Object {
                    $fields = $_ -split ':'
                    if ($fields.Count -ge 3 -and [int]$fields[2] -ge 1000 -and [int]$fields[2] -lt 65534) {
                        $fields[0]
                    }
                }) | Select-Object -First 1
        }
    }
    if (-not $User -or $User -eq 'root') {
        throw "Unable to resolve a non-root user for WSL distribution '$Distribution'."
    }

    $wslHome = (& wsl.exe -d $Distribution -u $User -- sh -lc 'printf %s "$HOME"' 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $wslHome) {
        throw "Unable to resolve HOME for $Distribution/$User."
    }

    [pscustomobject]@{
        Distribution = $Distribution
        User = $User
        Home = $wslHome.Trim()
        Key = ($Distribution.ToUpperInvariant() -replace '[^A-Z0-9]', '_')
    }
}

function ConvertTo-ShellLiteral {
    param([Parameter(Mandatory)][string]$Value)
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Get-WindowsManagedNode {
    $root = Join-Path $env:LOCALAPPDATA 'JetBrains\acp-agents\.runtimes\node'
    $nodes = Get-ChildItem -LiteralPath $root -Recurse -Filter node.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\bin\\node\.exe$' } |
        Sort-Object { [version]$_.Directory.Parent.Name } -Descending
    return $nodes | Select-Object -First 1 -ExpandProperty FullName
}

function Get-WslManagedNode {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)]$Wsl
    )

    $root = "$($Wsl.Home)/.cache/JetBrains/$($Context.Profile)/acp-agents/.runtimes/node"
    $script = "find $(ConvertTo-ShellLiteral $root) -mindepth 3 -maxdepth 3 -type f -path '*/bin/node' -print 2>/dev/null | sort -V | tail -n 1"
    $node = (& wsl.exe -d $Wsl.Distribution -u $Wsl.User -- sh -lc $script 2>$null)
    $selected = $node | Select-Object -Last 1
    if ($selected) {
        return $selected.Trim()
    }
    return $null
}

function Test-Sha256 {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Expected
    )
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -ne $Expected) {
        throw "SHA-256 mismatch for $Path. Expected $Expected, got $actual"
    }
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string[]]$Lines
    )
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [IO.File]::WriteAllLines($Path, $Lines, [Text.UTF8Encoding]::new($false))
}

function ConvertTo-StableJson {
    param(
        [Parameter(Mandatory)]$InputObject,
        [int]$Depth = 12
    )

    $compact = ConvertTo-Json -InputObject $InputObject -Depth $Depth -Compress
    $builder = [Text.StringBuilder]::new()
    $level = 0
    $inString = $false
    $escaped = $false

    for ($index = 0; $index -lt $compact.Length; $index++) {
        $character = $compact[$index]
        if ($inString) {
            [void]$builder.Append($character)
            if ($escaped) {
                $escaped = $false
            }
            elseif ($character -eq '\') {
                $escaped = $true
            }
            elseif ($character -eq '"') {
                $inString = $false
            }
            continue
        }

        switch ($character) {
            '"' {
                $inString = $true
                [void]$builder.Append($character)
            }
            { $_ -eq '{' -or $_ -eq '[' } {
                [void]$builder.Append($character)
                $closing = if ($character -eq '{') { '}' } else { ']' }
                if ($index + 1 -lt $compact.Length -and $compact[$index + 1] -ne $closing) {
                    $level++
                    [void]$builder.Append("`n")
                    [void]$builder.Append(' ' * ($level * 2))
                }
            }
            { $_ -eq '}' -or $_ -eq ']' } {
                $opening = if ($character -eq '}') { '{' } else { '[' }
                if ($index -gt 0 -and $compact[$index - 1] -ne $opening) {
                    $level--
                    [void]$builder.Append("`n")
                    [void]$builder.Append(' ' * ($level * 2))
                }
                [void]$builder.Append($character)
            }
            ',' {
                [void]$builder.Append(",`n")
                [void]$builder.Append(' ' * ($level * 2))
            }
            ':' {
                [void]$builder.Append(': ')
            }
            default {
                if (-not [char]::IsWhiteSpace($character)) {
                    [void]$builder.Append($character)
                }
            }
        }
    }

    return $builder.ToString()
}

function Read-KeyValueFile {
    param([Parameter(Mandatory)][string]$Path)

    $result = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $result
    }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -gt 0) {
            $result[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim()
        }
    }
    return $result
}

function Get-ZipEntrySha256 {
    param(
        [Parameter(Mandatory)][string]$ZipPath,
        [Parameter(Mandatory)][string]$EntrySuffix
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $ZipPath).Path)
    try {
        $normalizedSuffix = $EntrySuffix.Replace('\', '/').TrimStart('/')
        $entry = $archive.Entries |
            Where-Object { $_.FullName.Replace('\', '/').EndsWith($normalizedSuffix, [StringComparison]::OrdinalIgnoreCase) } |
            Sort-Object { $_.FullName.Length } |
            Select-Object -First 1
        if (-not $entry) {
            return $null
        }
        $stream = $entry.Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '')
        }
        finally {
            $sha.Dispose()
            $stream.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Test-JarContainsEntry {
    param(
        [Parameter(Mandatory)][string]$JarPath,
        [Parameter(Mandatory)][string]$Entry
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $JarPath).Path)
    try {
        return $null -ne $archive.GetEntry($Entry)
    }
    finally {
        $archive.Dispose()
    }
}

function Get-JarEntryText {
    param(
        [Parameter(Mandatory)][string]$JarPath,
        [Parameter(Mandatory)][string]$Entry
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $JarPath).Path)
    try {
        $item = $archive.GetEntry($Entry)
        if (-not $item) {
            return $null
        }
        $reader = [IO.StreamReader]::new($item.Open())
        try {
            return $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Test-ZipNestedJarContainsEntry {
    param(
        [Parameter(Mandatory)][string]$ZipPath,
        [Parameter(Mandatory)][string]$JarSuffix,
        [Parameter(Mandatory)][string]$Entry
    )

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outer = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $ZipPath).Path)
    $memory = [IO.MemoryStream]::new()
    try {
        $normalizedSuffix = $JarSuffix.Replace('\', '/').TrimStart('/')
        $jar = $outer.Entries |
            Where-Object { $_.FullName.Replace('\', '/').EndsWith($normalizedSuffix, [StringComparison]::OrdinalIgnoreCase) } |
            Sort-Object { $_.FullName.Length } |
            Select-Object -First 1
        if (-not $jar) {
            return $false
        }
        $source = $jar.Open()
        try {
            $source.CopyTo($memory)
        }
        finally {
            $source.Dispose()
        }
        $memory.Position = 0
        $inner = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
        try {
            return $null -ne $inner.GetEntry($Entry)
        }
        finally {
            $inner.Dispose()
        }
    }
    finally {
        $memory.Dispose()
        $outer.Dispose()
    }
}

function Get-ZipNestedJarEntryText {
    param(
        [Parameter(Mandatory)][string]$ZipPath,
        [Parameter(Mandatory)][string]$JarSuffix,
        [Parameter(Mandatory)][string]$Entry
    )

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outer = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $ZipPath).Path)
    $memory = [IO.MemoryStream]::new()
    try {
        $normalizedSuffix = $JarSuffix.Replace('\', '/').TrimStart('/')
        $jar = $outer.Entries |
            Where-Object { $_.FullName.Replace('\', '/').EndsWith($normalizedSuffix, [StringComparison]::OrdinalIgnoreCase) } |
            Sort-Object { $_.FullName.Length } |
            Select-Object -First 1
        if (-not $jar) {
            return $null
        }
        $source = $jar.Open()
        try {
            $source.CopyTo($memory)
        }
        finally {
            $source.Dispose()
        }
        $memory.Position = 0
        $inner = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
        try {
            $item = $inner.GetEntry($Entry)
            if (-not $item) {
                return $null
            }
            $reader = [IO.StreamReader]::new($item.Open())
            try {
                return $reader.ReadToEnd()
            }
            finally {
                $reader.Dispose()
            }
        }
        finally {
            $inner.Dispose()
        }
    }
    finally {
        $memory.Dispose()
        $outer.Dispose()
    }
}

function Get-PluginVersionFromJar {
    param([Parameter(Mandatory)][string]$JarPath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $JarPath).Path)
    try {
        $entry = $archive.GetEntry('META-INF/plugin.xml')
        if (-not $entry) {
            return $null
        }
        $reader = [IO.StreamReader]::new($entry.Open())
        try {
            $match = [regex]::Match($reader.ReadToEnd(), '<version>([^<]+)</version>')
            if ($match.Success) {
                return $match.Groups[1].Value.Trim()
            }
            return $null
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Get-PluginVersionFromZip {
    param([Parameter(Mandatory)][string]$ZipPath)

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outer = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $ZipPath).Path)
    $memory = [IO.MemoryStream]::new()
    try {
        $jar = $outer.Entries |
            Where-Object { $_.FullName.Replace('\', '/').EndsWith('lib/ml-llm.jar', [StringComparison]::OrdinalIgnoreCase) } |
            Sort-Object { $_.FullName.Length } |
            Select-Object -First 1
        if (-not $jar) {
            return $null
        }
        $source = $jar.Open()
        try {
            $source.CopyTo($memory)
        }
        finally {
            $source.Dispose()
        }
        $memory.Position = 0
        $inner = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
        try {
            $entry = $inner.GetEntry('META-INF/plugin.xml')
            if (-not $entry) {
                return $null
            }
            $reader = [IO.StreamReader]::new($entry.Open())
            try {
                $match = [regex]::Match($reader.ReadToEnd(), '<version>([^<]+)</version>')
                if ($match.Success) {
                    return $match.Groups[1].Value.Trim()
                }
                return $null
            }
            finally {
                $reader.Dispose()
            }
        }
        finally {
            $inner.Dispose()
        }
    }
    finally {
        $memory.Dispose()
        $outer.Dispose()
    }
}

function Resolve-JetBrainsPluginTarget {
    param([Parameter(Mandatory)]$Context)

    $installedJar = Join-Path $Context.PluginRoot 'lib\ml-llm.jar'
    $installedVersion = if (Test-Path -LiteralPath $installedJar -PathType Leaf) {
        Get-PluginVersionFromJar -JarPath $installedJar
    } else { $null }
    $pendingVersion = if (Test-Path -LiteralPath $Context.PendingPluginZip -PathType Leaf) {
        Get-PluginVersionFromZip -ZipPath $Context.PendingPluginZip
    } else { $null }

    if ($pendingVersion -and $pendingVersion -ne $installedVersion) {
        return [pscustomobject]@{
            Mode = 'pending-update'
            Path = $Context.PendingPluginZip
            Version = $pendingVersion
            InstalledVersion = $installedVersion
            PendingVersion = $pendingVersion
        }
    }
    return [pscustomobject]@{
        Mode = 'installed-plugin'
        Path = $Context.PluginRoot
        Version = $installedVersion
        InstalledVersion = $installedVersion
        PendingVersion = $pendingVersion
    }
}
