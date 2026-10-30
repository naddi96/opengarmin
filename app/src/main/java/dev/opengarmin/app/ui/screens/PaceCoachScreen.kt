package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.PaceCoachAdvisor
import dev.opengarmin.app.coach.PaceCoachContext
import dev.opengarmin.app.coach.PaceCoachException
import dev.opengarmin.app.coach.PaceCoachProposal
import dev.opengarmin.app.data.PacePlanCalculator
import dev.opengarmin.app.data.PacePlanSplit
import dev.opengarmin.app.diagnostics.Diagnostics
import dev.opengarmin.app.i18n.AppStrings
import dev.opengarmin.app.i18n.LocalAppLanguage
import dev.opengarmin.app.i18n.Text
import dev.opengarmin.app.i18n.coachLanguageInstruction
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.llm.LlmException
import dev.opengarmin.app.llm.LlmMessage
import dev.opengarmin.app.llm.OpenAiClient
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.ui.components.ChatBubble
import dev.opengarmin.app.ui.components.ChatComposer
import dev.opengarmin.app.ui.components.ChatRole
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SuggestionChip
import dev.opengarmin.app.ui.components.TypingIndicator
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private const val INITIAL_REQUEST =
    "Review these splits and propose a smoother, realistic distribution. Keep the finish time fixed."

private data class PaceChatTurn(
    val role: ChatRole,
    val text: String,
    val proposal: PaceCoachProposal? = null,
)

/**
 * Chat-style PacePro coach. Every answer keeps the finish time fixed and is
 * shown as a normal coach bubble; the split comparison is attached to the
 * message that proposed it. If the provider is unreachable or returns
 * something unreadable, a deterministic local proposal is shown instead of an
 * empty screen.
 */
