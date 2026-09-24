package com.inventory.desktop

import java.io.File
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The persisted AI auto-label settings, the desktop twin of Android's
 * `VisionPrefs`. OFF by default; [baseUrl] and [apiKey] mirror the
 * OpenAI-compatible values the shared
 * [com.inventory.ai.VisionClient] consumes.
 */
data class AiSettings(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = "",
    val outputLanguage: String = "",
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}

/**
 * The desktop app's local state: which folder is the inventory root, the
 * optional AI auto-label configuration and the optional trash (soft-delete)
 * switch. Persisted in
 * `~/.config/photoinventory/config.properties` — the same tree the
 * Android app manages, addressed directly through the shared
 * [com.inventory.storage.FileBackend] (no SAF, no permission dance on a
 * desktop filesystem).
 *
 * The AI fields are mirrored in an observable [aiSettings] flow and the
 * trash switch in [trashEnabled], so the controller and the UI react to
 * edits without polling the file; every write is atomic (temp + rename)
 * and preserves the other keys.
 *
 * A missing file, a blank value or a stored root that is no longer a
 * directory all read back as `null`, so the UI falls through to its
 * folder picker.
 */
class DesktopConfig(
    /** The properties file backing this instance; injectable for tests. */
    private val file: File = defaultConfigFile(),
) {

    private val _aiSettings = MutableStateFlow(loadAiSettings())

    /** The current AI auto-label settings; edits persist immediately. */
    val aiSettings: StateFlow<AiSettings> = _aiSettings.asStateFlow()

    private val _trashEnabled = MutableStateFlow(loadTrashEnabled())

    /** Whether deletions move to `.trash`; off by default. */
    val trashEnabled: StateFlow<Boolean> = _trashEnabled.asStateFlow()

    /** The saved inventory root folder, or null when absent/unusable. */
    fun loadRoot(): File? {
        val properties = readProperties() ?: return null
        val path = properties.getProperty(ROOT_KEY)?.trim()?.ifEmpty { null } ?: return null
        val folder = File(path)
        return folder.takeIf { it.isDirectory }
    }

    /**
     * Persists [folder] as the inventory root (atomic temp + rename),
     * leaving the AI keys untouched. Throws [IOException] when the file
     * cannot be written.
     */
    fun saveRoot(folder: File) {
        val properties = readProperties() ?: Properties()
        properties.setProperty(ROOT_KEY, folder.absolutePath)
        write(properties)
    }

    /** Reads the AI settings from disk (defaults when the file is absent). */
    fun loadAiSettings(): AiSettings {
        val properties = readProperties() ?: return AiSettings()
        return AiSettings(
            enabled = properties.getProperty(KEY_AI_ENABLED)?.toBooleanStrictOrNull() ?: false,
            baseUrl = properties.getProperty(KEY_AI_BASE_URL)?.trim()?.ifEmpty { null }
                ?: AiSettings.DEFAULT_BASE_URL,
            apiKey = properties.getProperty(KEY_AI_API_KEY) ?: "",
            model = properties.getProperty(KEY_AI_MODEL) ?: "",
            outputLanguage = properties.getProperty(KEY_AI_OUTPUT_LANGUAGE) ?: "",
        )
    }

    fun setAiEnabled(value: Boolean) = persistAi { it.copy(enabled = value) }

    fun setAiBaseUrl(value: String) = persistAi { it.copy(baseUrl = value) }

    fun setAiApiKey(value: String) = persistAi { it.copy(apiKey = value) }

    fun setAiModel(value: String) = persistAi { it.copy(model = value) }

    fun setAiOutputLanguage(value: String) = persistAi { it.copy(outputLanguage = value) }

    /** Reads the trash switch from disk (false when the file/key is absent). */
    fun loadTrashEnabled(): Boolean =
        readProperties()?.getProperty(KEY_TRASH_ENABLED)?.toBooleanStrictOrNull() ?: false

    /** Persists the trash switch (atomic temp + rename), leaving other keys untouched. */
    fun setTrashEnabled(value: Boolean) {
        val properties = readProperties() ?: Properties()
        properties.setProperty(KEY_TRASH_ENABLED, value.toString())
        write(properties)
        _trashEnabled.value = value
    }

    /** Writes the full AI snapshot (all five keys) and updates the flow. */
    private fun persistAi(transform: (AiSettings) -> AiSettings) {
        val next = transform(_aiSettings.value)
        val properties = readProperties() ?: Properties()
        properties.setProperty(KEY_AI_ENABLED, next.enabled.toString())
        properties.setProperty(KEY_AI_BASE_URL, next.baseUrl)
        properties.setProperty(KEY_AI_API_KEY, next.apiKey)
        properties.setProperty(KEY_AI_MODEL, next.model)
        properties.setProperty(KEY_AI_OUTPUT_LANGUAGE, next.outputLanguage)
        write(properties)
        _aiSettings.value = next
    }

    private fun readProperties(): Properties? {
        if (!file.isFile) return null
        val properties = Properties()
        return runCatching { file.inputStream().use { properties.load(it) } }.map { properties }.getOrNull()
    }

    private fun write(properties: Properties) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { properties.store(it, "Photo Inventory desktop configuration") }
        file.delete()
        if (!temp.renameTo(file)) {
            throw IOException("Could not persist config at ${file.path}")
        }
    }

    companion object {
        private const val ROOT_KEY = "root"
        private const val KEY_AI_ENABLED = "aiEnabled"
        private const val KEY_AI_BASE_URL = "aiBaseUrl"
        private const val KEY_AI_API_KEY = "aiApiKey"
        private const val KEY_AI_MODEL = "aiModel"
        private const val KEY_AI_OUTPUT_LANGUAGE = "aiOutputLanguage"
        private const val KEY_TRASH_ENABLED = "trashEnabled"
        private const val CONFIG_DIR_PROPERTY = "user.home"
        private const val CONFIG_SUBDIR = ".config/photoinventory"
        private const val CONFIG_FILE_NAME = "config.properties"

        /** The production config, at `~/.config/photoinventory/config.properties`. */
        val default: DesktopConfig by lazy { DesktopConfig(defaultConfigFile()) }

        /** The config file location, resolved from the user's home directory. */
        fun defaultConfigFile(): File =
            File(File(System.getProperty(CONFIG_DIR_PROPERTY, "."), CONFIG_SUBDIR), CONFIG_FILE_NAME)
    }
}
