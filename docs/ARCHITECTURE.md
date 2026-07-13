# Архитектура

## Компоненты

`scripts/build.ps1` компилирует Java helpers JBR-компилятором установленной IDE и применяет три независимых ASM patcher:

- `ml-llm.jar`: нормализация `AcpAgentStartConfig` непосредственно перед `AcpProcessLauncher.startProcess`;
- `intellij.ml.llm.agents.frontend.jar`: открытие Linux absolute paths через WSL VFS;
- `intellij.ml.llm.chat.jar`: usage-limit control и звук завершения при активном окне.

Каждый patcher проверяет конкретный class/method/call shape и завершает сборку с ошибкой, если hook отсутствует или неоднозначен. Compatibility manifest дополнительно фиксирует чистые SHA-256 всех трёх JAR.

## Runtime routing

`scripts/install-runtime.ps1` устанавливает pinned runtime в versioned directories внутри JetBrains Codex home. Он предпочитает управляемый JetBrains Node, а при его отсутствии разворачивает checksum-pinned Node fallback отдельно в Windows и WSL. Плавающий `npx package@latest` не используется.

Windows manifest:

```text
%LOCALAPPDATA%\JetBrains\<profile>\aia\codex\jetbrains-ai-wsl-patch.env
```

Manifest содержит Windows paths и набор WSL paths с ключом, полученным из имени дистрибутива. Java helper выбирает набор по project base path:

- обычный Windows path -> Windows Node + ACP entry + Codex;
- `\\wsl.localhost\<distro>\...` или `\\wsl$\<distro>\...` -> WSL Node + ACP entry + Codex.

Нормализация применяется только к start config, который идентифицирован как Codex. Остальные ACP agents проходят без изменений.

## Состояние Codex

Для каждого IDE profile используются отдельные Codex homes:

- Windows: `%LOCALAPPDATA%\JetBrains\<profile>\aia\codex`;
- WSL: `~/.local/share/JetBrains/<profile>/aia/codex`.

`auth.json` является переносимым состоянием авторизации: наиболее новый файл распространяется между user и IDE homes обеих платформ. `config.toml` содержит platform-specific commands и paths, поэтому новый файл копируется только внутри Windows-пары или WSL-пары.

## Usage telemetry

ACP bridge использует стабильный app-server RPC `account/rateLimits/read`. Сразу после ACP `initialize` и далее каждые 20 секунд он сохраняет полный response в `jetbrains-rate-limits.json` активного `CODEX_HOME`. Трансформация `codex-acp` привязана к clean/patched SHA-256 в `runtime.lock.json` и останавливается при неизвестном bundle.

Java UI читает только этот JSON snapshot из Codex home активной project environment. Windows и WSL telemetry не смешиваются. Snapshot старше 75 секунд считается stale и скрывается, а не выдается за актуальный. Окна сортируются по `windowDurationMins`; именованные buckets сопоставляются с выбранной моделью по `limitName`, default bucket определяется по `limitId=codex`. SQLite/WAL не является API и больше не используется.

## Supply chain

`runtime.lock.json` фиксирует release tag, asset name и SHA-256 Codex и Node для каждой архитектуры. ACP фиксируется по версии npm package. `scripts/update-runtime-lock.ps1` сверяет stable GitHub release ACP с npm latest перед изменением lock.