@Composable
fun PaceCoachScreen(
    context: PaceCoachContext,
    baseSplits: List<PacePlanSplit>,
    currentSplits: List<PacePlanSplit>,
    providerStore: ProviderStore,
    onApply: (Map<Int, Int>) -> Unit,
    onDiscard: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val language = LocalAppLanguage.current
    val turns = remember { mutableStateListOf<PaceChatTurn>() }
    val scrollState = rememberScrollState()
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var latestProposal by remember { mutableStateOf<PaceCoachProposal?>(null) }
    var requestJob by remember { mutableStateOf<Job?>(null) }

    val notConfiguredMessage = tr(
        "The AI provider is not configured. Here is a local proposal; configure a provider in Settings for AI advice.",
    )
    val providerFailureMessage = tr(
        "The AI coach did not answer. Here is a local proposal based on your splits.",
    )
    val parseFailureMessage = tr(
        "I could not read the coach response. Here is a local proposal based on your splits.",
    )
    val localMessage = tr(
        "Local proposal based on your splits. The finish time is unchanged.",
    )
    val cancelledMessage = tr("The request was cancelled.")
    val moreCautiousLabel = tr("More cautious")
    val moreAggressiveLabel = tr("More aggressive")
    val climbsLabel = tr("Handle climbs better")

    fun cancelRequest() {
        requestJob?.cancel()
        requestJob = null
        loading = false
        turns.add(PaceChatTurn(ChatRole.Assistant, cancelledMessage))
    }

    fun ask(requestText: String, displayText: String?) {
        if (loading) return
        val priorTurns = turns.toList()
        if (displayText != null) {
            turns.add(PaceChatTurn(ChatRole.User, displayText))
        }
        loading = true
        val config = providerStore.load()
        val providerReady = config.isConfigured && config.defaultModel != null
        requestJob = scope.launch {
            var rawResponse: String? = null
            val result = runCatching {
                if (!providerReady) {
                    throw PaceCoachException(notConfiguredMessage)
                }
                val model = config.defaultModel
                    ?: throw PaceCoachException(notConfiguredMessage)
                val client = OpenAiClient(config.baseUrl, providerStore.apiKeyFor(config), model.name)
                val history = priorTurns.takeLast(6).map { turn ->
                    LlmMessage(
                        if (turn.role == ChatRole.User) "user" else "assistant",
                        turn.text,
                    )
                }
                val messages = buildList {
                    add(
                        LlmMessage(
                            "system",
                            PaceCoachAdvisor.systemPrompt(language.coachLanguageInstruction()),
                        ),
                    )
                    addAll(history)
                    add(LlmMessage("user", PaceCoachAdvisor.userPrompt(context, requestText)))
                }
                var completion = client.complete(
                    messages = messages,
                    temperature = 0.3,
                    maxTokens = 1_500,
                )
                if (completion.content.isBlank()) {
                    // Some providers occasionally return an empty first answer.
                    completion = client.complete(
                        messages = listOf(
                            LlmMessage(
                                "system",
                                "Return only the JSON object. No prose before or after it.",
                            ),
                            LlmMessage("user", PaceCoachAdvisor.userPrompt(context, requestText)),
                        ),
                        temperature = 0.0,
                        maxTokens = 1_500,
                    )
                }
                rawResponse = completion.content
                if (completion.content.isBlank()) {
                    throw PaceCoachException("The coach returned an empty answer.")
                }
                PaceCoachAdvisor.parse(completion.content, currentSplits.size)
            }
            loading = false
            requestJob = null
            result.onSuccess { proposal ->
                val text = proposal.message.ifBlank { localMessage }
                turns.add(PaceChatTurn(ChatRole.Assistant, text, proposal))
                latestProposal = proposal
            }.onFailure { failure ->
                if (failure is CancellationException) {
                    return@onFailure
                }
                if (failure is PaceCoachException || failure is LlmException) {
                    Diagnostics.record(
                        area = Diagnostics.AREA_COACH,
                        code = "PACE_COACH_FALLBACK",
                        message = failure.message ?: "PacePro coach fallback.",
                        context = buildMap {
                            put("request", requestText.take(300))
                            rawResponse?.takeIf { it.isNotBlank() }?.let { put("response", it.take(1_500)) }
                        },
                    )
                }
                val fallback = runCatching {
                    PaceCoachAdvisor.localProposal(currentSplits, requestText)
                }.getOrNull()
                val text = when {
                    !providerReady -> notConfiguredMessage
                    rawResponse?.isNotBlank() == true -> parseFailureMessage
                    else -> providerFailureMessage
                }
                if (fallback != null) {
                    turns.add(PaceChatTurn(ChatRole.Assistant, text, fallback))
                    latestProposal = fallback
                } else {
                    turns.add(PaceChatTurn(ChatRole.Assistant, text))
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (turns.isEmpty()) ask(INITIAL_REQUEST, null)
    }
    LaunchedEffect(turns.size, loading) {
        yield()
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PulseColors.Background)
            .imePadding()
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButtonRound(Icons.AutoMirrored.Outlined.ArrowBack, tr("Back to preview"), onDiscard)
            Spacer(Modifier.width(PulseSpacing.sm))
            Column {
                Text("AI coach", style = MaterialTheme.typography.headlineSmall, color = PulseColors.Text)
                Text(
                    tr(
                        "Chat about your splits · %s · %s",
                        context.raceLabel,
                        PacePlanCalculator.formatClock(currentSplits.sumOf { it.targetSeconds }),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(vertical = PulseSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            turns.forEach { turn ->
                ChatBubble(role = turn.role, text = turn.text)
                turn.proposal?.let { proposal ->
                    ProposalCard(proposal = proposal, currentSplits = currentSplits)
                }
            }
            if (loading) {
                TypingIndicator()
            }
            Spacer(Modifier.height(PulseSpacing.sm))
        }

        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
            if (!loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    SuggestionChip(
                        label = moreCautiousLabel,
                        onClick = {
                            ask(
                                "Make the start more cautious and recover in the second half, keeping the finish time fixed.",
                                moreCautiousLabel,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SuggestionChip(
                        label = moreAggressiveLabel,
                        onClick = {
                            ask(
                                "Make the plan a little more aggressive without changing the finish time.",
                                moreAggressiveLabel,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    SuggestionChip(
                        label = climbsLabel,
                        onClick = {
                            ask(
                                "Ease the climbs and recover on the easier kilometres, keeping the finish time fixed.",
                                climbsLabel,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            ChatComposer(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        ask(text, text)
                    }
                },
                onCancel = { cancelRequest() },
                streaming = loading,
                enabled = true,
                placeholder = tr("Ask the PacePro coach"),
            )
        }

        if (latestProposal != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PulseSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                TextButton(
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                ) { Text(tr("Discard"), color = PulseColors.Text) }
                PrimaryButton(
                    text = tr("Apply to plan"),
                    onClick = {
                        val proposal = latestProposal ?: return@PrimaryButton
                        val proposed = PacePlanCalculator.applyOffsets(currentSplits, proposal.offsets)
                        onApply(PacePlanCalculator.offsetsFrom(baseSplits, proposed))
                    },
                    enabled = !loading,
                    modifier = Modifier.weight(2f),
                )
            }
        } else {
            Spacer(Modifier.height(PulseSpacing.xl))
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: PaceCoachProposal,
    currentSplits: List<PacePlanSplit>,
) {
    val proposedSplits = PacePlanCalculator.applyOffsets(currentSplits, proposal.offsets)
    val changed = proposal.offsets.keys.sorted()
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PulseSpacing.xl,
                        end = PulseSpacing.xl,
                        top = PulseSpacing.md,
                        bottom = PulseSpacing.sm,
                    ),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                CoachHeader("Km", 0.45f)
                CoachHeader("Before", 1f)
                CoachHeader("After", 1f)
                CoachHeader("Difference", 0.8f, TextAlign.End)
            }
            changed.forEach { index ->
                val before = currentSplits.getOrNull(index) ?: return@forEach
                val after = proposedSplits.getOrNull(index) ?: return@forEach
                val delta = after.targetSeconds - before.targetSeconds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PulseSpacing.xl, vertical = PulseSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.weight(0.45f),
                    )
                    Text(
                        tr("%s /km", PacePlanCalculator.formatPace(before.paceSecondsPerKm())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        tr("%s /km", PacePlanCalculator.formatPace(after.paceSecondsPerKm())),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = PulseColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        PacePlanCalculator.formatSignedSeconds(delta),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (delta < 0) PulseColors.Teal else PulseColors.Coral,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.8f),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PulseSpacing.xl),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    tr("Final time"),
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                )
                Text(
                    tr(
                        "%s → %s",
                        PacePlanCalculator.formatClock(currentSplits.sumOf { it.targetSeconds }),
                        PacePlanCalculator.formatClock(proposedSplits.sumOf { it.targetSeconds }),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = PulseColors.Teal,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CoachHeader(
    text: String,
    weight: Float,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = PulseColors.TextMuted,
        textAlign = align,
        modifier = Modifier.weight(weight),
    )
}
