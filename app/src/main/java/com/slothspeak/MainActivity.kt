package com.slothspeak

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.slothspeak.ui.screens.HistoryScreen
import com.slothspeak.ui.screens.MainScreen
import com.slothspeak.ui.screens.SettingsScreen
import com.slothspeak.ui.theme.SlothSpeakTheme
import com.slothspeak.viewmodel.ConversationViewModel
import com.slothspeak.viewmodel.HistoryViewModel
import com.slothspeak.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val conversationViewModel: ConversationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Passive listening (wake word / follow-up VAD) is only allowed while the
        // app is in the foreground — tear it down when we go to the background.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> conversationViewModel.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> conversationViewModel.onAppBackgrounded()
                else -> Unit
            }
        })

        setContent {
            SlothSpeakTheme {
                SlothSpeakNavigation(conversationViewModel)
            }
        }
    }
}

@Composable
fun SlothSpeakNavigation(conversationViewModel: ConversationViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            MainScreen(
                viewModel = conversationViewModel,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToHistory = { navController.navigate("history") }
            )
        }

        composable("settings") {
            val settingsViewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("history") {
            val historyViewModel: HistoryViewModel = viewModel()
            HistoryScreen(
                viewModel = historyViewModel,
                onBack = { navController.popBackStack() },
                onLoadConversation = { conversationId ->
                    conversationViewModel.loadConversation(conversationId)
                    navController.popBackStack()
                }
            )
        }
    }
}
