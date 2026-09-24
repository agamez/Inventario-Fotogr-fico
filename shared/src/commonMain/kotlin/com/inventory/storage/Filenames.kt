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
     * Base names a camera or the OS generates: a known prefix followed by
     * separator/digit groups (`IMG_1234`, `DSC_0123`, `PXL_20260903_1234`,
     * `Screenshot_20260903`, `MVIMG_20260101`) or a bare 8-digit date. The
     * trailing `([_\\-]?\\d+)*` allows the multi-segment camera shapes
     * (`PXL_..._...`) while still rejecting real words. Case-insensitive.
     */
    private val CAMERA_GENERATED_NAME = Regex(
        "^(IMG|DSC|DSCN|PXL|Screenshot|MVIMG|2026\\d{4}|20\\d{6})([_\\-]?\\d+)*$",
        RegexOption.IGNORE_CASE,
    )

    /** Bare digit runs of four or more (`12345`, `20260903`). */
    private val BARE_DIGITS = Regex("^\\d{4,}$")

    /** Any name the OS screenshotted, whatever follows the prefix. */
    private const val SCREENSHOT_PREFIX = "Screenshot"

    /**
     * True when [fileName] (with or without an extension) looks like a
     * camera/OS-generated photo name rather than a human description: a
     * known camera prefix plus optional separator/digit groups, a bare
     * four-plus-digit run, or anything starting with the "Screenshot"
     * prefix. Used by the AI tidy-up flow to find photos whose description
     * is still an auto-generated file name.
     */
    fun isCameraGenerated(fileName: String): Boolean {
        val stem = fileName.substringBeforeLast('.', fileName)
        return CAMERA_GENERATED_NAME.matches(stem) ||
            BARE_DIGITS.matches(stem) ||
            stem.startsWith(SCREENSHOT_PREFIX, ignoreCase = true)
    }

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
    fun uniqueName(folder: File, base: String, ext: String, ignoring: String? = null): String =
        uniqueNameIn(folder.list()?.toSet() ?: emptySet(), base, ext, ignoring)

    /**
     * [uniqueName] over an in-memory set of taken names — what the SAF
     * backend (and the store facade) use, since listing there costs a
     * query while the name set is already at hand.
     */
    fun uniqueNameIn(taken: Set<String>, base: String, ext: String, ignoring: String? = null): String {
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        val takenLower = taken
            .filterNot { it.equals(ignoring, ignoreCase = true) }
            .map { it.lowercase(Locale.getDefault()) }
            .toSet()
        var candidate = base
        var counter = 2
        while ((candidate + suffix).lowercase(Locale.getDefault()) in takenLower) {
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
