package com.inventory.desktop.ui

/**
 * Every user-visible string of the desktop app, in one auditable place.
 * The wording follows the Android app's `strings.xml` where the screens
 * are the same (container/photo/browse vocabulary); the desktop-only
 * chrome (window, pickers, storage line) is defined here.
 */
object DesktopStrings {

    // Window & top bar
    const val WINDOW_TITLE = "Photo Inventory"
    const val TITLE_INVENTORY = "Inventory"
    const val UP = "Up one level"
    const val RESCAN = "Rescan"
    const val SEARCH = "Search"
    const val SEARCH_HINT = "Search all photos"
    const val CLOSE_SEARCH = "Close search"
    const val IMPORT_PHOTOS = "Import photos"
    const val ADD_CONTAINER = "Add container"
    const val SETTINGS = "Settings"
    const val EXPORT_REPORT = "Export report"

    // Sections & rows
    const val CONTAINERS_SECTION = "Containers"
    const val PHOTOS_SECTION = "Photos"
    const val ROOT_FOLDER_LABEL = "Inventory (root)"
    const val MOVE_UP_HERE = "Move up one level"
    const val CONFLICT_BADGE = "Sync conflict"
    const val CONTAINER_OPTIONS = "Container options"
    const val SCANNING_LABEL = "Scanning…"

    // Empty states
    const val INVENTORY_EMPTY = "Your inventory is empty.\nClick \"Add container\" to create your first container."
    const val EMPTY_FOLDER = "Nothing here yet.\nAdd a container or import photos."
    const val NO_RESULTS = "No photos match this search."

    // Search scope chips
    const val SEARCH_SCOPE_HERE = "This container"
    const val SEARCH_SCOPE_SUBTREE = "Include sub-containers"

    // Dialog buttons & generic actions
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val DELETE = "Delete"
    const val CLOSE = "Close"
    const val RENAME = "Rename"
    const val MOVE = "Move"
    const val MOVE_TO = "Move to…"
    const val SET_COVER = "Set cover from photo…"
    const val REMOVE_COVER = "Remove cover"
    const val NEXT = "Next"

    // Container dialogs
    const val NEW_CONTAINER_TITLE = "New container"
    const val RENAME_CONTAINER_TITLE = "Rename container"
    const val NAME_LABEL = "Name"
    const val MOVE_CONTAINER_TITLE = "Move container to…"
    const val MOVE_CONTAINER_TO = "Move to…"
    const val PHOTO_MOVE_TITLE = "Move photo to…"

    // Photo dialog
    const val PHOTO_DETAILS_TITLE = "Photo"
    const val DESCRIPTION_LABEL = "Description"
    const val NOTE_LABEL = "Note"
    const val DATE_TAKEN_LABEL = "Date taken"
    const val NO_DATE = "no date"
    const val PHOTO_PATH_LINE = "In: %s"

    // Full-screen photo viewer
    const val VIEWER_EDIT = "Edit"
    const val VIEWER_CLOSE = "Close"
    const val VIEWER_PAGE = "%1\$d / %2\$d"
    const val VIEWER_PREVIOUS = "Previous photo"
    const val VIEWER_NEXT = "Next photo"

    // Folder picker dialog
    const val NO_FOLDER_TARGETS = "No other folders yet."

    // Import queue
    const val IMPORT_TITLE = "Import photo"
    const val N_MORE_WAITING = "%d more waiting"
    const val DESCRIPTION_REQUIRED = "Description is required"

    // Settings dialog (AI auto-label) — wording mirrors Android's ai_* resources
    const val SETTINGS_TITLE = "Settings"
    const val AI_ENABLE = "AI auto-label"
    const val AI_ENABLE_HELP =
        "When enabled, new photos are sent to the configured AI server to suggest a description and note. " +
            "You can always edit them before saving."
    const val AI_BASE_URL = "API base URL"
    const val AI_API_KEY = "API key"
    const val AI_KEY_HINT = "Stored in this app's config file. Leave empty for local servers."
    const val AI_MODEL = "Model"
    const val OUTPUT_LANGUAGE = "Output language"
    const val OUTPUT_LANGUAGE_HINT = "System default"
    const val AI_TEST_CONNECTION = "Test connection"
    const val AI_TEST_OK = "Connection OK"
    const val AI_TEST_FAILED = "Connection failed"
    const val AI_SUGGESTION_THINKING = "Asking AI…"
    const val AI_SUGGESTION_READY = "AI suggestion ready"
    const val AI_SUGGESTION_FAILED = "AI suggestion failed"

    // AI tidy-up (mirrors Android's tidy_* resources)
    const val TIDY_TITLE = "AI tidy-up"
    const val TIDY_PROGRESS = "%1\$d / %2\$d"
    const val TIDY_APPLY = "Apply"
    const val TIDY_SKIP = "Skip"
    const val TIDY_CANCEL = "Cancel"
    const val TIDY_RETRY = "Retry"
    const val TIDY_DONE = "Tidied %1\$d, skipped %2\$d"
    const val TIDY_NOTHING = "Nothing to tidy — no camera-generated names found"
    const val TIDY_NEED_AI = "Enable AI in Settings first"
    const val TIDY_SCANNING = "Scanning…"

