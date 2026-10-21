package dev.opengarmin.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseShapes
import dev.opengarmin.app.ui.theme.PulseSpacing

@Composable
fun PulseCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = PulseShapes.extraLarge
    val base = modifier
        .clip(shape)
        .background(if (elevated) PulseColors.SurfaceElevated else PulseColors.Surface)
        .border(
            1.dp,
            if (elevated) PulseColors.BorderElevated else PulseColors.Border,
            shape,
        )
    val clickable = if (onClick != null) {
        base.clickable(role = Role.Button, onClick = onClick)
    } else {
        base
    }
    Column(modifier = clickable, content = content)
}

@Composable
fun CompactCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = PulseShapes.medium
    val base = modifier
        .semantics { this.selected = selected }
        .clip(shape)
        .background(PulseColors.Surface)
        .border(
            1.dp,
            if (selected) PulseColors.Accent else PulseColors.Border,
            shape,
        )
    val clickable = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
    Column(modifier = clickable, content = content)
}

@Composable
fun StatTile(
    label: String,
    value: String,
    unit: String?,
    icon: ImageVector?,
    iconColor: Color,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    CompactCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(PulseSpacing.lgPlus),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = PulseColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            if (footnote != null) {
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = PulseColors.TextMuted,
    )
}

@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
) {
    val shape = CircleShape
    Row(
        modifier = modifier
            .height(PulseSpacing.touchTarget)
            .clip(shape)
            .background(if (selected) PulseColors.Accent else PulseColors.Surface)
            .border(1.dp, if (selected) PulseColors.Accent else PulseColors.BorderStrong, shape)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = PulseSpacing.lgPlus),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = if (selected) PulseColors.TextOnAccent else PulseColors.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (selected) PulseColors.TextOnAccent else PulseColors.Text,
            maxLines = 1,
        )
    }
}

@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val shape = CircleShape
    val gap = 3.dp
    val selected = selectedIndex.coerceIn(0, options.lastIndex)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(PulseSpacing.touchTarget)
            .clip(shape)
            .background(PulseColors.Surface)
            .border(1.dp, PulseColors.BorderStrong, shape)
            .padding(gap),
    ) {
        val segmentWidth = (maxWidth - gap * (options.size - 1)) / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = (segmentWidth + gap) * selected,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            label = "segmentIndicator",
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(shape)
                .background(PulseColors.Accent),
        )
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selected
                val labelColor by animateColorAsState(
                    targetValue = if (isSelected) PulseColors.TextOnAccent else PulseColors.TextMuted,
                    animationSpec = tween(durationMillis = 160),
                    label = "segmentLabel",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelected(index) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = labelColor,
                    )
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) {
    val shape = CircleShape
    Row(
        modifier = modifier
            .height(PulseSpacing.touchTarget)
            .clip(shape)
            .background(if (enabled) PulseColors.Accent else PulseColors.SurfaceElevated)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PulseSpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = if (enabled) PulseColors.TextOnAccent else PulseColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) PulseColors.TextOnAccent else PulseColors.TextMuted,
        )
    }
}

@Composable
fun IconButtonRound(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PulseColors.Text,
    background: Color = PulseColors.Surface,
    size: Dp = PulseSpacing.touchTarget,
) {
    val actualSize = if (size < PulseSpacing.touchTarget) PulseSpacing.touchTarget else size
    Box(
        modifier = modifier
            .sizeIn(minWidth = PulseSpacing.touchTarget, minHeight = PulseSpacing.touchTarget)
            .size(actualSize)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(CircleShape)
            .background(PulseColors.SurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PulseShapes.large)
            .background(PulseColors.Surface)
            .border(1.dp, PulseColors.Border, PulseShapes.large)
            .padding(PulseSpacing.xlPlus),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = PulseColors.Text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextMuted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            PrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PulseShapes.large)
            .background(PulseColors.Surface)
            .border(1.dp, PulseColors.Border, PulseShapes.large)
            .padding(PulseSpacing.xlPlus),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        Text("Couldn't load this", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
        Text(message, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted, textAlign = TextAlign.Center)
        if (onRetry != null) PrimaryButton("Try again", onRetry)
    }
}

@Composable
fun LoadingState(label: String = "Loading…", modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(PulseSpacing.xl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = PulseColors.Accent,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(PulseSpacing.lg))
        Text(label, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PulseColors.Border),
    )
}

@Composable
fun StatusPill(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = CircleShape
    val base = modifier
        .clip(shape)
        .background(PulseColors.Surface)
        .border(1.dp, PulseColors.BorderStrong, shape)
    val clickable = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
    Row(
        modifier = clickable.heightIn(min = PulseSpacing.touchTarget).padding(horizontal = PulseSpacing.lg, vertical = PulseSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(text, style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted, maxLines = 1)
    }
}
