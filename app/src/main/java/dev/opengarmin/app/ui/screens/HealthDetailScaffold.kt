package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.opengarmin.app.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.SegmentedControl
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import androidx.compose.material.icons.outlined.ArrowBack

/** The Health metrics that have a dedicated detail screen. */
enum class HealthMetric(
    val title: String,
    val about: String,
) {
    BODY_BATTERY(
        title = "Body Battery",
        about = "Body Battery combines stress, rest and activity into a 0–100 energy estimate. Charged and drained totals come from the watch's own readings.",
    ),
    SLEEP(
        title = "Sleep",
        about = "Sleep stages, score and overnight vitals come from the watch's sleep file. The nightly need is an age-based recommendation, not a Garmin value.",
    ),
    HRV(
        title = "HRV status",
        about = "Overnight HRV is the rMSSD of your heartbeat intervals. The balanced range is the baseline reported by your watch.",
    ),
    STRESS(
        title = "Stress",
        about = "Stress readings run from 0 to 100. Rest and low levels are recovery time; medium and high levels are load. Durations are estimated from the spacing of the watch readings.",
    ),
    VO2_MAX(
        title = "VO₂ max",
        about = "VO₂ max is estimated by the watch from outdoor sessions. The fitness band uses age and sex reference values; it is not a Garmin percentile.",
    ),
}

@Composable
internal fun HealthDetailHeader(
    metric: HealthMetric,
    subtitle: String,
    onBack: () -> Unit,
) {
    var showAbout by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButtonRound(Icons.Outlined.ArrowBack, "Back", onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = PulseSpacing.lg),
        ) {
            Text(metric.title, style = MaterialTheme.typography.headlineSmall, color = PulseColors.Text)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
        Box(
            modifier = Modifier
                .size(PulseSpacing.touchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button) { showAbout = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = "About this metric", tint = PulseColors.TextMuted, modifier = Modifier.size(22.dp))
        }
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(metric.title) },
            text = {
                Text(
                    metric.about + "\n\nNothing is estimated or zero-filled: a day without a watch reading is left empty.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("Got it") }
            },
        )
    }
}

@Composable
internal fun RangeSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    if (options.size < 2) return
    SegmentedControl(
        options = options,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun DateNavigator(
    label: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        NavigatorArrow(Icons.Outlined.ChevronLeft, "Previous range", canGoBack, onBack)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = PulseColors.Text,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        NavigatorArrow(Icons.Outlined.ChevronRight, "Next range", canGoForward, onForward)
    }
}

@Composable
private fun NavigatorArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(PulseColors.Surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = PulseColors.Text, modifier = Modifier.size(18.dp))
    }
}

/** Shared vertical rhythm for the metric detail screens. */
@Composable
internal fun MetricCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    dev.opengarmin.app.ui.components.PulseCard(modifier.fillMaxWidth()) {
        Box(Modifier.padding(PulseSpacing.xl)) {
            content()
        }
    }
}

@Composable
internal fun MetricCardRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        content = content,
    )
}

@Composable
internal fun EmptyMetricCard(message: String) {
    MetricCard {
        Text(message, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = PulseColors.TextMuted,
        modifier = Modifier.padding(top = PulseSpacing.sm),
    )
}

/** Accent-bordered one-liner used by every metric detail screen. */
@Composable
internal fun InsightLine(text: androidx.compose.ui.text.AnnotatedString) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PulseSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Outlined.Info,
            contentDescription = null,
            tint = PulseColors.Accent,
            modifier = Modifier.size(13.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = PulseColors.Text)
    }
}
