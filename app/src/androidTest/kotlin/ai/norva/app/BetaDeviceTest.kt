package ai.norva.app

import ai.norva.app.data.*
import ai.norva.app.platform.*
import ai.norva.app.ui.*
import ai.norva.inference.SyncCipher
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BetaDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun packagedRendererAndAndroidMediaEncoder() = runBlocking {
        val app = ctx.applicationContext as NorvaApp
        val exe = java.io.File(ctx.applicationInfo.nativeLibraryDir, "libnorva_diffusion.so")
        val process = ProcessBuilder(exe.path).start()
        assertEquals(2, process.waitFor())
        val raw = java.io.File.createTempFile("encoder-test", ".rgb", ctx.cacheDir)
        val file = java.io.File.createTempFile("encoder-test", ".mp4", ctx.cacheDir)
        try {
            raw.outputStream().buffered().use { out ->
                repeat(9) { i -> out.write(ByteArray(256*256*3) { (it+i*10).toByte() }) }
            }
            app.container.localDiffusion.encodeFrames(raw, true, file)
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val format = extractor.getTrackFormat(0)
                assertEquals("video/avc", format.getString(android.media.MediaFormat.KEY_MIME))
                assertEquals(256, format.getInteger(android.media.MediaFormat.KEY_WIDTH))
                extractor.selectTrack(0)
                var count = 0
                while (extractor.sampleTime >= 0) { count++; extractor.advance() }
                assertEquals(9, count)
            } finally { extractor.release() }
        } finally { raw.delete(); file.delete() }
        Unit
    }

    @Test fun memoryIsPersistentEncryptedAndRemovable() {
        val note = "device test " + System.nanoTime()
        val memory = PersonalMemory(ctx)
        memory.edit(note,false)
        try {
            assertTrue(note in PersonalMemory(ctx).contextFor(note))
            assertFalse(ctx.getSharedPreferences("personal_memory",0).getString("items","")!!.contains(note))
        } finally { memory.edit(note,true) }
        assertFalse(note in memory.list())
    }

    @Test fun encryptedChatExchangeDeduplicatesAndRestoresSettings() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx,NorvaDatabase::class.java).build()
        val second = Room.inMemoryDatabaseBuilder(ctx,NorvaDatabase::class.java).build()
        val settings = SettingsStore(ctx)
        val previous = settings.systemPrompt
        try {
            val dao = db.chatDao()
            val id = dao.insertConversation(ConversationEntity(title="Fixture",createdAt=10,updatedAt=10))
            dao.insertMessage(MessageEntity(conversationId=id,role="USER",content="Привет",thinking="",status="COMPLETE",
                mode=null,modelId=null,tokensPerSecond=null,errorMessage=null,createdAt=12))
            val pass = "Тестовый пароль 42".toCharArray()
            settings.systemPrompt = "test preference"
            val archive = CrossSync(db,settings).export(pass)
            settings.systemPrompt = "changed"
            val dest = CrossSync(second,settings)
            assertEquals(1,dest.merge(pass,archive,true))
            assertEquals("test preference",settings.systemPrompt)
            assertEquals(0,dest.merge(pass,archive,true))
            val obj = JSONObject(String(SyncCipher.open(pass,dest.export(pass))))
            assertEquals("Привет",obj.getJSONArray("chats").getJSONObject(0).getJSONArray("messages").getJSONObject(0).getString("text"))
        } finally { settings.systemPrompt=previous; db.close(); second.close() }
    }

    @Test fun glassRendersAndLiveDialogOpens() {
        compose.setContent {
            NorvaTheme { AppBackground { GlassPanel { Text("Glass fixture") }; LiveDialog(emptyList(),{ true }, {}, {}) } }
        }
        compose.onNodeWithText("NØRVA Live").assertIsDisplayed()
        compose.onNodeWithText("Начать разговор").assertExists()
    }

    @Test fun studioMemoryAndTurboControlsOpen() {
        val app = ctx.applicationContext as NorvaApp
        compose.setContent { NorvaTheme { StudioScreen(app.container) {} } }
        compose.onNodeWithText("MultiBrain ▾").performClick()
        compose.onNodeWithText("Memory 2.0").performClick()
        compose.onNodeWithText("Запомнить").assertIsDisplayed()
        compose.onNodeWithText("Memory 2.0 ▾").performClick()
        compose.onNodeWithText("Turbo Mode").performClick()
        compose.onNode(isToggleable()).assertExists()
    }
}
