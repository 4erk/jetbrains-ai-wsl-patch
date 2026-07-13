# Changelog

Все существенные изменения проекта фиксируются здесь. Версии следуют Semantic Versioning.

## [1.1.0] - 2026-07-13

### Added

- Version-checked ACP bridge that polls the official `account/rateLimits/read` RPC every 20 seconds.
- Dynamic model-to-bucket matching and dynamic quota windows for current and future Codex models.
- Release tags that encode both JetBrains AI and patch versions.

### Changed

- Reworked the limit control as a compact toolbar dropdown with a readable progress-card popup.
- Stale snapshots expire explicitly instead of remaining visible as current data.

### Removed

- Binary SQLite/WAL scraping and the hardcoded two-window plus Spark-only telemetry model.

## [1.0.0] - 2026-07-13

### Added

- Environment-aware Codex ACP routing for Windows and WSL projects.
- Versioned, checksum-verified Codex and ACP runtime installation for x64 and arm64.
- WSL absolute file navigation from JetBrains AI markdown.
- Active-environment Codex limit telemetry with a separate Spark bucket.
- Completion sound while the IDE window is focused.
- Fail-closed compatibility manifests and semantic bytecode patchers.
- Idempotent installer, status, rollback, state synchronization, tests, and update tooling.
