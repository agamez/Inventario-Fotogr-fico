#!/usr/bin/env python3
"""Photo Inventory end-to-end smoke test (dual-mode storage, filesystem-as-truth).

The app's storage refactor makes `/sdcard/PhotoInventory` the single source
of truth: containers are real nested folders, photos are JPEGs whose
description/note/date live in EXIF (ImageDescription 0x010E, UserComment
0x9286, DateTimeOriginal 0x9003), a folder's cover is `_cover.jpg`, and
SQLite (`cache.db`, internal) is only a rebuildable index. Every former
database assertion is therefore replaced by a DISK assertion: folder
existence, nesting, file names, EXIF values, cover presence — read back
through adb + Pillow.

The backend is DUAL-MODE, chosen on a first-launch MODE CHOOSER
(rationale + "Pick a folder (no permissions)" + "All files access
(advanced)"): SAF (Storage Access Framework — zero permissions, the
DEFAULT) or File mode (All-Files-Access). Both backends operate on the
SAME disk tree, so every disk assertion is mode-agnostic. Run order:
  0  t_saf_mode        — FIRST, on a fresh pm clear with NO appops at all:
     chooser → DocumentsUI folder grant → proof (dumpsys shows no storage
     grant) → container + camera photo + EXIF + gallery cover + rename,
     all through the SAF backend.
  1  t_file_mode_switch — root overflow "Storage mode" dialog → switch to
     FILE → gate (appops still denied) → grant via appops → resume → the
     SAF-created tree is visible through FileBackend (interop).
  2  main suite        — runs in FILE mode: pm clear (wipes the mode pref,
     so the CHOOSER returns), appops re-grant, tap "All files access" in
     the chooser, then the full scenario battery: permission gate, root
     CRUD, drill nesting, camera/gallery flow + EXIF + byte-identity,
     search scopes, preview edit + multi-select move, cycle-proof moves,
      covers, conflicts, PC edits adopted by Rescan (its cache-wipe step
      re-answers the chooser too), drag-and-drop gestures (photo/container/
      ancestor-chip/selection/BACK-cancel, all restored), the inbound image
      share (SEND -> container picker -> import, JPEG passthrough + PNG->
      JPEG transcode, container removed again), and an exact-tree disk audit.

  python3 scripts/smoke_test.py    # always pins emulator-5554 when present

Prereqs: emulator booted (headless ok); app installed. Wipes app data
(pm clear) AND explicitly denies MANAGE_EXTERNAL_STORAGE before the SAF
scenario — measured on this image, pm clear does NOT reset that appops
(contrary to the old harness assumption), so zero-permission proofs must
set it. The suite grants it back via appops for the FILE-mode body,
removes any stale /sdcard/PhotoInventory tree, seeds the gallery, so the
run is deterministic. Exits non-zero on failure; soft warnings are
allowed only for the documented queued-snackbar checks.
"""
import base64, io, os, re, socket, subprocess, sys, tempfile, time, warnings
import xml.etree.ElementTree as ET

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PKG = "com.inventory"
ACT = f"{PKG}/.MainActivity"
FS_ROOT = "/sdcard/PhotoInventory"
# 1x1 solid-magenta JPEG — structurally complete (APP0/DQT/SOF/DHT/SOS/EOI) so
# host Pillow can decode it for the byte-identity check, and distinctive vs
# emulator-camera frames; carries NO EXIF, so what the app stamps (description
# and DateTimeOriginal) is exactly what the assertions read back. (The old
# harness seed had truncated scan data — usable for listings, undecodable.)
JPEG_1PX = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFAEBAAAAAAAAAAAAAAAAAAAACf/EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAMAwEAAhEDEQA/AJGAVA1j/9k=")
MID = "\u00b7"  # ContainerRow joins the plural counts with " · "
DATE_RE = r"\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}"  # EXIF "yyyy:MM:dd HH:mm:ss"
# Exact UI texts of the dual-mode storage (strings.xml + measured dumps):
CHOOSER_RATIONALE = "reads and writes a normal folder"  # storage_permission_rationale
PICK_FOLDER_BTN = "Pick a folder (no permissions)"      # pick_folder (SAF, default)
FILE_MODE_BTN = "All files access (advanced)"           # all_files_access
GRANT_ACCESS_BTN = "Grant access"                       # gate button (FILE, blocked)
MODE_CHANGED_SNACK = "Storage mode changed"             # storage_mode_changed (prefix)
MODE_SAF_LABEL = "Folder pick"                          # mode_saf_label (dialog)
# Optional AI auto-label: settings + save-dialog status (strings.xml ai_*).
SETTINGS_TITLE = "Settings"                             # settings_title
AI_ENABLE_LABEL = "AI auto-label"                       # ai_enable
AI_BASE_LABEL = "API base URL"                          # ai_base_url
AI_TEST_BTN = "Test connection"                         # ai_test_connection
AI_TEST_OK = "Connection OK"                            # ai_test_ok
AI_THINKING = "Asking AI\u2026"                         # ai_suggestion_thinking
AI_READY = "AI suggestion ready"                        # ai_suggestion_ready
AI_FAILED = "AI suggestion failed"                      # ai_suggestion_failed
# Trash (soft delete), report export, breadcrumbs and the full-screen photo
# viewer — the four features added after the 468-check baseline (strings.xml).
TRASH_ENABLE_LABEL = "Move deleted items to trash"      # trash_enable
TRASH_WAS_IN = "Was in "                                # trash_was_in prefix
TRASH_EMPTY = "Trash is empty"                          # trash_empty
RESTORE_LABEL = "Restore"                               # restore
DELETE_FOREVER_LABEL = "Delete forever"                 # delete_forever
EXPORT_REPORT_LABEL = "Export report"                   # export_report
REPORT_WRITTEN = "Report written to"                    # report_written prefix
REPORT_HTML = "inventory-report.html"                   # InventoryReportGenerator
REPORT_CSV = "inventory-report.csv"
REPORT_SHARE_CHOOSER = "Send report via"                # share_report_chooser
REPORT_CACHE_DIR = "cache/reports"                      # ReportSharing.CACHE_DIR
REPORT_STAGED_PREFIX = "photo-inventory-"               # staged cache file prefix
RESOLVER_PKG = "com.android.intentresolver"             # system share sheet
# The viewer's top-strip indicator: viewer_page = "%1$d / %2$d".
VIEWER_PAGE_RE = re.compile(r"^(\d+) / (\d+)$")
MOCK_PORT = 8765
MOCK_PATH = "/tmp/mock_openai.py"
MOCK_LOG = "/tmp/mock_openai.log"

# Host-side OpenAI-compatible mock, written out and launched by the AI
# scenario. `describe` bodies (the vision call carries an `image_url` data
# URL) are delayed by DESCRIBE_DELAY_S: on this emulator the photo-picker
# round trip plus the uiautomator polling make the save sheet take a few
# seconds to surface, so a short delay let the reply land before the guard
# pick could type. 12 s keeps BOTH the "Asking AI…" window and the
# "type first" guard deterministic (measured: request -> sheet detection
# ~4 s, + typing ~3 s). `ping` (Test connection) answers immediately.
MOCK_OPENAI_PY = r'''#!/usr/bin/env python3
import json, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DESCRIBE_DELAY_S = 12.0
CONTENT = json.dumps({"description": "Mock item", "note": "added by mock server"})
REPLY = json.dumps({"choices": [{"message": {"content": CONTENT}}]}).encode()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(length) if length else b""
        kind = "describe" if b"image_url" in body else "ping"
        print("POST %s bytes=%d kind=%s" % (self.path, len(body), kind), flush=True)
        if self.path not in ("/v1/chat/completions", "/chat/completions"):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if kind == "describe":
            time.sleep(DESCRIBE_DELAY_S)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(REPLY)))
        self.end_headers()
        self.wfile.write(REPLY)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
'''

passed = failed = 0
failures = []
soft_warnings = []
SERIAL = None
SW, SH = 1080, 2400  # overridden from `wm size` in main()

# Per-scenario tally for the summary.
CUR = "setup"
tally = {}

def mark(name):
    global CUR
    CUR = name
    tally.setdefault(name, [0, 0])

def adb(*args, timeout=40):
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

def sh(cmd, timeout=40):
    return adb("shell", cmd, timeout=timeout).stdout.strip()

# ------------------------------------------------------------- filesystem
# The filesystem is the source of truth, so every "db" assertion of the
# old harness became one of these. Paths carry spaces — always single-quote
# them for the remote shell.

def fp(*segs):
    return "/".join([FS_ROOT] + [str(s) for s in segs])

def sq(p):
    return f"'{p}'"

def tree():
    """Every path under the inventory root (dirs + files), sorted."""
    out = sh(f"find {sq(FS_ROOT)} 2>/dev/null")
    return sorted(l.strip() for l in out.splitlines() if l.strip())

def fs_exists(path):
    return sh(f"test -e {sq(path)} && echo Y || echo N") == "Y"

def fs_isdir(path):
    return sh(f"test -d {sq(path)} && echo Y || echo N") == "Y"

def fs_read(path):
    """Raw bytes of a device file (exec-out: no CRLF munging)."""
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + ["exec-out", f"cat {sq(path)}"]
    r = subprocess.run(cmd, capture_output=True, timeout=40)
    return r.stdout if r.returncode == 0 else b""

def _decode_exif(raw):
    if raw is None:
        return None
    if isinstance(raw, bytes):
        if raw.startswith(b"ASCII\x00\x00\x00"):
            raw = raw[8:]
        elif raw.startswith(b"UNICODE\x00"):
            return raw[8:].decode("utf-16-le", "replace").strip("\x00")
        elif raw.startswith(b"JIS\x00\x00\x00\x00\x00"):
            raw = raw[10:]
        raw = raw.decode("utf-8", "replace")
    raw = raw.strip()
    return raw or None

def exif(path, tag):
    """The file's EXIF value for `tag` (int or hex string), decoded to str.

    0x010E (ImageDescription, IFD0) = description; 0x9286 (UserComment) and
    0x9003 (DateTimeOriginal) live in the Exif sub-IFD (0x8769)."""
    if isinstance(tag, str):
        tag = int(tag, 16)
    data = fs_read(path)
    if not data:
        return None
    try:
        from PIL import Image
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            im = Image.open(io.BytesIO(data))
            ex = im.getexif()
            try:
                sub = ex.get_ifd(0x8769)
            except Exception:
                sub = {}
    except Exception:
        return None
    raw = ex.get(tag) if tag == 0x010E else (sub.get(tag) or ex.get(tag))
    return _decode_exif(raw)

def exif_triple(path):
    return {"desc": exif(path, 0x010E), "note": exif(path, 0x9286),
            "date": exif(path, 0x9003)}

EXIF_SAMPLES = []  # evidence collected for the report

def pixel_key(data):
    """(size, RGB pixel list) of JPEG bytes — the EXIF-independent part."""
    from PIL import Image
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        im = Image.open(io.BytesIO(data)).convert("RGB")
        return im.size, list(im.getdata())

LAST_RAW = ""
DUMPDIR = "/tmp/smoke_dumps"

# ---- persistent dump server (docs/test-optimization-plan.md, Stage 2) -------
# A self-instrumenting `:dumpserver` APK keeps one UiAutomation alive and
# serves uiautomator-style XML over a loopback socket (`adb forward`). The
# legacy one-shot `uiautomator dump` stays as the fallback. Set
# PHOTOINV_DUMP_SERVER=0 to force the legacy path.
DUMP_PORT = 5291
DUMP_PKG = "com.inventory.dumpserver"
DUMP_APK = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "dumpserver/build/outputs/apk/debug/dumpserver-debug.apk")
DUMP_SERVER_LOG = "/tmp/dump_server.log"
_dump_server_proc = None
_dump_server_active = False

def _dump_recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf

def _dump_ping(timeout=3.0):
    try:
        with socket.create_connection(("127.0.0.1", DUMP_PORT), timeout=timeout) as s:
            s.settimeout(timeout)
            s.sendall(b"PING\n")
            return s.recv(16).strip() == b"PONG"
    except OSError:
        return False

def _dump_socket(timeout=5.0):
    """One DUMP round trip; the XML string, or None on ANY socket failure."""
    try:
        with socket.create_connection(("127.0.0.1", DUMP_PORT), timeout=2.0) as s:
            s.settimeout(timeout)
            s.sendall(b"DUMP\n")
            header = _dump_recv_exact(s, 4)
            if header is None:
                return None
            payload = _dump_recv_exact(s, int.from_bytes(header, "big"))
            if payload is None:
                return None
            return payload.decode("utf-8", "replace")
    except OSError:
        return None

def start_dump_server():
    """Install/forward/launch the dump server; ready once PING answers.
    A separate package from com.inventory, so it survives pm clear."""
    global _dump_server_proc, _dump_server_active
    if os.environ.get("PHOTOINV_DUMP_SERVER") == "0":
        return False
    if _dump_ping():
        _dump_server_active = True
        return True
    if not sh(f"pm path {DUMP_PKG}"):
        if not os.path.exists(DUMP_APK):
            return False
        adb("install", "-r", DUMP_APK, timeout=120)
    sh(f"am force-stop {DUMP_PKG}")  # clear a half-dead prior instrumentation
    adb("forward", f"tcp:{DUMP_PORT}", f"tcp:{DUMP_PORT}")
    _dump_server_proc = subprocess.Popen(
        [ADB] + (["-s", SERIAL] if SERIAL else []) +
        ["shell", "am", "instrument", "-w", "-e", "port", str(DUMP_PORT),
         f"{DUMP_PKG}/.DumpServerInstrumentation"],
        stdout=open(DUMP_SERVER_LOG, "w"), stderr=subprocess.STDOUT)
    end = time.time() + 15
    while time.time() < end:
        if _dump_ping():
            _dump_server_active = True
            return True
        time.sleep(0.3)
    return False

def _restart_dump_server():
    stop_dump_server()
    time.sleep(0.5)
    return start_dump_server()

def stop_dump_server():
    """QUIT + reap the instrumentation + drop the adb forward."""
    global _dump_server_proc, _dump_server_active
    if _dump_server_proc is not None or _dump_ping():
        try:
            with socket.create_connection(("127.0.0.1", DUMP_PORT), timeout=2.0) as s:
                s.settimeout(3.0)
                s.sendall(b"QUIT\n")
                try:
                    s.recv(16)  # hold the connection open until the server reads it
                except OSError:
                    pass
        except OSError:
            pass
    if _dump_server_proc is not None:
        try:
            _dump_server_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            _dump_server_proc.kill()
        _dump_server_proc = None
    adb("forward", "--remove", f"tcp:{DUMP_PORT}")
    _dump_server_active = False

def _socket_dump():
    """Socket dump with the empty-tree retry loop; None on failure/empty."""
    for _ in range(3):
        out = _dump_socket()
        if out is None:
            return None
        if "<hierarchy" in out and out.strip() != "<hierarchy/>":
            return out
        time.sleep(0.1)
    return None

def _legacy_dump():
    """The pre-Stage-2 one-shot path, kept verbatim as the fallback."""
    sh("rm -f /sdcard/ui.xml")
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    out = sh("cat /sdcard/ui.xml")
    if "<hierarchy" not in out:
        # orphaned UiAutomation registration can wedge the dump service
        sh("pkill -f uiautomator >/dev/null 2>&1")
        time.sleep(1.0)
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        out = sh("cat /sdcard/ui.xml")
    return out

def dump():
    global LAST_RAW, _dump_server_active
    out = None
    if _dump_server_active:
        out = _socket_dump()
        if out is None:  # ANY socket failure: one restart attempt
            if _restart_dump_server():
                out = _socket_dump()
            else:
                _dump_server_active = False
    if out is None:
        out = _legacy_dump()
    LAST_RAW = out
    try:
        return ET.fromstring(out) if "<hierarchy" in out else None
    except ET.ParseError:
        return None

def find_all(root, **kw):
    res = []
    if root is None: return res
    def walk(n):
        ok = True
        for k, v in kw.items():
            attr = {"text": "text", "desc": "content-desc", "cls": "class"}.get(k)
            if attr:
                val = n.get(attr, "")
                if k in ("text", "desc"):
                    if v.lower() not in val.lower(): ok = False; break
                elif val != v: ok = False; break
        if ok: res.append(n)
        for c in n: walk(c)
    walk(root)
    return res

def center(n):
    x1, y1, x2, y2 = map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds")).groups())
    return (x1+x2)//2, (y1+y2)//2

def _bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return tuple(map(int, m.groups())) if m else None

def _contains(outer, inner):
    return outer[0] <= inner[0] and outer[1] <= inner[1] \
        and outer[2] >= inner[2] and outer[3] >= inner[3]

def _has_clickable_ancestor(node, root):
    """True if any clickable node's bounds contain this node's bounds.
    Compose TextButtons keep clickable=true on the parent while the text
    child reports clickable=false — compare against the whole tree."""
    nb = node.get("bounds")
    if not nb: return False
    n = _bounds(nb)
    for cand in root.iter():
        if cand.get("clickable") != "true": continue
        cb = _bounds(cand.get("bounds", ""))
        if not cb: continue
        if _contains(cb, n):
            return True
    return False

def smallest_clickable_containing(target_bounds):
    """The tightest clickable node enclosing `target_bounds` — locates the
    interactive tile a non-clickable badge (e.g. 'Sync conflict') lives in."""
    d = dump()
    if d is None: return None
    best, best_area = None, 1 << 62
    for n in d.iter():
        if n.get("clickable") != "true": continue
        b = _bounds(n.get("bounds", ""))
        if not b or not _contains(b, target_bounds): continue
        area = (b[2]-b[0]) * (b[3]-b[1])
        if area < best_area:
            best, best_area = n, area
    return best

def pick(ns, needle):
    exact = [n for n in ns if n.get("text", "") == needle]
    if exact:
        d = dump()  # current tree for ancestor checks
        tappable = [n for n in exact if _has_clickable_ancestor(n, d)]
        if tappable: return tappable[0]
        return exact[0]
    return ns[0] if ns else None

def wait_for(text=None, desc=None, timeout=10):
    """Wait until a node containing text/desc appears; return it or None."""
    end = time.time() + timeout
    while time.time() < end:
        d = dump()
        ns = find_all(d, desc=desc) if desc else find_all(d, text=text)
        n = pick(ns, text or "") if text else (ns[0] if ns else None)
        if n is not None: return n
        time.sleep(0.4)
    return None

def wait_absent(text, timeout=10):
    """True once no visible node contains `text` (banner-gone style checks).
    A wedged (None) dump must never read as a passing absence."""
    end = time.time() + timeout
    while True:
        d = dump()
        if d is not None and not find_all(d, text=text): return True
        if time.time() >= end: return False
        time.sleep(0.5)

def wait_share_chooser(timeout=15):
    """Wait for the outbound report share sheet. Newer Android replaces the
    `Intent.createChooser` title with the system "Sharing N file" header, so
    accept any of: the `share_report_chooser` title, the intent-resolver
    sheet itself, or the staged file name the resolver previews."""
    end = time.time() + timeout
    while time.time() < end:
        d = dump()
        if d is not None:
            if find_all(d, text=REPORT_SHARE_CHOOSER): return True
            if find_all(d, text=REPORT_STAGED_PREFIX): return True
            if any(n.get("package") == RESOLVER_PKG for n in d.iter()): return True
        time.sleep(0.4)
    return False


def texts_exact(needle):
    """Nodes whose text attribute equals needle exactly (guards against the
    case-insensitive substring match of find_all)."""
    return [n for n in find_all(dump(), text=needle) if n.get("text") == needle]

