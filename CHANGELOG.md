# Changelog

Все существенные изменения проекта фиксируются здесь. Версии следуют Semantic Versioning.

## [1.2.0] - 2026-07-17

### Added

- Last-known-good rate-limit generation for interrupted, empty, or partial app-server refreshes.
- Event-driven session-history checkpoints after 30 active seconds or 256 pending updates at the next safe event boundary.
- Executable tests for ACP snapshot rotation, partial-bucket merge, and checkpoint policy.

### Changed

- Stale but valid quota values remain visible with an explicit age warning instead of becoming `--%`.
- Active session events are appended through the native `SessionHistoryStorage` and released from its pending buffer without rewriting or truncating history files.
- Pinned Codex CLI was updated to `0.144.5` and ACP to `1.1.4` after clean-hash and bridge compatibility verification.

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
