package dev.opengarmin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing

enum class PulseTab(val label: String, val icon: ImageVector) {
    Today("Today", Icons.Outlined.Home),
    Trends("Trends", Icons.Outlined.TrendingUp),
    Activities("Activities", Icons.Outlined.DirectionsRun),
    Coach("Coach", Icons.Outlined.AutoAwesome),
}

@Composable
fun PulseTabBar(
    selected: PulseTab,
    onSelect: (PulseTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PulseSpacing.tabBarHeight)
            .background(PulseColors.Background)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PulseTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                    .clip(CircleShape)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = if (active) PulseColors.Accent else PulseColors.TextMuted,
                    modifier = Modifier.sizeIn(minWidth = 24.dp, minHeight = 24.dp),
                )
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) PulseColors.Accent else PulseColors.TextMuted,
                )
            }
        }
    }
}
