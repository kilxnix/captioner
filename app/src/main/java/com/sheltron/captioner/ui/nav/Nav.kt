package com.sheltron.captioner.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.sheltron.captioner.ui.screens.HomeScreen
import com.sheltron.captioner.ui.screens.LiveScreen
import com.sheltron.captioner.ui.screens.SessionDetailScreen
import com.sheltron.captioner.ui.vm.CaptionerViewModel

object Routes {
    const val HOME = "home"
    const val LIVE = "live"
    const val SESSION = "session/{id}"
    fun session(id: Long) = "session/$id"
}

@Composable
fun CaptionerNav() {
    val nav = rememberNavController()
    val vm: CaptionerViewModel = viewModel()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onStart = { nav.navigate(Routes.LIVE) },
                onOpenSession = { id -> nav.navigate(Routes.session(id)) }
            )
        }
        composable(Routes.LIVE) {
            LiveScreen(
                vm = vm,
                onBack = { nav.popBackStack() }
            )
        }
        composable(
            route = Routes.SESSION,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            SessionDetailScreen(
                vm = vm,
                sessionId = id,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
