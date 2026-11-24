package ai.norva.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.norva.chat.MessageStatus
import ai.norva.chat.StoredMessage
import ai.norva.inference.Role
import java.util.Locale

private class LiveSession(val context: Context, val submit: (String) -> Boolean, val interrupt: () -> Unit) {
    var status by mutableStateOf("Готов к разговору")
    var transcript by mutableStateOf("")
    var active by mutableStateOf(false)
    var awaiting by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var after = 0L
    private var spoken = 0
    private var epoch = 0
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    fun init() {
        tts = TextToSpeech(context) { result ->
            val voice = tts ?: return@TextToSpeech
            ready = result == TextToSpeech.SUCCESS && voice.setLanguage(Locale.getDefault()) >= 0
            if (!ready) status = "Нет доступного голоса для языка устройства"
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { handler.post { if (active && id == "end-$epoch") listen() } }
                @Deprecated("SDK callback") override fun onError(id: String?) {
                    handler.post { if (active && id?.endsWith("-$epoch") == true) { stop(); status = "Ошибка синтеза речи" } }
                }
            })
        }
    }
    fun stop() {
        epoch++
        active = false
        val pending = awaiting
        awaiting = false
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        if (pending) interrupt()
    }
    fun close() { stop(); tts?.shutdown(); tts = null }
    fun listen() {
        stop()
        val local = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) { status = "На устройстве нет службы распознавания"; return }
        active = true
        transcript = ""
        status = "Слушаю…"
        val current = epoch
        try {
            val speech = if (Build.VERSION.SDK_INT >= 31 && local) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (epoch==current) status = "Распознаю…" }
                override fun onError(error: Int) { if (epoch==current) { stop(); status = "Распознавание остановлено (код $error)" } }
                override fun onPartialResults(results: Bundle?) {
                    if (epoch==current) transcript = words(results)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    if (epoch != current) return
                    transcript = words(results)
                    if (transcript.isBlank()) { stop(); status = "Речь не распознана"; return }
                    recognizer?.destroy()
                    recognizer = null
                    spoken = 0
                    awaiting = true
                    status = "Ассистент отвечает…"
                    if (!submit(transcript)) { awaiting = false; stop(); status = "Движок занят. Нажмите, чтобы повторить." }
                }
                private fun words(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
            })
        } catch (e: RuntimeException) { stop(); status = e.message ?: "Распознавание недоступно" }
    }
    fun read(message: StoredMessage?) {
        if (!awaiting || message == null || message.id <= after || message.role != Role.ASSISTANT) return
        if (message.status == MessageStatus.FAILED || message.status == MessageStatus.CANCELLED) {
            stop(); status = message.errorMessage ?: "Ответ остановлен"; return
        }
        val text = message.content
        val done = message.status == MessageStatus.COMPLETE
        val end = if (done) text.length else text.indexOfLast { it in ".!?\n" } + 1
        if (end > spoken) {
            for (chunk in text.substring(spoken,end).chunked(1800)) {
                if (tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null,"part-$epoch") != TextToSpeech.SUCCESS) {
                    stop(); status = "Не удалось озвучить ответ"; return
                }
            }
            spoken = end
            status = "Говорю…"
        }
        if (done) {
            after = message.id
            awaiting = false
            tts?.playSilentUtterance(100,TextToSpeech.QUEUE_ADD,"end-$epoch")
        }
    }
}

@Composable
fun LiveDialog(messages: List<StoredMessage>, send: (String) -> Boolean, stop: () -> Unit, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(messages.lastOrNull()?.id ?: 0L)
    val sender by rememberUpdatedState(send)
    val stopper by rememberUpdatedState(stop)
    val session = remember { LiveSession(ctx, { sender(it) }, { stopper() }) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session,lifecycle) {
        session.init()
        val observer = LifecycleEventObserver { _,event -> if (event == Lifecycle.Event.ON_STOP) session.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.close() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { session.after = latest; session.listen() } else session.status = "Нет доступа к микрофону"
    }
    val last = messages.lastOrNull()
    LaunchedEffect(last, session.awaiting) { session.read(last) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("NØRVA Live") }, text = { Column {
        Text(session.status)
        if (session.transcript.isNotBlank()) Text(session.transcript)
        Text("При отсутствии локального распознавателя системный голосовой сервис может использовать сеть.", style=MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(enabled=session.ready, onClick = {
        session.after = latest
        if (ContextCompat.checkSelfPermission(ctx,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) session.listen()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }) { Text(if (session.active) "Перебить и говорить" else "Начать разговор") } },
        dismissButton={ TextButton(onClick=dismiss) { Text("Закрыть") } })
}
