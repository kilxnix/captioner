package com.sheltron.captioner.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sheltron.captioner.ui.screens.HomeScreen
import com.sheltron.captioner.ui.screens.LiveScreen
import com.sheltron.captioner.ui.screens.SessionDetailScreen
import com.sheltron.captioner.ui.screens.SettingsScreen
import com.sheltron.captioner.ui.screens.TasksScreen
import com.sheltron.captioner.ui.theme.Accent
import com.sheltron.captioner.ui.theme.BoneDim
import com.sheltron.captioner.ui.theme.InkRaised
import com.sheltron.captioner.ui.vm.CaptionerViewModel
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val LIVE = "live"
    const val SESSION = "session/{id}"
    const val SETTINGS = "settings"
    fun session(id: Long) = "session/$id"
}

@Composable
fun CaptionerNav() {
    val nav = rememberNavController()
    val vm: CaptionerViewModel = viewModel()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomePager(
                vm = vm,
                onStart = { nav.navigate(Routes.LIVE) },
                onOpenSession = { id -> nav.navigate(Routes.session(id)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.LIVE) {
            LiveScreen(
                vm = vm,
                onBack = {
                    // Guard against popping past HOME if onBack fires more than once.
                    if (nav.currentDestination?.route == Routes.LIVE) nav.popBackStack()
                }
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
                onBack = { nav.popBackStack() },
                onOpenTasks = {
                    // Pop to home, which will show whichever pager page was last active.
                    nav.popBackStack(Routes.HOME, inclusive = false)
                },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomePager(
    vm: CaptionerViewModel,
    onStart: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    vm = vm,
                    onStart = onStart,
                    onOpenSession = onOpenSession,
                    onOpenSettings = onOpenSettings,
                    onOpenTasks = { scope.launch { pagerState.animateScrollToPage(1) } }
                )
                1 -> TasksScreen(
                    vm = vm,
                    onOpenSession = onOpenSession,
                    onOpenSettings = onOpenSettings
                )
            }
        }

        PagerDots(
            count = 2,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun PagerDots(count: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Surface(
        color = InkRaised.copy(alpha = 0.6f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until count) {
                val active = i == currentPage
                Box(
                    modifier = Modifier
                        .size(if (active) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (active) Accent else BoneDim)
                )
                if (i != count - 1) Spacer(Modifier.size(6.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text(
                if (currentPage == 0) "Home" else "Tasks",
                style = MaterialTheme.typography.labelSmall,
                color = BoneDim
            )
        }
    }
}
