package ai.norva.app

import android.app.Application
import android.content.ComponentCallbacks2
import kotlinx.coroutines.launch

class NorvaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.orchestrator.recoverInterrupted() }
    }

    /** Когда процесс уходит в фон или системе не хватает памяти, выгружаем модель (если не идёт генерация). */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            container.appScope.launch { container.orchestrator.releaseIfIdle() }
        }
    }
}
