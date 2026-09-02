package com.inventory.storage

import java.io.File
import java.util.Locale

/**
 * Filename rules for the filesystem model: photo files are named after
 * their (sanitized) description, container folders after their name.
 *
 * [sanitize] guarantees a single, cross-platform-safe path segment and
 * [uniqueName] guarantees that a chosen name never clobberes an existing
 * entry inside its folder (collision candidates grow a " (2)", " (3)", …
 * suffix, matched case-insensitively because sync targets and some mounts
 * are case-insensitive).
 */
object Filenames {

    /** Name used when a raw text yields nothing usable after sanitizing. */
    const val UNTITLED = "Untitled"

    /** Hard cap of a single ext4 filename component, in bytes. */
    private const val MAX_NAME_BYTES = 255

    /** Bytes reserved off the cap for the longest collision suffix. */
    private const val COLLISION_SUFFIX_RESERVE = 16

    /** Characters forbidden in Windows/shareable file names. */
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|]")

    /** Runs of whitespace collapsed to a single space. */
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Turns free user text into a legal file/folder name: illegal
     * characters become "_", control characters are dropped, whitespace
     * runs collapse, leading/trailing dots and spaces are trimmed (they
     * break Windows and many sync tools) and the result is capped to
     * [MAX_NAME_BYTES] UTF-8 bytes minus room for a collision suffix —
     * truncated on code-point boundaries, never leaving a trailing dot
     * or space. An empty result becomes [UNTITLED].
     */
    fun sanitize(raw: String): String {
        var name = ILLEGAL_CHARS.replace(raw, "_")
        name = name.filterNot { it.isISOControl() }
        name = WHITESPACE_RUN.replace(name, " ")
        name = trimDotsAndSpaces(name)
        val budget = MAX_NAME_BYTES - COLLISION_SUFFIX_RESERVE
        if (name.toByteArray(Charsets.UTF_8).size > budget) {
            val builder = StringBuilder()
            var used = 0
            var index = 0
            while (index < name.length) {
                val codePoint = name.codePointAt(index)
                val chars = String(Character.toChars(codePoint))
                val size = chars.toByteArray(Charsets.UTF_8).size
                if (used + size > budget) break
                builder.append(chars)
                used += size
                index += Character.charCount(codePoint)
            }
            name = trimDotsAndSpaces(builder.toString())
        }
        return name.ifEmpty { UNTITLED }
    }

    /**
     * A file name inside [folder] that no existing entry claims,
     * case-insensitively: `base.ext`, then `base (2).ext`, `base (3).ext`
     * … [ext] is the extension without the dot ("" for folders). Pass
     * [ignoring] (the entry's own current name) when re-deriving the name
     * of a file that is about to be renamed, so it never collides with
     * itself.
     */
    fun uniqueName(folder: File, base: String, ext: String, ignoring: String? = null): String {
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        val taken = folder.list()
            ?.filterNot { it.equals(ignoring, ignoreCase = true) }
            ?.map { it.lowercase(Locale.getDefault()) }
            ?.toSet()
            ?: emptySet()
        var candidate = base
        var counter = 2
        while (candidate.lowercase(Locale.getDefault()) + suffix.lowercase(Locale.getDefault()) in taken) {
            candidate = "$base ($counter)"
            counter++
        }
        return candidate + suffix
    }

    private fun trimDotsAndSpaces(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && (value[start] == '.' || value[start].isWhitespace())) start++
        while (end > start && (value[end - 1] == '.' || value[end - 1].isWhitespace())) end--
        return value.substring(start, end)
    }
}
