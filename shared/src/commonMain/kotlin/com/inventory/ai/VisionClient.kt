package com.inventory.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Everything a request needs: endpoint root, optional bearer key and model name. */
data class VisionConfig(val baseUrl: String, val apiKey: String?, val model: String)

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
 * optional note. [ping] sends a one-token text request for the settings
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
                            put("text", PROMPT)
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
            is RawResult.Ok -> parseSuggestion(raw.content)
            is RawResult.Error -> VisionOutcome.Failed(raw.reason)
        }
    }

    /** Tiny text-only round trip used by the "Test connection" button. */
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
                val content = extractContent(text)
                if (content == null) {
                    RawResult.Error(REASON_UNEXPECTED_REPLY)
                } else {
                    RawResult.Ok(content)
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

    /** choices[0].message.content, or null when the envelope is not a chat completion. */
    private fun extractContent(text: String): String? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val message = root["choices"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?: return null
        val content = message["content"] ?: return null
        if (content is JsonNull) return null
        return runCatching { content.jsonPrimitive.contentOrNull }.getOrNull()
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
        const val MAX_TOKENS_DESCRIBE = 300
        const val MAX_TOKENS_PING = 5
        const val MAX_ERROR_SNIPPET = 200
        const val REASON_UNEXPECTED_REPLY = "unexpected reply format"
        const val REASON_EMPTY_DESCRIPTION = "empty description"
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

/** Outcome of the raw HTTP exchange, before any reply-specific parsing. */
private sealed interface RawResult {
    data class Ok(val content: String) : RawResult
    data class Error(val reason: String) : RawResult
}
