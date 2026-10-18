package dev.opengarmin.app.coach

import android.content.Context
import dev.opengarmin.app.ui.components.ChatRole
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A persisted coach conversation. */
data class StoredConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<CoachUiMessage>,
)

/** Lightweight row used by the conversation-history list. */
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/**
 * Pure JSON codec for conversations. Kept separate from the SharedPreferences
 * store so backup export/import and unit tests can use it without an Android
 * context.
 */
internal object ConversationCodec {
    fun encode(conversations: List<StoredConversation>): String {
        val array = JSONArray()
        conversations.forEach { conversation ->
            array.put(
                JSONObject().apply {
                    put("id", conversation.id)
                    put("title", conversation.title)
                    put("createdAt", conversation.createdAt)
                    put("updatedAt", conversation.updatedAt)
                    put("messages", encodeMessages(conversation.messages))
                },
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<StoredConversation> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<StoredConversation>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val messages = decodeMessages(item.optJSONArray("messages"))
            val id = item.optString("id").takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val createdAt = item.optLong("createdAt", messages.firstOrNull()?.id ?: 0L)
            val updatedAt = item.optLong("updatedAt", messages.lastOrNull()?.id ?: createdAt)
            result.add(
                StoredConversation(
                    id = id,
                    title = item.optString("title").takeIf { it.isNotBlank() }
                        ?: titleFor(messages),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    messages = messages,
                ),
            )
        }
        return result
    }

    fun encodeMessages(messages: List<CoachUiMessage>): JSONArray {
        val array = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { message ->
            array.put(
                JSONObject().apply {
                    put("id", message.id)
                    put("role", message.role.name)
                    put("text", message.text)
                    put("status", message.status.name)
                    put(
                        "evidence",
                        JSONArray().apply { message.evidence.forEach(::put) },
                    )
                    put(
                        "charts",
                        JSONArray().apply { message.charts.forEach { put(it.toJson()) } },
                    )
                },
            )
        }
        return array
    }

    fun decodeMessages(array: JSONArray?): List<CoachUiMessage> {
        if (array == null) return emptyList()
        val messages = ArrayList<CoachUiMessage>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val role = runCatching { ChatRole.valueOf(item.optString("role")) }.getOrNull()
                ?: continue
            val storedStatus = runCatching { MessageStatus.valueOf(item.optString("status")) }
                .getOrDefault(MessageStatus.Complete)
            val evidence = mutableListOf<String>()
            item.optJSONArray("evidence")?.let { ev ->
                for (j in 0 until ev.length()) {
                    ev.optString(j).takeIf { it.isNotBlank() }?.let(evidence::add)
                }
            }
            val charts = mutableListOf<ChartSpec>()
            item.optJSONArray("charts")?.let { saved ->
                for (j in 0 until saved.length()) {
                    saved.optJSONObject(j)?.let { ChartSpec.fromJson(it) }?.let(charts::add)
                }
            }
            messages.add(
                CoachUiMessage(
                    id = item.optLong("id", i.toLong()),
                    role = role,
                    text = item.optString("text"),
                    evidence = evidence,
                    charts = charts,
                    status = if (storedStatus == MessageStatus.Streaming) {
                        MessageStatus.Cancelled
                    } else {
                        storedStatus
                    },
                ),
            )
        }
        return messages
    }

    fun titleFor(messages: List<CoachUiMessage>): String =
        messages.firstOrNull { it.role == ChatRole.User }?.text
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)
            ?.takeIf { it.isNotBlank() }
            ?: "Conversation"

    private const val MAX_MESSAGES = 200
}

/**
 * Stores coach conversations locally. The legacy single-conversation value is
 * migrated to the multi-conversation format on first read.
 */
class ChatStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pulse_chat", Context.MODE_PRIVATE)

    fun loadAll(): List<StoredConversation> =
        read().sortedByDescending { it.updatedAt }

    fun summaries(): List<ConversationSummary> =
        loadAll().map {
            ConversationSummary(
                id = it.id,
                title = it.title,
                updatedAt = it.updatedAt,
                messageCount = it.messages.size,
            )
        }

    fun load(id: String): StoredConversation? = read().firstOrNull { it.id == id }

    fun save(conversation: StoredConversation) {
        val all = read()
            .filterNot { it.id == conversation.id }
            .toMutableList()
        all.add(conversation)
        write(all.sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS))
    }

    fun delete(id: String) {
        write(read().filterNot { it.id == id })
    }

    fun clear() {
        prefs.edit().remove(KEY_CONVERSATIONS).remove(KEY_LEGACY_MESSAGES).apply()
    }

    /** All conversations as JSON, for inclusion in an exported backup. */
    fun exportJson(): String = ConversationCodec.encode(loadAll())

    /** Replaces the stored conversations with the JSON from a backup. */
    fun importJson(raw: String) {
        write(ConversationCodec.decode(raw).sortedByDescending { it.updatedAt })
    }

    private fun read(): List<StoredConversation> {
        val raw = prefs.getString(KEY_CONVERSATIONS, null)
        if (raw != null) return ConversationCodec.decode(raw)

        val legacy = prefs.getString(KEY_LEGACY_MESSAGES, null) ?: return emptyList()
        val messages = ConversationCodec.decodeMessages(runCatching { JSONArray(legacy) }.getOrNull())
        if (messages.none { it.role == ChatRole.User }) {
            prefs.edit().remove(KEY_LEGACY_MESSAGES).apply()
            return emptyList()
        }
        val createdAt = messages.firstOrNull()?.id ?: System.currentTimeMillis()
        val updatedAt = messages.lastOrNull()?.id ?: createdAt
        val conversation = StoredConversation(
            id = UUID.randomUUID().toString(),
            title = ConversationCodec.titleFor(messages),
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = messages,
        )
        write(listOf(conversation))
        return listOf(conversation)
    }

    private fun write(conversations: List<StoredConversation>) {
        prefs.edit()
            .putString(KEY_CONVERSATIONS, ConversationCodec.encode(conversations))
            .remove(KEY_LEGACY_MESSAGES)
            .apply()
    }

    companion object {
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_LEGACY_MESSAGES = "messages"
        private const val MAX_CONVERSATIONS = 50
    }
}
