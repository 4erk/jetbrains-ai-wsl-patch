[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EntryPath,
    [string]$LockPath,
    [switch]$UpdatePatchedHash
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\Common.ps1')

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Needle,
        [Parameter(Mandatory)][string]$Replacement,
        [Parameter(Mandatory)][string]$Description
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Needle)).Count
    if ($count -ne 1) {
        throw "ACP bridge marker '$Description' matched $count times; expected exactly one."
    }
    return $Text.Replace($Needle, $Replacement)
}

$repoRoot = Get-RepositoryRoot
if (-not $LockPath) {
    $LockPath = Join-Path $repoRoot 'runtime.lock.json'
}
if (-not (Test-Path -LiteralPath $EntryPath -PathType Leaf)) {
    throw "ACP entry point is missing: $EntryPath"
}

$lock = Get-Content -LiteralPath $LockPath -Raw | ConvertFrom-Json
$bridge = $lock.acp.rateLimitBridge
if (-not $bridge) {
    throw 'runtime.lock.json does not define acp.rateLimitBridge.'
}

$actualHash = (Get-FileHash -LiteralPath $EntryPath -Algorithm SHA256).Hash
$cleanHash = [string]$bridge.cleanSha256
$patchedHash = [string]$bridge.patchedSha256
if ($actualHash -eq $patchedHash) {
    Write-Output "ACP rate-limit bridge is already installed: $EntryPath"
    return
}
if ($actualHash -ne $cleanHash) {
    throw "Unsupported ACP entry point hash. Expected clean $cleanHash or patched $patchedHash, got $actualHash."
}

$text = [IO.File]::ReadAllText($EntryPath)
$bridgePrelude = @'
// jetbrains-ai-wsl-patch: structured Codex rate-limit bridge
import {
  mkdir as codexRateLimitMkdir,
  rename as codexRateLimitRename,
  rm as codexRateLimitRemove,
  writeFile as codexRateLimitWrite
} from "node:fs/promises";
var CODEX_RATE_LIMIT_REFRESH_MS = 2e4;
var CODEX_RATE_LIMIT_SNAPSHOT_FILE = "jetbrains-rate-limits.json";
async function writeCodexRateLimitSnapshot(result) {
  const codexHome = process.env["CODEX_HOME"]?.trim();
  if (!codexHome) return;
  await codexRateLimitMkdir(codexHome, { recursive: true });
  const target = `${codexHome}/${CODEX_RATE_LIMIT_SNAPSHOT_FILE}`;
  const temporary = `${target}.tmp-${process.pid}`;
  const payload = JSON.stringify({
    schema: 1,
    updatedAt: Date.now(),
    source: "account/rateLimits/read",
    ...result
  });
  await codexRateLimitWrite(temporary, payload, "utf8");
  try {
    await codexRateLimitRename(temporary, target);
  } catch (error) {
    if (error?.code !== "EEXIST" && error?.code !== "EPERM") throw error;
    await codexRateLimitRemove(target, { force: true });
    await codexRateLimitRename(temporary, target);
  } finally {
    await codexRateLimitRemove(temporary, { force: true }).catch(() => {});
  }
}

'@
$codexClientMarker = "// src/CodexAcpClient.ts`nvar CodexAcpClient = class {"
$text = Replace-ExactlyOnce -Text $text `
    -Needle $codexClientMarker `
    -Replacement ($bridgePrelude + $codexClientMarker) `
    -Description 'CodexAcpClient prelude'

$text = Replace-ExactlyOnce -Text $text `
    -Needle '  skillExtraRoots = [];' `
    -Replacement "  skillExtraRoots = [];`n  rateLimitBridgeTimer = null;`n  rateLimitBridgeRefreshing = false;" `
    -Description 'CodexAcpClient fields'

$initializeNeedle = @'
    });
  }
  async authenticate(authRequest) {
'@
$initializeReplacement = @'
    });
    this.startRateLimitBridge();
  }
  startRateLimitBridge() {
    if (this.rateLimitBridgeTimer !== null) return;
    const refresh = async () => {
      if (this.rateLimitBridgeRefreshing) return;
      this.rateLimitBridgeRefreshing = true;
      try {
        const snapshot = await this.codexClient.accountRateLimitsRead();
        await writeCodexRateLimitSnapshot(snapshot);
      } catch {
        // Keep the last successful snapshot; the UI expires stale data explicitly.
      } finally {
        this.rateLimitBridgeRefreshing = false;
      }
    };
    void refresh();
    this.rateLimitBridgeTimer = setInterval(refresh, CODEX_RATE_LIMIT_REFRESH_MS);
    this.rateLimitBridgeTimer.unref?.();
  }
  async authenticate(authRequest) {
'@
$text = Replace-ExactlyOnce -Text $text `
    -Needle $initializeNeedle `
    -Replacement $initializeReplacement `
    -Description 'CodexAcpClient initialize'

$accountReadNeedle = @'
  async accountRead(params) {
    return await this.sendRequest({ method: "account/read", params });
  }
'@
$accountReadReplacement = @'
  async accountRead(params) {
    return await this.sendRequest({ method: "account/read", params });
  }
  async accountRateLimitsRead() {
    return await this.sendRequest({ method: "account/rateLimits/read", params: void 0 });
  }
'@
$text = Replace-ExactlyOnce -Text $text `
    -Needle $accountReadNeedle `
    -Replacement $accountReadReplacement `
    -Description 'CodexAppServerClient rate-limit method'

[IO.File]::WriteAllText($EntryPath, $text, [Text.UTF8Encoding]::new($false))
$actualPatchedHash = (Get-FileHash -LiteralPath $EntryPath -Algorithm SHA256).Hash
if ($UpdatePatchedHash) {
    $bridge | Add-Member -NotePropertyName patchedSha256 -NotePropertyValue $actualPatchedHash -Force
    [IO.File]::WriteAllText($LockPath, (ConvertTo-StableJson -InputObject $lock -Depth 12) + "`n", [Text.UTF8Encoding]::new($false))
}
elseif ($actualPatchedHash -ne $patchedHash) {
    throw "Patched ACP hash changed. Expected $patchedHash, got $actualPatchedHash. Use -UpdatePatchedHash only after review."
}

Write-Output "Installed ACP rate-limit bridge: $actualPatchedHash"
