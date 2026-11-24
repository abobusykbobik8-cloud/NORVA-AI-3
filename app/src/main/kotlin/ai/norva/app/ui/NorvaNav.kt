package ai.norva.app.ui

import ai.norva.app.AppContainer
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.first
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun NorvaNav(container: AppContainer) {
    val nav = rememberNavController()
    var initialChatOpened by rememberSaveable { mutableStateOf(false) }
    fun openChat(id: Long) {
        nav.navigate("chat/$id") { popUpTo("chats"); launchSingleTop = true }
    }
    NavHost(
        navController = nav,
        startDestination = "chats",
        enterTransition = { slideInHorizontally(tween(320)) { it / 5 } + fadeIn(tween(320)) },
        exitTransition = { slideOutHorizontally(tween(320)) { -it / 8 } + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(tween(320)) { -it / 8 } + fadeIn(tween(320)) },
        popExitTransition = { slideOutHorizontally(tween(320)) { it / 5 } + fadeOut(tween(200)) },
    ) {
        composable("chats") {
            LaunchedEffect(Unit) {
                if (!initialChatOpened) {
                    val id = container.repo.observeConversations().first().firstOrNull()?.id
                        ?: container.repo.createConversation(ai.norva.chat.ChatOrchestrator.DEFAULT_TITLE)
                    initialChatOpened = true
                    openChat(id)
                }
            }
            ChatListScreen(
                container = container,
                onOpen = ::openChat,
                onModels = { nav.navigate("models") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            ChatScreen(
                container = container,
                conversationId = id,
                onOpenChat = ::openChat,
                onChatDeleted = { nav.popBackStack("chats", false) },
                onSettings = { nav.navigate("settings") },
                onModels = { nav.navigate("models") },
            )
        }
        composable("models") { ModelsScreen(container = container, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(container = container, onBack = { nav.popBackStack() }) }
    }
}
