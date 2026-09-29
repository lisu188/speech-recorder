package pl.lisu188.speechrecorder

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing

enum class AppDestination(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val activity: Class<out Activity>,
) {
    RECORDER("Dyktafon", Icons.Outlined.GraphicEq, Icons.Rounded.GraphicEq, MainActivity::class.java),
    RECORDINGS("Nagrania", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic, RecordingsActivity::class.java),
    SETTINGS("Ustawienia", Icons.Outlined.Settings, Icons.Rounded.Settings, SettingsActivity::class.java),
}

@Composable
fun AppScaffold(
    activity: Activity,
    selected: AppDestination,
    content: @Composable (PaddingValues) -> Unit,
) {
    NavigationSuiteScaffold(
        navigationItems = {
            AppDestination.entries.forEach { destination ->
                NavigationSuiteItem(
                    selected = destination == selected,
                    onClick = {
                        if (destination != selected) {
                            activity.startActivity(
                                Intent(activity, destination.activity)
                                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                            )
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (destination == selected) {
                                destination.selectedIcon
                            } else {
                                destination.icon
                            },
                            contentDescription = null,
                        )
                    },
                    label = { Text(destination.label) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            content(padding)
        }
    }
}
