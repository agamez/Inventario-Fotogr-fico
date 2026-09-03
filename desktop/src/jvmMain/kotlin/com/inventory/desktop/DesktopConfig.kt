package com.inventory.desktop

import java.io.File
import java.util.Properties

/**
 * The desktop app's one piece of local state: which folder is the
 * inventory root. Persisted as `root=<absolute path>` in
 * `~/.config/photoinventory/config.properties` — the same tree the
 * Android app manages, addressed directly through the shared
 * [com.inventory.storage.FileBackend] (no SAF, no permission dance on a
 * desktop filesystem).
 *
 * A missing file, a blank value or a stored path that is no longer a
 * directory all read back as `null`, so the UI falls through to its
 * folder picker.
 */
object DesktopConfig {

    private const val ROOT_KEY = "root"
    private const val CONFIG_DIR_PROPERTY = "user.home"
    private const val CONFIG_SUBDIR = ".config/photoinventory"
    private const val CONFIG_FILE_NAME = "config.properties"

    /** The config file location, resolved from the user's home directory. */
    val configFile: File
        get() = File(File(System.getProperty(CONFIG_DIR_PROPERTY, "."), CONFIG_SUBDIR), CONFIG_FILE_NAME)

    /** The saved inventory root folder, or null when absent/unusable. */
    fun loadRoot(): File? {
        val file = configFile
        if (!file.isFile) return null
        val properties = Properties()
        runCatching { file.inputStream().use { properties.load(it) } }.getOrNull() ?: return null
        val path = properties.getProperty(ROOT_KEY)?.trim()?.ifEmpty { null } ?: return null
        val folder = File(path)
        return folder.takeIf { it.isDirectory }
    }

    /**
     * Persists [folder] as the inventory root (atomic temp + rename).
     * Throws [java.io.IOException] when the file cannot be written.
     */
    fun saveRoot(folder: File) {
        val target = configFile
        target.parentFile?.mkdirs()
        val properties = Properties().apply { setProperty(ROOT_KEY, folder.absolutePath) }
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.outputStream().use { properties.store(it, "Photo Inventory desktop storage root") }
        target.delete()
        if (!temp.renameTo(target)) {
            throw java.io.IOException("Could not persist config at ${target.path}")
        }
    }
}
