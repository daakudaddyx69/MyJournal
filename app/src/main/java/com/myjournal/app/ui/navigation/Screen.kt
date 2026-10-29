package com.myjournal.app.ui.navigation
import kotlinx.serialization.Serializable
@Serializable
sealed interface Screen {
    @Serializable data object Feed : Screen
    @Serializable data object Calendar : Screen
    @Serializable data object Insights : Screen
    @Serializable data object Profile : Screen
    @Serializable
    data class Editor(
        val entryId: String? = null,
        val initialMode: String = "WRITE",
        val defaultDate: String = ""
    ) : Screen
}