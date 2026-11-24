package ai.norva.app.ui

import ai.norva.app.AppContainer
import ai.norva.chat.InstallState
import ai.norva.inference.ResearchIndex
import ai.norva.inference.SyncCipher
import ai.norva.policy.DefaultCatalog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(c: AppContainer, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val installed by c.installer.states.collectAsStateWithLifecycle()
    val models = DefaultCatalog.models.filter { installed[it.id] == InstallState.Installed }
    var section by rememberSaveable { mutableStateOf("MultiBrain") }
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var turbo by remember { mutableStateOf(c.settings.turbo) }
    var notes by remember { mutableStateOf(emptyList<String>()) }
    var note by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var davUrl by remember { mutableStateOf("") }
    var davUser by remember { mutableStateOf("") }
    var davKey by remember { mutableStateOf("") }
    var applySettings by remember { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    fun runTask(action: suspend () -> String) {
        if (busy) return
        busy = true
        result = ""
        job = scope.launch {
            try { result = action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { result = e.localizedMessage ?: "Операция завершилась ошибкой" }
            catch (e: LinkageError) { result = "Движок недоступен: ${e.message}" }
            finally { busy = false; phase = "" }
        }
    }
    fun exchange(uri: Uri?, export: Boolean) {
        if (uri == null) return
        val pass = password.toCharArray()
        password = ""
        runTask {
            try { withContext(Dispatchers.IO) {
                if (export) {
                    val bytes = c.sync.export(pass)
                    checkNotNull(resolver.openOutputStream(uri,"wt")).use { it.write(bytes) }
                    "Зашифрованная копия сохранена"
                } else {
                    val bytes = checkNotNull(resolver.openInputStream(uri)).use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            require(out.size()+n <= SyncCipher.LIMIT+52) { "Архив слишком велик" }
                            out.write(buf,0,n)
                        }
                        out.toByteArray()
                    }
                    "Добавлено сообщений: " + c.sync.merge(pass,bytes,applySettings)
                }
            } } finally { pass.fill('\u0000') }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { exchange(it,true) }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { exchange(it,false) }
    LaunchedEffect(section) {
        if (section == "Memory 2.0") try { notes = withContext(Dispatchers.IO) { c.personal.list() } }
        catch (e: Exception) { result = "Не удалось открыть память" }
    }
    AppBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("NØRVA Studio") },
            navigationIcon = { TextButton(onClick=back) { Text("Назад") } }) }) { inset ->
            Column(Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(enabled=!busy,onClick={menu=true}) { Text("$section ▾") }
                    DropdownMenu(menu,{menu=false}) {
                        for (name in listOf("MultiBrain","Deep Research","Benchmark","Memory 2.0","Turbo Mode","Cross Sync")) {
                            DropdownMenuItem(text={Text(name)},onClick={section=name;menu=false;result=""})
                        }
                    }
                }
                when (section) {
                    "Memory 2.0" -> {
                        Text("Сохранённые предпочтения используются в локальных чатах.")
                        OutlinedTextField(note,{ note=it.take(240) },label={Text("Что запомнить?")})
                        Button(enabled=!busy && note.isNotBlank(),onClick={ val value=note; runTask {
                            notes=withContext(Dispatchers.IO) { c.personal.edit(value,false); c.personal.list() }; note=""; "Сохранено"
                        } }) { Text("Запомнить") }
                        notes.forEach { value -> Row { Text(value,Modifier.weight(1f)); TextButton(enabled=!busy,onClick={runTask {
                            notes=withContext(Dispatchers.IO) { c.personal.edit(value,true);c.personal.list() }; "Удалено"
                        }}) { Text("Удалить") } } }
                    }
                    "Turbo Mode" -> {
                        Text("При автовыборе предпочитает меньшую установленную модель, ограничивает контекст и длину ответа для коротких запросов. Защита от нагрева и низкого заряда остаётся активной.")
                        Switch(checked=turbo,onCheckedChange={turbo=it;c.settings.turbo=it})
                    }
                    "Cross Sync" -> {
                        Text("Обмен с ПК через зашифрованный файл. В копию входят завершённые текстовые сообщения, системный промпт, режим и Turbo. Пароль понадобится на обоих устройствах.")
                        OutlinedTextField(password,{password=it},label={Text("Пароль архива · от 12 символов")},singleLine=true,
                            visualTransformation=PasswordVisualTransformation())
                        Row { Checkbox(applySettings,{applySettings=it});Text("Применить настройки при импорте") }
                        Button(enabled=!busy && password.length>=12,onClick={save.launch("norva-1.4.nvault")}) { Text("Экспортировать") }
                        OutlinedButton(enabled=!busy && password.length>=12,onClick={load.launch(arrayOf("*/*"))}) { Text("Объединить с архивом") }
                        HorizontalDivider()
                        Text("Синхронизация по HTTPS WebDAV", style=MaterialTheme.typography.titleMedium)
                        OutlinedTextField(davUrl,{davUrl=it},label={Text("HTTPS-адрес файла .nvault")},singleLine=true)
                        OutlinedTextField(davUser,{davUser=it},label={Text("Логин сервера")},singleLine=true)
                        OutlinedTextField(davKey,{davKey=it},label={Text("Пароль приложения WebDAV")},singleLine=true,
                            visualTransformation=PasswordVisualTransformation())
                        Button(enabled=!busy && password.length>=12 && davUrl.isNotBlank(),onClick={
                            val pass=password.toCharArray()
                            val endpoint=davUrl.trim()
                            val user=davUser
                            val secret=davKey
                            val restore=applySettings
                            password=""; davKey=""
                            runTask {
                                try { withContext(Dispatchers.IO) {
                                    val server=ai.norva.inference.WebDavVault(endpoint,user,secret)
                                    val snapshot=server.read()
                                    val added=snapshot?.let { c.sync.merge(pass,it.bytes,restore) } ?: 0
                                    currentCoroutineContext().ensureActive()
                                    server.write(c.sync.export(pass),snapshot)
                                    "Синхронизация завершена. Добавлено сообщений: $added"
                                } } finally { pass.fill('\u0000') }
                            }
                        }) { Text("Синхронизировать") }
                    }
                    else -> {
                        if (section=="Deep Research") Text("Запрос отправляется в Wikipedia и Crossref. Модель сопоставит доступные статьи и аннотации и перепроверит черновик.")
                        if (section=="MultiBrain") Text("Две установленные модели ответят независимо, проверят ответы друг друга и подготовят итог.")
                        if (section=="Benchmark") Text("Три контрольные задачи: точность, скорость декодирования, память процесса и завершение серии.")
                        if (section!="Benchmark") OutlinedTextField(query,{query=it.take(1600)},label={Text("Запрос")},modifier=Modifier.fillMaxWidth())
                        val selected = models.firstOrNull { it.id==c.settings.selectedModel } ?: models.firstOrNull()
                        val other = models.firstOrNull { it != selected }
                        val enough = selected!=null && (section!="MultiBrain" || other!=null)
                        Text(if (!enough) "Установите ${if (section=="MultiBrain") "две разные модели" else "модель"} в Model Hub" else
                            listOfNotNull(selected?.displayName,other?.displayName?.takeIf { section=="MultiBrain" }).joinToString(" + "))
                        Button(enabled=!busy && enough && (query.isNotBlank() || section=="Benchmark"),onClick={
                            val q=query
                            val model=selected ?: return@Button
                            runTask {
                                phase="Подготовка…"
                                val sources = if (section=="Deep Research") withContext(Dispatchers.IO) { ResearchIndex().search(q) } else emptyList()
                                c.orchestrator.withLocalEngine {
                                    when(section) {
                                        "MultiBrain" -> c.workbench.panel(q,model,checkNotNull(other)) { phase=it }
                                        "Deep Research" -> c.workbench.research(q,model,sources) { phase=it }
                                        else -> c.workbench.benchmark(model) { phase=it }
                                    }
                                }
                            }
                        }) { Text("Запустить") }
                    }
                }
                if (busy) { LinearProgressIndicator(); Text(phase); TextButton(onClick={job?.cancel()}) { Text("Остановить") } }
                if (result.isNotBlank()) SelectionContainer { Text(result) }
            }
        }
    }
}
