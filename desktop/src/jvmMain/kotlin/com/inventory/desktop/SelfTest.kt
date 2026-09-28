package com.inventory.desktop

import com.inventory.ai.VisionOutcome
import com.inventory.desktop.exif.DesktopExifEditor
import com.inventory.desktop.ui.DragKind
import com.inventory.desktop.ui.DragPayload
import com.inventory.storage.TrashManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * The scripted end-to-end check of the whole desktop stack — controller,
 * shared [com.inventory.storage.FileBackend], [com.inventory.desktop.exif.DesktopExifEditor]
 * and [com.inventory.desktop.index.DesktopIndex] — driven through exactly
 * the same entry points the UI uses, with no window and no watcher.
 * Prints `PASS`/`FAIL` lines and returns whether all of them passed;
 * the folder's contents are left in place for inspection (a Pillow dump
 * of the produced files is the external ground-truth check).
 */
suspend fun runHeadlessSelfTest(folder: File): Boolean {
    val report = SelfTestReport()
    folder.mkdirs()
    val editor = DesktopExifEditor()

    // The selftest never reads or writes the user's real config: it runs
    // against a throwaway properties file, deleted at the end.
    val configFile = File.createTempFile("pi-selftest", ".properties")
    val config = DesktopConfig(configFile)

    // 1. Fresh controller: index the (possibly leftover) folder.
    val controller = DesktopAppController(folder, config = config)
    controller.refreshNow()
    report.check("index built on empty root", controller.snapshot.value.folders == setOf(""))

    // 2. Containers A and B.
    val createdA = controller.createFolder("", "A")
    val createdB = controller.createFolder("", "B")
    report.check("container A created", createdA.first == "A", "got ${createdA.first}")
    report.check("container B created", createdB.first == "B", "got ${createdB.first}")

    // 3. Nested container A/C.
    val createdC = controller.createFolder("A", "C")
    report.check("nested A/C created", createdC.first == "A/C", "got ${createdC.first}")

    // 4. Two synthetic photos with EXIF triples into A.
    val alpha = controller.importPhoto("A", syntheticJpeg(), "Alpha item", "has hinge")
    val beta = controller.importPhoto("A", syntheticJpeg(), "Beta item", null)
    report.check("photo Alpha item imported", alpha == DesktopAppController.Outcome.Saved)
    report.check("photo Beta item imported", beta == DesktopAppController.Outcome.Saved)

    // 5. Disk names + EXIF through the desktop editor (ground truth bytes).
    val alphaFile = File(folder, "A/Alpha item.jpg")
    val betaFile = File(folder, "A/Beta item.jpg")
    report.check("A/Alpha item.jpg exists", alphaFile.isFile)
    report.check("A/Beta item.jpg exists", betaFile.isFile)
    val alphaMeta = alphaFile.takeIf { it.isFile }?.let { editor.read(it) }
    report.check("Alpha EXIF description", alphaMeta?.description == "Alpha item", "got ${alphaMeta?.description}")
    report.check("Alpha EXIF note", alphaMeta?.note == "has hinge", "got ${alphaMeta?.note}")
    report.check("Alpha EXIF date stamped", alphaMeta?.dateTaken != null)
    val betaMeta = betaFile.takeIf { it.isFile }?.let { editor.read(it) }
    report.check("Beta EXIF description", betaMeta?.description == "Beta item", "got ${betaMeta?.description}")
    report.check("Beta EXIF note absent", betaMeta?.note == null, "got ${betaMeta?.note}")

    // 6. Index rebuild sees the tree with the right counts.
    val snapshot = controller.snapshot.value
    report.check(
        "A holds 1 container and 2 photos",
        snapshot.subtreeCounts["A"] == (1 to 2),
        "got ${snapshot.subtreeCounts["A"]}",
    )
    report.check("root children are A and B", snapshot.containersByParent[""]?.map { it.name } == listOf("A", "B"))
    report.check("A photo rows", controller.photosIn("A", "", includeSubtree = false).size == 2)

    // 7. Subtree search from the root finds the note.
    val hingeHits = controller.photosIn("", "hinge", includeSubtree = true)
    report.check(
        "subtree search 'hinge' finds Alpha item",
        hingeHits.map { it.relPath } == listOf("A/Alpha item.jpg"),
        "got ${hingeHits.map { it.relPath }}",
    )
    report.check("global search 'hinge' finds Alpha item", controller.searchAll("hinge").size == 1)

    // 8. Move the Alpha photo into B: the bytes must land there, gone here.
    val moved = controller.movePhotosTo(listOf("A/Alpha item.jpg"), "B")
    report.check("photo moved (outcome)", moved == DesktopAppController.Outcome.Moved)
    report.check("A/Alpha item.jpg gone from disk", !alphaFile.exists())
    report.check("B/Alpha item.jpg exists on disk", File(folder, "B/Alpha item.jpg").isFile)

    // 9. Rename B.
    val renamed = controller.renameFolder("B", "B renamed")
    report.check("B renamed on disk", renamed.first == "B renamed" && File(folder, "B renamed").isDirectory)
    report.check("old B gone", !File(folder, "B").exists())

    // 10. Delete the nested container.
    controller.deleteFolder("A/C")
    report.check("A/C deleted", !File(folder, "A/C").exists())

    // 11. A FRESH index (new controller over the same folder) reconstructs the tree.
    val rebuilt = DesktopAppController(folder, config = config)
    rebuilt.refreshNow()
    val finalSnapshot = rebuilt.snapshot.value
    report.check(
        "fresh index root children",
        finalSnapshot.containersByParent[""]?.map { it.name } == listOf("A", "B renamed"),
        "got ${finalSnapshot.containersByParent[""]?.map { it.name }}",
    )
    report.check(
        "fresh index A photos",
        rebuilt.photosIn("A", "", includeSubtree = false).map { it.description } == listOf("Beta item"),
    )
    report.check(
        "fresh index renamed-folder photos",
        rebuilt.photosIn("B renamed", "", includeSubtree = false).map { it.description } == listOf("Alpha item"),
    )
    report.check("fresh index counts for A", finalSnapshot.subtreeCounts["A"] == (0 to 1))
    report.check("fresh index counts for B renamed", finalSnapshot.subtreeCounts["B renamed"] == (0 to 1))

    // 12. Drag resolution: the desktop dropDrag contract over a fresh subtree
    //     (photo move, container subtree move, and the no-op targets). This is
    //     the same entry point the Compose release handler calls.
    val dragFolder = rebuilt.createFolder("", "Drag").first
    val dragWest = dragFolder?.let { rebuilt.createFolder(it, "West").first }
    val dragEast = dragFolder?.let { rebuilt.createFolder(it, "East").first }
    report.check("drag tree created", dragFolder != null && dragWest != null && dragEast != null)
    if (dragFolder != null && dragWest != null && dragEast != null) {
        rebuilt.importPhoto(dragWest, syntheticJpeg(), "Drag photo", null)
        val photoRel = "$dragWest/Drag photo.jpg"
        val photoDrag = rebuilt.dropDrag(
            DragPayload(listOf(photoRel), "Drag photo", DragKind.PHOTO),
            dragEast,
        )
        report.check(
            "drag photo moved into hovered container",
            photoDrag &&
                File(folder, "$dragEast/Drag photo.jpg").isFile &&
                !File(folder, photoRel).exists(),
        )
        val subtreeDrag = rebuilt.dropDrag(
            DragPayload(listOf(dragWest), "West", DragKind.CONTAINER),
            dragEast,
        )
        report.check(
            "drag container moved its subtree",
            subtreeDrag &&
                File(folder, "$dragEast/West").isDirectory &&
                !File(folder, dragWest).exists(),
        )
        report.check(
            "drag with no target is a no-op",
            !rebuilt.dropDrag(DragPayload(listOf(dragEast), "East", DragKind.CONTAINER), null),
        )
        report.check(
            "drag into own subtree is a no-op",
            !rebuilt.dropDrag(
                DragPayload(listOf(dragEast), "East", DragKind.CONTAINER),
                "$dragEast/West",
            ),
        )
    }

    // 13. Multi-select deletion, trash OFF (the default): selecting two of
    //     three photos and running deleteSelectedPhotos removes exactly
    //     those two from disk, keeps the third and clears the selection.
    val selectionFolder = rebuilt.createFolder("", "Selection").first
    report.check("selection container created", selectionFolder == "Selection", "got $selectionFolder")
    if (selectionFolder != null) {
        rebuilt.importPhoto(selectionFolder, syntheticJpeg(), "Sel one", null)
        rebuilt.importPhoto(selectionFolder, syntheticJpeg(), "Sel two", null)
        rebuilt.importPhoto(selectionFolder, syntheticJpeg(), "Keep me", null)
        val selected = rebuilt.photosIn(selectionFolder, "", includeSubtree = false).map { it.relPath }
        report.check("selection lists three photos", selected.size == 3, "got $selected")
        if (selected.size == 3) {
            rebuilt.toggleSelect(selected[0])
            rebuilt.toggleSelect(selected[1])
            report.check("selection holds two", rebuilt.selectedPhotoRels.value.size == 2)
            val deleted = rebuilt.deleteSelectedPhotos()
            report.check("selection delete outcome", deleted == DesktopAppController.Outcome.Deleted, "got $deleted")
            report.check(
                "trash off: selected photos gone from disk",
                !File(folder, selected[0]).exists() && !File(folder, selected[1]).exists(),
            )
            report.check("trash off: unselected photo kept", File(folder, selected[2]).isFile)
            report.check("selection cleared after delete", rebuilt.selectedPhotoRels.value.isEmpty())
            report.check("trash off: selection delete left no .trash", !File(folder, TrashManager.TRASH_DIR).exists())
        }
    }

    // 14. Trash segment (soft delete / undo) — BOTH modes. OFF (the default):
    //     deletions are permanent and leave no .trash behind. ON: an entry is
    //     moved into a stamp folder with its sidecar, restored to its original
    //     parent, then trashed again and deleted forever.
    val permanentFolder = controller.createFolder("", "Permanent").first
    report.check("permanent-mode container created", permanentFolder == "Permanent", "got $permanentFolder")
    if (permanentFolder != null) {
        controller.importPhoto(permanentFolder, syntheticJpeg(), "Permanent photo", null)
        val deleted = controller.deleteFolder(permanentFolder)
        report.check("trash off: delete outcome", deleted == DesktopAppController.Outcome.Deleted, "got $deleted")
        report.check("trash off: folder gone from disk", !File(folder, permanentFolder).exists())
        report.check("trash off: no .trash created", !File(folder, TrashManager.TRASH_DIR).exists())
    }

    controller.setTrashEnabled(true)
    report.check("trash switch enabled", controller.trashEnabled.value)

    val trashFolder = controller.createFolder("", "Trashable").first
    report.check("trash container created", trashFolder == "Trashable", "got $trashFolder")
    if (trashFolder != null) {
        controller.importPhoto(trashFolder, syntheticJpeg(), "Trash photo", null)
        val trashed = controller.deleteFolder(trashFolder)
        report.check("container trashed (outcome)", trashed == DesktopAppController.Outcome.Deleted, "got $trashed")
        report.check("trashed container gone from its old home", !File(folder, trashFolder).exists())

        val boxEntry = controller.trashEntries.value.firstOrNull { it.name == "Trashable" }
        report.check("trash lists the container", boxEntry != null, "got ${controller.trashEntries.value.map { it.name }}")
        report.check("trash entry original parent", boxEntry?.originalParentRel == "", "got ${boxEntry?.originalParentRel}")
        report.check("trash entry kind is container", boxEntry?.kind == TrashManager.KIND_CONTAINER, "got ${boxEntry?.kind}")
        report.check("trash entry has a deletion time", (boxEntry?.deletedAt ?: 0L) > 0L, "got ${boxEntry?.deletedAt}")
        val stampDir = boxEntry?.let { File(folder, "${TrashManager.TRASH_DIR}/${it.id}") }
        report.check(
            "trash stamp holds the subtree and .origin",
            stampDir != null &&
                File(stampDir, "Trashable/Trash photo.jpg").isFile &&
                File(stampDir, TrashManager.ORIGIN_FILE).isFile,
            "got ${stampDir?.absolutePath}",
        )

        val restored = boxEntry?.let { controller.restore(it.id) } ?: false
        report.check(
            "container restored to its parent",
            restored && File(folder, "Trashable/Trash photo.jpg").isFile,
        )
        report.check(
            "trash no longer lists the restored container",
            controller.trashEntries.value.none { it.name == "Trashable" },
            "got ${controller.trashEntries.value.map { it.name }}",
        )

        controller.deleteFolder("Trashable")
        val again = controller.trashEntries.value.firstOrNull { it.name == "Trashable" }
        report.check("container trashed again", again != null)
        val purged = again?.let { controller.deleteForever(it.id) } ?: false
        report.check(
            "deleteForever removes the stamp from disk",
            purged && again != null && !File(folder, "${TrashManager.TRASH_DIR}/${again.id}").exists(),
        )
        controller.emptyTrash()
        report.check("emptyTrash leaves nothing", controller.trashEntries.value.isEmpty(), "got ${controller.trashEntries.value.map { it.name }}")
    }

    // Multi-select deletion with the trash ON: both selected photos leave
    // their folder and appear as two photo entries in the trash listing.
    val selectionTrashFolder = controller.createFolder("", "SelectTrash").first
    report.check("trash selection container created", selectionTrashFolder == "SelectTrash", "got $selectionTrashFolder")
    if (selectionTrashFolder != null) {
        controller.importPhoto(selectionTrashFolder, syntheticJpeg(), "Trash one", null)
        controller.importPhoto(selectionTrashFolder, syntheticJpeg(), "Trash two", null)
        val ordered = controller.photosIn(selectionTrashFolder, "", includeSubtree = false).map { it.relPath }
        report.check("trash selection lists two photos", ordered.size == 2, "got $ordered")
        if (ordered.size == 2) {
            ordered.forEach { controller.toggleSelect(it) }
            report.check("trash selection holds two", controller.selectedPhotoRels.value.size == 2)
            controller.deleteSelectedPhotos()
            report.check("trash selection gone from its folder", ordered.none { File(folder, it).exists() })
            report.check("trash selection cleared", controller.selectedPhotoRels.value.isEmpty())
            report.check(
                "trash selection listed as two photo entries",
                controller.trashEntries.value.count { it.kind == TrashManager.KIND_PHOTO } == 2,
                "got ${controller.trashEntries.value.map { it.kind }}",
            )
        }
        controller.emptyTrash()
    }
    controller.setTrashEnabled(false)

    // 15. AI auto-label segment against an in-test loopback mock server
    //     (fully self-contained, no external services). The controller is
    //     reused: configure AI, stage one photo, await the suggestion,
    //     save it, then turn AI back off and tear the mock down.
    val mockServer = startMockAiServer()
    controller.setAiEnabled(true)
    controller.setAiBaseUrl("http://127.0.0.1:${mockServer.address.port}/v1")
    controller.setAiApiKey("")
    controller.setAiModel("mock-vision-model")
    report.check("AI config active", controller.aiConfig() != null)

    val aiFolder = controller.createFolder("", "AI")
    report.check("AI container created", aiFolder.first == "AI", "got ${aiFolder.first}")

    controller.queueForImport(listOf(syntheticJpeg()))
    val staged = controller.pendingQueue.value.firstOrNull()
    report.check("AI photo staged", staged != null)
    if (staged != null) {
        val outcome = withTimeout(AI_TIMEOUT_MS) {
            controller.aiSuggestions.first { it.containsKey(staged.id) }[staged.id]!!
        }
        report.check("AI suggestion received", outcome is VisionOutcome.Suggested, "got $outcome")
        if (outcome is VisionOutcome.Suggested) {
            report.check("AI suggestion description", outcome.description == MOCK_AI_DESCRIPTION, "got ${outcome.description}")
            report.check("AI suggestion note", outcome.note == MOCK_AI_NOTE, "got ${outcome.note}")
            val saved = controller.importPendingPhoto("AI", outcome.description, outcome.note)
            report.check("AI photo saved", saved == DesktopAppController.Outcome.Saved, "got $saved")
            val aiFile = File(folder, "AI/$MOCK_AI_DESCRIPTION.jpg")
            report.check("AI photo named after suggestion", aiFile.isFile)
            val aiMeta = aiFile.takeIf { it.isFile }?.let { editor.read(it) }
            report.check("AI photo EXIF description", aiMeta?.description == MOCK_AI_DESCRIPTION, "got ${aiMeta?.description}")
            report.check("AI photo EXIF note", aiMeta?.note == MOCK_AI_NOTE, "got ${aiMeta?.note}")
        }
    }
    controller.setAiEnabled(false)
    report.check("AI disabled cleans the gate", controller.aiConfig() == null)

    // 16. Report export: the shared generator writes both documents into the
    //     inventory root; the UI confirms with the HTML path. Content markers
    //     prove the tree was walked, paths URL-encoded and EXIF read. A
    //     container cover is set first so its embedded header image is
    //     asserted too (and proven to stay out of the content grid).
    report.check(
        "container cover set for the report",
        controller.setCover("AI", syntheticJpeg()) == DesktopAppController.Outcome.Saved,
    )
    val exported = controller.exportReport()
    val reportHtml = File(folder, exported.htmlRelPath)
    val reportCsv = File(folder, exported.csvRelPath)
    report.check("report HTML written", reportHtml.isFile, exported.htmlRelPath)
    report.check("report CSV written", reportCsv.isFile, exported.csvRelPath)
    val htmlText = reportHtml.takeIf { it.isFile }?.readText().orEmpty()
    val csvText = reportCsv.takeIf { it.isFile }?.readText().orEmpty()
    report.check("report HTML has title", htmlText.contains("<h1>Photo Inventory</h1>"))
    report.check("report HTML has the summary totals", htmlText.contains("<li>Photos: "))
    report.check("report HTML has the AI container section", htmlText.contains("<h2>AI</h2>"), htmlText)
    report.check("report HTML has the mock description", htmlText.contains("Mock item"))
    report.check(
        "report HTML embeds base64 thumbnails",
        htmlText.contains("data:image/jpeg;base64,"),
        htmlText.take(200),
    )
    report.check(
        "report HTML embeds the container cover in its section header",
        htmlText.contains("<img class=\"cover\" src=\"data:image/jpeg;base64,") &&
            htmlText.contains("alt=\"AI\">"),
        htmlText,
    )
    report.check(
        "report HTML keeps the cover out of the photo grid",
        !htmlText.contains("_cover.jpg"),
        htmlText,
    )
    report.check(
        "report HTML URL-encodes the original photo links",
        htmlText.contains("href=\"B%20renamed/Alpha%20item.jpg\""),
        htmlText,
    )
    report.check(
        "report CSV header",
        csvText.startsWith("containerPath,fileName,description,note,dateTaken"),
        csvText.take(80),
    )
    report.check(
        "report CSV carries the Alpha row",
        csvText.contains("B renamed,Alpha item.jpg,Alpha item,has hinge,"),
        csvText,
    )

    controller.stop()
    mockServer.stop(0)
    configFile.delete()

    report.finish()
    return report.allPassed
}

