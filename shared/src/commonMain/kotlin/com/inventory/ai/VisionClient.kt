package com.inventory.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Everything a request needs: endpoint root, optional bearer key, model name and output language. */
data class VisionConfig(
    val baseUrl: String,
    val apiKey: String?,
    val model: String,
    val outputLanguage: String? = null,
)

/**
 * Result of one vision call. [Failed.reason] is human-readable and NEVER
 * contains the API key — callers may surface it (or a generic resource) to
 * the user.
 */
sealed interface VisionOutcome {
    data class Suggested(val description: String, val note: String?) : VisionOutcome
    data class Failed(val reason: String) : VisionOutcome
}

/** Tolerant parser: unknown fields are ignored and the payload need not be strict JSON. */
val lenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Minimal OpenAI-compatible vision client, pure JVM so both the Android app
 * and the desktop app can share it later.
 *
 * [describe] sends a JPEG as a `data:` URL inside a chat-completion request
 * and asks the model for a small JSON object with an item description and an
 * optional note. [ping] sends a small text request for the settings
 * "Test connection" button. Both wrap blocking [HttpURLConnection] I/O in
 * [Dispatchers.IO]; all JSON is built with kotlinx-serialization, never by
 * concatenating user data.
 */
class VisionClient(
    private val config: VisionConfig,
    private val json: Json = lenientJson,
) {

    /**
     * Asks the model to label [jpegBytes]. Success yields the parsed
     * suggestion; every failure mode (transport, HTTP status, unparseable
     * reply) is reported as [VisionOutcome.Failed].
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun describe(jpegBytes: ByteArray): VisionOutcome {
        val dataUrl = "data:image/jpeg;base64," + Base64.Default.encode(jpegBytes)
        val body = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", promptText())
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUrl) }
                        }
                    }
                }
            }
            put("max_tokens", MAX_TOKENS_DESCRIBE)
        }
        return when (val raw = execute(body)) {
            is RawResult.Ok -> {
                val content = contentText(raw.reply.content)
                if (content == null) {
                    val reason = if (raw.reply.finishReason == FINISH_LENGTH) {
                        REASON_TOKEN_BUDGET
                    } else {
                        REASON_NO_CONTENT
                    }
                    VisionOutcome.Failed(reason)
                } else {
                    parseSuggestion(content)
                }
            }
            is RawResult.Error -> VisionOutcome.Failed(raw.reason)
        }
    }

    /**
     * [PROMPT] plus, when [VisionConfig.outputLanguage] is non-blank, one
     * sentence that asks for the description and note in that language.
     */
    private fun promptText(): String {
        val language = config.outputLanguage?.trim()
        return if (language.isNullOrEmpty()) PROMPT else "$PROMPT Write the description and note in $language."
    }

    /**
     * Tiny text-only round trip used by the "Test connection" button. It
     * only proves connectivity: HTTP 200 with a `choices` array is a
     * success even when the content is null, because reasoning models may
     * spend the whole budget on reasoning and return a truncated (empty)
     * assistant message.
     */
    suspend fun ping(): VisionOutcome {
        val body = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", PING_PROMPT)
                }
            }
            put("max_tokens", MAX_TOKENS_PING)
        }
        return when (val raw = execute(body)) {
            is RawResult.Ok -> VisionOutcome.Suggested("OK", null)
            is RawResult.Error -> VisionOutcome.Failed(raw.reason)
        }
    }

    /** One blocking POST, executed off the caller's thread. */
    private suspend fun execute(body: JsonObject): RawResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(config.baseUrl.trimEnd('/') + CHAT_COMPLETIONS_PATH)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (!config.apiKey.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code == HTTP_OK) {
                val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val reply = extractReply(text)
                if (reply == null) {
                    RawResult.Error(REASON_UNEXPECTED_REPLY)
                } else {
                    RawResult.Ok(reply)
                }
            } else {
                RawResult.Error(httpError(code, connection))
            }
        } catch (exception: IOException) {
            RawResult.Error("network error: ${exception.message}")
        } catch (exception: RuntimeException) {
            // Bad URLs and protocol violations surface here; no key is ever echoed.
            RawResult.Error("network error: ${exception.message}")
        } finally {
            connection?.disconnect()
        }
    }

    /** "HTTP <code>" plus a short parsed error snippet (never the key). */
    private fun httpError(code: Int, connection: HttpURLConnection): String {
        val snippet = runCatching {
            (connection.errorStream ?: connection.inputStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.trim()?.take(MAX_ERROR_SNIPPET)
        val parsed = snippet?.takeIf { it.isNotEmpty() }?.let {
            runCatching { json.parseToJsonElement(it) }.getOrNull()
        }
        return if (parsed == null) "HTTP $code" else "HTTP $code: $snippet"
    }

    /**
     * choices[0] of a chat completion as a [Reply], or null when the
     * envelope is not a chat completion with a usable choice. The message
     * content is returned raw so callers can decide how to flatten it.
     */
    private fun extractReply(text: String): Reply? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val message = choice["message"]?.jsonObject
        val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        return Reply(content = message?.get("content"), finishReason = finishReason)
    }

    /**
     * Flattens an assistant message content node to plain text. OpenAI's
     * chat-completions reply is usually a JSON string, but multimodal
     * endpoints may return an array of parts; only `"type":"text"` parts
     * contribute, their `text` fields concatenated in order. JSON null and
     * a missing node both flatten to null.
     */
    private fun contentText(content: JsonElement?): String? {
        if (content == null || content is JsonNull) return null
        return when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            }.joinToString(separator = "")
            else -> null
        }
    }

    /** Leniently pulls `{description, note}` out of a model reply. */
    private fun parseSuggestion(content: String): VisionOutcome {
        val unfenced = stripCodeFences(content)
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        if (start < 0 || end <= start) return VisionOutcome.Failed(REASON_UNEXPECTED_REPLY)
        val obj = runCatching {
            json.parseToJsonElement(unfenced.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return VisionOutcome.Failed(REASON_UNEXPECTED_REPLY)
        val description = obj["description"].asTextOrNull()?.trim()
        if (description.isNullOrBlank()) return VisionOutcome.Failed(REASON_EMPTY_DESCRIPTION)
        val note = obj["note"].asTextOrNull()?.trim()?.ifEmpty { null }
        return VisionOutcome.Suggested(description, note)
    }

    private companion object {
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val HTTP_OK = 200
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 45_000
        const val MAX_TOKENS_DESCRIBE = 2048
        const val MAX_TOKENS_PING = 512
        const val MAX_ERROR_SNIPPET = 200
        const val FINISH_LENGTH = "length"
        const val REASON_UNEXPECTED_REPLY = "unexpected reply format"
        const val REASON_EMPTY_DESCRIPTION = "empty description"
        const val REASON_NO_CONTENT = "model returned no content"
        const val REASON_TOKEN_BUDGET =
            "model used all tokens before answering (reasoning models need a larger budget)"
        const val PING_PROMPT = "Reply with the single word OK"

        const val PROMPT =
            "You label inventory photos. Look at the photo of a single item and reply with ONLY a JSON object " +
                "of the form {\"description\": \"<short item name, 2-6 words>\", \"note\": \"<one short useful sentence or empty>\"}. " +
                "The description must be a short item name (2-6 words). The note must be one short useful sentence, " +
                "or an empty string. Reply with the raw JSON object only: no markdown, no code fences, no extra text."
    }
}

/** A JSON string member read defensively (null for JSON null or a non-string). */
private fun kotlinx.serialization.json.JsonElement?.asTextOrNull(): String? {
    if (this == null || this is JsonNull) return null
    return runCatching { jsonPrimitive.contentOrNull }.getOrNull()
}

/** Drops a leading ```json fence and its closing counterpart, if present. */
private fun stripCodeFences(text: String): String {
    var value = text.trim()
    if (!value.startsWith("```")) return value
    value = value.removePrefix("```")
    val newline = value.indexOf('\n')
    if (newline >= 0 && value.substring(0, newline).trim().let { it.isEmpty() || it == "json" }) {
        value = value.substring(newline + 1)
    }
    return value.removeSuffix("```").trim()
}

/**
 * choices[0] of a chat completion: the raw `message.content` node (a JSON
 * string, an array of parts or null for reasoning models that answered
 * nothing) and the `finish_reason` that explains why generation stopped.
 */
private data class Reply(val content: JsonElement?, val finishReason: String?)

/** Outcome of the raw HTTP exchange, before any reply-specific parsing. */
private sealed interface RawResult {
    data class Ok(val reply: Reply) : RawResult
    data class Error(val reason: String) : RawResult
}
