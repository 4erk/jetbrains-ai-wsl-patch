# Перенос IntelliJ IDEA на новый компьютер

Инструкция фиксирует проверенный baseline текущего компьютера на 14 июля 2026 года. Цель переноса: получить ту же IntelliJ IDEA, плагины, настройки, WSL-проекты и Codex ACP без копирования кэшей и без повторного использования несовместимых пропатченных JAR.

Текущий baseline находится в [workstation/current-baseline.json](workstation/current-baseline.json).

## 1. Целевое состояние

- IntelliJ IDEA Ultimate `2026.1.3`, build `261.25134.95`.
- Профиль `IntelliJIdea2026.1`.
- JetBrains AI Assistant `261.25134.237`.
- JetBrains AI WSL Patch `1.1.0`, tag `jbai-261.25134.237-patch-1.1.0`.
- WSL 2 distribution `Ubuntu`, пользователь `4erk`, home `/home/4erk`.
- Codex CLI `0.144.3`, Codex ACP `1.1.2`, Node `24.13.0`.

Для воспроизводимого переноса сначала устанавливайте именно этот baseline. Обновлять IDEA или JetBrains AI следует только после того, как репозиторий патча содержит compatibility manifest для новой версии.

## 2. Что переносится раздельно

Перенос состоит из пяти независимых частей:

1. Штатный ZIP настроек IntelliJ IDEA.
2. Plugin bundle текущего профиля без `ml-llm`.
3. Дополнительное состояние IDEA: история AI-чатов, workspace и список отключенных плагинов.
4. Windows Codex state без runtime/cache.
5. Полный export WSL `Ubuntu`, содержащий Linux-проекты, Codex state, SSH и Linux toolchain.

Не складывайте эти архивы в Git. Они могут содержать историю работы, приватные пути и credentials.

## 3. Подготовка старого компьютера

### 3.1. Экспорт настроек IDEA

В IDEA выполните `File | Manage IDE Settings | Export Settings`, выберите нужные компоненты и сохраните `idea-settings.zip`. Это штатный переносимый формат JetBrains. Backup and Sync на текущем компьютере включен, но его plugin manifest уже содержит три записи, отсутствующие на диске, поэтому Sync нельзя использовать как единственный источник истины.

