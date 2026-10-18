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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.CoachController
import dev.opengarmin.app.coach.ConversationSummary
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
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
fun CoachScreen(
    controller: CoachController,
    dataSummary: String,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val providerConfigured = controller.providerConfigured
    var showHistory by remember { mutableStateOf(false) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                IconButtonRound(
                    icon = Icons.Outlined.History,
                    contentDescription = "Conversation history",
                    onClick = { showHistory = true },
                    background = PulseColors.Surface,
                )
                IconButtonRound(
                    icon = Icons.Outlined.AddComment,
                    contentDescription = "New conversation",
                    onClick = { controller.startNewChat() },
                    background = PulseColors.Surface,
                )
            }
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

    if (showHistory) {
        ConversationHistoryDialog(
            conversations = controller.conversations,
            currentId = controller.currentConversationId,
            onOpen = {
                controller.openConversation(it)
                showHistory = false
            },
            onDelete = { controller.deleteConversation(it) },
            onNew = {
                controller.startNewChat()
                showHistory = false
            },
            onDismiss = { showHistory = false },
        )
    }
}

@Composable
private fun ConversationHistoryDialog(
    conversations: List<ConversationSummary>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    val deleteTarget = pendingDelete

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("\"${deleteTarget.title}\" and its messages will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(deleteTarget.id)
                }) {
                    Text("Delete", color = PulseColors.Coral)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversations") },
        text = {
            if (conversations.isEmpty()) {
                Text(
                    "No saved conversations yet. Send a message to start one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        val current = conversation.id == currentId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(PulseSpacing.md))
                                .clickable(role = Role.Button) { onOpen(conversation.id) }
                                .padding(
                                    start = PulseSpacing.md,
                                    end = PulseSpacing.xs,
                                    top = PulseSpacing.sm,
                                    bottom = PulseSpacing.sm,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conversation.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    color = if (current) PulseColors.Accent else PulseColors.Text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = conversationSubtitle(conversation),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PulseColors.TextMuted,
                                )
                            }
                            IconButton(onClick = { pendingDelete = conversation }) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete conversation",
                                    tint = PulseColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) { Text("New conversation") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun conversationSubtitle(conversation: ConversationSummary): String {
    val whenText = conversationTime(conversation.updatedAt)
    val messages = conversation.messageCount
    return if (messages > 0) {
        "$whenText · $messages message${if (messages == 1) "" else "s"}"
    } else {
        whenText
    }
}

private fun conversationTime(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochMillis))
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
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

