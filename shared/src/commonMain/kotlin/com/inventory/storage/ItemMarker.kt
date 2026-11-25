package com.inventory.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The `_item.json` marker that turns a container folder into a multi-photo
 * item (element): a folder carrying a valid marker IS an item, a folder
 * without one (or with a corrupt/unknown marker) is an ordinary container.
 * The folder-item is only ever created by the app, never by hand, so there
 * are no heuristics and no ambiguity — see
 * `docs/ideas-multi-photo-items.md`.
 *
 * The canonical payload is `{"type":"item","v":1,"note":"…"}` with the note
 * omitted entirely when absent (encoded UTF-8, no trailing newline). A
 * marker of a future version (`v` > [ITEM_MARKER_VERSION]) still counts as
 * an item so an older build never demotes a newer folder to a container;
 * an unknown `type` does not (the folder is scanned as a normal container).
 */
const val ITEM_MARKER: String = "_item.json"

/** The `type` value every item marker carries. */
const val ITEM_MARKER_TYPE: String = "item"

/** The marker version this build writes. */
const val ITEM_MARKER_VERSION: Int = 1

/** Parsed content of an item marker: its type, schema version and optional element note. */
data class ItemMarkerData(
    val type: String,
    val v: Int,
    val note: String? = null,
)

/**
 * Parsing, classification and serialization of the item marker. Every
 * function is pure (except [isItemFolder], a thin backend convenience) and
 * never throws on bad input: a corrupt marker is simply not an item, and
 * the scan is never blocked by it.
 */
object ItemMarker {

    /**
     * Reads [bytes] as an item marker; null when the payload is missing,
     * not decodable text, not a JSON object, lacks a string `type` or a
     * numeric `v`, or carries a non-string `note` (which would make the
     * note untrustworthy). Corrupt content never throws.
     */
    fun parse(bytes: ByteArray?): ItemMarkerData? {
        if (bytes == null || bytes.isEmpty()) return null
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        return parseText(text)
    }

    /** [parse] over already-decoded text; null on the same invalid shapes. */
    fun parseText(content: String?): ItemMarkerData? {
        if (content.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()
            ?: return null
        return parseObject(obj)
    }

    private fun parseObject(obj: JsonObject): ItemMarkerData? {
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val versionPrimitive = obj["v"] as? JsonPrimitive ?: return null
        if (versionPrimitive.isString) return null
        val version = versionPrimitive.intOrNull ?: return null
        val notePrimitive = obj["note"]
        val note = when {
            notePrimitive == null || notePrimitive is JsonNull -> null
            notePrimitive is JsonPrimitive && notePrimitive.isString -> notePrimitive.content
            else -> return null
        }
        return ItemMarkerData(type = type, v = version, note = note)
    }

    /** True when [marker] is a marker of type [ITEM_MARKER_TYPE] with a known-compatible version. */
    fun isItem(marker: ItemMarkerData?): Boolean =
        marker != null && marker.type == ITEM_MARKER_TYPE && marker.v >= ITEM_MARKER_VERSION

    /** True when [bytes] hold a valid item marker; false for absent, corrupt or foreign payloads. */
    fun isItemMarker(bytes: ByteArray?): Boolean = isItem(parse(bytes))

    /**
     * Canonical marker bytes for an item folder: `{"type":"item","v":1}`,
     * or with `,"note":"…"` when [note] is non-null. Always UTF-8.
     */
    fun writeItemMarker(note: String? = null): ByteArray =
        buildJsonObject {
            put("type", ITEM_MARKER_TYPE)
            put("v", ITEM_MARKER_VERSION)
            if (note != null) put("note", note)
        }.toString().toByteArray(Charsets.UTF_8)

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}

/**
 * True when the folder at [relPath] is an item: it carries a readable
 * [_item.json][ITEM_MARKER] with a valid payload. Reads one known file, so
 * a full scan costs one extra marker read per subfolder.
 */
suspend fun StorageBackend.isItemFolder(relPath: String): Boolean =
    ItemMarker.isItemMarker(readAuxFile(joinRel(relPath, ITEM_MARKER)))
