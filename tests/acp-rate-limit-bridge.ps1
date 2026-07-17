[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PatchedEntry,
    [Parameter(Mandatory)][string]$NodePath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$entry = (Resolve-Path $PatchedEntry).Path
$node = (Resolve-Path $NodePath).Path
$source = [IO.File]::ReadAllText($entry)
$startMarker = '// jetbrains-ai-wsl-patch: structured Codex rate-limit bridge'
$endMarker = 'var CodexAcpClient = class {'
$start = $source.IndexOf($startMarker, [StringComparison]::Ordinal)
$end = $source.IndexOf($endMarker, $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) {
    throw 'Patched ACP rate-limit bridge source markers were not found.'
}

$testRoot = Join-Path $env:TEMP ('jetbrains-ai-wsl-patch-acp-test-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $module = Join-Path $testRoot 'bridge-test.mjs'
    $bridgeSource = $source.Substring($start, $end - $start)
    $harness = @'
const codexHome = process.argv[2];
process.env.CODEX_HOME = codexHome;
let fakeNow = 1000;
Date.now = () => fakeNow;

await writeCodexRateLimitSnapshot({
  rateLimits: {
    limitId: "codex",
    primary: { usedPercent: 10, windowDurationMins: 300, resetsAt: 2000000000 }
  },
  rateLimitsByLimitId: {
    codex: {
      limitId: "codex",
      primary: { usedPercent: 10, windowDurationMins: 300, resetsAt: 2000000000 }
    }
  }
});

fakeNow = 2000;
await writeCodexRateLimitSnapshot({
  rateLimits: null,
  rateLimitsByLimitId: {
    codex_spark: {
      limitId: "codex_spark",
      limitName: "Codex Spark",
      primary: { usedPercent: 20, windowDurationMins: 10080, resetsAt: 2000001000 }
    }
  }
});

const target = `${codexHome}/${CODEX_RATE_LIMIT_SNAPSHOT_FILE}`;
const backup = `${target}${CODEX_RATE_LIMIT_BACKUP_SUFFIX}`;
const beforeInvalid = await codexRateLimitRead(target, "utf8");
fakeNow = 3000;
await writeCodexRateLimitSnapshot({ rateLimits: null, rateLimitsByLimitId: {} });
const afterInvalid = await codexRateLimitRead(target, "utf8");
if (beforeInvalid !== afterInvalid) throw new Error("Invalid response replaced the last good snapshot");

const best = await readCodexRateLimitSnapshot(target, backup);
if (!best?.rateLimitsByLimitId?.codex || !best?.rateLimitsByLimitId?.codex_spark) {
  throw new Error("Partial response did not preserve all valid buckets");
}
'@
    [IO.File]::WriteAllText($module, $bridgeSource + $harness, [Text.UTF8Encoding]::new($false))

    & $node --check $module
    if ($LASTEXITCODE -ne 0) {
        throw "Patched ACP bridge syntax check failed with exit code $LASTEXITCODE"
    }
    & $node $module $testRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Patched ACP bridge behavior test failed with exit code $LASTEXITCODE"
    }

    $primaryPath = Join-Path $testRoot 'jetbrains-rate-limits.json'
    $backupPath = Join-Path $testRoot 'jetbrains-rate-limits.json.last-good'
    $primary = Get-Content -LiteralPath $primaryPath -Raw | ConvertFrom-Json
    $backup = Get-Content -LiteralPath $backupPath -Raw | ConvertFrom-Json
    if ([int]$primary.schema -ne 2) {
        throw 'Primary ACP snapshot schema is not 2.'
    }
    if (-not $primary.rateLimitsByLimitId.codex -or -not $primary.rateLimitsByLimitId.codex_spark) {
        throw 'Primary ACP snapshot is missing a merged rate-limit bucket.'
    }
    if ([long]$primary.updatedAt -ne 2000 -or
        [long]$primary.rateLimitsByLimitId.codex._jetbrainsUpdatedAt -ne 1000 -or
        [long]$primary.rateLimitsByLimitId.codex_spark._jetbrainsUpdatedAt -ne 2000) {
        throw 'ACP snapshot did not preserve per-bucket freshness.'
    }
    if (-not $backup.rateLimitsByLimitId.codex) {
        throw 'Last-good ACP snapshot does not preserve the previous generation.'
    }
}
finally {
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    $resolvedTemp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
    if ($resolvedTestRoot.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Output 'ACP rate-limit bridge tests passed.'
