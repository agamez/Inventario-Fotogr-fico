package com.inventory.desktop.index

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

/**
 * A recursive [WatchService] over the inventory root: any create/modify
 * (or an overflow of the event queue) schedules [onChange] after the
 * events fall silent for [DEBOUNCE_MILLIS], so a burst of Syncthing file
 * drops converges into a single index rebuild. The watch runs on one
 * daemon thread bound to the application lifecycle via [start]/[stop].
 */
class FolderWatcher(
    private val root: Path,
    private val onChange: () -> Unit,
) {

    @Volatile private var running = false
    private var watchService: WatchService? = null
    private var thread: Thread? = null

    /**
     * Begins watching; a no-op when already started. Blocks until the
     * initial recursive registration is in place, so anything written
     * after this returns is guaranteed to produce events.
     */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        val registered = java.util.concurrent.CountDownLatch(1)
        thread = Thread({ loop(registered) }, "folder-watcher").apply {
            isDaemon = true
            start()
        }
        runCatching { registered.await(10, TimeUnit.SECONDS) }
    }

    /** Stops watching and joins the daemon thread. */
    @Synchronized
    fun stop() {
        running = false
        runCatching { watchService?.close() }
        thread?.let { worker ->
            worker.interrupt()
            runCatching { worker.join(TimeUnit.SECONDS.toMillis(2)) }
        }
        thread = null
    }

    private fun loop(registered: java.util.concurrent.CountDownLatch) {
        val service = try {
            FileSystems.getDefault().newWatchService().also { watchService = it }
        } catch (exception: Exception) {
            registered.countDown()
            return
        }
        val keys = mutableMapOf<WatchKey, Path>()
        registerAll(root, keys, service)
        registered.countDown()
        var pendingSince = 0L
        var changed = false
        while (running) {
            val key = try {
                service.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (exception: InterruptedException) {
                break
            } catch (exception: ClosedWatchServiceException) {
                break
            }
            if (key != null) {
                val dir = keys[key]
                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    if (kind == OVERFLOW) {
                        registerAll(root, keys, service)
                        break
                    }
                    @Suppress("UNCHECKED_CAST")
                    val name = (event as WatchEvent<Path>).context()
                    if (kind == ENTRY_CREATE && dir != null && Files.isDirectory(dir.resolve(name))) {
                        registerAll(root, keys, service)
                    }
                }
                if (!key.reset()) keys.remove(key)
                // Events seen: (re)start the quiet-period timer.
                pendingSince = if (pendingSince == 0L) System.currentTimeMillis() else pendingSince
                changed = true
            }
            if (changed && pendingSince != 0L &&
                System.currentTimeMillis() - pendingSince >= DEBOUNCE_MILLIS
            ) {
                // A full debounce window passed without new events: announce once.
                pendingSince = 0L
                changed = false
                runCatching { onChange() }
            }
        }
        runCatching { service.close() }
    }

    private fun registerAll(
        base: Path,
        keys: MutableMap<WatchKey, Path>,
        service: WatchService,
    ) {
        runCatching {
            Files.walkFileTree(
                base,
                object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (keys.values.none { it == dir }) {
                            runCatching {
                                val key = dir.register(service, ENTRY_CREATE, ENTRY_MODIFY)
                                keys[key] = dir
                            }
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                },
            )
        }
    }

    companion object {
        /** Quiet period before a burst of filesystem events becomes one rebuild. */
        private const val DEBOUNCE_MILLIS = 500L
        private const val POLL_MILLIS = 200L
    }
}
