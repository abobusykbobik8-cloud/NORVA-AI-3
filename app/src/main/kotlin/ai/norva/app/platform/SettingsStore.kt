package ai.norva.app.platform

import ai.norva.policy.ThinkingMode
import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("norva_settings", Context.MODE_PRIVATE)

    var systemPrompt: String
        get() = prefs.getString(KEY_PROMPT, null) ?: DEFAULT_SYSTEM_PROMPT
        set(value) {
            prefs.edit().putString(KEY_PROMPT, value).apply()
        }

    var mode: ThinkingMode
        get() = prefs.getString(KEY_MODE, null)?.let { name -> ThinkingMode.entries.firstOrNull { it.name == name } }
            ?: ThinkingMode.NORMAL
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Ты NØRVA - дружелюбный и точный ИИ-ассистент. Отвечай на языке пользователя, по делу. Если данных недостаточно, скажи об этом. Для фото описывай видимые детали, не додумывай нечитаемый текст."
        private const val KEY_PROMPT = "system_prompt"
        private const val KEY_MODE = "mode"
    }
}