    // Delete confirmations (mode-dependent: trash wording when the switch is
    // on, the pre-trash permanent wording when it is off)
    const val DELETE_CONTAINER_TITLE = "Delete container?"
    const val DELETE_CONTAINER_MESSAGE =
        "\"%s\" and everything inside it will be moved to the trash and auto-purged after 30 days."
    const val DELETE_CONTAINER_MESSAGE_PERMANENT =
        "\"%s\" and its contents will be deleted from disk. This cannot be undone."
    const val DELETE_CONTAINS = "It holds %s and %s."
    const val CONTAINERS_COUNTED = "%d containers"
    const val CONTAINER_COUNTED_ONE = "%d container"
    const val PHOTOS_COUNTED = "%d photos"
    const val PHOTO_COUNTED_ONE = "%d photo"
    const val COUNT_SEPARATOR = " · "
    const val DELETE_PHOTO_TITLE = "Delete photo?"
    const val DELETE_PHOTO_MESSAGE = "\"%s\" will be moved to the trash."
    const val DELETE_PHOTO_MESSAGE_PERMANENT =
        "\"%s\" will be deleted from disk. This cannot be undone."

    // Trash setting (settings dialog) — mirrors Android's trash_enable*
    const val TRASH_ENABLE = "Move deleted items to trash"
    const val TRASH_ENABLE_HELP =
        "When off, deletions are permanent. When on, deleted items are kept in .trash for 30 days or until the trash exceeds 500 MB."

    // Trash view (soft delete / undo) — wording mirrors Android's trash_* resources
    const val TRASH = "Trash"
    const val TRASH_TITLE = "Trash"
    const val TRASH_EMPTY = "Trash is empty"
    const val RESTORE = "Restore"
    const val DELETE_FOREVER = "Delete forever"
    const val EMPTY_TRASH = "Empty trash"
    const val TRASH_WAS_IN = "Was in %s"
    const val TRASH_DELETED_ON = "Deleted %s"
    const val TRASH_KIND_CONTAINER = "Container"
    const val TRASH_KIND_PHOTO = "Photo"
    const val DELETE_FOREVER_CONFIRM = "Permanently delete \"%s\"? This cannot be undone."
    const val EMPTY_TRASH_CONFIRM = "Permanently delete every trash entry? This cannot be undone."
    const val MSG_TRASH_RESTORED = "Restored"
    const val MSG_TRASH_PURGED = "Deleted forever"
    const val MSG_TRASH_EMPTIED = "Trash emptied"

    // Conflict banner
    const val CONFLICT_BANNER_ONE = "1 sync-conflicted file — resolve it with a file manager, then rescan."
    const val CONFLICT_BANNER_MANY = "%d sync-conflicted files — resolve them with a file manager, then rescan."

    // Storage line
    const val STORAGE_LABEL = "Storage folder:"

    // Folder gate (no config yet / config folder missing)
    const val PICK_FOLDER_TITLE = "Choose your inventory folder"
    const val PICK_FOLDER_MESSAGE =
        "Point the app at the folder that holds your photo inventory — the same one the Android app uses."
    const val PICK_FOLDER_BUTTON = "Choose folder…"
    const val FOLDER_PICKER_TITLE = "Pick the inventory folder"
    const val IMAGE_PICKER_TITLE = "Choose photos to import"

    // Messages (snackbar line under the top bar)
    const val MSG_CONTAINER_CREATED = "Container created"
    const val MSG_CONTAINER_SAVED = "Container saved"
    const val MSG_CONTAINER_MOVED = "Container moved"
    const val MSG_CONTAINER_DELETED = "Container deleted"
    const val MSG_PHOTO_SAVED = "Photo saved"
    const val MSG_PHOTO_DELETED = "Photo deleted"
    const val MSG_PHOTOS_MOVED = "Photos moved"
    const val MSG_PHOTO_ADDED = "Photo added"
    const val MSG_RESCANNED = "Storage rescanned"
    const val MSG_COVER_UPDATED = "Cover updated"
    const val REPORT_WRITTEN = "Report written to %1\$s"
    const val REPORT_EXPORT_FAILED = "Report export failed"
    const val MSG_ERROR_BLANK_NAME = "The name cannot be empty"
    const val MSG_ERROR_BLANK_DESCRIPTION = "The description cannot be empty"
    const val MSG_ERROR_INVALID_IMAGE = "That file is not a valid image"
    const val MSG_ERROR_OPERATION = "The operation failed — check the folder and retry"
    const val MSG_ERROR_INVALID_MOVE = "A folder cannot be moved into itself"
    const val MSG_IMPORT_DONE = "Import finished"
}
