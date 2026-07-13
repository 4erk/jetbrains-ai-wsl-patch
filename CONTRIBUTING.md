# Contributing

Изменения должны оставаться минимальными и проверяемыми. Сначала подтвердите дефект на чистой поддерживаемой версии плагина, затем меняйте один из существующих feature boundaries или обоснуйте новый.

Перед коммитом выполните:

```powershell
.\scripts\test.ps1
.\scripts\test.ps1 -PluginRoot <clean-plugin-root> -IdeHome <ide-home>
```

Не публикуйте vendor artifacts, runtime binaries, логи, credentials или локальные конфиги. Для новой версии JetBrains AI добавляйте новый compatibility manifest и запись в changelog.
