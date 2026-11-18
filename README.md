# PocketLink

Android-пульт для Windows: тачпад, клавиатура и свои сочетания, экран ПК на телефоне, файлы, буфер обмена, звук и YouTube.

**В репозитории теперь есть полный Android-проект версии 1.3.1**, включая исходники интерфейса, ресурсы, справочник сочетаний и Gradle Wrapper. Это рабочая исходная версия для улучшения визуала, а не только APK.

- **Для Claude и других разработчиков: [задание и карта интерфейса](docs/UI-HANDOFF.md).**
- [Исходники Android](android/)
- [Последние сборки и изменения](https://github.com/olegskorostrel-wq/PocketLink/releases)
- [Android APK 1.3.1](https://github.com/olegskorostrel-wq/PocketLink/releases/download/v1.3.1-preview/PocketLink.apk)
- [Windows ZIP 1.2.0, совместим с Android 1.3.1](https://github.com/olegskorostrel-wq/PocketLink/releases/download/v1.3.0-preview/PocketLink-Windows.zip)

## Вид приложения

На скриншоте экран команд из 1.3.0. В исходниках 1.3.1 уже исправлено центрирование подписей нижних вкладок; остальные элементы этого экрана соответствуют скриншоту.

![Команды PocketLink на телефоне, версия 1.3.0](docs/screenshots/commands-1.3.0.jpg)

Более ранние скриншоты настроек и точные различия версий — в [задании по визуалу](docs/UI-HANDOFF.md).

## Сборка Android

Нужны JDK 17 и Android SDK 35. Открыть папку `android` в Android Studio либо настроить `ANDROID_HOME` и выполнить:

```powershell
cd android
.\gradlew.bat assembleDebug lintDebug
```

Linux/macOS:

```sh
cd android
sh ./gradlew assembleDebug lintDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Для автономной сборки сервер Windows не нужен. Пакет `com.pocketlink.app`, имя PocketLink, minSdk 29, targetSdk 35.

Ключ подписи опубликованных APK не хранится в Git. Собранный на другой машине debug APK обычно имеет другую подпись и не установится поверх текущего. Для выдачи пользователю обновления верни изменения исходников владельцу проекта, чтобы он собрал APK прежним ключом. Удалять установленное приложение для обхода ошибки подписи не следует: можно потерять сопряжение и настройки.

В этом репозитории сейчас опубликована Android-часть. Исходники Windows-сервера и расширений браузера сюда пока не добавлены; готовая совместимая Windows-сборка доступна по ссылке выше.
