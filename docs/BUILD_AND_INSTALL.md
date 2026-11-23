# Сборка и установка NØRVA AI Mobile

Ни один шаг этого документа не выполнялся автором на реальной сборке: в среде разработки нет Gradle, Android SDK, NDK и сети.
Команды и версии подобраны по документации и проверены только на согласованность. Первая сборка может выдать ошибки, их нужно присылать вместе с логом.

## Закреплённые версии
| Компонент | Версия | Основание |
|---|---|---|
| llama.cpp | `b12591` (файл `llama.cpp.version`) | тег существует (релизы на GitHub); JNI сверен с `llama.h` ноября 2026 |
| Gradle | 8.10.2 | AGP 8.7 требует Gradle 8.9 и выше |
| Android Gradle Plugin | 8.7.3 | согласован с Gradle 8.10.2 и JDK 17 |
| Kotlin | 2.0.21 | Compose-плагин и KSP берут ту же версию |
| KSP | 2.0.21-1.0.28 | существует на Maven Central (15.11.2024), префикс совпадает с Kotlin |
| Compose BOM | 2024.10.01 | эпоха Kotlin 2.0.x |
| Room | 2.6.1 | работает с KSP 1.0.x |
| NDK / CMake / Build Tools | 27.2.12479018 / 3.22.1 / 35.0.0 | `compileSdk 35`, `minSdk 29`, ABI только `arm64-v8a` |
| JDK | 17 | минимум для AGP 8.7 |

Почему не новейшие версии: AGP 9.4 (сентябрь 2026) требует Gradle 9.6 и Build Tools 36 и меняет модель плагинов Kotlin.
Переход на него это отдельная задача. Закреплённый набор старше, но согласован внутри себя.
GitHub Actions используют актуальные мажоры: checkout v7, setup-java v6, setup-gradle v6, upload-artifact v7.

## Способ 1. Сборка APK в GitHub Actions (рекомендуется)
1. Создайте пустой репозиторий на GitHub (можно приватный).
2. В папке проекта:
   ```
   git init -b main
   git add -A
   git commit -m "NØRVA AI Mobile"
   git remote add origin https://github.com/<ваш-логин>/<репозиторий>.git
   git push -u origin main
   ```
3. Откройте вкладку **Actions**. Запустится workflow `ci` (или Run workflow вручную).
4. Четыре job: `jvm-tests`, `native-stub-check`, `llama-api-check`, `android-apk`. APK собирается независимо от остальных.
5. Когда `android-apk` станет зелёным, откройте страницу запуска, внизу раздел **Artifacts**, скачайте `norva-ai-debug-apk`
   (zip, внутри `app-debug.apk`). Скачивание требует входа в GitHub. Хранится 30 дней. SHA-256 файла выведен в Summary запуска.
6. Если job красный: откройте его, раскройте упавший шаг и пришлите лог. Порядок разбора:
   - `jvm-tests`: ошибки Kotlin и тестов в JVM-модулях;
   - `llama-api-check`: расхождение JNI-кода с `llama.h` (правится только `norva_llama.cpp`);
   - `android-apk`: ошибки Gradle, Compose, Room (KSP) или CMake/NDK.

## Способ 2. Локальная сборка
Нужны: JDK 17, Git, Android Studio или командная строка Android SDK (платформа 35, Build Tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1).
```
sh scripts/fetch_llama.sh                         # на Windows: Git Bash или WSL
echo "sdk.dir=/путь/к/Android/Sdk" > local.properties
gradle wrapper --gradle-version 8.10.2            # один раз, нужен установленный Gradle
./gradlew :core-policy:test :core-inference:test :core-chat:test
sh native-check/verify_real_llama.sh third_party/llama.cpp
./gradlew :app:assembleDebug
```
Результат: `app/build/outputs/apk/debug/app-debug.apk`. Первая сборка llama.cpp занимает несколько минут.
Для процессора без i8mm добавьте `-Pnorva.cpuArch=armv8.2-a+dotprod+fp16`.

## Установка на realme GT 8 Pro
Вариант A (через ПК):
1. Настройки, О телефоне, Версия: 7 раз нажмите на номер сборки, включится режим разработчика.
2. Для разработчиков: включите «Отладка по USB» (и «Установка через USB», если есть).
3. Подключите телефон, подтвердите запрос на отладку. Команда: `adb install -r app-debug.apk`.

Вариант B (без ПК): перенесите `app-debug.apk` на телефон, откройте его из файлового менеджера, разрешите установку из этого источника,
на предупреждение Google Play Protect выберите «Всё равно установить».

Рекомендуется: Настройки, Батарея, NØRVA AI, «Без ограничений», чтобы система не замораживала приложение во время ответа.

## Первый запуск
1. Откройте «Модели» и скачайте **Qwen3.5 4B** (2,74 ГБ, по Wi-Fi). Загрузка идёт, пока приложение запущено; после обрыва нажмите «Скачать»
   ещё раз, докачка продолжится. Проверяется SHA-256 из Hugging Face.
2. Создайте чат, выберите режим Fast, Normal или Deep, отправьте сообщение.
3. Если после загрузки ответа нет, смотрите сообщение под ответом и лог: `adb logcat -s NorvaLlama`.

## Типичные проблемы
| Симптом | Причина и действие |
|---|---|
| Сообщение «собрана под инструкции (i8mm)» | у процессора нет этих инструкций: пересобрать с `-Pnorva.cpuArch=armv8.2-a+dotprod+fp16` |
| «Нативная библиотека движка недоступна» | `libnorva_llama.so` не попала в APK или не загрузилась: смотреть лог сборки CMake |
| «Не удалось загрузить модель» | мало свободной памяти, повреждённый файл или модель не поддерживается версией llama.cpp |
| Ошибка Gradle `android.useAndroidX` | должен быть `gradle.properties` из архива |
| Ошибка Hugging Face «файл не найден» | репозиторий или файл переименованы: поправить `Catalog.kt` |