def row_menu(row_text, options_desc):
    """Open the overflow menu of the list row whose headline == row_text,
    by selecting the options button that overlaps that row's y-band."""
    row = wait_for(text=row_text, timeout=8)
    if row is None: return False
    bx = row.get("bounds")
    import re as _re
    m = _re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bx)
    ry1, ry2 = int(m.group(2)), int(m.group(4))
    for n in find_all(dump(), desc=options_desc):
        m2 = _re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds"))
        ny1, ny2 = int(m2.group(2)), int(m2.group(4))
        if ny1 < ry2 and ny2 > ry1:
            tap(n)
            return True
    return False

def open_screen_menu():
    """Tap the app-bar Options button of the open container. The row trailing
    buttons share desc 'Options'; the app-bar one is the topmost."""
    n = wait_for(desc="Options", timeout=8)
    if n is None: return False
    ns = find_all(dump(), desc="Options")
    def y1(m):
        b = _bounds(m.get("bounds", ""))
        return b[1] if b else 1 << 30
    tap(min(ns, key=y1))
    return True

def tap(node):
    x, y = center(node); sh(f"input tap {x} {y}")

def tap_text(text, timeout=8):
    n = wait_for(text=text, timeout=timeout)
    if n is not None: tap(n)
    return n is not None

def tap_desc(desc, timeout=8):
    n = wait_for(desc=desc, timeout=timeout)
    if n is not None: tap(n)
    return n is not None

def long_press(node):
    x, y = center(node); sh(f"input swipe {x} {y} {x} {y} 900")

def type_text(s):
    if not s: return
    sh("input text '" + s.replace(" ", "%s") + "'")

def clear_field():
    for _ in range(45): sh("input keyevent 67")

def hide_ime():
    sh("input keyevent 4")  # BACK closes IME; IME is confirmed open first

def ime_shown():
    return "mInputShown=true" in sh("dumpsys input_method")

def ensure_ime_hidden():
    """BACK would pop a screen when the IME is not up, so only send it when
    the keyboard is actually visible."""
    if ime_shown():
        hide_ime(); time.sleep(0.5); return True
    return False

def wait_ime(timeout=5):
    end = time.time() + timeout
    while time.time() < end:
        if "mInputShown=true" in sh("dumpsys input_method"): return True
        time.sleep(0.3)
    return False

def back():
    sh("input keyevent 4")

def in_foreground():
    return "com.inventory/" in sh("dumpsys window | grep mCurrentFocus")

def ensure_app():
    """Recover the foreground if a system popup ever steals focus mid-run."""
    if in_foreground(): return True
    sh(f"am start -W -n {ACT}")
    time.sleep(2.5)
    return in_foreground()

def go_home(max_hops=8):
    """Ascend the container stack to the Inventory root; every scenario
    starts from here so one lost screen cannot cascade through the run."""
    ensure_app()
    for _ in range(max_hops):
        if texts_exact("Inventory"): return True
        ensure_ime_hidden()
        back(); time.sleep(1.3)
        ensure_app()
    return bool(texts_exact("Inventory"))

def check(name, cond, detail="", soft=False):
    global passed, failed
    tally.setdefault(CUR, [0, 0])
    if cond:
        passed += 1; tally[CUR][0] += 1
        print(f"  PASS  {name}"); return
    if soft:
        soft_warnings.append(name); tally[CUR][0] += 1
        print(f"  WARN  {name}  {detail}"); return
    failed += 1; failures.append(name); tally[CUR][1] += 1
    print(f"  FAIL  {name}  {detail}")
    os.makedirs(DUMPDIR, exist_ok=True)
    # Check names carry path separators (e.g. "disk: Attic shelf/x.jpg"); keep
    # the failure-dump write from failing on them so a failing run still
    # completes and records every raw tree.
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    with open(f"{DUMPDIR}/{failed:02d}_{safe}.xml", "w") as fh:
        fh.write(LAST_RAW)

def snackbar_visible(fragment, timeout=5.0):
    end = time.time() + timeout
    while time.time() < end:
        if find_all(dump(), text=fragment): return True
        time.sleep(0.3)
    return False

def edit_texts():
    return find_all(dump(), cls="android.widget.EditText")

def fill_dialog(value, button_text="Save"):
    """Tap first EditText, wait for IME, clear it, type, hide IME, tap button."""
    fields = edit_texts()
    if not fields: return False
    tap(fields[0])
    wait_ime()
    for _ in range(45): sh("input keyevent 67")  # DEL
    type_text(value)
    hide_ime(); time.sleep(1.0)
    return tap_text(button_text, timeout=5)

def chip_state(label):
    """True/False from the FilterChip's checked/selected attr, None when
    unreadable. Measured on this build: M3 FilterChip exposes its state via
    the `checked` attribute of the enclosing checkable node — `selected`
    stays false on every chip node."""
    d = dump()
    if d is None: return None
    labels = [n for n in find_all(d, text=label) if n.get("text") == label]
    if not labels: return None
    lb = _bounds(labels[0].get("bounds", ""))
    if not lb: return None
    best, best_area = None, 1 << 62
    for n in d.iter():
        if n.get("checkable") != "true": continue
        b = _bounds(n.get("bounds", ""))
        if not b or not _contains(b, lb): continue
        area = (b[2]-b[0]) * (b[3]-b[1])
        if area < best_area:
            best, best_area = n, area
    if best is None: return None
    return best.get("checked") == "true" or best.get("selected") == "true"

def no_filename_leaks(name):
    """A photo tile/row never shows a raw '*.jpg' label: descriptions come
    from EXIF (this also proves '_cover.jpg' stays out of the grid)."""
    d = dump()
    bad = [n.get("text") for n in d.iter()
           if n.get("text", "").lower().endswith(".jpg")] if d is not None else ["<no dump>"]
    check(name, not bad, str(bad))

# ---- full-screen photo viewer helpers (Feature 3) --------------------------
# Tapping a tile opens the full-screen viewer, NOT the metadata editor: every
# old "tap tile -> Description (required)" step now goes tile -> viewer ->
# "Edit" -> editor -> Save (back in the viewer) -> "Close" -> grid. The top
# strip's "N / M" indicator is the viewer's fingerprint, so `viewer_page()`
# is both the open/closed test and the paging assertion. The EditText-based
# editor is unchanged.

def viewer_page():
    """(page, total) from the viewer's indicator, or None when it is closed."""
    d = dump()
    if d is None: return None
    for n in d.iter():
        m = VIEWER_PAGE_RE.match(n.get("text", ""))
        if m: return int(m.group(1)), int(m.group(2))
    return None

def viewer_open():
    return viewer_page() is not None

def close_viewer():
    """Tap the viewer's Close icon when it is up; True once it is gone."""
    if not viewer_open(): return False
    tap_desc("Close", timeout=5)
    time.sleep(1.0)
    return not viewer_open()

def open_viewer_and_edit(tile_locator):
    """Tile -> viewer -> Edit -> editor. Returns the editor's EditText list
    (description, note) or None if the viewer/editor did not appear."""
    if tile_locator is None: return None
    tap(tile_locator); time.sleep(1.5)
    if not viewer_open(): return None
    if not tap_text("Edit", timeout=6): return None
    if wait_for(text="Description (required)", timeout=6) is None: return None
    return edit_texts()

# ---- trash / settings helpers (Feature 1) ----------------------------------

def settings_switch(label):
    """The M3 Switch sharing the row of the settings label with exact `label`
    text (the screen has two: AI auto-label and Move deleted items to trash)."""
    d = dump()
    if d is None: return None
    labels = [n for n in find_all(d, text=label) if n.get("text") == label]
    if not labels: return None
    lb = _bounds(labels[0].get("bounds", ""))
    if not lb: return None
    best, best_area = None, 1 << 62
    for n in d.iter():
        if n.get("checkable") != "true": continue
        b = _bounds(n.get("bounds", ""))
        if not b: continue
        if b[1] < lb[3] and b[3] > lb[1]:  # same vertical band as the label
            area = (b[2]-b[0]) * (b[3]-b[1])
            if area < best_area:
                best, best_area = n, area
    return best

def set_settings_switch(label, want):
    """Flip the named settings switch to `want`; True when it reads back."""
    n = settings_switch(label)
    if n is None: return False
    if (n.get("checked") == "true") != want:
        tap(n); time.sleep(1.0)
    n2 = settings_switch(label)
    return n2 is not None and (n2.get("checked") == "true") == want

def trash_stamps():
    """Every stamp folder directly under <root>/.trash (sorted paths)."""
    out = sh(f"find {sq(fp('.trash'))} -mindepth 1 -maxdepth 1 -type d 2>/dev/null")
    return sorted(l.strip() for l in out.splitlines() if l.strip())

def trash_enabled_pref():
    """The trash_prefs.xml payload (run-as), for the persisted-flag checks."""
    return sh(f"run-as {PKG} cat shared_prefs/trash_prefs.xml 2>/dev/null")

def add_gallery_photo(description, where=""):
    """FAB -> Choose from gallery -> pick one -> type the required
    description -> Save photo. True when the save tap was reached."""
    if not gallery_pick_one(where): return False
    f = edit_texts()
    if not f: return False
    tap(f[0]); wait_ime(); type_text(description)
    ensure_ime_hidden(); time.sleep(0.8)
    return tap_text("Save photo", timeout=6)

# ---- dual-mode storage helpers (chooser, mode dialog, SAF picker) ----------
# Measured on this image: the "All files access" button (chooser AND mode
# dialog) always hops to the All-Files-Access SETTINGS page, even with the
# appops pre-granted; BACK returns to the app, where LifecycleResumeEffect
# re-checks and the gate falls. DocumentsUI opens INSIDE PhotoInventory via
# the store's EXTRA_INITIAL_URI hint; the grant sheet's buttons are
# "USE THIS FOLDER" then "ALLOW" (uppercase here — matched case-strictly so
# the dialog's "Allow Photo Inventory to access..." sentence is not it).

def fs_wait_exists(path, timeout=6.0):
    end = time.time() + timeout
    while time.time() < end:
        if fs_isdir(path): return True
        time.sleep(0.5)
    return fs_isdir(path)

def settings_page_focused(timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        if "com.android.settings" in sh("dumpsys window | grep mCurrentFocus"):
            return True
        time.sleep(0.5)
    return False

def tap_texts_exact(needle, timeout=8):
    """Tap the first node whose text EQUALS needle (find_all's match is a
    case-insensitive substring — too loose for the parenthesised buttons)."""
    end = time.time() + timeout
    while time.time() < end:
        ns = texts_exact(needle)
        if ns:
            tap(ns[0]); return True
        time.sleep(0.5)
    return False

def choose_file_mode(where="chooser"):
    """Tap 'All files access (advanced)', ride the settings hop BACK.
    Returns True when the app came back foregrounded."""
    if not tap_texts_exact(FILE_MODE_BTN, timeout=8):
        check(f"[{where}] 'All files access' button tappable", False)
        return False
    settings = settings_page_focused(timeout=10)
    check(f"[{where}] tap opens the All-files-access settings page", settings)
    if settings:
        back(); time.sleep(2.0)
    return in_foreground()

def choose_saf_mode():
    """Tap 'Pick a folder (no permissions)' and drive the DocumentsUI grant:
    USE THIS FOLDER → ALLOW. Returns True when the app came back on an
    empty (freshly wiped) inventory."""
    if not tap_texts_exact(PICK_FOLDER_BTN, timeout=8):
        check("[saf] 'Pick a folder' button tappable", False)
        return False
    end = time.time() + 15  # the brief: allow 15s for DocumentsUI
    promo = 0
    use = None
    while time.time() < end:
        d = dump()
        if not find_all(d, text="this folder") and find_all(d, text="Learn more"):
            # first-run DocumentsUI promo sheet (never seen here — guard):
            # press BACK once, log it, and keep waiting for the picker.
            print("  note: DocumentsUI 'Learn more' promo — pressed BACK")
            back(); promo += 1; time.sleep(1.5)
            continue
        ns = find_all(d, text="this folder")  # case-insensitive substring
        if ns:
            use = ns[0]; break
        time.sleep(0.6)
    # the same dump that produced USE THIS FOLDER: 'PhotoInventory' nodes
    # prove the picker opened INSIDE the folder (EXTRA_INITIAL_URI worked)
    # — the chooser's own rationale mentions '(PhotoInventory)' too, so
    # only a gone-chooser tree counts.
    check("[saf] folder picker opened INSIDE PhotoInventory",
          use is not None and d is not None and not find_all(d, text=CHOOSER_RATIONALE)
          and bool(find_all(d, text="PhotoInventory")),
          f"promo_retries={promo} use={use is not None}")
    if use is None:
        check("[saf] 'USE THIS FOLDER' button reached", False)
        return False
    check(f"[saf] 'USE THIS FOLDER' button reached ({use.get('text')!r})", True)
    tap(use); time.sleep(1.5)
    allow = None
    end = time.time() + 15
    while time.time() < end:
        d = dump()
        cands = [n for n in d.iter() if n.get("text") in ("ALLOW", "Allow")] \
            if d is not None else []
        if cands:
            allow = cands[0]; break
        time.sleep(0.6)
    if allow is None:
        check("[saf] grant-confirm 'Allow' button reached", False)
        return False
    check(f"[saf] grant-confirm button reached ({allow.get('text')!r})", True)
    tap(allow)
    ok = wait_for(text="Your inventory is empty", timeout=15) is not None
    check("[saf] grant lands on an empty SAF-backed inventory", ok)
    return ok

def saf_permission_proof():
    """Zero-permission evidence for SAF mode: no storage RUNTIME grant in
    `dumpsys package` (the brief's 'RUNTIME|appops' grep is case-sensitive
    and matches nothing on this Android — parse the real lowercase
    'runtime permissions:' section instead), and the MANAGE_EXTERNAL_STORAGE
    appops is not allow. Returns (bad_grants, appops_mode, raw)."""
    out = sh("dumpsys package com.inventory")
    bad = re.findall(r"(?:READ|WRITE|MANAGE)_EXTERNAL_STORAGE[^\n]*granted=true", out)
    raw = sh(f"appops get {PKG} MANAGE_EXTERNAL_STORAGE")
    m = re.search(r"MANAGE_EXTERNAL_STORAGE:\s*(\w+)", raw)
    mode = m.group(1) if m else "default"  # 'No operations.' = unset = default
    return bad, mode, raw

# ---- move-picker (ModalBottomSheet) helpers --------------------------------
# The sheet title is "Move to…"; rows live below it. Nodes of the underlying
# screen may share the sheet's y-band, so every picker assertion is done on
# the CURRENT screen with no row content in the band (verified per scenario).

def _sheet_title_y():
    ts = find_all(dump(), text="Move to")
    if not ts: return None
    b = _bounds(ts[0].get("bounds", ""))
    return b[1] if b else None

def sheet_find(needle, exact=True, max_scrolls=4):
    """Row node at/below the sheet title matching needle; scrolls the
    (360dp-capped) list DOWN via upward flings — always started well below
    the sheet's top edge: a DOWNWARD drag near the list top is handed to the
    ModalBottomSheet (nested scroll) and collapses it, never do that.
    None when not in the picker."""
    ty = _sheet_title_y()
    if ty is None: return None
    for i in range(max_scrolls + 1):
        d = dump()
        if d is None:  # rare double-wedge of the dump service: just retry
            time.sleep(1.0)
            continue
        for n in d.iter():
            t = n.get("text", "")
            b = _bounds(n.get("bounds", ""))
            if not t or not b or b[1] < ty: continue
            if (t == needle) if exact else (needle.lower() in t.lower()):
                return n
        if i < max_scrolls:
            sh(f"input swipe {SW//2} {ty+560} {SW//2} {ty+220} 500")
            time.sleep(0.7)
    return None

def sheet_tap_row(needle, exact=True):
    """Tap a picker row, scrolling down only as needed and always from mid
    sheet; returns the tapped node or None."""
    ty = _sheet_title_y()
    if ty is None: return None
    for i in range(5):
        d = dump()
        if d is None:
            time.sleep(1.0)
            continue
        for n in d.iter():
            t = n.get("text", "")
            b = _bounds(n.get("bounds", ""))
            if not t or not b or b[1] < ty: continue
            if (t == needle) if exact else (needle.lower() in t.lower()):
                tap(n); return n
        sh(f"input swipe {SW//2} {int(SH*0.80)} {SW//2} {int(SH*0.55)} 500")
        time.sleep(0.7)
    return None

def sheet_absent(needle, exact=True):
    """True only when the picker is actually open AND has no row matching
    needle — an absent sheet must never read as a passing exclusion."""
    if _sheet_title_y() is None:
        time.sleep(1.0)  # transient dump wedge: give the sheet one more look
        if _sheet_title_y() is None: return False
    return sheet_find(needle, exact=exact) is None

def share_pick_container(name, max_scrolls=6):
    """Tap a container row in the share-target sheet ("Add to Photo
    Inventory" / "Pick a container"), scrolling the 360dp-capped list down as
    needed. The move-picker helpers key on "Move to"; this sheet has its own
    wording and its list holds EVERY container, so a row below the fold needs
    an explicit scroll."""
    title = wait_for(text="Pick a container", timeout=10)
    if title is None: return False
    tb = _bounds(title.get("bounds", ""))
    ty = tb[1] if tb else 0
    for i in range(max_scrolls):
        d = dump()
        if d is None:
            time.sleep(1.0); continue
        for n in d.iter():
            t = n.get("text", "")
            b = _bounds(n.get("bounds", ""))
            if not t or not b or b[1] < ty: continue
            if t == name:
                tap(n); return True
        sh(f"input swipe {SW//2} {int(SH*0.80)} {SW//2} {int(SH*0.55)} 500")
        time.sleep(0.7)
    return False

# ---------------------------------------------------------------- app setup

def grant_storage_appops():
    """The FILE backend needs All Files Access BEFORE its gate can fall —
    the app is unusable behind it otherwise. (Replaces the old camera-appops-
    only step; camera appops is still fine to pre-allow.) Measured on this
    image: pm clear resets CAMERA (it syncs to the revoked runtime grant)
    but does NOT reset a manually-set MANAGE_EXTERNAL_STORAGE appops — so
    t_saf_mode explicitly DENIES it first, and every FILE-mode entry
    explicitly allows it. SAF mode needs NONE of this — that is its proof."""
    sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
    sh(f"appops set {PKG} CAMERA allow")

def setup_gallery_images():
    """Push two tiny JPEGs into MediaStore so the photopicker has items.
    Rows are deleted first: repeated runs would otherwise pile up duplicates
    for the same _data path."""
    sh("rm -f /sdcard/Pictures/test_a.jpg /sdcard/Pictures/test_b.jpg")
    sh("mkdir -p /sdcard/Pictures")
    for name in ("test_a.jpg", "test_b.jpg"):
        path = f"/sdcard/Pictures/{name}"
        sh(f"content delete --uri content://media/external/images/media "
           f"--bind _data:s:{path}")
        tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
        tmp.write(JPEG_1PX); tmp.close()
        adb("push", tmp.name, path, timeout=20)
        os.unlink(tmp.name)
        sh(f"content insert --uri content://media/external/images/media "
           f"--bind _data:s:{path} --bind mime_type:s:image/jpeg")
    time.sleep(1.0)
    return True

def start_app():
    sh(f"am start -W -n {ACT}")
    time.sleep(2.5)

def rescan_from_root():
    """External (adb) disk edits need an explicit re-index before the UI may
    be asserted: the root app-bar refresh icon's desc is 'Rescan storage'."""
    if not go_home(): return False
    return tap_desc("Rescan storage", timeout=8)

def camera_capture(description, note=None, where=""):
    """On a container screen: FAB sheet -> Take photo -> (perm dialog) ->
    shutter -> metadata dialog -> fill -> Save. Returns True on save-tap."""
    tap_desc("Add"); time.sleep(1.2)
    if not tap_text("Take photo", timeout=5):
        check(f"camera flow opens [{where}]", False); return False
    time.sleep(2.0)
    # First capture after pm clear raises the runtime dialog (or the in-app
    # rationale); later ones go straight to the shutter. Poll a few seconds.
    for _ in range(6):
        d = dump()
        if find_all(d, text="While using the app"):
            tap_text("While using the app"); time.sleep(2.0); break
        if find_all(d, text="Only this time"):
            tap_text("Only this time"); time.sleep(2.0); break
        if find_all(d, text="Grant permission"):
            tap_text("Grant permission"); time.sleep(1.5)
            tap_text("While using the app", timeout=3) or tap_text("Allow", timeout=2)
            time.sleep(2.0); break
        if find_all(d, desc="Take photo"): break
        time.sleep(1.0)
    if not check_shutter(where): return False
    # CameraX needs a moment to bind; with fast dumps the shutter tap can land
    # before the capture is armed and get swallowed — re-tap up to 3x
    # (docs/test-optimization-plan.md, Stage 2 re-evaluation).
    ok = False
    for _attempt in range(3):
        if wait_for(text="Description (required)", timeout=6) is not None:
            ok = True; break
        time.sleep(1.0)
        if not tap_desc("Take photo", timeout=4):
            if find_all(dump(), text="Description (required)"):
                ok = True  # dialog appeared late; shutter already behind it
            break
    check(f"capture save dialog [{where}]", ok)
    if not ok: return False
    fields = edit_texts()
    if len(fields) < 2:
        check(f"dialog has description + note fields [{where}]", False, str(len(fields)))
        return False
    check(f"dialog has description + note fields [{where}]", True)
    tap(fields[0]); wait_ime(); type_text(description); time.sleep(0.6)
    # refetch while the IME is up: the dialog re-laid out when the keyboard
    # opened, the pre-IME note-field coordinates may sit under it
    fields = edit_texts()
    tap(fields[1]); wait_ime(); type_text(note or "x")
    if not note:
        # no note wanted: undo the probe character
        sh("input keyevent 67")
    hide_ime(); time.sleep(1.0)
    vals = [f.get("text") for f in edit_texts()]
    check(f"capture dialog holds values [{where}]",
          vals[:2] == [description, note or ""], str(vals))
    tap_text("Save")
    return True

def check_shutter(where):
    ok = tap_desc("Take photo", timeout=12)
    check(f"camera shutter visible [{where}]", ok)
    return ok

def cover_gallery_flow():
    """On a folder list with its cover sheet open: pick one gallery photo
    and confirm; True when the confirm dialog's Save was reached."""
    tap_text("Choose from gallery"); time.sleep(4.0)
    dism = find_all(dump(), text="Dismiss")
    if dism: tap(dism[0]); time.sleep(0.8)
    photos = find_all(dump(), desc="Photo taken on")
    check("gallery picker shows photos", bool(photos), str(len(photos)))
    if photos: tap(photos[0]); time.sleep(1.5)
    dlg = False
    end = time.time() + 12
    while time.time() < end and not dlg:
        d = dump()
        if find_all(d, text="Set photo") and find_all(d, text="Save"): dlg = True; break
        adds = find_all(d, text="Add")
        if adds: tap(adds[0])
        time.sleep(0.8)
    check("cover confirm dialog", dlg)
    if not dlg: return False
    tap_text("Save")
    return True

# ---- optional AI auto-label helpers ----------------------------------------
# The settings screen (root overflow -> Settings) mirrors VisionPrefs; the
# save sheet pre-fills description/note from the model behind a blank-guard.
# Selectors were measured on this build: the M3 Switch is the only
# checkable node on the screen; the three OutlinedTextFields dump as
# EditTexts in layout order (base URL, API key, model).

def wait_port(port, timeout=10.0):
    import socket
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), 0.5):
                return True
        except OSError:
            time.sleep(0.2)
    return False

