# Версионирование

У проекта две независимые оси совместимости:

- `VERSION` содержит SemVer самого патча;
- `compatibility/<jetbrains-ai-version>.json` фиксирует поддерживаемую версию JetBrains AI, IDE build range, patch version и clean/patched JAR hashes.

SemVer меняется по смыслу патча:

- `PATCH` - исправление существующей функции без изменения контракта;
- `MINOR` - новая функция или заметное изменение поведения/UI с обратной совместимостью;
- `MAJOR` - несовместимое изменение install/runtime contract.

Обновление только JetBrains AI hashes и hook compatibility обычно повышает `PATCH`. Новая функция, как app-server rate-limit bridge, повышает `MINOR`, даже если версия JetBrains AI не изменилась.

Каждый проверенный выпуск получает annotated tag:

```text
jbai-<jetbrains-ai-version>-patch-<patch-version>
```

Исторический tag замораживает соответствующий compatibility manifest. В `main` хранится актуальный manifest для каждой поддерживаемой версии JetBrains AI.

После commit и live-проверки ветка и все release tags отправляются одним атомарным push:

```powershell
.\scripts\release.ps1
```
