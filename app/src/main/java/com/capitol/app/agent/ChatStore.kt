package com.capitol.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
sealed interface ChatItem {
    @Serializable @SerialName("user") data class User(val text: String) : ChatItem
    @Serializable @SerialName("assistant") data class Assistant(val text: String, val costUsd: Double? = null, val tokens: String? = null) : ChatItem
    @Serializable @SerialName("tool") data class Tool(val text: String, val done: Boolean = false, val ok: Boolean = true) : ChatItem
    @Serializable @SerialName("error") data class Error(val text: String) : ChatItem
    @Serializable @SerialName("note") data class Note(val text: String) : ChatItem
}

@Serializable
data class SavedChat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val items: List<ChatItem>,
    val usage: CapitolAgent.Usage = CapitolAgent.Usage(),
    val session: CapitolAgent.Session? = null,
)

/** Saved AI-agent conversations (one JSON file each, newest 30 kept). */
class ChatStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun list(): List<SavedChat> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<SavedChat>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun save(chat: SavedChat) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        File(dir, "${chat.id}.json").writeText(json.encodeToString(chat))
        list().drop(MAX).forEach { File(dir, "${it.id}.json").delete() }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { File(dir, "$id.json").delete() }

    companion object { const val MAX = 30 }
}
