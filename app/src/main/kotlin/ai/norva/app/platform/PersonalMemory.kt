package ai.norva.app.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PersonalMemory(context: Context) {
    private val prefs = context.getSharedPreferences("personal_memory", Context.MODE_PRIVATE)
    private val alias = "norva.memory.v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun list(): List<String> {
        val value = prefs.getString("items", null) ?: return emptyList()
        val data = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0,12)))
        val items = JSONArray(String(cipher.doFinal(data,12,data.size-12), Charsets.UTF_8))
        return List(items.length(), items::getString)
    }
    @Synchronized fun edit(text: String, remove: Boolean) {
        val clean = text.trim()
        require(clean.length in 1..240) { "Воспоминание должно содержать от 1 до 240 символов" }
        val items = list().toMutableList()
        if (remove) items.remove(clean) else if (clean !in items) items.add(clean)
        require(items.size <= 32) { "Доступно 32 воспоминания" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = cipher.iv + cipher.doFinal(JSONArray(items).toString().toByteArray())
        check(prefs.edit().putString("items", Base64.encodeToString(value,Base64.NO_WRAP)).commit())
    }
    fun contextFor(query: String): String {
        val words = Regex("[\\p{L}\\p{N}]{3,}").findAll(query.lowercase()).map { it.value }.toSet()
        return list().asReversed().sortedByDescending { note -> words.count { it in note.lowercase() } }
            .take(4).joinToString("\n")
    }
}
