# Слой инференса

## Модули
- `:core-policy` (JVM): режимы, бюджет памяти, нагрев, маршрутизация.
- `:core-inference` (JVM): контракт `InferenceEngine`, ChatML-промпт, обрезка истории, фильтр `<think>`,
  загрузка моделей (Hugging Face API, докачка, SHA-256), хранилище моделей.
- `:engine-llama` (Android): JNI-мост к llama.cpp и `LlamaEngine`. Подключается, если найден Android SDK.

## Первая сборка
```
sh scripts/fetch_llama.sh          # клонирует тег из llama.cpp.version (сейчас b12591) в third_party/llama.cpp
gradle wrapper --gradle-version 8.10.2
./gradlew :core-policy:test :core-inference:test        # без Android SDK
sh native-check/run.sh                                   # UTF-8 тест и проверка синтаксиса JNI
./gradlew :engine-llama:assembleRelease                  # нужны Android SDK, NDK 27.2.12479018, CMake 3.22.1
```

## Что почти наверняка придётся поправить
- **API llama.cpp** меняется часто. `norva_llama.cpp` сверен вручную с `include/llama.h` (master, ноябрь 2026): исправлены удалённое
  `use_mmap` (теперь `load_mode`) и пятиаргументный `llama_sampler_init_penalties`. Различия версий выбираются через SFINAE.
  Настоящую проверку делает `native-check/verify_real_llama.sh` (job `llama-api-check`); заглушки в `native-check/stubs` её не заменяют.
- **Инструкции CPU.** По умолчанию `armv8.2-a+dotprod+fp16+i8mm`. `CpuGuard` сверяет сборку с `/proc/cpuinfo` и вместо SIGILL выдаёт
  понятную ошибку. Если нужна сборка для процессора без i8mm:
  `./gradlew ... -Pnorva.cpuArch=armv8.2-a+dotprod`. Строка `LlamaEngine.systemInfo()` показывает, что реально включено.
- **Шаблон чата Qwen3.5.** Модель рассуждает по умолчанию (карточка Qwen/Qwen3.5-4B). `ChatMl` без рассуждений добавляет пустой
  блок `<think>\n\n</think>\n\n`, с рассуждениями заканчивает промпт на `<think>\n`, поток разбирает `ThinkingFilter(startInThinking = true)`.
  Это формат Qwen3; точное совпадение с `tokenizer.chat_template` в GGUF не проверялось.
- **Формат ответа Hugging Face tree API** в тесте взят по памяти. Проверить на живом запросе:
  `curl https://huggingface.co/api/models/unsloth/Qwen3.5-4B-GGUF/tree/main`.
- **Версии** закреплены и описаны в `docs/BUILD_AND_INSTALL.md`.

## Что проверено
| Компонент | Статус |
|---|---|
| UTF-8 буфер токенов (C++) | собран и выполнен здесь, 9 сценариев |
| `norva_llama.cpp` | сверен вручную с настоящим `llama.h`; синтаксис против заглушек; **против настоящих заголовков не компилировался** |
| Kotlin-код и тесты (`ChatMl`, `ThinkingFilter`, `HistoryTrimmer`, загрузчик, Hub, Store) | написаны, **не компилировались** (нет kotlinc и Gradle) |
| Загрузка: докачка, редирект, отказ сервера от Range, неверный хеш, нехватка места | покрыто тестами с локальным HTTP-сервером, **не запускалось** |
| Скорость, память, нагрев на GT 8 Pro | не проверялось |

## Ограничения текущей версии
- Каждый запрос заново считает весь промпт: повторного использования KV-кэша между репликами нет.
- Бюджет токенов на рассуждение (`thinkingBudgetTokens`) применяет оркестратор чата (`core-chat`): остановка и повторный запрос с закрытым `</think>`.
- Только CPU. Offload на GPU Adreno (OpenCL) рассматриваем после замеров, чтобы не потерять в батарее и нагреве.
