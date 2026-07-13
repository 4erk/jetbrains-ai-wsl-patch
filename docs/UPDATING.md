# Перенос патча на обновление JetBrains AI

## 1. Получить чистый plugin tree

Скачайте обновление средствами IDE и закройте IDE до его применения. Распакуйте pending `ml-llm.zip` во временный каталог. Не используйте уже пропатченный ZIP как источник compatibility manifest.

## 2. Зафиксировать совместимость

```powershell
.\scripts\capture-compatibility.ps1 -PluginRoot C:\temp\ml-llm
```

Скрипт откажется работать, если runtime helper уже присутствует. Проверьте version, build range и исходные SHA-256 в новом `compatibility/<plugin-version>.json`.

## 3. Провести upstream audit

Перед переносом проверьте, не исправлена ли функция самим JetBrains plugin. Не переносите старые lifecycle/session/model workarounds автоматически. Текущий scope ограничен четырьмя feature из compatibility manifest.

Запустите build test:

```powershell
.\scripts\test.ps1 -PluginRoot C:\temp\ml-llm -IdeHome 'C:\Program Files\JetBrains\<IDE>'
```

Если hook shape изменился, patcher должен упасть. Измените semantic matcher минимально, затем снова проверьте exact hook count и содержимое выходных JAR.

После review обновите ожидаемые итоговые хеши:

```powershell
.\scripts\build.ps1 -PluginRoot C:\temp\ml-llm -IdeHome 'C:\Program Files\JetBrains\<IDE>' -UpdatePatchedHashes
```

Повторный обычный build должен пройти уже без `-UpdatePatchedHashes`.

## 4. Версионировать

- измените `VERSION` по SemVer;
- обновите `CHANGELOG.md`;
- добавьте manifest, не заменяя историю старых версий;
- при необходимости обновите `runtime.lock.json` отдельной командой;
- не добавляйте plugin ZIP, JAR, runtime downloads или `.state`.

Release tag имеет форму `jbai-<plugin-version>-patch-<patch-version>`, например `jbai-261.25134.237-patch-1.1.0`. После успешной live-проверки commit и tag публикуются командой `scripts/release.ps1`.

## 5. Установить и проверить

Закройте IDE и выполните `scripts/install.ps1`. После запуска IDE проверьте:

1. `scripts/status.ps1` показывает новую версию и все три patched JAR.
2. Windows project запускает Windows runtime.
3. WSL project запускает Linux runtime с правильным distro/user.
4. В `idea.log` нет Windows command для WSL ACP launch.
5. Ссылка `/home/.../file` открывает WSL VirtualFile.
6. `jetbrains-rate-limits.json` обновляется не реже чем раз в 20 секунд, stale SQLite values не используются, bucket меняется вместе с выбранной моделью.
7. В логе нет `NoClassDefFoundError`, `VerifyError` или plugin load errors.

Только после фактической проверки создавайте release tag.
