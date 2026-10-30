package com.myjournal.app.ui
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import com.myjournal.app.ui.editor.EditorScreen
import com.myjournal.app.ui.navigation.Screen
import com.myjournal.app.ui.screens.*
import com.myjournal.app.ui.theme.MyJournalTheme
import com.myjournal.app.ui.viewmodel.AppThemeMode
import com.myjournal.app.ui.viewmodel.JournalViewModel
import com.myjournal.app.util.BiometricHelper
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyJournalApp(viewModel: JournalViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isInsideEditor = currentRoute?.contains("Editor") == true
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isPrivacyShield by viewModel.isPrivacyShieldActive.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val streak by viewModel.streakCount.collectAsState()
    val settings by viewModel.userSettings.collectAsState()
    var isSearching by remember { mutableStateOf(false) }
    var inputName by remember { mutableStateOf("") }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val useDarkTheme = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MyJournalTheme(darkTheme = useDarkTheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    if (!isInsideEditor) {
                        TopAppBar(
                            title = {
                                if (isSearching) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = viewModel::updateSearchQuery,
                                        placeholder = { Text("Search reflections, tags, folders...") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    Text(
                                        text = "My Journal",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 26.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    isSearching = !isSearching
                                    if (!isSearching) viewModel.updateSearchQuery("")
                                }) {
                                    Icon(
                                        imageVector = if (isSearching) Icons.Default.Close else Icons.Outlined.Search,
                                        contentDescription = "Search",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = "\uD83D\uDD25 $streak",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                IconButton(onClick = {
                                    if (isPrivacyShield) {
                                        BiometricHelper.authenticate(context) {
                                            viewModel.setPrivacyShield(false)
                                            Toast.makeText(context, "Privacy Shield Unlocked", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        viewModel.setPrivacyShield(true)
                                        Toast.makeText(context, "Privacy Shield Locked", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (isPrivacyShield) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                                        contentDescription = "Toggle Privacy Shield",
                                        tint = if (isPrivacyShield) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    }
                },
                bottomBar = {
                    if (!isInsideEditor) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 4.dp
                        ) {
                            val items = listOf(
                                Triple(Screen.Feed, "Feed", Icons.Outlined.Article),
                                Triple(Screen.Calendar, "Calendar", Icons.Outlined.CalendarMonth),
                                Triple(Screen.Insights, "Insights", Icons.Outlined.TrendingUp),
                                Triple(Screen.Profile, "Profile", Icons.Outlined.Person)
                            )
                            items.forEach { (screen, label, icon) ->
                                val selected = currentRoute?.contains(screen::class.simpleName.orEmpty()) == true
                                NavigationBarItem(
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            navController.navigate(screen) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = Screen.Feed,
                    modifier = if (isInsideEditor) Modifier.fillMaxSize() else Modifier.padding(innerPadding)
                ) {
                    composable<Screen.Feed> {
                        FeedScreen(
                            viewModel = viewModel,
                            onOpenEditor = { entryId, mode, dateStr ->
                                navController.navigate(
                                    Screen.Editor(
                                        entryId = entryId,
                                        initialMode = mode,
                                        defaultDate = dateStr
                                    )
                                )
                            }
                        )
                    }
                    composable<Screen.Calendar> {
                        CalendarScreen(
                            viewModel = viewModel,
                            onOpenEditor = { entryId, mode, dateStr ->
                                navController.navigate(
                                    Screen.Editor(
                                        entryId = entryId,
                                        initialMode = mode,
                                        defaultDate = dateStr
                                    )
                                )
                            }
                        )
                    }
                    composable<Screen.Insights> {
                        InsightsScreen(
                            viewModel = viewModel,
                            onJumpToFeedWithFilter = { tag ->
                                viewModel.selectFilter(tag)
                                navController.navigate(Screen.Feed) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                    composable<Screen.Profile> {
                        ProfileScreen(
                            viewModel = viewModel,
                            onJumpToFeedWithFilter = { filter ->
                                viewModel.selectFilter(filter)
                                navController.navigate(Screen.Feed) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                    composable<Screen.Editor> { backStackEntry ->
                        val route: Screen.Editor = backStackEntry.toRoute()
                        EditorScreen(
                            entryId = route.entryId,
                            initialMode = route.initialMode,
                            defaultDate = route.defaultDate,
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
            if (!settings.hasCompletedOnboarding) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Welcome to Your Sanctuary ✨") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("A quiet, offline-first space for your thoughts and reflections. What should we call you?")
                            OutlinedTextField(
                                value = inputName,
                                onValueChange = { inputName = it },
                                label = { Text("Your Name") },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.completeOnboarding(inputName.trim().ifBlank { "Rockstar" })
                        }) {
                            Text("Begin Journaling")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.completeOnboarding("Rockstar")
                        }) {
                            Text("Skip")
                        }
                    }
                )
            }
        }
    }
}