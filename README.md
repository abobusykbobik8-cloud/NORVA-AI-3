# NØRVA AI Mobile

Android-приложение (Kotlin, Compose) с локальным ИИ на llama.cpp. Целевое устройство: realme GT 8 Pro, 12 ГБ ОЗУ.
Что реализовано, проверено и не проверено: `docs/STATUS.md`. Профиль устройства и бюджет памяти: `docs/DEVICE_GT8PRO.md`. Движок: `docs/ENGINE.md`.

## Модули
| Модуль | Тип | Назначение |
|---|---|---|
| `core-policy` | JVM | режимы Fast/Normal/Deep, каталог моделей, бюджет памяти, нагрев и батарея, маршрутизатор |
| `core-inference` | JVM | ChatML, фильтр `<think>`, загрузчик с докачкой и SHA-256, контракт движка |
| `core-chat` | JVM | оркестратор чата, установка моделей, интерфейс истории |
| `engine-llama` | Android | JNI-мост и CMake для llama.cpp |
| `app` | Android | Compose-интерфейс, Room, провайдеры памяти и нагрева |

## Сборка
Подробно: `docs/BUILD_AND_INSTALL.md`. Кратко:
- **Без локальной среды:** загрузите проект в GitHub, запустите workflow `ci`, скачайте артефакт `norva-ai-debug-apk` из job `android-apk`.
- **Локально:** `sh scripts/fetch_llama.sh`, затем `./gradlew :app:assembleDebug` (JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1).

Версия llama.cpp закреплена в `llama.cpp.version` (сейчас `b12591`).
