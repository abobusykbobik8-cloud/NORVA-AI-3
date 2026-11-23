# JNI находит метод onToken по имени, R8 не должен его переименовывать.
-keep interface ai.norva.engine.TokenSink { *; }
-keep class ai.norva.engine.LlamaNative { native <methods>; }
