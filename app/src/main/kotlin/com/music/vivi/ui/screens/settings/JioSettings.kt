/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.screens.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.navigation.NavController

@Deprecated("JioSaavn settings have been removed in favor of automatic audio quality selection.")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JioSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    // Deprecated / No-op
}
