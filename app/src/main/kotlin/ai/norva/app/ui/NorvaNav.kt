package ai.norva.app.ui

import ai.norva.app.AppContainer
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun NorvaNav(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "chats") {
        composable("chats") {
            ChatListScreen(
                container = container,
                onOpen = { id -> nav.navigate("chat/$id") },
                onModels = { nav.navigate("models") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            ChatScreen(
                container = container,
                conversationId = id,
                onBack = { nav.popBackStack() },
                onModels = { nav.navigate("models") },
            )
        }
        composable("models") { ModelsScreen(container = container, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(container = container, onBack = { nav.popBackStack() }) }
    }
}