/** Starts a loopback OpenAI-compatible endpoint answering with a fixed suggestion. */
private fun startMockAiServer(): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(MOCK_AI_PATH) { exchange: HttpExchange ->
        exchange.requestBody.use { it.readBytes() }
        val bytes = MOCK_AI_RESPONSE.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return server
}

private const val AI_TIMEOUT_MS = 15_000L
private const val MOCK_AI_PATH = "/v1/chat/completions"
private const val MOCK_AI_DESCRIPTION = "Mock item"
private const val MOCK_AI_NOTE = "added by mock"

/** A minimal chat-completion envelope whose content is the mock's JSON suggestion. */
private val MOCK_AI_RESPONSE: String =
    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" +
        "\"{\\\"description\\\": \\\"$MOCK_AI_DESCRIPTION\\\", \\\"note\\\": \\\"$MOCK_AI_NOTE\\\"}\"}}]}"

/** A small valid JPEG (SOI, ICC, SOF0/SOS/EOI) without any EXIF segment. */
internal fun syntheticJpeg(): ByteArray = Base64.getDecoder().decode(SYNTHETIC_JPEG_B64)

private const val SYNTHETIC_JPEG_B64: String =
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQ" +
    "ERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
    "FBQUFBQUFBQUFBQUFBT/wAARCAAMABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA" +
    "AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6" +
    "Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
    "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA" +
    "AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5" +
    "OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPE" +
    "xcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDyqiiivij60//Z"

/** Line printer + verdict keeper of [runHeadlessSelfTest]. */
private class SelfTestReport {

    var allPassed = true
        private set

    fun check(name: String, passed: Boolean, detail: String = "") {
        if (!passed) allPassed = false
        val status = if (passed) "PASS" else "FAIL"
        val suffix = if (!passed && detail.isNotEmpty()) " — $detail" else ""
        println("$status $name$suffix")
    }

    fun finish() {
        println(if (allPassed) "SELFTEST OK" else "SELFTEST FAILED")
    }
}
