package com.droid.flippy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/**
 * Submenu that groups the two TV casting tools: Flippy's own DLNA/DIAL caster
 * and the Google Cast SDK caster. Opened from the Other screen so the function
 * list stays uncluttered.
 */
@Composable
fun CastMenuScreen(navController: NavController) {
    val accent = MaterialTheme.colorScheme.primary
    val entries = listOf(
        CastMenuEntry(
            Icons.Default.Cast,
            stringResource(R.string.smarttv_cast_title),
            stringResource(R.string.smarttv_cast_card_description),
            "other/smarttv_cast",
        ),
        CastMenuEntry(
            Icons.Default.CastConnected,
            stringResource(R.string.cast_sdk_card_title),
            stringResource(R.string.cast_sdk_card_desc),
            "other/tv_cast_sdk",
        ),
    )
    MaterialBackground(accentColor = accent) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            SectionTopBar(
                title = stringResource(R.string.cast_menu_title),
                onBack = { navController.popBackStack() },
                accentColor = accent,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(M3SegmentedListItemSpacing),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.cast_menu_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                    )
                }
                m3SegmentedItems(entries) { index, count, entry ->
                    M3SegmentedListItem(
                        index = index,
                        count = count,
                        headline = entry.title,
                        supporting = entry.description,
                        leadingIcon = entry.icon,
                        leadingIconTint = accent,
                        onClick = { navController.navigate(entry.route) },
                    )
                }
            }
        }
    }
}

private data class CastMenuEntry(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val route: String,
)
