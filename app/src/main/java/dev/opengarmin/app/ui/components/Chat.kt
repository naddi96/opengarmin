package dev.opengarmin.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseShapes
import dev.opengarmin.app.ui.theme.PulseSpacing

enum class ChatRole { Assistant, User }

@Composable
fun CoachAvatar(size: Int = 44, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.32f).dp))
            .background(if (size >= 40) PulseColors.Accent else PulseColors.SurfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = if (size >= 40) PulseColors.TextOnAccent else PulseColors.Accent,
            modifier = Modifier.size((size * 0.55f).dp),
        )
    }
}

@Composable
fun ChatBubble(
    role: ChatRole,
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val isUser = role == ChatRole.User
    if (isUser) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp))
                    .background(PulseColors.Accent)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = PulseColors.TextOnAccent,
                )
            }
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        CoachAvatar(size = 28)
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp))
                .background(PulseColors.Surface)
                .border(
                    1.dp,
                    PulseColors.Border,
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = if (text.isEmpty() && streaming) "…" else text,
                style = MaterialTheme.typography.bodyMedium,
                color = PulseColors.Text,
            )
        }
    }
}

@Composable
fun DataUsedRow(labels: List<String>, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
    ) {
        labels.distinct().forEach { label ->
            Text(
                text = label,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.dp, PulseColors.BorderStrong, CircleShape)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoachAvatar(size = 28)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp))
                .background(PulseColors.Surface)
                .border(
                    1.dp,
                    PulseColors.Border,
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { index ->
                val alpha by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = index * 120),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                )
                Box(
                    Modifier
                        .size(6.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(PulseColors.TextMuted),
                )
            }
        }
    }
}

@Composable
fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    streaming: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask about your data",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .semantics { contentDescription = "Message the coach" }
                .clip(CircleShape)
                .background(PulseColors.Surface)
                .border(1.dp, PulseColors.BorderStrong, CircleShape)
                .padding(horizontal = 18.dp),
            enabled = enabled && !streaming,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = PulseColors.Text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = PulseColors.TextMuted,
                        )
                    }
                    inner()
                }
            },
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) PulseColors.Accent else PulseColors.SurfaceElevated)
                .clickable(
                    role = Role.Button,
                    enabled = if (streaming) true else enabled && draft.isNotBlank(),
                    onClick = { if (streaming) onCancel() else onSend() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (streaming) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                contentDescription = if (streaming) "Stop response" else "Send message",
                tint = if (enabled) PulseColors.TextOnAccent else PulseColors.TextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
fun SuggestionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(PulseSpacing.touchTarget)
            .clip(CircleShape)
            .background(PulseColors.SurfaceElevated)
            .border(1.dp, PulseColors.BorderElevated, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.Text,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun CoachInsightCard(
    text: String,
    onAskCoach: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PulseShapes.extraLarge)
            .background(PulseColors.SurfaceElevated)
            .border(1.dp, PulseColors.BorderElevated, PulseShapes.extraLarge)
            .padding(PulseSpacing.xlPlus),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = PulseColors.Accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "COACH INSIGHT",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.7.sp),
                fontWeight = FontWeight.Bold,
                color = PulseColors.Accent,
            )
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = PulseColors.Text)
        if (onAskCoach != null) {
            PrimaryButton(text = "Ask Coach", onClick = onAskCoach)
        }
    }
}