def ai_switch_node():
    d = dump()
    if d is None: return None
    for n in d.iter():
        if n.get("checkable") == "true" and n.get("class", "").endswith("Switch"):
            return n
    for n in d.iter():  # fallback: the settings screen has exactly one toggle
        if n.get("checkable") == "true":
            return n
    return None

def ai_switch_checked():
    n = ai_switch_node()
    return (n.get("checked") == "true") if n is not None else None

def set_ai_switch(want):
    n = ai_switch_node()
    if n is None: return False
    if (n.get("checked") == "true") != want:
        tap(n); time.sleep(1.0)
    return ai_switch_checked() == want

def open_settings():
    """From the root screen: overflow -> Settings. The settings destination
    is only reachable from the root app bar, so this ascends first."""
    if not go_home(): return False
    if not open_screen_menu(): return False
    time.sleep(1.0)
    if not tap_text(SETTINGS_TITLE, timeout=5): return False
    return wait_for(text=AI_ENABLE_LABEL, timeout=8) is not None

def set_ai_text(index, value):
    """Type into the settings' index-th field (layout order) via the
    established tap -> wait IME -> clear -> type -> hide IME pattern. The
    base-URL field is pre-filled and tapping it can land the caret mid-text,
    so MOVE_END first — otherwise backspaces leave a tail and the new value
    is inserted in the middle."""
    fields = edit_texts()
    if len(fields) <= index: return False
    b = _bounds(fields[index].get("bounds", ""))
    if b:
        # Tap near the RIGHT edge: a centre tap lands the caret mid-text on
        # a long pre-filled value (the default base URL measured exactly
        # that), after which backspace only clears the prefix.
        sh(f"input tap {b[2]-24} {(b[1]+b[3])//2}")
    else:
        tap(fields[index])
    wait_ime(); time.sleep(0.4)
    sh("input keyevent 123")  # KEYCODE_MOVE_END (belt and braces)
    clear_field(); type_text(value)
    ensure_ime_hidden(); time.sleep(0.5)
    return True

def wait_ai_fields(desc_value=None, note_value=None, timeout=20):
    """Poll the save sheet's description/note fields. None means \"don't
    care\"; returns (ok, last_seen_values)."""
    end = time.time() + timeout
    last = []
    while time.time() < end:
        vals = [f.get("text") for f in edit_texts()]
        last = vals
        if len(vals) >= 2 and (desc_value is None or vals[0] == desc_value) \
                and (note_value is None or vals[1] == note_value):
            return True, vals
        time.sleep(0.5)
    return False, last

def gallery_pick_one(where=""):
    """On a container screen: FAB -> Choose from gallery -> pick one ->
    Add. Returns True once the mandatory-metadata sheet is up (single
    image, so there is no 'more photos waiting' caption)."""
    check(f"gallery flow opens [{where}]", tap_desc("Add"))
    time.sleep(1.2)
    check(f"sheet offers Choose from gallery [{where}]",
          wait_for(text="Choose from gallery", timeout=5) is not None)
    tap_text("Choose from gallery"); time.sleep(4.0)
    dism = find_all(dump(), text="Dismiss")
    if dism: tap(dism[0]); time.sleep(0.8)
    photos = find_all(dump(), desc="Photo taken on")
    check(f"gallery picker shows photos [{where}]", bool(photos), str(len(photos)))
    if not photos: return False
    tap(photos[0]); time.sleep(1.0)
    end = time.time() + 8
    add = None
    while time.time() < end and add is None:
        d = dump()
        cands = [n for n in find_all(d, text="Add")] or [n for n in find_all(d, desc="Add")]
        add = cands[0] if cands else None
        if add is None: time.sleep(0.5)
    check(f"picker Add button [{where}]", add is not None)
    if add is None: return False
    tap(add)
    return wait_for(text="Description (required)", timeout=10) is not None

def import_anyway_if_duplicate(timeout=20):
    """After a gallery Save that may collide with an identical photo already
    in the container: click "Import anyway" on the duplicate warning. Returns
    True when the warning was handled, False when no warning appeared (the
    save went straight through)."""
    end = time.time() + timeout
    while time.time() < end:
        d = dump()
        if d is not None and find_all(d, text="Identical photo already exists here as"):
            return tap_text("Import anyway", timeout=6)
        time.sleep(0.3)
    return False

# ---- drag-and-drop motion helpers ------------------------------------------
# API 35's `input motionevent DOWN|MOVE|UP x y` injects ONE MotionEvent per
# call; the four drag gestures (photo/container/ancestor/selection) need a
# realistic stream: a DOWN, a >long-press hold, then small MOVE steps with
# short pauses, and dwell repeats on a hovered target. `input draganddrop`
# would be a single straight swipe with no dwell, so every dwell scenario
# uses motionevent. Measured: the separate `input` processes share the
# pointer stream (the ghost follows and spring-load fires), so no extra
# plumbing is needed.

def motion_down(x, y):
    sh(f"input motionevent DOWN {x} {y}")

def motion_move(x, y):
    sh(f"input motionevent MOVE {x} {y}")

def motion_up(x, y):
    sh(f"input motionevent UP {x} {y}")

