# JetBrains AI WSL Patch

Версионируемый патч для запуска Codex ACP в JetBrains AI в той же среде, где открыт проект:

- Windows-проект использует Windows Node, ACP и Codex.
- WSL-проект использует Linux Node, ACP и Codex внутри соответствующего дистрибутива и от непривилегированного пользователя.
- Абсолютные Linux-ссылки из чата открываются как WSL-файлы.
- В панели чата отображаются актуальные динамические лимиты Codex для выбранной модели.
- Звук завершения воспроизводится и при активном окне IDE.

Патч не содержит JAR JetBrains или бинарники Codex. Он проверяет исходные SHA-256, компилирует небольшие helper-классы и меняет только подтверждённые bytecode hook points. При изменении структуры плагина сборка останавливается, а не пытается применить несовместимый патч.

## Поддерживаемые версии

Текущий release `1.1.0` поддерживает:

- JetBrains AI Assistant `261.25134.237`;
- JetBrains IDE build `261.25134.*`;
- Windows x64/arm64;
- WSL 2 Linux x64/arm64;
- Codex CLI `0.144.3`;
- `@agentclientprotocol/codex-acp` `1.1.2`.

Индикатор не читает диагностическую SQLite. Version-checked bridge внутри установленного ACP опрашивает официальный `account/rateLimits/read` раз в 20 секунд и атомарно обновляет небольшой JSON snapshot в активном `CODEX_HOME`. Окна строятся по `windowDurationMins`: если backend вернул только недельное окно, UI не дорисовывает несуществующий 5-часовой лимит. Именованные buckets сопоставляются с выбранной моделью по `limitName`; остальные модели используют объявленный backend default bucket.

Имя IDE, профиль, WSL-дистрибутив, пользователь и домашний каталог не зашиты в исходники. Установщик читает IDE metadata и определяет WSL target. Для нескольких IDE или дистрибутивов параметры лучше передать явно.

## Установка

1. Установите или скачайте обновление JetBrains AI, затем полностью закройте IDE.
2. Клонируйте репозиторий и запустите установщик из PowerShell 7 или Windows PowerShell 5.1:

```powershell
git clone git@github.com:4erk/jetbrains-ai-wsl-patch.git
cd jetbrains-ai-wsl-patch
.\scripts\install.ps1
```

Для явного выбора окружения:

```powershell
.\scripts\install.ps1 `
  -IdeHome 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3' `
  -WslDistribution Ubuntu `
  -WslUser 4erk
```

Установщик:

- проверяет, что IDE остановлена;
- определяет pending update ZIP или установленный plugin tree;
- принимает только версию и исходные хеши из `compatibility/`;
- создаёт бэкап до замены;
- устанавливает pinned Codex и ACP отдельно для Windows и WSL;
- использует JetBrains managed Node либо устанавливает checksum-pinned Node fallback для каждой среды;
- создаёт environment manifest для runtime routing;
- синхронизирует новый `auth.json` между средами, но не смешивает platform-specific `config.toml`.

## Проверка и откат

```powershell
.\scripts\status.ps1
.\scripts\test.ps1
.\scripts\rollback.ps1
```

Полная build-проверка на чистом дереве плагина:

```powershell
.\scripts\test.ps1 `
  -PluginRoot 'C:\path\to\clean\ml-llm' `
  -IdeHome 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3'
```

Откат выбирает не самый новый ZIP, а бэкап, JAR которого совпадает с исходным SHA-256 compatibility manifest. Это исключает восстановление уже пропатченной копии.

## Обновление версий

Проверить новые стабильные Codex и ACP без изменения файлов:

```powershell
.\scripts\update-runtime-lock.ps1
```

Применить обновление lock-файла:

```powershell
.\scripts\update-runtime-lock.ps1 -Apply
```

Процесс переноса на новую версию JetBrains AI описан в [docs/UPDATING.md](docs/UPDATING.md). Архитектурные границы и формат runtime manifest описаны в [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Политика версий и release tags описана в [docs/VERSIONING.md](docs/VERSIONING.md).

Полный перенос текущей IntelliJ IDEA, плагинов, настроек, WSL и Codex на новый компьютер описан в [docs/NEW-PC-SETUP.md](docs/NEW-PC-SETUP.md).

## Важные границы

- Патч не меняет лицензирование JetBrains, netfilter, MCP-конфигурацию, model selector или lifecycle сессий.
- Автоматическая установка поддерживает Windows host с WSL. Нативный Linux host не требует WSL routing и пока не входит в install flow.
- `auth.json` синхронизируется по времени изменения. `config.toml` синхронизируется только внутри одной платформы: Windows user/IDE и WSL user/IDE.
- Исходные и пропатченные vendor JAR не публикуются.
