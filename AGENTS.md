# Agent Rules

## Scope

This repository patches JetBrains AI only for environment-correct Codex runtime routing, WSL file navigation, Codex usage telemetry, and focused completion sound. Do not add licensing, netfilter, plugin synchronization, MCP configuration, model fallback, session recovery, compaction, proxy, MTU, or IDE indexing behavior.

## Invariants

- Never hardcode a workstation user, WSL distribution, JetBrains product, profile, or year-specific selector in source code.
- Never commit JetBrains JARs, Codex binaries, credentials, auth files, runtime state, build output, or backups.
- Treat compatibility manifests and runtime lock files as security boundaries. Verify hashes before patching or installing.
- Patch semantic bytecode anchors and require an exact hook count. Fail closed when plugin structure changes.
- Route only Codex ACP configs. Other ACP agents must remain unchanged.
- Use native Windows paths for Windows projects and native Linux paths for WSL projects. Never pass `C:\...` or `/mnt/c/...` as a WSL runtime command or `CODEX_HOME`.
- Invoke WSL with an explicit distribution and non-root user after discovery. Avoid shell-quoted command strings when direct argv is sufficient.
- Share the newest `auth.json` across Windows and WSL. Keep `config.toml` platform-specific and synchronize it only within the same platform.
- Keep installation idempotent, produce machine-readable status, and preserve a verified rollback path.

## Update Workflow

1. Audit the clean updated plugin and upstream behavior before retaining any patch feature.
2. Capture clean JAR hashes with `scripts/capture-compatibility.ps1`.
3. Update patchers only where semantic hook validation identifies a real upstream change.
4. Run `scripts/test.ps1` and the build test against the clean plugin tree.
5. Install with the IDE stopped, start the IDE, and verify the actual runtime path in `idea.log`.
6. Update `VERSION`, `CHANGELOG.md`, compatibility docs, and runtime lock when applicable.

## Verification

Internal PASS output is not enough. Confirm Windows and WSL executable versions, the WSL user and project path, a clickable absolute Linux file link, active-environment usage telemetry, and absence of plugin class-loading errors.
