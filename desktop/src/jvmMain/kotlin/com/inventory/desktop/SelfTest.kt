package com.inventory.desktop

import com.inventory.desktop.exif.DesktopExifEditor
import java.io.File
import java.util.Base64

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

    // 1. Fresh controller: index the (possibly leftover) folder.
    val controller = DesktopAppController(folder)
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
    val rebuilt = DesktopAppController(folder)
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

    report.finish()
    return report.allPassed
}

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