Официальная документация: [IDE settings backup and sync](https://www.jetbrains.com/help/idea/sharing-your-ide-settings.html).

### 3.2. Остановка процессов

Полностью закройте IDEA. Убедитесь, что IDE действительно остановлена:

```powershell
Get-Process idea64 -ErrorAction SilentlyContinue
```

Команда не должна вернуть процесс. После этого остановите WSL перед export:

```powershell
wsl.exe --shutdown
```

### 3.3. Создание каталога переноса

Используйте отдельный локальный или зашифрованный внешний диск:

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$bundle = "D:\IdeaMigration\$stamp"
New-Item -ItemType Directory -Path $bundle -Force | Out-Null
```

Скопируйте созданный IDEA archive:

```powershell
Copy-Item -LiteralPath 'D:\path\to\idea-settings.zip' -Destination $bundle
```

### 3.4. Архив плагинов

Текущий профиль содержит PHP-модули `261.25134.104`, GitHub Actions Manager `2026.1.10` и локальный `platform-daemon-plugin` с версией `wslpatch1`. Marketplace не гарантирует выдачу этих же сборок, поэтому для точного клона переносится текущая plugin tree.

`ml-llm` исключается намеренно: на новом компьютере сначала устанавливается чистый JetBrains AI `261.25134.237`, затем применяется патч из Git.

```powershell
$profile = "$env:APPDATA\JetBrains\IntelliJIdea2026.1"
$pluginExportRoot = Join-Path $env:TEMP ("idea-plugins-export-" + [guid]::NewGuid())
$pluginStage = Join-Path $pluginExportRoot 'plugins'
New-Item -ItemType Directory -Path $pluginStage -Force | Out-Null

Get-ChildItem -LiteralPath (Join-Path $profile 'plugins') -Directory |
    Where-Object Name -ne 'ml-llm' |
    Copy-Item -Destination $pluginStage -Recurse

Compress-Archive -LiteralPath $pluginStage -DestinationPath (Join-Path $bundle 'idea-plugins.zip')
```

Публичные плагины также можно устанавливать по plugin ID командой `idea64.exe installPlugins`, но для этого baseline archive является более точным источником. Официальная документация: [install plugins from the command line](https://www.jetbrains.com/help/idea/install-plugins-from-the-command-line.html).

### 3.5. Дополнительное состояние IDEA

Штатный ZIP настроек не следует считать backup истории AI-задач. Текущая `aia-task-history` занимает около 326 MB и должна переноситься отдельно, если нужна история чатов.

```powershell
$extraExportRoot = Join-Path $env:TEMP ("idea-profile-extra-export-" + [guid]::NewGuid())
$extra = Join-Path $extraExportRoot 'idea-profile-extra'
New-Item -ItemType Directory -Path $extra -Force | Out-Null

Copy-Item -LiteralPath (Join-Path $profile 'disabled_plugins.txt') -Destination $extra
Copy-Item -LiteralPath (Join-Path $profile 'early-access-registry.txt') -Destination $extra
Copy-Item -LiteralPath (Join-Path $profile 'aia-task-history') -Destination $extra -Recurse
Copy-Item -LiteralPath (Join-Path $profile 'workspace') -Destination $extra -Recurse

$aiOptions = @(
    'acpAgents.xml',
    'AIAssistantPromptLibraryStorage.xml',
    'llm.mcpServers.xml',
    'mcpServer.xml'
)
New-Item -ItemType Directory -Path (Join-Path $extra 'options') -Force | Out-Null
foreach ($name in $aiOptions) {
    $source = Join-Path (Join-Path $profile 'options') $name
    if (Test-Path -LiteralPath $source) {
        Copy-Item -LiteralPath $source -Destination (Join-Path $extra 'options')
    }
}

Compress-Archive -LiteralPath $extra -DestinationPath (Join-Path $bundle 'idea-profile-extra.zip')
```

`recentProjects.xml` переносите только если Windows username, OneDrive path, WSL distribution и Linux home останутся прежними. Иначе проекты лучше открыть заново.

### 3.6. Windows Codex state

Windows и WSL используют разные `config.toml`. Нельзя заменять Linux config Windows-версией или наоборот. Полный WSL export сохранит Linux state; Windows state архивируется отдельно без runtime и cache.

```powershell
$codexHome = "$env:LOCALAPPDATA\JetBrains\IntelliJIdea2026.1\aia\codex"
$codexExportRoot = Join-Path $env:TEMP ("windows-codex-state-export-" + [guid]::NewGuid())
$codexStage = Join-Path $codexExportRoot 'windows-codex-state'
New-Item -ItemType Directory -Path $codexStage -Force | Out-Null

foreach ($name in 'auth.json', 'config.toml') {
    $source = Join-Path $codexHome $name
    if (Test-Path -LiteralPath $source) {
        Copy-Item -LiteralPath $source -Destination $codexStage
    }
}
foreach ($name in 'sessions', 'skills', 'memories') {
    $source = Join-Path $codexHome $name
    if (Test-Path -LiteralPath $source) {
        Copy-Item -LiteralPath $source -Destination $codexStage -Recurse
    }
}

Compress-Archive -LiteralPath $codexStage -DestinationPath (Join-Path $bundle 'windows-codex-state.zip')
```

Не переносите `runtime`, `bin`, `downloads`, `tmp`, `logs_2.sqlite*`, `models_cache.json`, `jetbrains-rate-limits.json` и `installation_id`. Установщик патча создаст runtime заново и проверит его хеши.

`auth.json` является credential. Храните bundle зашифрованным и не публикуйте его.

### 3.7. Export WSL

Полный export является предпочтительным вариантом, потому что сохраняет локальные проекты без Git remote, Linux SSH, пакеты, shell settings и WSL Codex sessions.

```powershell
wsl.exe --shutdown
wsl.exe --export Ubuntu (Join-Path $bundle 'Ubuntu.tar')
```

Официальная документация: [WSL export and import](https://learn.microsoft.com/windows/wsl/basic-commands#export-a-distribution).

### 3.8. Контрольные суммы

```powershell
Get-ChildItem -LiteralPath $bundle -File -Recurse |
    Where-Object Name -ne 'SHA256SUMS.csv' |
    ForEach-Object {
        [pscustomobject]@{
            RelativePath = $_.FullName.Substring($bundle.Length).TrimStart('\')
            Hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
    } |
    Export-Csv -LiteralPath (Join-Path $bundle 'SHA256SUMS.csv') -NoTypeInformation -Encoding UTF8
```

Windows SSH keys, offline license material, Password Safe files и plugin license files не входят в обычный bundle. Переносите их отдельно только через защищенный канал и в рамках действующих лицензий. Предпочтительно заново авторизовать GitHub, базы данных и коммерческие плагины.

## 4. Установка на новом компьютере

Перед использованием bundle проверьте контрольные суммы:

```powershell
$bundle = 'D:\IdeaMigration\<bundle>'
$failed = Import-Csv (Join-Path $bundle 'SHA256SUMS.csv') | Where-Object {
    (Get-FileHash -LiteralPath (Join-Path $bundle $_.RelativePath) -Algorithm SHA256).Hash -ne $_.Hash
}
if ($failed) {
    $failed | Format-Table RelativePath, Hash
    throw 'Migration bundle integrity check failed.'
}
```

### 4.1. Базовые компоненты

1. Установите Windows updates.
2. Установите Git for Windows и проверьте `git --version`.
3. Установите или обновите WSL:

```powershell
wsl.exe --install --no-distribution
wsl.exe --update
wsl.exe --set-default-version 2
```

Перезагрузите Windows, если установщик WSL этого требует.

### 4.2. Import Ubuntu

```powershell
New-Item -ItemType Directory -Path 'D:\WSL\Ubuntu' -Force | Out-Null
wsl.exe --import Ubuntu 'D:\WSL\Ubuntu' 'D:\IdeaMigration\<bundle>\Ubuntu.tar' --version 2

@"
[user]
default=4erk
"@ | wsl.exe -d Ubuntu -u root -- tee /etc/wsl.conf

wsl.exe --terminate Ubuntu
wsl.exe --set-default Ubuntu
wsl.exe -d Ubuntu -u 4erk -- whoami
wsl.exe -d Ubuntu -u 4erk -- printenv HOME
```

Ожидаемый результат: `4erk` и `/home/4erk`. Для imported distribution пользователь по умолчанию задается через `/etc/wsl.conf`, а не через `ubuntu.exe config`.

### 4.3. IntelliJ IDEA

Установите IntelliJ IDEA Ultimate `2026.1.3`, build `261.25134.95`. Запустите IDE один раз до установки плагинов, чтобы она создала профиль и предложила штатный import. JetBrains отдельно предупреждает, что установка плагинов через CLI в пустой профиль отключает предложение импортировать старые настройки.

Импортируйте `idea-settings.zip` через `File | Manage IDE Settings | Import Settings`, но пока не включайте Backup and Sync. Если export/import dialog предлагает отдельный компонент Sync, снимите его. Перезапустите IDE и затем снова полностью закройте ее.

### 4.4. Восстановление plugin tree

```powershell
$profile = "$env:APPDATA\JetBrains\IntelliJIdea2026.1"
$source = 'D:\IdeaMigration\<bundle>\idea-plugins.zip'
$stage = Join-Path $env:TEMP ("idea-plugins-restore-" + [guid]::NewGuid())
Expand-Archive -LiteralPath $source -DestinationPath $stage -Force

New-Item -ItemType Directory -Path (Join-Path $profile 'plugins') -Force | Out-Null
foreach ($plugin in Get-ChildItem -LiteralPath (Join-Path $stage 'plugins') -Directory) {
    $target = Join-Path (Join-Path $profile 'plugins') $plugin.Name
    if (Test-Path -LiteralPath $target) {
        throw "Plugin target already exists and requires manual review: $target"
    }
    Copy-Item -LiteralPath $plugin.FullName -Destination $target -Recurse
}
```

Не запускайте IDEA до восстановления disabled state и чистого JetBrains AI.

### 4.5. Восстановление profile extras

```powershell
$extraStage = Join-Path $env:TEMP ("idea-profile-extra-restore-" + [guid]::NewGuid())
Expand-Archive -LiteralPath 'D:\IdeaMigration\<bundle>\idea-profile-extra.zip' -DestinationPath $extraStage -Force

Copy-Item -LiteralPath (Join-Path $extraStage 'idea-profile-extra\disabled_plugins.txt') -Destination $profile -Force
Copy-Item -LiteralPath (Join-Path $extraStage 'idea-profile-extra\early-access-registry.txt') -Destination $profile -Force
foreach ($name in 'aia-task-history', 'workspace') {
    $source = Join-Path (Join-Path $extraStage 'idea-profile-extra') $name
    $target = Join-Path $profile $name
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Get-ChildItem -LiteralPath $source -Force |
        Copy-Item -Destination $target -Recurse -Force
}
Get-ChildItem -LiteralPath (Join-Path $extraStage 'idea-profile-extra\options') -File |
    Copy-Item -Destination (Join-Path $profile 'options') -Force
```

### 4.6. Чистый JetBrains AI

Установите именно JetBrains AI Assistant `261.25134.237` из раздела Versions на [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/22282-jetbrains-ai-assistant/versions). Если Marketplace уже предлагает более новую версию, скачайте нужный ZIP и используйте `Install Plugin from Disk`.

После установки полностью закройте IDEA. Не копируйте старую папку `ml-llm`: текущий архив плагинов намеренно ее не содержит.

### 4.7. Windows Codex state

```powershell
$codexHome = "$env:LOCALAPPDATA\JetBrains\IntelliJIdea2026.1\aia\codex"
$codexStage = Join-Path $env:TEMP ("windows-codex-state-restore-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $codexHome -Force | Out-Null
Expand-Archive -LiteralPath 'D:\IdeaMigration\<bundle>\windows-codex-state.zip' -DestinationPath $codexStage -Force
Get-ChildItem -LiteralPath (Join-Path $codexStage 'windows-codex-state') -Force |
    Copy-Item -Destination $codexHome -Recurse -Force
```

### 4.8. Установка патча

```powershell
git clone git@github.com:4erk/jetbrains-ai-wsl-patch.git
Set-Location jetbrains-ai-wsl-patch
git checkout jbai-261.25134.237-patch-1.1.0

.\scripts\install.ps1 `
  -IdeHome 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3' `
  -WslDistribution Ubuntu `
  -WslUser 4erk

.\scripts\test.ps1
.\scripts\status.ps1
```

Установщик заново поставит checksum-pinned Windows/WSL Codex, ACP и Node, создаст runtime manifest и синхронизирует более новый `auth.json`. Windows и Linux `config.toml` останутся раздельными.

## 5. Проекты

Полный WSL export сохранит текущие Linux paths:

- `/home/4erk/Projects/neurofox-project-starter`, remote `https://github.com/neurofoxpro/project-starter.git`;
- `/home/4erk/Projects/forma`, remote `git@github.com:medialuki/forma.git`;
- `/home/4erk/Projects/supervisor`, remote `git@github.com:neurofoxpro/supervisor.git`;
- `/home/4erk/Projects/meta`, remote `git@github.com:neurofoxpro/meta.git`;
- `/home/4erk/Projects/nspd`, remote `git@github.com:medialuki/nspd.git`;
- `/home/4erk/Projects/neurofoxpro`, без настроенного remote;
- `/home/4erk/Projects/test`, локальный каталог;
- `/home/4erk/Projects/codex-assistant-integration-wsl`, локальный каталог.

Windows-проекты под OneDrive появятся после синхронизации OneDrive. Отдельно проверьте `C:\Users\<user>\Projects\codex-assistant-integration-win`, если он нужен.

Если Windows username, distro name или Linux user отличаются от baseline, не восстанавливайте `recentProjects.xml`; откройте проекты вручную из новых путей.

## 6. Финальная проверка

1. Запустите IDEA и дождитесь завершения plugin loading.
2. В `Settings | Plugins` проверьте критические версии из baseline JSON.
3. Убедитесь, что отключенные плагины совпадают с `disabledPluginIds`.

```powershell
$repo = 'C:\path\to\jetbrains-ai-wsl-patch'
$baseline = Get-Content -Raw (Join-Path $repo 'docs\workstation\current-baseline.json') | ConvertFrom-Json
$profile = "$env:APPDATA\JetBrains\IntelliJIdea2026.1"
$actualPlugins = @(Get-ChildItem (Join-Path $profile 'plugins') -Directory | Select-Object -ExpandProperty Name)
$actualDisabled = @(Get-Content (Join-Path $profile 'disabled_plugins.txt'))

Compare-Object $baseline.pluginDirectories $actualPlugins
Compare-Object $baseline.disabledPluginIds $actualDisabled
```

Обе команды `Compare-Object` не должны выводить различий.

4. В WSL-проекте выполните в Codex:

```text
whoami
pwd
command -v codex
codex --version
```

Ожидается пользователь `4erk`, Linux project path, Linux Codex из IDE-specific runtime и версия `0.144.3`.

5. В Windows-проекте проверьте, что запускается Windows Codex, а не WSL binary.
6. Проверьте кликабельную ссылку `/home/4erk/.../file`.
7. Проверьте список моделей, лимит-индикатор и переключение Spark/default bucket.
8. Выполните `/mcp` и убедитесь, что доступен JetBrains MCP.
9. Проверьте историю старых AI-чатов.
10. Проверьте лог:

```powershell
$log = "$env:LOCALAPPDATA\JetBrains\IntelliJIdea2026.1\log\idea.log"
Select-String -Path $log -Pattern 'NoClassDefFoundError|VerifyError|PluginException|Failed to initialize ACP'
```

11. Повторно выполните:

```powershell
.\scripts\status.ps1 -Json
```

У plugin JAR и обоих ACP bridge поле `integrity` должно быть `true`.

Только после этой проверки включайте Backup and Sync на новом компьютере. При конфликте выбирайте проверенную локальную конфигурацию нового компьютера как исходную. Учтите ограничение JetBrains: отключенный плагин, отсутствующий на новом IDE, Backup and Sync самостоятельно не установит.

## 7. Что не надо копировать

- `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.1\log` и IDE caches.
- Старую пропатченную папку `ml-llm`.
- Codex `runtime`, `bin`, `downloads`, cache и telemetry SQLite.
- `jetbrains-rate-limits.json`.
- `installation_id`.
- Windows `config.toml` в WSL или WSL `config.toml` в Windows.
- Plugin ZIP/JAR, licenses, tokens и SSH private keys в Git-репозиторий.
- `settingsSyncEntriesMissingOnDisk` из baseline как подтвержденные установленные плагины.

Официальное расположение config/plugins/system directories описано в [IntelliJ IDEA directories](https://www.jetbrains.com/help/idea/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html).
