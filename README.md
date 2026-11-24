# NØRVA AI Mobile

Android-приложение (Kotlin, Compose) с локальным ИИ на llama.cpp. Основное устройство — realme GT 8 Pro с 12 ГБ ОЗУ. Для устройств с 3–4 ГБ доступна Qwen3.5 0.8B.
Что реализовано, проверено и не проверено: `docs/STATUS.md`. Профиль устройства и бюджет памяти: `docs/DEVICE_GT8PRO.md`. Движок: `docs/ENGINE.md`.

Функции беты, установка APK и обмен чатами с ПК описаны в [заметках к версии 1.4](docs/RELEASE-1.4-beta.md).

## Модули
| Модуль | Тип | Назначение |
|---|---|---|
| `core-policy` | JVM | режимы Fast/Normal/Deep, каталог моделей, бюджет памяти, нагрев и батарея, маршрутизатор |
| `core-inference` | JVM | ChatML, фильтр `<think>`, загрузчик с докачкой и SHA-256, контракт движка |
| `core-chat` | JVM | оркестратор чата, установка моделей, интерфейс истории |
| `core-vision` | JVM | собственные анализатор снимков и генератор картинок и видео (`docs/VISION_AND_MEDIA.md`) |
| `engine-llama` | Android | JNI-мост и CMake для llama.cpp и mtmd |
| `app` | Android | Compose-интерфейс, Room, провайдеры памяти и нагрева |

## Сборка
Подробно: `docs/BUILD_AND_INSTALL.md`. Кратко:
- **Без локальной среды:** загрузите проект в GitHub, запустите workflow `ci`, скачайте артефакт `norva-ai-debug-apk` из job `android-apk`.
- **Локально:** `sh scripts/fetch_llama.sh`, затем `gradle :app:assembleDebug` (JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1).

Версия llama.cpp закреплена в `llama.cpp.version` (сейчас `b12591`).