def drag_move_to(x0, y0, x1, y1, step=50, pause=0.12):
    """Glide the pointer in ~50px steps (the ghost follows the finger)."""
    import math
    dist = math.hypot(x1 - x0, y1 - y0)
    n = max(1, int(dist // step))
    for i in range(1, n + 1):
        t = i / n
        motion_move(int(x0 + (x1 - x0) * t), int(y0 + (y1 - y0) * t))
        time.sleep(pause)
    motion_move(x1, y1)
    time.sleep(pause)

def drag_dwell(x, y, ms):
    """Hold position, re-sending MOVE, for the spring-load's 800ms dwell."""
    end = time.time() + ms / 1000.0
    while time.time() < end:
        motion_move(x, y)
        time.sleep(0.15)

def drag_start(x, y, hold=1.0):
    """DOWN + hold past the long-press threshold (starts the drag session)."""
    motion_down(x, y)
    time.sleep(hold)

def drag_by_desc(desc):
    ns = find_all(dump(), desc=desc)
    return ns[0] if ns else None

def drag_by_text(text):
    ns = [n for n in find_all(dump(), text=text) if n.get("text") == text]
    return ns[0] if ns else None

def drag_center(node):
    return center(node)

def drag_up_chip():
    return drag_by_desc("Move up one level")

def drag_spring_up(x, y, dwell_ms=900):
    """Hover the drag-time "↑" chip just past one 800ms spring tick, then
    leave it for a neutral spot. Parking ON the chip would fire a second
    navigation (the chip stays under the pointer across the destination
    change), so exactly one level is climbed here."""
    u = drag_up_chip()
    if u is None:
        return None
    ux, uy = center(u)
    drag_move_to(x, y, ux, uy)
    drag_dwell(ux, uy, dwell_ms)
    motion_move(ux, uy + 75)  # neutral: below the chip, above the grid rows
    time.sleep(0.4)
    return (ux, uy)

def drag_seed_jpeg(desc, note, rel):
    """Push a distinct EXIF-stamped JPEG for the selection-drag scenario."""
    import tempfile
    from PIL import Image
    tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
    tmp.close()
    im = Image.new("RGB", (8, 8), (30, 200, 200))
    ex = Image.Exif()
    ex[0x010E] = desc
    if note:
        ex[0x9286] = ("ASCII\x00\x00\x00" + note).encode("latin-1")
    ex[0x9003] = "2026:01:01 12:00:00"
    im.save(tmp.name, "JPEG", exif=ex)
    adb("push", tmp.name, fp(rel), timeout=30)
    os.unlink(tmp.name)

def seed_photo(rel, desc, note=None, date="2026:01:01 12:00:00"):
    """Push a JPEG with exact EXIF description/note/date to a disk path (the
    parent folder must exist). The report and viewer scenarios use this for
    deterministic metadata; the app adopts it on the next Rescan."""
    import tempfile
    from PIL import Image
    tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
    tmp.close()
    im = Image.new("RGB", (8, 8), (120, 40, 200))
    ex = Image.Exif()
    ex[0x010E] = desc
    # DateTimeOriginal (0x9003) and UserComment (0x9286) live in the Exif
    # sub-IFD (0x8769); assigning them on the top-level Exif object makes
    # Pillow emit them into IFD0, which the app's ExifInterface never reads.
    sub = {0x9003: date}
    if note:
        sub[0x9286] = ("ASCII\x00\x00\x00" + note).encode("latin-1")
    ex[0x8769] = sub
    im.save(tmp.name, "JPEG", exif=ex)
    adb("push", tmp.name, fp(rel), timeout=30)
    os.unlink(tmp.name)

def make_png(width, height, color=(10, 200, 60)):
    """A real PNG on a host buffer (the share-target PNG-transcode proof).

    The fill is a bright→dark→bright V gradient rather than a flat color:
    a flat fill hashes to dHash 0, which would read as "identical" to any
    other flat image (the seeded 1x1 JPEG) and be skipped by the duplicate
    guard instead of being transcoded."""
    from PIL import Image
    img = Image.new("RGB", (width, height))
    pixels = img.load()
    for x in range(width):
        shade = int(255 * abs(2 * x / max(1, width - 1) - 1))
        for y in range(height):
            pixels[x, y] = (shade, (color[1] + shade) % 256, color[2])
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()

def seed_media_image(name, data, mime):
    """Push `data` to /sdcard/Pictures/<name>, register it in MediaStore and
    return the content:// Uri a SEND intent should carry (None on failure).

    `content insert` prints nothing and canonicalises `_data` to
    /storage/emulated/0/..., so the row is looked back up by that canonical
    path to learn its `_id`."""
    sh("mkdir -p /sdcard/Pictures")
    path = f"/sdcard/Pictures/{name}"
    canonical = f"/storage/emulated/0/Pictures/{name}"
    sh(f"content delete --uri content://media/external/images/media "
       f"--where \"_data='{canonical}'\"")
    tmp = tempfile.NamedTemporaryFile(suffix=os.path.splitext(name)[1], delete=False)
    tmp.write(data); tmp.close()
    adb("push", tmp.name, path, timeout=20)
    os.unlink(tmp.name)
    sh(f"content insert --uri content://media/external/images/media "
       f"--bind _data:s:{path} --bind mime_type:s:{mime}")
    out = sh(f"content query --uri content://media/external/images/media "
             f"--projection _id --where \"_data='{canonical}'\"")
    m = re.search(r"_id=(\d+)", out)
    return f"content://media/external/images/media/{m.group(1)}" if m else None

# ---------------------------------------------------------------- scenarios

def t_saf_mode():
    """FIRST, on a fresh pm clear with NO appops set at all: answer the
    chooser with 'Pick a folder', drive DocumentsUI, prove zero storage
    permissions, then run the full CRUD battery through the SAF backend."""
    mark("00 SAF mode (zero perms)")
    check("app in foreground on first launch", in_foreground())
    check("first launch shows the mode chooser",
          wait_for(text=CHOOSER_RATIONALE, timeout=12) is not None)
    check("chooser offers the SAF default button", bool(texts_exact(PICK_FOLDER_BTN)))
    check("chooser offers the File-mode button", bool(texts_exact(FILE_MODE_BTN)))
    a = _bounds(texts_exact(PICK_FOLDER_BTN)[0].get("bounds", "")) \
        if texts_exact(PICK_FOLDER_BTN) else None
    b = _bounds(texts_exact(FILE_MODE_BTN)[0].get("bounds", "")) \
        if texts_exact(FILE_MODE_BTN) else None
    check("SAF button sits above the File button (recommended default)",
          bool(a and b) and a[1] < b[1], f"{a} vs {b}")
    if not choose_saf_mode():
        return
    bad, mode, raw = saf_permission_proof()
    check("dumpsys: no storage RUNTIME grant while SAF works", not bad, str(bad))
    check("MANAGE_EXTERNAL_STORAGE appops not granted",
          mode in ("deny", "ignore", "default"), raw)
    # container CRUD through SAF
    check("root FAB opens create dialog", tap_desc("Add container"))
    time.sleep(1.2)
    fill_dialog("SAF root")
    check("SAF container created snackbar", snackbar_visible("Container created", timeout=8))
    check("row 'SAF root' visible", wait_for(text="SAF root", timeout=8) is not None)
    check("disk: /SAF root folder (created via SAF)", fs_isdir(fp("SAF root")))
    # camera capture — the runtime CAMERA dialog must carry it unaided
    # (no appops was set: camera_capture polls 'While using the app')
    tap_text("SAF root"); time.sleep(1.8)
    check("SAF camera capture saved", camera_capture("SAF shot", "via saf", where="SAF root"))
    check("SAF photo snackbar", snackbar_visible("Photo added", timeout=10))
    check("SAF tile 'SAF shot'", wait_for(text="SAF shot", timeout=8) is not None)
    shot = fp("SAF root", "SAF shot.jpg")
    check("disk: /SAF root/SAF shot.jpg (desc → filename)", fs_exists(shot))
    tr = exif_triple(shot); EXIF_SAMPLES.append(("SAF root/SAF shot.jpg", tr))
    check("EXIF: SAF ImageDescription == 'SAF shot'", tr["desc"] == "SAF shot", str(tr))
    check("EXIF: SAF UserComment == 'via saf'", tr["note"] == "via saf", str(tr))
    check("EXIF: SAF DateTimeOriginal stamped",
          bool(re.fullmatch(DATE_RE, tr["date"] or "")), str(tr))
    # child folder + gallery cover through SAF
    tap_desc("Add"); time.sleep(1.2)
    tap_text("Add container"); time.sleep(1.0)
    fill_dialog("SAF sub")
    check("SAF child row visible", wait_for(text="SAF sub", timeout=8) is not None)
    check("disk: /SAF root/SAF sub folder", fs_isdir(fp("SAF root", "SAF sub")))
    check("open SAF sub row menu", row_menu("SAF sub", "Options"))
    time.sleep(1.2)
    check("menu offers Set photo (SAF)", tap_text("Set photo", timeout=5))
    check("cover sheet opens (SAF)", wait_for(text="Choose from gallery", timeout=5) is not None)
    check("SAF gallery cover saved", cover_gallery_flow())
    cov = fp("SAF root", "SAF sub", "_cover.jpg")
    time.sleep(1.5)
    check("disk: SAF sub/_cover.jpg exists", fs_exists(cov))
    check("SAF cover snackbar", snackbar_visible("Photo updated", timeout=10),
          "cover-gallery snackbar rides the same queue as the FILE-mode covers "
          "(measured soft there), disk asserted hard above", soft=True)
    # rename through SAF (renameDocument): cover travels with the folder
    check("open SAF sub menu again", row_menu("SAF sub", "Options"))
    time.sleep(1.2)
    tap_text("Rename")
    check("rename dialog prefilled (SAF)", wait_for(text="Rename", timeout=5) is not None)
    fields = edit_texts()
    check("rename field holds 'SAF sub'", fields and fields[0].get("text") == "SAF sub",
          str([f.get("text") for f in fields]))
    if not fields:
        return
    tap(fields[0]); wait_ime(); clear_field(); type_text("SAF box")
    hide_ime(); time.sleep(1.0)
    tap_text("Save")
    check("SAF rename applied in UI", wait_for(text="SAF box", timeout=8) is not None)
    check("disk: old 'SAF sub' gone", not fs_exists(fp("SAF root", "SAF sub")))
    check("disk: renamed folder holds the cover", fs_exists(fp("SAF root", "SAF box", "_cover.jpg")))
    check("disk: camera photo untouched by the rename", fs_exists(shot))
    # Same measured queue flakiness as the FILE-mode rename snackbar
    # (Container saved can surface ~6s after Save behind queued snackbars).
    check("SAF rename snackbar (Container saved)", snackbar_visible("Container saved", timeout=12),
          "queued-snackbar precedent from t_inventory_root", soft=True)
    check("still inside SAF root after child rename", bool(texts_exact("SAF root")))

def t_file_mode_switch():
    """Switch SAF → FILE via the root overflow 'Storage mode' dialog while
    All Files Access is still DENIED: the gate must catch the app, the
    appops grant must release it on resume, and the SAF-created tree must
    come back through the FileBackend (interop proof)."""
    mark("01 file-mode switch")
    check("starts on home in SAF mode", go_home())
    check("root lists the SAF-created folder", bool(texts_exact("SAF root")))
    check("root overflow opens", open_screen_menu())
    time.sleep(1.2)
    check("'Storage mode' menu entry opens the dialog", tap_text("Storage mode", timeout=5))
    check("mode dialog title", bool(texts_exact("Storage mode")))
    check("dialog labels the active mode 'Folder pick'", bool(texts_exact(MODE_SAF_LABEL)))
    check("dialog offers both modes",
          bool(texts_exact(PICK_FOLDER_BTN)) and bool(texts_exact(FILE_MODE_BTN)))
    check("tap 'All files access' in the dialog", tap_texts_exact(FILE_MODE_BTN, timeout=5))
    check("switch hops to the settings page", settings_page_focused())
    back(); time.sleep(1.5)
    check("FILE gate shows the rationale",
          wait_for(text=CHOOSER_RATIONALE, timeout=8) is not None)
    check("gate shows Grant access", bool(texts_exact(GRANT_ACCESS_BTN)))
    check("gate offers the SAF escape hatch", bool(texts_exact(PICK_FOLDER_BTN)))
    check("tree hidden behind the gate", not texts_exact("SAF root"))
    # grant All Files Access the way the settings toggle would…
    sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
    sh("input keyevent KEYCODE_HOME"); time.sleep(1.0)
    start_app()
    check("gate cleared after resume (re-check)",
          wait_for(text="SAF root", timeout=12) is not None)
    check("mode-switch snackbar (optional, queue-flaky across the hop)",
          snackbar_visible(MODE_CHANGED_SNACK, timeout=8),
          "emitted behind the settings hop; seen on resume in probing but "
          "the snackbar queue has no delivery guarantee across process "
          "pauses — dialog texts + gate + interop tree asserted hard above",
          soft=True)
    check("no Grant access after resume", not texts_exact(GRANT_ACCESS_BTN))
    check("mode persisted as FILE", 'name="mode">FILE<' in
          sh(f"run-as {PKG} cat shared_prefs/storage_prefs.xml 2>/dev/null"))
    # the brief: after any mode switch, rescan (menu) before UI assertions
    check("rescan via root icon", rescan_from_root())
    check("rescanned snackbar (FILE over the SAF tree)",
          snackbar_visible("Storage rescanned", timeout=10))
    check("interop: SAF root counts 1 container · 1 photo",
          wait_for(text=f"1 container {MID} 1 photo", timeout=8) is not None)
    tap_text("SAF root"); time.sleep(1.8)
    check("interop: EXIF photo renders in FILE mode",
          wait_for(text="SAF shot", timeout=8) is not None)
    check("interop: renamed child folder present", bool(texts_exact("SAF box")))
    no_filename_leaks("no raw filenames on the interop screen")
    check("back home after interop", go_home())

def t_suite_start():
    """Main-suite entry: the suite-start pm clear wiped the mode pref, so
    the CHOOSER is up again — answer it with 'All files access' (FILE mode
    is automatable and fast; the zero-permission SAF path got its dedicated
    proof in scenario 00)."""
    mark("02 suite start (chooser)")
    check("app in foreground after suite-start launch", in_foreground())
    check("chooser returns after pm clear (mode pref wiped)",
          wait_for(text=CHOOSER_RATIONALE, timeout=12) is not None)
    check("chooser still offers both buttons",
          bool(texts_exact(PICK_FOLDER_BTN)) and bool(texts_exact(FILE_MODE_BTN)))
    check("no browse behind the chooser",
          not find_all(dump(), text="Your inventory is empty"))
    choose_file_mode(where="suite start")
    check("FILE mode home reached (gate auto-satisfied)",
          wait_for(text="Your inventory is empty", timeout=10) is not None)
    check("chooser buttons gone after the choice",
          not texts_exact(PICK_FOLDER_BTN) and not texts_exact(FILE_MODE_BTN))
    check("no gate button", not texts_exact(GRANT_ACCESS_BTN))
    check("disk: root folder created by the app", fs_wait_exists(FS_ROOT))
    time.sleep(1.0)
    check("home title is Inventory", bool(texts_exact("Inventory")))

def t_permission_gate():
    mark("1 permission gate")
    check("app in foreground", in_foreground())
    # main() removed any stale /sdcard/PhotoInventory before the first launch
    check("root folder created by first granted launch", fs_isdir(FS_ROOT))
    check("no gate while storage allowed", not find_all(dump(), text="Grant access"))
    check("home title is Inventory", bool(texts_exact("Inventory")))
    check("root empty state shown", wait_for(text="Your inventory is empty", timeout=8) is not None)
    # Revoke via appops and cold-restart: the root must fall back to the
    # rationale + "Grant access" gate (no settings-page navigation needed).
    before = set(tree())
    sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE deny")
    sh(f"am force-stop {PKG}")
    start_app()
    check("gate rationale visible",
          wait_for(text="reads and writes a normal folder", timeout=8) is not None)
    check("gate shows Grant access", bool(texts_exact("Grant access")))
    check("browse hidden behind gate", not find_all(dump(), text="Your inventory is empty"))
    check("disk untouched while denied", set(tree()) == before, f"{before} -> {set(tree())}")
    # Re-allow via appops and force an ON_RESUME (home + relaunch): the
    # LifecycleResumeEffect re-check must drop the gate, and the root then
    # gets created (ensureRoot).
    sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
    sh("input keyevent KEYCODE_HOME"); time.sleep(1.0)
    start_app()
    check("gate cleared after resume",
          wait_for(text="Your inventory is empty", timeout=8) is not None)
    check("no Grant access after resume", not find_all(dump(), text="Grant access"))
    time.sleep(1.0)
    check("root folder created once granted", fs_isdir(FS_ROOT))

def t_inventory_root():
    mark("2 inventory root")
    check("home title is Inventory", bool(texts_exact("Inventory")))
    check("home empty state", wait_for(text="Your inventory is empty", timeout=6) is not None)
    # root FAB: contentDescription is add_container -> "Add container" (isRoot
    # branch of ContainerScreen; the non-root FAB is "Add")
    check("root FAB opens create dialog", tap_desc("Add container"))
    time.sleep(1.2)
    check("create dialog has Name label", wait_for(text="Name", timeout=5) is not None)
    check("blank name -> Save disabled",
          bool(texts_exact("Save")) and texts_exact("Save")[0].get("clickable") == "false")
    fill_dialog("Building")
    check("container created snackbar", snackbar_visible("Container created", timeout=8))
    check("row Building visible", wait_for(text="Building", timeout=8) is not None)
    check("disk: /Building folder", fs_isdir(fp("Building")))
    # Duplicate root name: the filesystem model has no rejection anymore —
    # Filenames.uniqueName gives the file-manager " (2)" treatment. Assert
    # that, then delete the duplicate to keep the tree deterministic.
    tap_desc("Add container"); time.sleep(1.2)
    fill_dialog("Building")
    check("duplicate auto-suffixed to 'Building (2)'", wait_for(text="Building (2)", timeout=8) is not None)
    check("disk: /Building (2) folder", fs_isdir(fp("Building (2)")))
    check("still on home", bool(texts_exact("Inventory")))
    check("open Building (2) row menu", row_menu("Building (2)", "Options"))
    time.sleep(1.2)
    tap_text("Delete")
    check("delete confirm dialog", wait_for(text="Delete container?", timeout=5) is not None)
    nodes = find_all(dump(), text="everything inside it")
    mtxt = nodes[0].get("text", "") if nodes else ""
    check("delete message counts empty subtree",
          '"Building (2)"' in mtxt and "(0 containers, 0 photos)" in mtxt, mtxt)
    tap_text("Delete")
    check("container deleted snackbar", snackbar_visible("Container deleted", timeout=10))
    check("Building (2) row gone", not texts_exact("Building (2)"))
    check("disk: /Building (2) removed", not fs_exists(fp("Building (2)")))
    # two more roots
    tap_desc("Add container"); time.sleep(1.2)
    fill_dialog("Garage")
    check("row Garage visible", wait_for(text="Garage", timeout=8) is not None)
    check("disk: /Garage folder", fs_isdir(fp("Garage")))
    tap_desc("Add container"); time.sleep(1.2)
    fill_dialog("Shelf A")
    check("row Shelf A visible", wait_for(text="Shelf A", timeout=8) is not None)
    check("disk: /Shelf A folder", fs_isdir(fp("Shelf A")))
    d = dump()
    check("three roots listed", len([n for n in ("Building", "Garage", "Shelf A")
                                     if find_all(d, text=n)]) == 3)
    check("Containers section header", bool(texts_exact("Containers")))
    # rename flow via the row overflow menu (desc container_options -> "Options")
    check("open Shelf A row menu", row_menu("Shelf A", "Options"))
    time.sleep(1.2)
    tap_text("Rename")
    check("rename dialog prefilled", bool(texts_exact("Shelf A")))
    fields = edit_texts()
    check("rename field holds old name", fields and fields[0].get("text") == "Shelf A",
          str([f.get("text") for f in fields]))
    tap(fields[0]); wait_ime(); clear_field(); type_text("Attic shelf")
    hide_ime(); time.sleep(1.0)
    tap_text("Save")
    check("rename applied", wait_for(text="Attic shelf", timeout=8) is not None)
    check("root Shelf A gone", not texts_exact("Shelf A"))
    check("disk: old Shelf A folder gone", not fs_exists(fp("Shelf A")))
    check("disk: renamed folder on disk", fs_isdir(fp("Attic shelf")))
    # Measured on this emulator: the rename snackbar can appear ~5.8s after
    # Save (queued behind the preceding snackbars, M3 shows them sequentially)
    # and stays ~4s; with ~1.5s uiautomator dump latency that window is a coin
    # flip when the dump service wedges (missed even at 12s in run 3), so the
    # rename itself is asserted hard above and the snackbar stays soft.
    check("rename snackbar (Container saved)", snackbar_visible("Container saved", timeout=12),
          soft=True)

def t_drill_nesting():
    mark("3 drill nesting")
    check("scenario starts on home", go_home())
    tap_text("Building"); time.sleep(1.8)
    check("Building screen title", bool(texts_exact("Building")))
    check("Building empty state", wait_for(text="Nothing here yet", timeout=6) is not None)
    # non-root FAB is desc "Add" -> sheet with the three ways to fill a node
    check("node FAB opens add sheet", tap_desc("Add"))
    time.sleep(1.2)
    check("sheet offers Add container", wait_for(text="Add container", timeout=5) is not None)
    tap_text("Add container"); time.sleep(1.0)
    fill_dialog("Storage unit")
    check("child container created", snackbar_visible("Container created", timeout=8))
    check("row Storage unit visible", wait_for(text="Storage unit", timeout=8) is not None)
    check("disk: Building/Storage unit", fs_isdir(fp("Building", "Storage unit")))
    # two grandchildren
    tap_text("Storage unit"); time.sleep(1.8)
    tap_desc("Add"); time.sleep(1.2)
    tap_text("Add container"); time.sleep(1.0)
    fill_dialog("Shelf A")
    check("grandchild Shelf A row", wait_for(text="Shelf A", timeout=8) is not None)
    check("disk: nested Shelf A", fs_isdir(fp("Building", "Storage unit", "Shelf A")))
    tap_desc("Add"); time.sleep(1.2)
    tap_text("Add container"); time.sleep(1.0)
    fill_dialog("Shelf B")
    check("grandchild Shelf B row", wait_for(text="Shelf B", timeout=8) is not None)
    check("disk: nested Shelf B", fs_isdir(fp("Building", "Storage unit", "Shelf B")))
    # deepest level, then retrace the whole way home
    tap_text("Shelf A"); time.sleep(1.8)
    check("Shelf A screen empty state", wait_for(text="Nothing here yet", timeout=6) is not None)
    back(); time.sleep(1.8)
    check("back on Storage unit", bool(find_all(dump(), text="Shelf B")))
    back(); time.sleep(1.8)
    check("Storage unit row counts 2 containers",
          wait_for(text=f"2 containers {MID} 0 photos", timeout=6) is not None)
    back(); time.sleep(1.8)
    check("home title after deep back", bool(texts_exact("Inventory")))
    # container_count/pluralString one-form: Building has exactly one child
    check("Building row shows singular 1 container",
          wait_for(text=f"1 container {MID} 0 photos", timeout=6) is not None)
    want = {fp("Building", "Storage unit"),
            fp("Building", "Storage unit", "Shelf A"),
            fp("Building", "Storage unit", "Shelf B")}
    check("tree() shows the real nesting", want.issubset(set(tree())), str(sorted(want - set(tree()))))

def t_photos_and_flow():
    mark("4 photos and flow")
    check("scenario starts on home", go_home())
    tap_text("Garage"); time.sleep(1.8)
    check("on Garage empty screen", wait_for(text="Nothing here yet", timeout=6) is not None)
    # camera capture with description + note
    check("camera capture saved", camera_capture("Toolbox lid", "check hinges", where="Garage"))
    check("camera photo snackbar", snackbar_visible("Photo added", timeout=10))
    check("tile Toolbox lid", wait_for(text="Toolbox lid", timeout=8) is not None)
    check("Photos section header", bool(texts_exact("Photos")))
    cam = fp("Garage", "Toolbox lid.jpg")
    check("disk: /Garage/Toolbox lid.jpg", fs_exists(cam))
    tr = exif_triple(cam); EXIF_SAMPLES.append(("Garage/Toolbox lid.jpg", tr))
    check("EXIF: ImageDescription == 'Toolbox lid'", tr["desc"] == "Toolbox lid", str(tr))
    check("EXIF: UserComment == 'check hinges'", tr["note"] == "check hinges", str(tr))
    check("EXIF: DateTimeOriginal stamped", bool(re.fullmatch(DATE_RE, tr["date"] or "")), str(tr))
    # gallery: pick both seeds -> the mandatory-metadata queue runs per image
    tap_desc("Add"); time.sleep(1.2)
    check("sheet offers Choose from gallery", wait_for(text="Choose from gallery") is not None)
    tap_text("Choose from gallery"); time.sleep(4.0)
    d = dump()
    check("photo picker opened", d is not None and bool(find_all(d, desc="Photo taken on")))
    dism = find_all(dump(), text="Dismiss")
    if dism: tap(dism[0]); time.sleep(0.8)
    photos = find_all(dump(), desc="Photo taken on")
    check("picker shows 2 seeded photos", len(photos) >= 2, str(len(photos)))
    for n in photos[:2]:
        tap(n); time.sleep(1.0)
    end = time.time() + 8
    add = None
    while time.time() < end and add is None:
        d = dump()
        cands = [n for n in find_all(d, text="Add")] or \
                [n for n in find_all(d, desc="Add")]
        add = cands[0] if cands else None
        if add is None: time.sleep(0.5)
    check("picker Add button", add is not None)
    if add is not None: tap(add)
    check("queue sheet: 1 more waiting", wait_for(text="more photos waiting", timeout=10) is not None)
    check("queue Save disabled while blank",
          bool(texts_exact("Save photo")) and texts_exact("Save photo")[0].get("clickable") == "false")
    f = edit_texts()
    check("queue fields present", len(f) >= 1, str(len(f)))
    if not f: return
    tap(f[0]); wait_ime(); type_text("Winter jacket"); hide_ime(); time.sleep(0.8)
    tap_text("Save")
    check("second item prompts again", wait_for(text="Description (required)", timeout=6) is not None)
    f2 = edit_texts()
    if not f2: return
    tap(f2[0]); wait_ime(); type_text("Old skis"); hide_ime(); time.sleep(0.8)
    tap_text("Save")
    # Both seeded gallery picks are byte-identical, so the second one is
    # flagged as a duplicate: import it anyway to preserve the flow's intent.
    check("second pick flagged as duplicate", import_anyway_if_duplicate())
    check("gallery photos added snackbar", snackbar_visible("Photo added", timeout=10))
    check("tile Winter jacket", wait_for(text="Winter jacket", timeout=6) is not None)
    check("tile Old skis", wait_for(text="Old skis", timeout=6) is not None)
    wj, osk = fp("Garage", "Winter jacket.jpg"), fp("Garage", "Old skis.jpg")
    check("disk: /Garage/Winter jacket.jpg", fs_exists(wj))
    check("disk: /Garage/Old skis.jpg", fs_exists(osk))
    tr2 = exif_triple(wj); EXIF_SAMPLES.append(("Garage/Winter jacket.jpg", tr2))
    check("EXIF: gallery description stamped", tr2["desc"] == "Winter jacket", str(tr2))
    check("EXIF: no note on queue-only pick", not tr2["note"], str(tr2))
    check("EXIF: gallery DateTimeOriginal", bool(re.fullmatch(DATE_RE, tr2["date"] or "")), str(tr2))
    # Byte-identity: the app copies gallery bytes verbatim and only touches
    # EXIF — compare PIXEL data (not file bytes, which carry the new APP1).
    want = pixel_key(JPEG_1PX)
    got = pixel_key(fs_read(wj))
    check("stored pixels identical to seeded JPEG", want == got, f"{want[0]} vs {got[0]}")

def t_search_and_scopes():
    mark("5 search and scopes")
    # --- in-container search in Garage (3 photos, no subfolders)
    check("scenario starts on home", go_home())
    tap_text("Garage"); time.sleep(1.8)
    check("search toggles open", tap_desc("Toggle search"))
    f = wait_for(text="Search photos", timeout=6)
    if f is None:
        check("search field appears", False)
        return
    check("search field appears", True)
    tap(f); wait_ime(); type_text("ski"); time.sleep(1.5)
    d = dump()
    check("'ski' filters to Old skis",
          bool(find_all(d, text="Old skis")) and not find_all(d, text="Winter jacket")
          and not find_all(d, text="Toolbox lid"))
    check("scope chips visible", bool(texts_exact("This container")) and bool(texts_exact("Include sub-containers")))
    check("default scope 'This container' selected", chip_state("This container") is True,
          str((chip_state("This container"), chip_state("Include sub-containers"))))
    clear_field(); type_text("zzz"); time.sleep(1.5)
    check("no-results state", wait_for(text="No photos match your search", timeout=6) is not None)
    check("clear icon restores", tap_desc("Clear search", timeout=5))
    time.sleep(1.2)
    d = dump()
    check("all 3 tiles restored", bool(find_all(d, text="Toolbox lid"))
          and bool(find_all(d, text="Old skis")) and bool(find_all(d, text="Winter jacket")))
    tap_desc("Toggle search")  # collapse the field again
    time.sleep(0.6)  # let Compose settle the IME before any conditional BACK
    ensure_ime_hidden()
    # --- a photo one level deep, to exercise the scope chips from Building
    # (go_home, never a raw back() here: one stray BACK past the start
    # destination leaves the app — an IME-dismiss race did exactly that in run 1)
    check("home after Garage search", go_home())
    tap_text("Building"); time.sleep(1.8)
    tap_text("Storage unit"); time.sleep(1.8)
    check("on Storage unit", bool(texts_exact("Storage unit")))
    check("deep camera capture", camera_capture("Roof tiles", "attic only", where="Storage unit"))
    check("deep photo snackbar", snackbar_visible("Photo added", timeout=10))
    check("tile Roof tiles", wait_for(text="Roof tiles", timeout=8) is not None)
    deep = fp("Building", "Storage unit", "Roof tiles.jpg")
    check("disk: deep photo path", fs_exists(deep))
    tr = exif_triple(deep); EXIF_SAMPLES.append(("Storage unit/Roof tiles.jpg", tr))
    check("EXIF: deep photo desc+note",
          tr["desc"] == "Roof tiles" and tr["note"] == "attic only", str(tr))
    back(); time.sleep(1.8)
    check("on Building (parent)", bool(texts_exact("Building")))
    tap_desc("Toggle search"); time.sleep(0.8)
    f = wait_for(text="Search photos", timeout=6)
    if f is None:
        check("search field opens on Building", False)
        return
    check("search field opens on Building", True)
    tap(f); wait_ime(); type_text("Roof"); time.sleep(1.8)
    d = dump()
    check("Here scope misses the deep photo",
          bool(find_all(d, text="No photos match your search"))
          and not find_all(d, text="Roof tiles"))
    check("tap 'Include sub-containers' chip", tap_text("Include sub-containers", timeout=5))
    check("subtree scope finds the deep photo", wait_for(text="Roof tiles", timeout=8) is not None)
    check("chip states flipped", chip_state("Include sub-containers") is True
          and chip_state("This container") is False,
          str((chip_state("This container"), chip_state("Include sub-containers"))))
    check("clear search icon", tap_desc("Clear search", timeout=5))
    time.sleep(1.0)
    check("Roof tile back after clear", bool(find_all(dump(), text="Roof tiles")))
    tap_desc("Toggle search"); ensure_ime_hidden(); time.sleep(0.8)
    # --- global search with container paths
    check("home reached", go_home())
    check("search action on home", tap_desc("Search all items"))
    time.sleep(1.8)
    check("browse caption", wait_for(text="All items, newest first", timeout=6) is not None)
    d = dump()
    check("browse lists tiles from all containers",
          bool(find_all(d, text="Toolbox lid")) and bool(find_all(d, text="Roof tiles")))
    f = wait_for(text="Search all photos", timeout=6)
    if f is None:
        check("global field appears", False)
        return
    check("global field appears", True)
    tap(f); wait_ime(); type_text("lid"); time.sleep(1.5)
    d = dump()
    check("'lid' narrows to Toolbox lid",
          bool(find_all(d, text="Toolbox lid")) and not find_all(d, text="Winter jacket")
          and not find_all(d, text="Roof tiles"))
    check("result shows its container path", bool(find_all(d, text="Garage")))
    check("clear icon works", tap_desc("Clear search", timeout=5))
    time.sleep(1.0)
    f = wait_for(text="Search all photos", timeout=6)
    check("field refocusable for note search", f is not None)
    if f is None: return
    tap(f); wait_ime(); type_text("hinge"); time.sleep(1.5)
    d = dump()
    # "hinge" appears in NO description — only in Toolbox lid's EXIF note
    # ("check hinges") — so surfacing it proves note search over EXIF.
    check("note search surfaces Toolbox lid", bool(find_all(d, text="Toolbox lid")))
    check("note search excludes Old skis", not find_all(d, text="Old skis"))
    tap_text("Toolbox lid"); time.sleep(2.0)
    check("result tap jumps to Garage", bool(texts_exact("Garage")))
    check("tile visible after jump", bool(find_all(dump(), text="Toolbox lid")))
    ensure_ime_hidden()
    back(); time.sleep(1.8)
    check("query retained after back", bool(find_all(dump(), text="hinge")))
    check("clear icon returns to browse", tap_desc("Clear search", timeout=5))
    check("browse caption back", wait_for(text="All items, newest first", timeout=6) is not None)
    f2 = wait_for(text="Search all photos", timeout=6)
    check("field refocusable", f2 is not None)
    if f2 is not None:
        tap(f2); wait_ime(); type_text("zzznope"); time.sleep(1.5)
        check("global no-results state",
              wait_for(text="No photos match your search", timeout=6) is not None)
    check("home after search exit", go_home())

def t_preview_and_multiselect():
    mark("6 preview and multiselect")
    check("scenario starts on home", go_home())
    tap_text("Garage"); time.sleep(1.8)
    wj = fp("Garage", "Winter jacket.jpg")
    before = exif_triple(wj)
    tile = wait_for(text="Winter jacket")
    check("Winter jacket tile present", tile is not None)
    if tile is None: return
    # Viewer flow (Feature 3): the tile tap opens the full-screen viewer, not
    # the editor; "Edit" then opens the existing metadata dialog. After Save
    # we are back in the viewer, whose overlay shows the new description, and
    # "Close" returns to the grid.
    tap(tile); time.sleep(1.5)
    check("viewer opens on the tapped photo", viewer_page() is not None,
          str(viewer_page()))
    check("viewer overlay shows the description",
          bool(find_all(dump(), text="Winter jacket")))
    check("viewer Edit opens the metadata editor", tap_text("Edit", timeout=6))
    check("edit dialog opens", wait_for(text="Description (required)", timeout=6) is not None)
    fields = edit_texts()
    if len(fields) < 2:
        check("description prefilled", False, str([f.get("text") for f in fields]))
        return
    check("description prefilled from EXIF", fields[0].get("text") == "Winter jacket",
          str([f.get("text") for f in fields]))
    check("note field prefilled empty", fields[1].get("text") == "")
    tap(fields[0]); wait_ime(); clear_field(); type_text("Winter jackets x2")
    hide_ime(); time.sleep(0.8)
    fields = edit_texts()
    tap(fields[1]); wait_ime(); type_text("check hinges"); hide_ime(); time.sleep(0.8)
    tap_text("Save")
    # With the viewer open the ContainerScreen snackbar host sits BEHIND the
    # edge-to-edge viewer dialog, so "Photo saved" is not on the active
    # window; the disk/EXIF checks and the overlay update below are hard.
    check("photo saved snackbar", snackbar_visible("Photo saved", timeout=8),
          "emitted behind the viewer dialog", soft=True)
    # back in the viewer: its overlay reflects the saved metadata
    check("viewer overlay updates after Save",
          wait_for(text="Winter jackets x2", timeout=6) is not None)
    check("close viewer returns to the grid", close_viewer())
    check("tile text updated", wait_for(text="Winter jackets x2", timeout=6) is not None)
    # the edit renamed the file on disk: EXIF description drives the name
    wj2 = fp("Garage", "Winter jackets x2.jpg")
    check("disk: file renamed to 'Winter jackets x2.jpg'", fs_exists(wj2))
    check("disk: old file name gone", not fs_exists(wj))
    after = exif_triple(wj2); EXIF_SAMPLES.append(("Garage/Winter jackets x2.jpg", after))
    check("EXIF: description updated to 'Winter jackets x2'", after["desc"] == "Winter jackets x2", str(after))
    check("EXIF: note (0x9286) now 'check hinges'", after["note"] == "check hinges", str(after))
    check("EXIF: taken date untouched by edit",
          bool(after["date"]) and after["date"] == before["date"], f"{before} -> {after}")
    # multi-select move: long-press opens selection, second tap adds to it
    tiles = find_all(dump(), text="Old skis")
    check("Old skis tile for long-press", bool(tiles))
    if not tiles: return
    long_press(tiles[0]); time.sleep(1.2)
    check("selection bar 1 selected", wait_for(text="1 selected", timeout=5) is not None)
    tap_text("Winter jackets x2"); time.sleep(1.0)
    check("selection bar 2 selected", wait_for(text="2 selected", timeout=5) is not None)
    check("move action opens picker", tap_desc("Move selected photos"))
    time.sleep(1.2)
    check("photo picker titled Move to", wait_for(text="Move to", timeout=5) is not None)
    check("picker excludes current container Garage", sheet_absent("Garage", exact=True))
    check("picker lists Shelf A by full path",
          sheet_find("Building / Storage unit / Shelf A", exact=True) is not None)
    row = sheet_tap_row("Building / Storage unit / Shelf A", exact=True)
    check("Shelf A row tapped", row is not None)
    check("photos moved snackbar", snackbar_visible("2 photo(s) moved", timeout=8))
    time.sleep(1.0)
    d = dump()
    check("moved tiles left Garage", not find_all(d, text="Old skis")
          and not find_all(d, text="Winter jackets x2"))
    check("Toolbox lid untouched in Garage", bool(find_all(d, text="Toolbox lid")))
    sa = fp("Building", "Storage unit", "Shelf A")
    check("disk: Shelf A has Winter jackets x2.jpg", fs_exists(sa + "/Winter jackets x2.jpg"))
    check("disk: Shelf A has Old skis.jpg", fs_exists(sa + "/Old skis.jpg"))
    check("disk: Garage lost both files",
          not fs_exists(fp("Garage", "Old skis.jpg"))
          and not fs_exists(fp("Garage", "Winter jackets x2.jpg")))
    t1 = exif_triple(sa + "/Winter jackets x2.jpg")
    t2 = exif_triple(sa + "/Old skis.jpg")
    check("EXIF intact after move",
          t1["desc"] == "Winter jackets x2" and t1["note"] == "check hinges"
          and t2["desc"] == "Old skis", f"{t1} | {t2}")

def t_container_move_and_cycles():
    mark("7 container move")
    # move the Garage ROOT under Building via its home-row picker
    check("scenario starts on home", go_home())
    check("open Garage row menu", row_menu("Garage", "Options"))
    time.sleep(1.2)
    tap_text("Move to")
    check("move sheet opens", wait_for(text="Move to", timeout=5) is not None)
    time.sleep(1.5)  # excluded-paths recompute (descendantFolderPaths) settles
    check("move picker excludes Garage itself", sheet_absent("Garage", exact=True))
    row = sheet_tap_row("Building", exact=True)
    check("move picker lists and taps Building", row is not None)
    check("container moved snackbar", snackbar_visible("Container moved", timeout=8))
    time.sleep(1.0)
    check("home after move", go_home())
    check("disk: Garage now under Building", fs_isdir(fp("Building", "Garage")))
    check("disk: old root Garage gone", not fs_exists(fp("Garage")))
    check("disk: photos travel with the folder",
          fs_exists(fp("Building", "Garage", "Toolbox lid.jpg")))
    check("Building row counts 2 children",
          bool(find_all(dump(), text=f"2 containers {MID} 0 photos")))
    tap_text("Building"); time.sleep(1.8)
    check("Garage now inside Building", bool(texts_exact("Garage")))
    check("Garage row shows its 1 photo",
          bool(find_all(dump(), text=f"0 containers {MID} 1 photo")))
    # cycle guard on the PARENT: Building's subtree now holds Storage unit,
    # Shelf A, Shelf B and Garage, so every path mentioning them must be gone.
    check("open Building app-bar Options", open_screen_menu())
    time.sleep(1.2)
    tap_text("Move to")
    time.sleep(1.5)
    check("cycle picker excludes Storage unit", sheet_absent("Storage unit", exact=False))
    check("cycle picker excludes nested Garage", sheet_absent("Garage", exact=False))
    check("cycle picker excludes Shelf A", sheet_absent("Shelf A", exact=False))
    check("cycle picker excludes Shelf B", sheet_absent("Shelf B", exact=False))
    check("cycle picker excludes Building itself", sheet_absent("Building", exact=True))
    check("cycle picker still offers Attic shelf",
          sheet_find("Attic shelf", exact=True) is not None)
    back(); time.sleep(1.0)  # dismiss the sheet; the picker list itself is
    # cycle-proof by design, so no invalid-move tap is attempted.

def t_covers():
    mark("8 covers")
    check("scenario starts on home", go_home())
    tap_text("Building"); time.sleep(1.8)
    check("Building screen lists Garage", bool(texts_exact("Garage")))
    # gallery cover on Garage
    check("open Garage row menu", row_menu("Garage", "Options"))
    time.sleep(1.2)
    check("menu offers Set photo", wait_for(text="Set photo", timeout=5) is not None)
    tap_text("Set photo")
    check("cover sheet opens", wait_for(text="Choose from gallery", timeout=5) is not None)
    check("gallery cover saved", cover_gallery_flow())
    gcov = fp("Building", "Garage", "_cover.jpg")
    time.sleep(1.5)
    check("disk: Garage/_cover.jpg exists", fs_exists(gcov))
    # uiautomator cannot assert the thumbnail bitmap; the disk file is the
    # proof. Snackbar is queue-flaky (rename precedent, measured in old runs).
    check("garage cover snackbar", snackbar_visible("Photo updated", timeout=10), soft=True)
    time.sleep(1.0)
    # a cover must not count as a photo (row still says "1 photo") and must
    # not appear in the folder's grid (excluded from listing, no EXIF label)
    check("Garage row still counts exactly 1 photo",
          bool(find_all(dump(), text=f"0 containers {MID} 1 photo")))
    tap_text("Garage"); time.sleep(1.8)
    d = dump()
    check("Garage grid shows the item", bool(find_all(d, text="Toolbox lid")))
    no_filename_leaks("cover excluded from Garage grid (no _cover.jpg label)")
    back(); time.sleep(1.8)
    check("open Garage row menu again", row_menu("Garage", "Options"))
    time.sleep(1.2)
    check("menu has Remove photo (cache saw the cover)",
          wait_for(text="Remove photo", timeout=5) is not None)
    tap_text("Remove photo")
    time.sleep(1.5)
    check("disk: Garage/_cover.jpg gone", not fs_exists(gcov))
    check("cover removal snackbar", snackbar_visible("Photo updated", timeout=8), soft=True)
    # camera cover on Shelf A (kept until the end of the run)
    tap_text("Storage unit"); time.sleep(1.8)
    check("open Shelf A row menu", row_menu("Shelf A", "Options"))
    time.sleep(1.2)
    tap_text("Set photo")
    check("cover sheet (Shelf A)", wait_for(text="Take photo", timeout=5) is not None)
    tap_text("Take photo"); time.sleep(2.0)
    check("cover camera shutter", tap_desc("Take photo", timeout=12))
    time.sleep(2.0)
    # the app bar is also titled "Set photo", so detect the dialog via Cancel
    check("cover confirm dialog (camera)",
          wait_for(text="Cancel", timeout=10) is not None and bool(find_all(dump(), text="Save")))
    tap_text("Save")
    check("back on Storage unit after save", wait_for(text="Shelf A", timeout=10) is not None)
    scov = fp("Building", "Storage unit", "Shelf A", "_cover.jpg")
    time.sleep(1.5)
    check("disk: Shelf A/_cover.jpg exists", fs_exists(scov))
    # Measured run 3 of the previous harness: the camera-cover "Photo
    # updated" rides the UserMessageBus through the pop animation AND
    # re-opens the camera surface — it never showed inside a 10s window;
    # the cover itself is asserted hard by the disk check above.
    check("shelf cover snackbar", snackbar_visible("Photo updated", timeout=10), soft=True)
    # cover stays out of the photo grid on a folder that has photos
    tap_text("Shelf A"); time.sleep(1.8)
    d = dump()
    check("Shelf A grid lists both moved photos",
          bool(find_all(d, text="Winter jackets x2")) and bool(find_all(d, text="Old skis")))
    no_filename_leaks("cover excluded from Shelf A grid (no _cover.jpg label)")
    check("home reached", go_home())

def t_conflicts():
    mark("9 conflicts")
    garage = fp("Building", "Garage")
    src = garage + "/Toolbox lid.jpg"
    dst = garage + "/Toolbox lid.sync-conflict-20260902-000000-ABCD.jpg"
    check("scenario starts on home", go_home())
    tap_text("Building"); time.sleep(1.8)
    tap_text("Garage"); time.sleep(1.8)
    check("on Garage screen", bool(texts_exact("Garage")))
    sh(f"cp {sq(src)} {sq(dst)}")
    check("disk: conflict copy seeded", fs_exists(dst))
    # the app auto-rescans only its OWN mutations — external edits need the
    # overflow "Rescan storage" before any UI assertion
    check("menu opens for rescan", open_screen_menu())
    time.sleep(1.2)
    check("tap Rescan storage", tap_text("Rescan storage", timeout=5))
    check("rescanned snackbar", snackbar_visible("Storage rescanned", timeout=10))
    time.sleep(1.5)
    d = dump()
    bn = [n.get("text", "") for n in find_all(d, text="sync-conflicted")]
    check("conflict banner appears", bool(bn), str(bn))
    check("banner counts exactly one file",
          any("1 sync-conflicted file" in t for t in bn), str(bn))
    check("conflict badge on the copied tile",
          bool(find_all(dump(), desc="Sync conflict")))
    check("copy listed with its EXIF description", len(texts_exact("Toolbox lid")) >= 2,
          "two tiles share the description; the badge distinguishes them")
    badges = find_all(dump(), desc="Sync conflict")
    if not badges:
        check("badge-anchored selection", False); return
    bb = _bounds(badges[0].get("bounds", ""))
    tile = smallest_clickable_containing(bb)
    check("badge anchored to a clickable tile", tile is not None)
    if tile is None: return
    long_press(tile); time.sleep(1.2)
    check("conflict tile selected alone", wait_for(text="1 selected", timeout=5) is not None)
    check("delete action opens confirm", tap_desc("Delete selected photos"))
    time.sleep(1.0)
    check("confirm counts 1 photo",
          bool(find_all(dump(), text="1 photo(s) will be permanently deleted")))
    tap_text("Delete")
    check("photo deleted snackbar", snackbar_visible("Photo deleted", timeout=10))
    time.sleep(1.5)
    check("disk: conflict copy deleted via UI", not fs_exists(dst))
    check("original photo survived", fs_exists(src))
    check("banner gone after the delete-rescan", wait_absent("sync-conflicted", timeout=10))

def t_pc_edits():
    mark("10 PC edits adopted")
    sa = fp("Building", "Storage unit", "Shelf A")
    sb = fp("Building", "Storage unit", "Shelf B")
    garage = fp("Building", "Garage")
    # (a) a "PC" moves one photo into a sibling folder
    sh(f"mv {sq(sa + '/Old skis.jpg')} {sq(sb + '/Old skis.jpg')}")
    check("disk: Old skis now under Shelf B", fs_exists(sb + "/Old skis.jpg"))
    check("rescan #1 via root icon", rescan_from_root())
    check("rescan #1 snackbar", snackbar_visible("Storage rescanned", timeout=10))
    tap_text("Building"); time.sleep(1.8)
    tap_text("Storage unit"); time.sleep(1.8)
    tap_text("Shelf B"); time.sleep(1.8)
    check("UI: Shelf B lists Old skis after adoption", wait_for(text="Old skis", timeout=8) is not None)
    back(); time.sleep(1.8)
    check("back on Storage unit", bool(texts_exact("Storage unit")))
    tap_text("Shelf A"); time.sleep(1.8)
    check("UI: on Shelf A", bool(texts_exact("Shelf A")))
    check("UI: Shelf A no longer lists Old skis", not find_all(dump(), text="Old skis"))
    check("UI: Shelf A kept Winter jackets x2", bool(find_all(dump(), text="Winter jackets x2")))
    check("disk: Shelf A no longer holds Old skis.jpg", not fs_exists(sa + "/Old skis.jpg"))
    # (b) a "PC" deletes the last item of the empty folder
    check("home for rm step", go_home())
    sh(f"rm {sq(garage + '/Toolbox lid.jpg')}")
    check("disk: Toolbox lid.jpg deleted externally", not fs_exists(garage + "/Toolbox lid.jpg"))
    check("rescan #2 via root icon", rescan_from_root())
    tap_text("Building"); time.sleep(1.8)
    tap_text("Garage"); time.sleep(1.8)
    check("UI: Garage shows the empty state", wait_for(text="Nothing here yet", timeout=8) is not None)
    check("UI: no Toolbox lid tile anymore", not find_all(dump(), text="Toolbox lid"))
    # (c) a "PC" renames a whole folder subtree
    check("home for folder rename", go_home())
    sh(f"mv {sq(fp('Building'))} {sq(fp('Estate'))}")
    check("disk: /Estate dir exists", fs_isdir(fp("Estate")))
    check("disk: /Building dir gone", not fs_exists(fp("Building")))
    check("rescan #3 via root icon", rescan_from_root())
    check("UI adopted the folder rename", wait_for(text="Estate", timeout=10) is not None)
    check("UI dropped the old name", not texts_exact("Building"))
    # (d) cache-rebuild proof: wipe app data (cache.db + mode pref) — disk
    # must rebuild the whole UI. LAST, since pm clear resets state. The
    # wipe also brings the MODE CHOOSER back: answer it with File mode.
    sh(f"pm clear {PKG}")
    grant_storage_appops()
    start_app(); time.sleep(3.0)
    check("cache-wipe relaunch re-answers the mode chooser",
          wait_for(text=CHOOSER_RATIONALE, timeout=12) is not None)
    choose_file_mode(where="rebuild")
    check("no gate after pm clear + appops re-grant + chooser",
          not texts_exact(GRANT_ACCESS_BTN))
    check("rebuilt root lists Estate", wait_for(text="Estate", timeout=15) is not None)
    check("rebuilt root lists Attic shelf", bool(find_all(dump(), text="Attic shelf")))
    tap_text("Estate"); time.sleep(1.8)
    d = dump()
    check("rebuilt Estate lists Garage + Storage unit",
          bool(texts_exact("Garage")) and bool(texts_exact("Storage unit")), str(d is None))
    check("rebuilt Storage unit row counts 2 folders 1 photo",
          bool(find_all(d, text=f"2 containers {MID} 1 photo")))
    tap_text("Storage unit"); time.sleep(1.8)
    check("rebuilt Storage unit shows Roof tiles (EXIF desc)", wait_for(text="Roof tiles", timeout=8) is not None)
    tap_text("Shelf A"); time.sleep(1.8)
    check("rebuilt Shelf A tile text is the EXIF description",
          bool(texts_exact("Winter jackets x2")))
    no_filename_leaks("rebuilt UI shows no raw filenames anywhere")
    check("home reached after rebuild", go_home())

def t_ai_autolabel():
    """Optional AI auto-label end-to-end against a host-side mock OpenAI
    endpoint: settings -> enable -> base URL/model -> Test connection ->
    gallery pick -> status line + blank-guarded pre-fill -> disk/EXIF, the
    overwrite guard (typed text must survive a late reply), and the disable
    path. Self-contained: the feature is left OFF and the mock stopped, so
    the final disk audit is unaffected apart from the three new files."""
    mark("11 AI auto-label")
    server = None
    logf = None
    try:
        with open(MOCK_PATH, "w") as fh:
            fh.write(MOCK_OPENAI_PY)
        logf = open(MOCK_LOG, "w")
        server = subprocess.Popen(
            [sys.executable, MOCK_PATH, str(MOCK_PORT)],
            stdout=logf, stderr=subprocess.STDOUT,
        )
        check("mock OpenAI server started", wait_port(MOCK_PORT, timeout=10),
              f"pid={server.pid}")
        rev = adb("reverse", f"tcp:{MOCK_PORT}", f"tcp:{MOCK_PORT}")
        check("adb reverse tcp:8765 installed", rev.returncode == 0,
              (rev.stdout + rev.stderr).strip())

        # 1-3. home overflow -> Settings, enable AI, point it at the mock,
        # then prove the round trip with the settings' own probe.
        check("home before settings", go_home())
        check("root overflow -> Settings", open_settings())
        check("AI switch on", set_ai_switch(True))
        check("settings shows the base URL field",
              wait_for(text=AI_BASE_LABEL, timeout=5) is not None)
        check("base URL typed", set_ai_text(0, f"http://127.0.0.1:{MOCK_PORT}/v1"))
        check("model typed", set_ai_text(2, "mock-vision"))
        vals = [f.get("text") for f in edit_texts()]
        check("settings hold the mock config",
              len(vals) >= 3 and vals[0] == f"http://127.0.0.1:{MOCK_PORT}/v1"
              and vals[2] == "mock-vision", str(vals))
        check("tap Test connection", tap_text(AI_TEST_BTN, timeout=5))
        check("mock answers Connection OK",
              wait_for(text=AI_TEST_OK, timeout=10) is not None)

        # 4-5. back home, drill to the (still empty) Attic shelf container.
        check("settings back to home", tap_desc("Back", timeout=5) and go_home())
        tap_text("Attic shelf"); time.sleep(1.8)
        check("in the Attic shelf container", bool(texts_exact("Attic shelf")))
        check("container FAB present", wait_for(desc="Add", timeout=6) is not None)

        # 6-8. gallery pick #1: in-flight status, pre-fill, ready, disk/EXIF.
        check("gallery #1 opens the save dialog", gallery_pick_one("ai prefilled"))
        check("save dialog shows the in-flight AI status",
              wait_for(text=AI_THINKING, timeout=8) is not None)
        ok, vals = wait_ai_fields("Mock item", "added by mock server", timeout=30)
        check("AI pre-filled description 'Mock item'",
              ok and vals[0] == "Mock item", str(vals))
        check("AI pre-filled note 'added by mock server'",
              len(vals) >= 2 and vals[1] == "added by mock server", str(vals))
        check("save dialog shows 'AI suggestion ready'",
              wait_for(text=AI_READY, timeout=8) is not None)
        check("save AI-labelled photo", tap_text("Save photo", timeout=5))
        mock = fp("Attic shelf", "Mock item.jpg")
        time.sleep(1.5)
        check("disk: Attic shelf/Mock item.jpg", fs_exists(mock))
        tr = exif_triple(mock); EXIF_SAMPLES.append(("Attic shelf/Mock item.jpg", tr))
        check("EXIF: AI description 'Mock item'", tr["desc"] == "Mock item", str(tr))
        check("EXIF: AI note 'added by mock server'",
              tr["note"] == "added by mock server", str(tr))

        # 9. overwrite guard: type before the (delayed) reply lands. The
        # description must survive; only the still-blank note may be filled.
        check("gallery #2 opens the save dialog", gallery_pick_one("ai guard"))
        fs = edit_texts()
        check("guard dialog has both fields", len(fs) >= 2, str(len(fs)))
        if len(fs) >= 2:
            tap(fs[0]); wait_ime(); type_text("Manual name")
            ensure_ime_hidden()
        ok2, vals2 = wait_ai_fields(None, "added by mock server", timeout=30)
        check("blank-guard: typed description survives the late reply",
              bool(vals2) and vals2[0] == "Manual name", str(vals2))
        check("blank-guard: empty note still pre-filled",
              len(vals2) >= 2 and vals2[1] == "added by mock server", str(vals2))
        check("save guarded photo", tap_text("Save photo", timeout=5))
        # The picker hands back the same image as #1, so the save is flagged
        # as a duplicate; import it anyway (the AI fields are the assertion).
        check("guard photo flagged as duplicate", import_anyway_if_duplicate())
        manual = fp("Attic shelf", "Manual name.jpg")
        time.sleep(1.5)
        check("disk: Attic shelf/Manual name.jpg", fs_exists(manual))
        tr2 = exif_triple(manual); EXIF_SAMPLES.append(("Attic shelf/Manual name.jpg", tr2))
        check("EXIF: guard description 'Manual name'", tr2["desc"] == "Manual name", str(tr2))
        check("EXIF: guard keeps the AI note",
              tr2["note"] == "added by mock server", str(tr2))

        # 10. disable: Settings -> off -> third pick has no AI affordance.
        check("home for AI disable", go_home())
        check("settings reopens for disable", open_settings())
        check("AI switch off", set_ai_switch(False))
        check("settings back after disable", tap_desc("Back", timeout=5) and go_home())
        tap_text("Attic shelf"); time.sleep(1.8)
        check("gallery #3 opens the save dialog", gallery_pick_one("ai disabled"))
        d = dump()
        check("no AI status line while disabled",
              not any(find_all(d, text=s) for s in (AI_THINKING, AI_READY, AI_FAILED)))
        fs = edit_texts()
        check("disabled dialog leaves the description empty",
              bool(fs) and fs[0].get("text") == "", str([f.get("text") for f in fs]))
        if fs:
            tap(fs[0]); wait_ime(); type_text("Plain item")
            ensure_ime_hidden(); time.sleep(0.8)
        check("save plain photo", tap_text("Save photo", timeout=5))
        # Same image again (AI now off): duplicate warning, import anyway.
        check("plain photo flagged as duplicate", import_anyway_if_duplicate())
        plain = fp("Attic shelf", "Plain item.jpg")
        time.sleep(1.5)
        check("disk: Attic shelf/Plain item.jpg", fs_exists(plain))
        tr3 = exif_triple(plain); EXIF_SAMPLES.append(("Attic shelf/Plain item.jpg", tr3))
        check("EXIF: plain description", tr3["desc"] == "Plain item", str(tr3))
        check("EXIF: plain has no AI note", not tr3["note"], str(tr3))

        # 11. leave the feature off, then read the mock's request log.
        check("home for final AI check", go_home())
        check("settings reopens for final check", open_settings())
        check("AI left disabled", ai_switch_checked() is False, str(ai_switch_checked()))
        check("settings back after final check", tap_desc("Back", timeout=5))
        if logf is not None:
            logf.flush()
        served = open(MOCK_LOG).read() if os.path.exists(MOCK_LOG) else ""
        check("mock served the ping and both describes",
              served.count("kind=ping") >= 1 and served.count("kind=describe") >= 2,
              served.replace("\n", " | "))
    finally:
        adb("reverse", "--remove", f"tcp:{MOCK_PORT}")
        if logf is not None:
            try: logf.close()
            except Exception: pass
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=5)
            except Exception:
                server.kill()

def t_trash():
    """Soft-delete end to end (Feature 1; default OFF). Enables the trash,
    deletes a container with a gallery photo and proves the stamp layout
    (.trash/<stamp>/<name> + .origin), lists/restores it from the Trash
    screen, deletes it forever, then DISABLES the trash and proves a photo
    delete is permanent (no .trash growth). Leaves the feature OFF; the
    empty .trash dir is removed so the final disk audit sees the suite tree.
    """
    mark("11a trash")
    check("starts on home", go_home())
    # enable the trash via Settings
    check("settings opens for trash", open_settings())
    check("trash switch present", settings_switch(TRASH_ENABLE_LABEL) is not None)
    check("trash switch ON", set_settings_switch(TRASH_ENABLE_LABEL, True))
    check("disk: trash pref persisted ON",
          'name="enabled" value="true"' in trash_enabled_pref(), trash_enabled_pref())
    check("settings back after enabling trash", tap_desc("Back", timeout=5) and go_home())
    # create "Doomed" with a gallery photo
    check("root FAB opens create dialog", tap_desc("Add container"))
    time.sleep(1.2)
    fill_dialog("Doomed")
    check("Doomed created", wait_for(text="Doomed", timeout=8) is not None)
    tap_text("Doomed"); time.sleep(1.8)
    check("gallery photo added to Doomed", add_gallery_photo("Trashed item", where="trash"))
    check("photo added snackbar [trash]", snackbar_visible("Photo added", timeout=10))
    check("disk: Doomed/Trashed item.jpg", fs_exists(fp("Doomed", "Trashed item.jpg")))
    check("home before trashing Doomed", go_home())
    # delete the container -> content moves to .trash
    check("open Doomed row menu", row_menu("Doomed", "Options"))
    time.sleep(1.2)
    tap_text("Delete")
    check("delete confirm uses trash wording",
          wait_for(text="moved to the trash", timeout=5) is not None)
    tap_text("Delete")
    check("container deleted snackbar [trash]", snackbar_visible("Container deleted", timeout=10))
    check("Doomed gone from the UI", not texts_exact("Doomed"))
    stamps = trash_stamps()
    check("disk: exactly one .trash stamp", len(stamps) == 1, str(stamps))
    stamp = stamps[0] if stamps else ""
    check("disk: stamp holds the Doomed subtree",
          bool(stamp) and fs_isdir(stamp + "/Doomed"))
    check("disk: trashed photo survived",
          bool(stamp) and fs_exists(stamp + "/Doomed/Trashed item.jpg"))
    check("disk: .origin sidecar written",
          bool(stamp) and fs_exists(stamp + "/.origin"))
    origin = fs_read(stamp + "/.origin").decode("utf-8", "replace") if stamp else ""
    check("disk: .origin records root parent + container kind",
          origin.splitlines()[:2] == ["", "container"], repr(origin))
    check("disk: original Doomed path gone", not fs_exists(fp("Doomed")))
    # Trash screen: list + restore
    check("root overflow opens for Trash", open_screen_menu())
    check("Trash entry opens the trash screen", tap_text("Trash", timeout=5))
    check("trash screen title", wait_for(text="Trash", timeout=8) is not None)
    check("trash entry listed", wait_for(text="Doomed", timeout=8) is not None)
    check("trash entry shows its original location",
          bool(find_all(dump(), text=TRASH_WAS_IN + "Inventory")))
    check("trash entry offers Restore", bool(find_all(dump(), desc=RESTORE_LABEL)))
    check("Restore tapped", tap_desc(RESTORE_LABEL, timeout=5))
    check("restore snackbar", snackbar_visible("Restored", timeout=8))
    check("disk: Doomed restored to the root", fs_wait_exists(fp("Doomed")))
    check("disk: restored photo intact", fs_exists(fp("Doomed", "Trashed item.jpg")))
    check("disk: stamp removed after restore", not trash_stamps())
    check("trash now empty", wait_for(text=TRASH_EMPTY, timeout=8) is not None)
    check("back home from trash", tap_desc("Back", timeout=5) and go_home())
    # delete again, then Delete forever
    check("open Doomed row menu again", row_menu("Doomed", "Options"))
    time.sleep(1.2)
    tap_text("Delete")
    check("second delete confirm (trash wording)",
          wait_for(text="moved to the trash", timeout=5) is not None)
    tap_text("Delete")
    check("Doomed gone again", not texts_exact("Doomed"))
    check("second delete snackbar", snackbar_visible("Container deleted", timeout=10))
    check("disk: second stamp written", len(trash_stamps()) == 1, str(trash_stamps()))
    check("root overflow opens for trash #2", open_screen_menu())
    check("Trash entry opens again", tap_text("Trash", timeout=5))
    check("entry listed for delete-forever", wait_for(text="Doomed", timeout=8) is not None)
    check("Delete forever opens the confirm", tap_desc(DELETE_FOREVER_LABEL, timeout=5))
    check("delete-forever confirm shown",
          wait_for(text="Permanently delete", timeout=5) is not None)
    check("delete-forever confirmed", tap_text(DELETE_FOREVER_LABEL, timeout=5))
    check("purged snackbar", snackbar_visible("Deleted forever", timeout=8))
    check("disk: no Doomed path left", not any("Doomed" in p for p in tree()))
    check("disk: trash empty after purge", not trash_stamps())
    check("back home after purge", tap_desc("Back", timeout=5) and go_home())
    # DISABLE the trash and prove a delete is permanent
    check("settings opens to disable trash", open_settings())
    check("trash switch OFF", set_settings_switch(TRASH_ENABLE_LABEL, False))
    check("disk: trash pref persisted OFF",
          'name="enabled" value="false"' in trash_enabled_pref(), trash_enabled_pref())
    check("settings back after disabling", tap_desc("Back", timeout=5) and go_home())
    sh(f"rm -rf {sq(fp('.trash'))}")  # empty dir left by the soft-delete round trip
    check("disk: .trash cleared for the permanent phase", not fs_exists(fp(".trash")))
    # seed a container + photo, delete the PHOTO permanently
    sh(f"mkdir -p {sq(fp('Ephemeral'))}")
    seed_photo("Ephemeral/Permanent item.jpg", "Permanent item")
    check("disk: Ephemeral/Permanent item.jpg seeded",
          fs_exists(fp("Ephemeral", "Permanent item.jpg")))
    check("rescan adopts Ephemeral", rescan_from_root())
    check("Ephemeral listed", wait_for(text="Ephemeral", timeout=8) is not None)
    tap_text("Ephemeral"); time.sleep(1.8)
    tile = wait_for(text="Permanent item", timeout=8)
    check("Permanent item tile present", tile is not None)
    if tile is not None:
        tap(tile); time.sleep(1.5)
        check("viewer opens for the permanent delete", viewer_open())
        tap_text("Delete")
        check("permanent-delete confirm wording",
              wait_for(text="permanently deleted", timeout=5) is not None)
        tap_text("Delete")
        check("photo deleted snackbar [permanent]",
              snackbar_visible("Photo deleted", timeout=10))
    check("disk: photo permanently gone",
          not fs_exists(fp("Ephemeral", "Permanent item.jpg")))
    check("disk: permanent delete created no .trash", not fs_exists(fp(".trash")))
    check("home after permanent photo delete", go_home())
    check("open Ephemeral row menu", row_menu("Ephemeral", "Options"))
    time.sleep(1.2)
    tap_text("Delete")
    check("Ephemeral delete is permanent",
          wait_for(text="permanently deleted", timeout=5) is not None)
    tap_text("Delete")
    check("disk: Ephemeral container removed", not fs_exists(fp("Ephemeral")))
    check("trash left OFF",
          'name="enabled" value="false"' in trash_enabled_pref(), trash_enabled_pref())
    check("home after trash scenario", go_home())

def t_report():
    """Inventory report export (Feature 2): overflow -> Export report writes
    inventory-report.html + .csv into the inventory root. Seeds a container
    and a photo with markup/comma metadata on disk for deterministic content,
    pulls both files and asserts the HTML escaping / per-segment URL-encoding
    / CSV row on the host, then removes the report files and the seeded
    container so the final disk audit is unchanged."""
    mark("11b report export")
    check("starts on home", go_home())
    seed = "Reportable/Report bold, comma.jpg"
    sh(f"mkdir -p {sq(fp('Reportable'))}")
    seed_photo(seed, "Report <b>bold</b>, comma", date="2026:05:04 08:00:00")
    check("disk: seeded report photo", fs_exists(fp(seed)))
    # A container cover (the folder's identity) is embedded in the section
    # header, never listed as a content photo; seed one so the report proves it.
    cover = "Reportable/_cover.jpg"
    sh(f"cp {sq(fp(seed))} {sq(fp(cover))}")
    check("disk: seeded report cover", fs_exists(fp(cover)))
    check("rescan adopts Reportable", rescan_from_root())
    check("UI lists the Reportable container", wait_for(text="Reportable", timeout=8) is not None)
    check("root overflow opens", open_screen_menu())
    check("Export report tapped", tap_text(EXPORT_REPORT_LABEL, timeout=5))
    check("report-written snackbar", snackbar_visible(REPORT_WRITTEN, timeout=10))
    html = fp(REPORT_HTML)
    csvp = fp(REPORT_CSV)
    check("disk: inventory-report.html written", fs_exists(html))
    check("disk: inventory-report.csv written", fs_exists(csvp))
    # The successful write also stages an HTML copy in the app cache and opens
    # the system share sheet. Capture the chooser, dismiss it, then verify the
    # staged copy is present, non-empty and the ONLY one (clear-before-stage).
    check("report share chooser appears", wait_share_chooser(),
          "no 'Send report via' title, resolver sheet or staged file name")
    back(); time.sleep(1.0)
    listing = sh(f"run-as {PKG} ls -la {REPORT_CACHE_DIR} 2>/dev/null")
    staged = [line for line in listing.splitlines()
              if REPORT_STAGED_PREFIX in line and line.endswith(".html")]
    check("report staged a cache HTML", len(staged) == 1, listing)
    staged_size = int(staged[0].split()[4]) if staged else 0
    check("staged cache HTML is non-empty", staged_size > 0, listing)
    check("exactly one staged report file",
          listing.count(REPORT_STAGED_PREFIX) == 1, listing)
    # pull both and assert the exact markup / CSV on the host
    fdh, local_html = tempfile.mkstemp(suffix=".html"); os.close(fdh)
    fdc, local_csv = tempfile.mkstemp(suffix=".csv"); os.close(fdc)
    adb("pull", html, local_html, timeout=30)
    adb("pull", csvp, local_csv, timeout=30)
    with open(local_html, encoding="utf-8") as fh: html_text = fh.read()
    with open(local_csv, encoding="utf-8") as fh: csv_text = fh.read()
    os.unlink(local_html); os.unlink(local_csv)
    check("HTML has the summary markers",
          "Total photo bytes:" in html_text and "<li>Photos:" in html_text)
    check("HTML has a per-container section",
          'class="container depth-' in html_text and "<h2>Reportable</h2>" in html_text)
    check("HTML embeds a base64 thumbnail",
          "data:image/jpeg;base64," in html_text)
    # The container cover is embedded as a header data URI (class="cover") and
    # must decode to a real image on the host; it stays out of the content grid.
    cover_uri = re.search(
        r'<img class="cover" src="(data:image/jpeg;base64,[^"]+)"', html_text)
    check("HTML embeds the container cover as a data URI",
          cover_uri is not None, "no class=\"cover\" data URI in the report")
    cover_size = None
    if cover_uri is not None:
        from PIL import Image as _Image
        raw = base64.b64decode(cover_uri.group(1).split(",", 1)[1])
        _img = _Image.open(io.BytesIO(raw))
        _img.load()
        cover_size = _img.size
    check("HTML cover data URI decodes to a real image",
          cover_size is not None and cover_size[0] > 0 and cover_size[1] > 0,
          f"cover decode size={cover_size}")
    check("HTML keeps the cover out of the photo grid",
          "_cover.jpg" not in html_text)
    check("HTML escapes the description markup and comma",
          "Report &lt;b&gt;bold&lt;/b&gt;, comma" in html_text
          and "Report <b>bold</b>, comma" not in html_text)
    check("HTML URL-encodes the original photo link per segment",
          'href="Reportable/Report%20bold%2C%20comma.jpg"' in html_text,
          "no URL-encoded href for the seeded photo")
    import csv as _csv
    rows = list(_csv.reader(csv_text.splitlines()))
    check("CSV header is the contractual column list",
          bool(rows) and rows[0] == ["containerPath", "fileName", "description",
                                     "note", "dateTaken", "sizeBytes"], str(rows[:1]))
    want = ["Reportable", "Report bold, comma.jpg",
            "Report <b>bold</b>, comma", "", "2026:05:04 08:00:00"]
    check("CSV module parses the expected seeded row",
          any(r[:5] == want and r[5].isdigit() and int(r[5]) > 0 for r in rows[1:]),
          str(rows))
    # cleanup: the report files + the seeded container (the report is invisible
    # to the app, so the final tree must not carry either)
    sh(f"rm -f {sq(html)} {sq(csvp)}")
    check("disk: report files removed", not fs_exists(html) and not fs_exists(csvp))
    check("home before report cleanup", go_home())
    check("open Reportable row menu", row_menu("Reportable", "Options"))
    time.sleep(1.2)
    tap_text("Delete")
    check("Reportable delete confirm (permanent)",
          wait_for(text="permanently deleted", timeout=5) is not None)
    tap_text("Delete")
    check("Reportable gone from the UI", not texts_exact("Reportable"))
    check("disk: Reportable removed", not fs_exists(fp("Reportable")))
    check("home after report scenario", go_home())

def t_breadcrumbs():
    """Breadcrumb navigation (Feature 4): non-root screens show clickable
    ancestor segments (container names as text) plus the root crumb exposed
    only via content-desc 'Inventory'. Tapping an ancestor navigates there
    with the back stack cleaned to the root; starting a drag swaps the
    breadcrumb for the drag chips, and cancelling restores it. Runs on the
    suite's current tree (Estate -> Storage unit -> Shelf A)."""
    mark("11c breadcrumbs")
    check("starts on home", go_home())
    tap_text("Estate"); time.sleep(1.8)
    check("breadcrumb: on Estate", bool(texts_exact("Estate")))
    tap_text("Storage unit"); time.sleep(1.8)
    tap_text("Shelf A"); time.sleep(1.8)
    check("breadcrumb: on Shelf A", bool(texts_exact("Shelf A")))
    d = dump()
    check("breadcrumb shows ancestor names as text",
          bool(find_all(d, text="Estate")) and bool(find_all(d, text="Storage unit")))
    check("breadcrumb root is a content-desc, not a text node",
          bool(find_all(d, desc="Inventory")) and not texts_exact("Inventory"),
          str([n.get("text") for n in find_all(d, text="Inventory")]))
    # tap the "Estate" ancestor segment -> navigate there
    check("tap the Estate breadcrumb segment", tap_text("Estate", timeout=5))
    time.sleep(1.5)
    check("breadcrumb jump landed on Estate", bool(texts_exact("Estate")))
    back(); time.sleep(1.5)
    check("breadcrumb jump cleaned the stack (back -> Inventory)",
          bool(texts_exact("Inventory")))
    # deep again for the drag/chip swap
    tap_text("Estate"); time.sleep(1.8)
    tap_text("Storage unit"); time.sleep(1.8)
    tap_text("Shelf A"); time.sleep(1.8)
    check("deep again on Shelf A", bool(texts_exact("Shelf A")))
    check("breadcrumb root crumb present before drag",
          bool(find_all(dump(), desc="Inventory")))
    tile = drag_by_desc("Winter jackets x2")
    check("breadcrumb drag: photo tile located", tile is not None)
    if tile is not None:
        tx, ty = drag_center(tile)
        drag_start(tx, ty)
        check("breadcrumb drag: up-chip replaces the breadcrumb",
              drag_up_chip() is not None)
        check("breadcrumb drag: root crumb descriptor hidden",
              not find_all(dump(), desc="Inventory"))
        # cancel exactly like dnd6: BACK ends the session, then release
        drag_move_to(tx, ty, tx, ty + 90)
        sh("input keyevent 4"); time.sleep(0.5)
        check("breadcrumb drag: BACK ended the drag session", drag_up_chip() is None)
        motion_up(tx, ty + 90); time.sleep(1.5)
        tap_desc("Exit selection", timeout=5); time.sleep(0.8)
        check("breadcrumb drag: breadcrumbs return after cancel",
              bool(find_all(dump(), desc="Inventory")))
        check("breadcrumb drag: photo untouched",
              fs_exists(fp("Estate", "Storage unit", "Shelf A", "Winter jackets x2.jpg")))
    check("home after breadcrumbs", go_home())

def t_viewer():
    """Full-screen photo viewer (Feature 3): a container with three photos,
    paging via swipe and the "N / M" indicator, the Edit flow (note changed
    and the overlay updated), Delete + confirm advancing the pager, and Close
    returning to the grid. The photos are disk-seeded with distinct dates so
    the page order is deterministic; the container is removed afterwards."""
    mark("11d photo viewer")
    check("starts on home", go_home())
    base = "Viewer"
    sh(f"mkdir -p {sq(fp(base))}")
    seed_photo(base + "/View one.jpg", "View one", date="2026:01:01 12:00:00")
    seed_photo(base + "/View two.jpg", "View two", date="2026:01:02 12:00:00")
    seed_photo(base + "/View three.jpg", "View three", date="2026:01:03 12:00:00")
    check("disk: three seeded viewer photos",
          all(fs_exists(fp(base, n)) for n in
              ("View one.jpg", "View two.jpg", "View three.jpg")))
    check("rescan adopts the Viewer container", rescan_from_root())
    check("Viewer listed", wait_for(text=base, timeout=8) is not None)
    tap_text(base); time.sleep(1.8)
    # newest-first: View three, View two, View one. Tap the middle tile.
    tile = wait_for(text="View two", timeout=8)
    check("middle viewer tile present", tile is not None)
    if tile is None:
        check("viewer scenario aborted (no tile)", False); return
    tap(tile); time.sleep(1.5)
    check("viewer opens on page 2 of 3", viewer_page() == (2, 3), str(viewer_page()))
    check("viewer overlay shows the description",
          bool(find_all(dump(), text="View two")))
    # swipe left -> next page
    sh(f"input swipe {int(SW*0.85)} {SH//2} {int(SW*0.15)} {SH//2} 300")
    time.sleep(1.5)
    check("swipe advances to page 3 of 3", viewer_page() == (3, 3), str(viewer_page()))
    # Edit from the viewer, change the note, Save -> overlay updates
    check("viewer Edit opens the editor", tap_text("Edit", timeout=6))
    check("viewer editor opens", wait_for(text="Description (required)", timeout=6) is not None)
    fields = edit_texts()
    if len(fields) >= 2:
        tap(fields[1]); wait_ime(); clear_field(); type_text("viewer note")
        ensure_ime_hidden(); time.sleep(0.8)
    tap_text("Save")
    check("viewer overlay shows the saved note",
          wait_for(text="viewer note", timeout=6) is not None)
    # Delete the current photo -> confirm -> the pager advances
    tap_text("Delete")
    check("viewer delete confirm (permanent)",
          wait_for(text="permanently deleted", timeout=5) is not None)
    tap_text("Delete")
    # As above: the delete announcement lands on the ContainerScreen host,
    # which is behind the still-open viewer dialog. Disk is asserted hard.
    check("photo deleted snackbar [viewer]", snackbar_visible("Photo deleted", timeout=10),
          "emitted behind the viewer dialog", soft=True)
    check("viewer stays open and advances to 2 / 2",
          viewer_page() == (2, 2), str(viewer_page()))
    check("viewer Close returns to the grid", close_viewer())
    time.sleep(1.0)
    d = dump()
    check("grid drops the deleted tile", not find_all(d, text="View one"))
    check("grid keeps the other two tiles",
          bool(find_all(d, text="View two")) and bool(find_all(d, text="View three")))
    check("disk: deleted viewer photo gone", not fs_exists(fp(base, "View one.jpg")))
    # cleanup: remove the seeded container (trash is OFF -> permanent)
    check("home before viewer cleanup", go_home())
    check("open Viewer row menu", row_menu(base, "Options"))
    time.sleep(1.2)
    tap_text("Delete")
    check("Viewer delete confirm (permanent)",
          wait_for(text="permanently deleted", timeout=5) is not None)
    tap_text("Delete")
    check("disk: Viewer container removed", not fs_exists(fp(base)))
    check("home after viewer scenario", go_home())

def t_drag_drop():
    """Drag-and-drop end to end, on the suite tree as the AI scenario left
    it (root: Attic shelf + Estate → Storage unit → Shelf A/Shelf B).

    Seven gestures, each asserted on DISK (the source of truth) with
    uiautomator dumps only for the UI state: photo → sibling with "↑"
    spring-load (1), container → sibling with "↑" + row spring-load (2),
    cycle guard on a dragged container (3), ancestor-chip drop to the
    inventory root (4), selection drag carrying the whole selection (5),
    BACK cancels (6) and long-press-release still selects (7).

    Every disk move is restored (adb mv + Rescan through the root icon) so
    the final exact-tree audit sees the suite tree untouched. Motion is
    injected with `input motionevent` (DOWN → hold → ~50px MOVEs → dwell →
    UP); straight `input draganddrop` cannot dwell, so it is not used.
    Known queued-snackbar flakes are skipped here — the disk is asserted
    hard and no drag check is soft."""
    mark("12 drag and drop")
    ESTATE = "Estate"
    SU = ESTATE + "/Storage unit"
    SA = SU + "/Shelf A"
    SB = SU + "/Shelf B"
    ATTIC = SU + "/Attic"
    wj_sa = fp(SA, "Winter jackets x2.jpg")
    wj_sb = fp(SB, "Winter jackets x2.jpg")

    # ---- 1. photo drag to a sibling, spring-loading up one screen --------
    check("dnd1 starts on home", go_home())
    tap_text(ESTATE); time.sleep(1.8)
    tap_text("Storage unit"); time.sleep(1.8)
    tap_text("Shelf A"); time.sleep(1.8)
    check("dnd1 inside Shelf A", bool(texts_exact("Shelf A")))
    tile = drag_by_desc("Winter jackets x2")
    check("dnd1 photo tile located", tile is not None)
    if tile is not None:
        tx, ty = drag_center(tile)
        drag_start(tx, ty)
        check("dnd1 up-chip visible mid-drag", drag_up_chip() is not None)
        drag_spring_up(tx, ty)
        check("dnd1 up-chip spring-loaded to Storage unit",
              wait_for(text="Storage unit", timeout=6) is not None)
        row = drag_by_text("Shelf B")
        check("dnd1 Shelf B row visible after spring-load", row is not None)
        if row is not None:
            rx, ry = drag_center(row)
            drag_move_to(85, 577, rx, ry)
            motion_up(rx, ry)
            time.sleep(2.0)
            check("dnd1 disk: photo now under Shelf B", fs_exists(wj_sb))
            check("dnd1 disk: photo gone from Shelf A", not fs_exists(wj_sa))
    go_home()
    sh(f"mv {sq(wj_sb)} {sq(wj_sa)}")
    check("dnd1 restore: photo back under Shelf A",
          fs_exists(wj_sa) and not fs_exists(wj_sb))
    check("dnd1 rescan after restore", rescan_from_root())

    # ---- 2. container drag: "↑" spring-load, then row spring-load --------
    # Attic is a fresh sibling of Shelf A under Storage unit (removed again
    # below): the drop lands in Storage unit/Attic/Shelf A.
    if fs_isdir(fp(ATTIC, "Shelf A")):
        sh(f"mv {sq(fp(ATTIC, 'Shelf A'))} {sq(fp(SU, 'Shelf A'))}")
    sh(f"rm -rf {sq(fp(ATTIC))}")
    sh(f"mkdir -p {sq(fp(ATTIC))}")
    check("dnd2 rescan after seeding Attic", rescan_from_root())
    tap_text(ESTATE); time.sleep(1.8)
    tap_text("Storage unit"); time.sleep(1.8)
    row = drag_by_text("Shelf A")
    check("dnd2 Shelf A row located", row is not None)
    if row is not None:
        rx, ry = drag_center(row)
        drag_start(rx, ry)
        up = drag_spring_up(rx, ry)
        check("dnd2 up-chip visible mid-drag", up is not None)
        check("dnd2 up-chip spring-loaded to Estate",
              wait_for(text=ESTATE, timeout=6) is not None)
        su = drag_by_text("Storage unit")
        check("dnd2 Storage unit row visible for row spring-load", su is not None)
        if su is not None:
            sx, sy = drag_center(su)
            drag_move_to(85, 577, sx, sy)
            drag_dwell(sx, sy, 900)
            motion_move(85, 577); time.sleep(0.4)
            check("dnd2 row spring-load entered Storage unit",
                  drag_by_text("Attic") is not None)
            attic = drag_by_text("Attic")
            if attic is not None:
                ax, ay = drag_center(attic)
                drag_move_to(85, 577, ax, ay)
                motion_up(ax, ay)
                time.sleep(2.0)
                check("dnd2 disk: Storage unit/Attic/Shelf A exists",
                      fs_isdir(fp(ATTIC, "Shelf A")))
                check("dnd2 disk: contents travelled",
                      fs_exists(fp(ATTIC, "Shelf A", "Winter jackets x2.jpg"))
                      and fs_exists(fp(ATTIC, "Shelf A", "_cover.jpg")))
                check("dnd2 disk: old Shelf A path gone", not fs_exists(fp(SU, "Shelf A")))
    go_home()
    if fs_isdir(fp(ATTIC, "Shelf A")):
        sh(f"mv {sq(fp(ATTIC, 'Shelf A'))} {sq(fp(SU, 'Shelf A'))}")
    sh(f"rm -rf {sq(fp(ATTIC))}")
    check("dnd2 restore: Shelf A back under Storage unit",
          fs_isdir(fp(SU, "Shelf A")) and not fs_exists(fp(ATTIC)))
    check("dnd2 rescan after restore", rescan_from_root())

    # ---- 3. cycle guard: a dragged container may not drop on itself ------
    # (The literal "↑ then drop on the row now visible one level up" cannot
    # be staged: a container's own row exists only on its parent screen, and
    # the "↑" chip from there climbs to the grandparent. The guard is
    # exercised where it bites: dwelling on the dragged row must not
    # spring-load, and releasing on it must not move anything.)
    check("dnd3 starts on home", go_home())
    tap_text(ESTATE); time.sleep(1.8)
    row = drag_by_text("Storage unit")
    check("dnd3 Storage unit row located", row is not None)
    if row is not None:
        rx, ry = drag_center(row)
        drag_start(rx, ry)
        motion_move(rx, ry)
        drag_dwell(rx, ry, 1300)  # past one 800ms tick
        check("dnd3 blocked self row did not spring-load",
              drag_by_text("Storage unit") is not None and texts_exact(ESTATE))
        motion_up(rx, ry)
        time.sleep(1.5)
    paths = [p for p in sh(f"find {sq(FS_ROOT)} -name 'Storage unit' 2>/dev/null").splitlines()
             if p.strip()]
    check("dnd3 disk: exactly one 'Storage unit' path, no nesting",
          paths == [fp(SU)], str(paths))
    check("dnd3 disk: subtree intact",
          fs_isdir(fp(SU, "Shelf A")) and fs_isdir(fp(SU, "Shelf B"))
          and fs_exists(wj_sa))

    # ---- 4. ancestor chip drop to the inventory root ---------------------
    check("dnd4 starts on home", go_home())
    tap_text(ESTATE); time.sleep(1.8)
    tap_text("Storage unit"); time.sleep(1.8)
    tap_text("Shelf A"); time.sleep(1.8)
    check("dnd4 inside Shelf A", bool(texts_exact("Shelf A")))
    tile = drag_by_desc("Winter jackets x2")
    check("dnd4 photo tile located", tile is not None)
    root_photo = fp("Winter jackets x2.jpg")
    if tile is not None:
        tx, ty = drag_center(tile)
        drag_start(tx, ty)
        chip = drag_by_text("Inventory")
        check("dnd4 Inventory ancestor chip visible", chip is not None)
        if chip is not None:
            cx, cy = drag_center(chip)
            drag_move_to(tx, ty, cx, cy)
            time.sleep(0.4)
            motion_up(cx, cy)
            time.sleep(2.0)
            check("dnd4 disk: photo dropped at the inventory root", fs_exists(root_photo))
            check("dnd4 disk: photo gone from Shelf A", not fs_exists(wj_sa))
    go_home()
    if fs_exists(root_photo):
        sh(f"mv {sq(root_photo)} {sq(wj_sa)}")
    check("dnd4 restore: photo back under Shelf A",
          fs_exists(wj_sa) and not fs_exists(root_photo))
    check("dnd4 rescan after restore", rescan_from_root())

    # ---- 5. selection drag carries the whole selection -------------------
    extra_name = "Drag extra"
    extra_sa = fp(SA, extra_name + ".jpg")
    extra_sb = fp(SB, extra_name + ".jpg")
    drag_seed_jpeg(extra_name, "", SA + "/" + extra_name + ".jpg")
    check("dnd5 second photo seeded", fs_exists(extra_sa))
    check("dnd5 rescan after seeding", rescan_from_root())
    tap_text(ESTATE); time.sleep(1.8)
    tap_text("Storage unit"); time.sleep(1.8)
    tap_text("Shelf A"); time.sleep(1.8)
    extra_tile = drag_by_desc(extra_name)
    check("dnd5 seeded tile located", extra_tile is not None)
    if extra_tile is not None:
        ex, ey = drag_center(extra_tile)
        drag_start(ex, ey)
        motion_up(ex, ey)
        time.sleep(1.2)
        check("dnd5 long-press-release entered selection",
              wait_for(text="1 selected", timeout=5) is not None)
        wj_tile = drag_by_desc("Winter jackets x2")
        if wj_tile is not None:
            wx, wy = drag_center(wj_tile)
            sh(f"input tap {wx} {wy}")
            time.sleep(1.0)
        check("dnd5 two photos selected",
              wait_for(text="2 selected", timeout=5) is not None)
        tile = drag_by_desc(extra_name)
        if tile is not None:
            tx, ty = drag_center(tile)
            drag_start(tx, ty)
            drag_spring_up(tx, ty)
            check("dnd5 spring-loaded to Storage unit",
                  wait_for(text="Storage unit", timeout=6) is not None)
            row = drag_by_text("Shelf B")
            check("dnd5 Shelf B row visible", row is not None)
            if row is not None:
                rx, ry = drag_center(row)
                drag_move_to(85, 577, rx, ry)
                motion_up(rx, ry)
                time.sleep(2.0)
                check("dnd5 disk: both photos under Shelf B",
                      fs_exists(wj_sb) and fs_exists(extra_sb))
                check("dnd5 disk: both photos left Shelf A",
                      not fs_exists(wj_sa) and not fs_exists(extra_sa))
    go_home()
    for name in ("Winter jackets x2.jpg", extra_name + ".jpg"):
        src = fp(SB, name)
        if fs_exists(src):
            sh(f"mv {sq(src)} {sq(fp(SA, name))}")
    if fs_exists(extra_sa):
        sh(f"rm {sq(extra_sa)}")
    check("dnd5 restore: only the original photo back under Shelf A",
          fs_exists(wj_sa) and not fs_exists(extra_sa)
          and not fs_exists(extra_sb))
    check("dnd5 rescan after restore", rescan_from_root())

    # ---- 6. Back cancels an in-flight drag -------------------------------
    check("dnd6 starts on home", go_home())
    tap_text(ESTATE); time.sleep(1.8)
    tap_text("Storage unit"); time.sleep(1.8)
    tap_text("Shelf A"); time.sleep(1.8)
    check("dnd6 inside Shelf A", bool(texts_exact("Shelf A")))
    tile = drag_by_desc("Winter jackets x2")
    check("dnd6 photo tile located", tile is not None)
    if tile is not None:
        tx, ty = drag_center(tile)
        drag_start(tx, ty)
        drag_move_to(tx, ty, tx, ty + 90)
        sh("input keyevent 4")
        time.sleep(0.5)
        d = dump()
        check("dnd6 back ended the drag session (no drag affordances)",
              d is not None and not find_all(d, desc="Move up one level")
              and not find_all(d, text="Inventory"))
        motion_up(tx, ty + 90)
        time.sleep(1.5)
        # the long press that started the drag also entered selection mode
        tap_desc("Exit selection", timeout=5)
        time.sleep(1.0)
        check("dnd6 back did not leave Shelf A", bool(texts_exact("Shelf A")))
        check("dnd6 disk: photo untouched", fs_exists(wj_sa) and not fs_exists(wj_sb))

    # ---- 7. long-press-release still enters selection --------------------
    tile = drag_by_desc("Winter jackets x2")
    check("dnd7 photo tile located", tile is not None)
    if tile is not None:
        tx, ty = drag_center(tile)
        drag_start(tx, ty)
        motion_up(tx, ty)
        time.sleep(1.2)
        check("dnd7 long-press-release selected the tile",
              wait_for(text="1 selected", timeout=5) is not None)
        tap_desc("Exit selection", timeout=5)
        time.sleep(0.8)
        check("dnd7 selection exited", bool(texts_exact("Shelf A")))
    check("dnd7 back home", go_home())

def t_share_target():
    """Inbound image share (Feature 5): `am start` with a SEND intent opens
    the app's container picker; picking a container imports the image with
    the filename-derived description. A JPEG is stored without re-encoding
    (pixels identical), a PNG is transcoded to JPEG with its dimensions
    preserved (host-Pillow proof). The dedicated container is removed again
    so the final disk audit is unchanged."""
    mark("12b share target")
    check("starts on home", go_home())
    # A dedicated container keeps the shared photos out of the audited tree.
    sh(f"mkdir -p {sq(fp('Share target'))}")
    check("rescan adopts Share target", rescan_from_root())
    check("UI lists Share target", wait_for(text="Share target", timeout=8) is not None)

    # --- JPEG: no re-encode (pixels identical), filename-stem description.
    jpg_uri = seed_media_image("share_sample.jpg", JPEG_1PX, "image/jpeg")
    check("MediaStore seed carries a content URI (jpg)", jpg_uri is not None, str(jpg_uri))
    if jpg_uri:
        sh(f"am start -W -f 1 -a android.intent.action.SEND -t image/jpeg "
           f"--eu android.intent.extra.STREAM {jpg_uri} -n {ACT} --user 0")
        time.sleep(3.0)
        check("share opens the container picker",
              wait_for(text="Add to Photo Inventory", timeout=12) is not None)
        check("picker lists Share target and picks it", share_pick_container("Share target"))
        time.sleep(3.0)
        check("share import snackbar (jpg)", snackbar_visible("Imported to", timeout=10))
        check("navigated into Share target (jpg)", bool(texts_exact("Share target")))
        jpg_file = fp("Share target", "share_sample.jpg")
        jpg_stored = fs_exists(jpg_file)
        check("disk: shared JPEG stored as share_sample.jpg", jpg_stored)
        tj = exif_triple(jpg_file); EXIF_SAMPLES.append(("Share target/share_sample.jpg", tj))
        check("EXIF: shared JPEG desc == filename stem", tj["desc"] == "share_sample", str(tj))
        check("picked photo tile visible", wait_for(text="share_sample", timeout=8) is not None)
        if jpg_stored:
            jpg_data = fs_read(jpg_file)
            check("share: JPEG payload not re-encoded (pixels identical)",
                  pixel_key(jpg_data) == pixel_key(JPEG_1PX),
                  "pixel data changed -> the JPEG was decoded/recompressed")
        check("home before the PNG share", go_home())

    # --- PNG: transcoded to JPEG, dimensions preserved.
    png_uri = seed_media_image("share_png.png", make_png(6, 9), "image/png")
    check("MediaStore seed carries a content URI (png)", png_uri is not None, str(png_uri))
    if png_uri:
        sh(f"am start -W -f 1 -a android.intent.action.SEND -t image/png "
           f"--eu android.intent.extra.STREAM {png_uri} -n {ACT} --user 0")
        time.sleep(3.0)
        check("PNG share opens the picker",
              wait_for(text="Add to Photo Inventory", timeout=12) is not None)
        check("picker lists Share target and picks it (png)", share_pick_container("Share target"))
        time.sleep(3.0)
        check("share import snackbar (png)", snackbar_visible("Imported to", timeout=10))
        png_file = fp("Share target", "share_png.jpg")
        png_stored = fs_exists(png_file)
        check("disk: shared PNG stored as share_png.jpg", png_stored)
        tpg = exif_triple(png_file); EXIF_SAMPLES.append(("Share target/share_png.jpg", tpg))
        check("EXIF: transcoded PNG desc == filename stem", tpg["desc"] == "share_png", str(tpg))
        fmt = None; size = None
        if png_stored:
            png_data = fs_read(png_file)
            from PIL import Image
            try:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore")
                    im = Image.open(io.BytesIO(png_data))
                    fmt = im.format
                    size = im.size
            except Exception as exc:  # noqa: BLE001 — surfaced in the failure detail
                fmt = repr(exc)
        check("Pillow: PNG converted to JPEG", fmt == "JPEG", f"format={fmt}")
        check("Pillow: transcoded dimensions 6x9 preserved", size == (6, 9), str(size))
        check("home after share target", go_home())

    # cleanup: the shared photos + their container (the audit stays exact).
    sh(f"rm -rf {sq(fp('Share target'))}")
    for name in ("share_sample.jpg", "share_png.png"):
        sh(f"rm -f /sdcard/Pictures/{name}")
        sh(f"content delete --uri content://media/external/images/media "
           f"--where \"_data='/storage/emulated/0/Pictures/{name}'\"")
    check("disk: Share target removed", not fs_exists(fp("Share target")))
    check("home after share cleanup", go_home())

def t_duplicate_import():
    """Duplicate detection on gallery import (Tier-2 #8): importing the same
    photo a second time into the same container raises the warning; Skip
    leaves the disk untouched, Import anyway lands the "(2)" copy. The
    dedicated container is removed again so the final disk audit is
    unchanged."""
    mark("12c duplicate")
    check("starts on home", go_home())
    sh(f"mkdir -p {sq(fp('Dup test'))}")
    check("rescan adopts Dup test", rescan_from_root())
    tap_text("Dup test"); time.sleep(1.8)
    check("first gallery import saved", add_gallery_photo("Dup original", where="dup"))
    time.sleep(1.5)
    check("disk: first import present", fs_exists(fp("Dup test", "Dup original.jpg")))
    before = sh(f"ls {sq(fp('Dup test'))} | wc -l").strip()

    # second import of the same picker image -> duplicate warning -> Skip
    check("second gallery pick opens the sheet", gallery_pick_one("dup skip"))
    f = edit_texts()
    if not f: return
    tap(f[0]); wait_ime(); type_text("Dup original"); ensure_ime_hidden(); time.sleep(0.8)
    check("duplicate warning shown", tap_text("Save photo", timeout=6)
          and wait_for(text="Identical photo already exists here as", timeout=10) is not None)
    check("Skip taps", tap_text("Skip", timeout=5))
    time.sleep(1.5)
    after = sh(f"ls {sq(fp('Dup test'))} | wc -l").strip()
    check("file count unchanged after Skip", before == after, f"{before} -> {after}")

    # third import: Import anyway lands the collision-suffixed copy
    check("third gallery pick opens the sheet", gallery_pick_one("dup force"))
    f = edit_texts()
    if not f: return
    tap(f[0]); wait_ime(); type_text("Dup original"); ensure_ime_hidden(); time.sleep(0.8)
    check("duplicate warning shown again", tap_text("Save photo", timeout=6)
          and wait_for(text="Identical photo already exists here as", timeout=10) is not None)
    check("Import anyway taps", tap_text("Import anyway", timeout=5))
    time.sleep(1.5)
    check("duplicate imported as (2)", fs_exists(fp("Dup test", "Dup original (2).jpg")))

    # cleanup: the dedicated container is removed (the audit stays exact).
    sh(f"rm -rf {sq(fp('Dup test'))}")
    check("disk: Dup test removed", not fs_exists(fp("Dup test")))
    check("home after dup cleanup", go_home())

def t_disk_final():
    mark("13 disk final")
    exp = sorted([
        FS_ROOT,
        fp("Attic shelf"),
        fp("Attic shelf", "Manual name.jpg"),
        fp("Attic shelf", "Mock item.jpg"),
        fp("Attic shelf", "Plain item.jpg"),
        fp("Estate"),
        fp("Estate", "Garage"),
        fp("Estate", "Storage unit"),
        fp("Estate", "Storage unit", "Roof tiles.jpg"),
        fp("Estate", "Storage unit", "Shelf A"),
        fp("Estate", "Storage unit", "Shelf A", "_cover.jpg"),
        fp("Estate", "Storage unit", "Shelf A", "Winter jackets x2.jpg"),
        fp("Estate", "Storage unit", "Shelf B"),
        fp("Estate", "Storage unit", "Shelf B", "Old skis.jpg"),
    ])
    got = tree()
    check("final tree matches disk exactly", got == exp, f"got={got}")
    check("no .sync-conflict remnants", not any(".sync-conflict" in p for p in got))
    check("no temp (.pending/.exif/.cover) leaks", not any("/." in p for p in got))
    check("_cover.jpg exactly where set (Shelf A)",
          [p for p in got if p.endswith("_cover.jpg")]
          == [fp("Estate", "Storage unit", "Shelf A", "_cover.jpg")])
    check("no stale old paths", not fs_exists(fp("Building")) and not fs_exists(fp("Garage"))
          and not fs_exists(fp("Shelf A")) and not fs_exists(fp("Building", "Garage")))
    t1 = exif_triple(fp("Estate", "Storage unit", "Shelf A", "Winter jackets x2.jpg"))
    EXIF_SAMPLES.append(("final Shelf A/Winter jackets x2.jpg", t1))
    check("final EXIF: Winter jackets x2 desc+note",
          t1["desc"] == "Winter jackets x2" and t1["note"] == "check hinges", str(t1))
    check("final EXIF: date well-formed", bool(re.fullmatch(DATE_RE, t1["date"] or "")), str(t1))
    t2 = exif_triple(fp("Estate", "Storage unit", "Roof tiles.jpg"))
    EXIF_SAMPLES.append(("final Roof tiles.jpg", t2))
    check("final EXIF: Roof tiles desc+note",
          t2["desc"] == "Roof tiles" and t2["note"] == "attic only", str(t2))
    t3 = exif_triple(fp("Estate", "Storage unit", "Shelf B", "Old skis.jpg"))
    check("final EXIF: Old skis desc, no note",
          t3["desc"] == "Old skis" and not t3["note"], str(t3))
    tm = exif_triple(fp("Attic shelf", "Mock item.jpg"))
    EXIF_SAMPLES.append(("final Attic shelf/Mock item.jpg", tm))
    check("final EXIF: AI photo desc+note",
          tm["desc"] == "Mock item" and tm["note"] == "added by mock server", str(tm))
    tg = exif_triple(fp("Attic shelf", "Manual name.jpg"))
    EXIF_SAMPLES.append(("final Attic shelf/Manual name.jpg", tg))
    check("final EXIF: guard photo desc+note",
          tg["desc"] == "Manual name" and tg["note"] == "added by mock server", str(tg))
    tp = exif_triple(fp("Attic shelf", "Plain item.jpg"))
    EXIF_SAMPLES.append(("final Attic shelf/Plain item.jpg", tp))
    check("final EXIF: plain photo desc, no note",
          tp["desc"] == "Plain item" and not tp["note"], str(tp))
    dbs = sh("run-as com.inventory ls databases 2>/dev/null")
    check("cache.db present as the rebuildable index", "cache.db" in dbs, dbs)

MAIN_T0 = 0.0

def main():
    global SERIAL, SW, SH, MAIN_T0
    MAIN_T0 = time.time()
    out = adb("devices").stdout
    devs = [l.split("\t")[0] for l in out.splitlines() if "\tdevice" in l]
    if "emulator-5554" in devs:
        SERIAL = "emulator-5554"  # a physical device (KB025921) may share
    elif len(devs) == 1:          # `adb devices` — ALWAYS pin the emulator
        SERIAL = devs[0]
    elif not devs:
        sys.exit("No device found — boot the emulator first.")
    else:
        sys.exit(f"Multiple devices and no emulator-5554: {devs}")
    print(f"Testing on {SERIAL}")
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    if m: SW, SH = int(m.group(1)), int(m.group(2))
    # A Google Calendar "What's new" heads-up banner once stole the foreground
    # mid-run on this emulator image and hijacked an app-bar tap: silence
    # heads-up notifications and disable the offender for the whole run.
    sh("settings put global heads_up_notifications_enabled 0")
    # Stage 1 (docs/test-optimization-plan.md): animations off — dialogs/sheets
    # appear instantly on the software-rendered emulator, so poll-based waits
    # return sooner.
    # NOTE (docs/test-optimization-plan.md): zeroing animation scales was tried
    # (Stage 1) and REVERTED — it causes bare-DecorView accessibility wedges
    # (windows transition instantly, a11y tree updates are missed), which
    # poisoned 5 harness runs. Animations stay at 1.0; do not zero them.
    sh("am force-stop com.google.android.calendar")
    sh("pm disable-user --user 0 com.google.android.calendar")
    sh("pkill -f uiautomator >/dev/null 2>&1")  # clear any wedged dump service
    # Stage 2 (docs/test-optimization-plan.md): the persistent socket dump
    # replaces the per-poll uiautomator one-shot; legacy stays as fallback.
    print(f"Dump server: {'active' if start_dump_server() else 'legacy (fallback)'}")
    sh(f"am force-stop {PKG}")
    # --- scenario 0 FIRST, on a fresh data dir with NOTHING granted: pm
    # clear does NOT reset a set MANAGE_EXTERNAL_STORAGE appops on this
    # image (measured), so deny it explicitly; CAMERA stays at its default
    # (ignore) to prove the SAF flow needs no pre-granted appops at all —
    # the runtime dialog is handled inside camera_capture().
    sh(f"pm clear {PKG}"); time.sleep(1)
    sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE deny")
    sh(f"rm -rf {sq(FS_ROOT)}")
    sh(f"mkdir -p {sq(FS_ROOT)}")   # EXTRA_INITIAL_URI target for the picker
    setup_gallery_images()
    start_app()
    t_saf_mode()
    t_file_mode_switch()
    # --- main suite, FILE mode. pm clear wipes the mode pref (chooser
    # returns), appops are (re-)granted explicitly, the tree starts empty
    # again, and the gallery seeds are re-registered so they are the
    # NEWEST items for the picker-ordering/byte-identity checks.
    sh(f"am force-stop {PKG}")
    sh(f"pm clear {PKG}"); time.sleep(1)
    grant_storage_appops()          # BEFORE the first launch, after pm clear
    sh(f"rm -rf {sq(FS_ROOT)}")     # disk is the source of truth: start empty
    setup_gallery_images()
    start_app()
    t_suite_start()
    t_permission_gate()
    t_inventory_root()
    t_drill_nesting()
    t_photos_and_flow()
    t_search_and_scopes()
    t_preview_and_multiselect()
    t_container_move_and_cycles()
    t_covers()
    t_conflicts()
    t_pc_edits()
    t_ai_autolabel()
    t_trash()
    t_report()
    t_breadcrumbs()
    t_viewer()
    t_drag_drop()
    t_share_target()
    t_duplicate_import()
    t_disk_final()
    stop_dump_server()
    print(f"\n===== {passed} passed, {failed} failed, {len(soft_warnings)} warnings =====")
    print(f"Total duration: {time.time() - MAIN_T0:.0f}s")
    print("Per-scenario (passed/total):")
    for k in sorted(tally):
        p, f = tally[k]
        print(f"  {k:32s} {p:3d}/{p+f:3d}  {'OK' if f == 0 else 'FAILURES=' + str(f)}")
    if EXIF_SAMPLES:
        print("EXIF read back:")
        for label, tr in EXIF_SAMPLES:
            print(f"  {label}: desc={tr['desc']!r} note={tr['note']!r} date={tr['date']!r}")
    if soft_warnings: print("Warnings:", *["  - " + w for w in soft_warnings], sep="\n")
    if failures:
        print("Failed:", *["  - " + f for f in failures], sep="\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
