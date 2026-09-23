package com.inventory.dumpserver

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch

/**
 * Self-instrumenting UI-dump server.
 *
 * Launched once per harness run with
 * `adb shell am instrument -w -e port 5291 com.inventory.dumpserver/.DumpServerInstrumentation`
 * and reached from the host through `adb forward tcp:5291 tcp:5291`. The line
 * protocol is:
 *
 *   PING  -> "PONG\n"
 *   DUMP  -> 4-byte big-endian length + UTF-8 uiautomator-style XML
 *   QUIT  -> the process exits
 *
 * A single [UiAutomation] instance is kept alive for the whole run, replacing
 * the ~2 s one-shot `uiautomator dump` call with a ~150-250 ms socket read.
 * The XML is byte-compatible with what the harness already parsed: active
 * window only, attribute set class/text/content-desc/checkable/checked/
 * clickable/enabled/focusable/scrollable/selected/bounds.
 */
class DumpServerInstrumentation : Instrumentation() {

    private val latch = CountDownLatch(1)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var quitting = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // A bare Instrumentation is never started by the test framework: AOSP
        // calls onStart() only once start() is invoked, so do it here.
        start()
    }

    override fun onStart() {
        super.onStart()
        val automation = try {
            uiAutomation
        } catch (t: Throwable) {
            null
        }
        val worker = Thread({ serve(automation) }, "dumpserver")
        worker.isDaemon = true
        worker.start()
        latch.await() // keep the instrumentation alive until QUIT
    }

    private fun serve(automation: UiAutomation?) {
        try {
            val socket = ServerSocket(PORT)
            server = socket
            println("DUMPSERVER READY")
            System.out.flush()
            while (!quitting) {
                val client = try {
                    socket.accept()
                } catch (t: Throwable) {
                    if (quitting) break else throw t
                }
                handle(automation, client)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            latch.countDown()
        }
    }

    private fun handle(automation: UiAutomation?, client: Socket) {
        try {
            client.use { socket ->
                val input = BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val output = socket.getOutputStream()
                while (true) {
                    val line = input.readLine() ?: break
                    when (line.trim()) {
                        "PING" -> writeLine(output, "PONG")
                        "DUMP" -> writeFrame(output, dumpXml(automation))
                        "QUIT" -> {
                            quitting = true
                            return
                        }
                        else -> writeLine(output, "ERR")
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            if (quitting) {
                try {
                    server?.close()
                } catch (_: Throwable) {
                }
                latch.countDown()
                System.exit(0)
            }
        }
    }

    /** Two identical serializations 150 ms apart settle a screen; cap 1.6 s. */
    private fun dumpXml(automation: UiAutomation?): String {
        val deadline = System.currentTimeMillis() + STABLE_CAP_MS
        var last = serialize(automation)
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(STABLE_GAP_MS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            val next = serialize(automation)
            if (next == last) return next
            last = next
        }
        return last
    }

    private fun serialize(automation: UiAutomation?): String {
        val root = activeRoot(automation) ?: return "<hierarchy/>\n"
        val sb = StringBuilder()
        sb.append("<hierarchy>")
        appendNode(sb, root)
        sb.append("</hierarchy>")
        return sb.toString()
    }

    /** Root of the active window, retried 3x/100 ms before giving up. */
    private fun activeRoot(automation: UiAutomation?): AccessibilityNodeInfo? {
        if (automation == null) return null
        repeat(3) {
            val root = try {
                automation.rootInActiveWindow
            } catch (t: Throwable) {
                null
            }
            if (root != null) return root
            try {
                Thread.sleep(100)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun appendNode(sb: StringBuilder, node: AccessibilityNodeInfo) {
        sb.append("<node")
        append(sb, "class", node.className)
        append(sb, "text", node.text)
        append(sb, "content-desc", node.contentDescription)
        sb.append(" checkable=\"").append(node.isCheckable).append('"')
        sb.append(" checked=\"").append(node.isChecked).append('"')
        sb.append(" clickable=\"").append(node.isClickable).append('"')
        sb.append(" enabled=\"").append(node.isEnabled).append('"')
        sb.append(" focusable=\"").append(node.isFocusable).append('"')
        sb.append(" scrollable=\"").append(node.isScrollable).append('"')
        sb.append(" selected=\"").append(node.isSelected).append('"')
        val bounds = Rect()
        try {
            node.getBoundsInScreen(bounds)
        } catch (t: Throwable) {
            // keep the default empty rect; the attribute must still be present
        }
        sb.append(" bounds=\"[").append(bounds.left).append(',')
            .append(bounds.top).append("][").append(bounds.right).append(',')
            .append(bounds.bottom).append("]\">")
        val count = try {
            node.childCount
        } catch (t: Throwable) {
            0
        }
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (t: Throwable) {
                null
            } ?: continue // per-child stale guard
            appendNode(sb, child)
        }
        sb.append("</node>")
    }

    private fun append(sb: StringBuilder, name: String, value: CharSequence?) {
        // Omit only truly null attributes: an empty EditText exposes text="" and
        // the harness distinguishes "" (empty field) from a missing attribute.
        if (value == null) return
        sb.append(' ').append(name).append("=\"").append(escape(value.toString())).append('"')
    }

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun writeLine(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun writeFrame(output: OutputStream, xml: String) {
        val bytes = xml.toByteArray(StandardCharsets.UTF_8)
        output.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        output.write(bytes)
        output.flush()
    }

    companion object {
        private const val PORT = 5291
        private const val STABLE_GAP_MS = 150L
        private const val STABLE_CAP_MS = 1600L
    }
}
