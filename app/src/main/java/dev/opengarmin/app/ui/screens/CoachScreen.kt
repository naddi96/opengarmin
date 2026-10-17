package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.CoachController
import dev.opengarmin.app.coach.MessageStatus
import dev.opengarmin.app.ui.components.ChatBubble
import dev.opengarmin.app.ui.components.ChatRole
import dev.opengarmin.app.ui.components.ChatComposer
import dev.opengarmin.app.ui.components.CoachAvatar
import dev.opengarmin.app.ui.components.DataUsedRow
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SuggestionChip
import dev.opengarmin.app.ui.components.TypingIndicator
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing

@Composable
fun CoachScreen(
    controller: CoachController,
    dataSummary: String,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val providerConfigured = controller.providerConfigured
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
        ) {
            CoachAvatar(size = 44)
            Column(modifier = Modifier.weight(1f)) {
                Text("Coach", style = MaterialTheme.typography.headlineMedium, color = PulseColors.Text)
                Text(
                    "AI insights from your watch data",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
            IconButtonRound(
                icon = Icons.Outlined.AddComment,
                contentDescription = "New conversation",
                onClick = { controller.startNewChat() },
                background = PulseColors.Surface,
            )
        }

        Spacer(Modifier.size(PulseSpacing.lg))
        DataAccessBar(
            text = dataSummary,
            connected = providerConfigured,
            onManage = onManage,
        )
        Text(
            "Informational only · not medical advice",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
            modifier = Modifier.padding(start = PulseSpacing.xs, top = PulseSpacing.xs),
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = PulseSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
        ) {
            items(controller.messages.asReversed(), key = { it.id }) { message ->
                if (message.role == ChatRole.User) {
                    ChatBubble(role = ChatRole.User, text = message.text)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                        if (message.status == MessageStatus.Streaming && message.text.isBlank() && message.charts.isEmpty()) {
                            TypingIndicator()
                            controller.statusText?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PulseColors.TextMuted,
                                    modifier = Modifier.padding(start = 36.dp),
                                )
                            }
                        } else {
                            ChatBubble(
                                role = ChatRole.Assistant,
                                text = message.text,
                                streaming = message.status == MessageStatus.Streaming,
                                charts = message.charts,
                            )
                            if (message.evidence.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.padding(start = 36.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        "Data used",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PulseColors.TextMuted,
                                    )
                                    DataUsedRow(message.evidence)
                                }
                            }
                            if (message.status == MessageStatus.Cancelled) {
                                Text(
                                    "Cancelled",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PulseColors.TextMuted,
                                    modifier = Modifier.padding(start = 36.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!providerConfigured) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Text(
                        "Connect a provider to chat",
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Text,
                    )
                    Text(
                        "Use OpenAI, OpenRouter, Ollama, LM Studio, vLLM or any compatible endpoint. Health data is sent only to the endpoint you choose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    PrimaryButton("Provider settings", onManage)
                }
            }
            Spacer(Modifier.size(PulseSpacing.md))
        }

        controller.lastError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelMedium,
                color = PulseColors.Coral,
                modifier = Modifier.padding(bottom = PulseSpacing.sm),
            )
        }

        if (!controller.isStreaming && controller.messages.size < 3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = PulseSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                listOf(
                    "How was my week?",
                    "Why is my resting HR changing?",
                    "Plan tomorrow's workout",
                    "How are my workouts trending?",
                ).forEach { suggestion ->
                    SuggestionChip(label = suggestion, onClick = { controller.send(suggestion) })
                }
            }
        }

        ChatComposer(
            draft = controller.draft,
            onDraftChange = { controller.updateDraft(it) },
            onSend = { controller.send(controller.draft) },
            onCancel = { controller.cancel() },
            streaming = controller.isStreaming,
            enabled = providerConfigured,
            modifier = Modifier.padding(bottom = PulseSpacing.lg),
        )
    }
}

@Composable
private fun DataAccessBar(text: String, connected: Boolean, onManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(PulseColors.Surface)
            .heightIn(min = PulseSpacing.touchTarget)
            .padding(horizontal = PulseSpacing.lgPlus, vertical = PulseSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) PulseColors.Teal else PulseColors.Orange),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = PulseColors.TextMuted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = "Manage",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = PulseColors.Accent,
            modifier = Modifier
                .heightIn(min = PulseSpacing.touchTarget)
                .clickable(role = Role.Button, onClick = onManage)
                .padding(vertical = PulseSpacing.md, horizontal = PulseSpacing.xs),
        )
    }
}

