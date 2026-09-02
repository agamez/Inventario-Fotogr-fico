#!/usr/bin/env python3
"""Photo Inventory end-to-end smoke test (filesystem-as-truth model).

The app's storage refactor makes `/sdcard/PhotoInventory` the single source
of truth: containers are real nested folders, photos are JPEGs whose
description/note/date live in EXIF (ImageDescription 0x010E, UserComment
0x9286, DateTimeOriginal 0x9003), a folder's cover is `_cover.jpg`, and
SQLite (`cache.db`, internal) is only a rebuildable index. Every former
database assertion is therefore replaced by a DISK assertion: folder
existence, nesting, file names, EXIF values, cover presence — read back
through adb + Pillow.

Scenarios: storage-permission gate (via appops), root CRUD + disk,
drill nesting, camera/gallery photo flow + EXIF + byte-identity,
in-container search with here/subtree scopes, global search, preview
edit (rename-on-disk) + multi-select move, cycle-proof container moves,
covers (gallery + camera, removal, grid exclusion), sync-conflict
banner/badge/multi-select delete, PC-side edits adopted by Rescan, and a
cache-wipe rebuild proof. Ends with an exact-tree disk audit.

  python3 scripts/smoke_test.py    # always pins emulator-5554 when present

Prereqs: emulator booted (headless ok); app installed. Wipes app data
(pm clear), grants MANAGE_EXTERNAL_STORAGE via appops BEFORE launch,
removes any stale /sdcard/PhotoInventory tree, seeds the gallery, then
starts the app, so the run is deterministic. Exits non-zero on failure;
soft warnings are allowed only for the documented queued-snackbar checks.
"""
import base64, io, os, re, subprocess, sys, tempfile, time, warnings
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

def dump():
    global LAST_RAW
    sh("rm -f /sdcard/ui.xml")
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    out = sh("cat /sdcard/ui.xml")
    if "<hierarchy" not in out:
        # orphaned UiAutomation registration can wedge the dump service
        sh("pkill -f uiautomator >/dev/null 2>&1")
        time.sleep(1.0)
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        out = sh("cat /sdcard/ui.xml")
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
    with open(f"{DUMPDIR}/{failed:02d}_{name.replace(' ', '_').replace('->', '_')}.xml", "w") as fh:
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

# ---------------------------------------------------------------- app setup

def grant_storage_appops():
    """The new model needs All Files Access BEFORE the first frame — the app
    is unusable behind the gate otherwise. (Replaces the old camera-appops-
    only step; camera appops is still fine to pre-allow.) pm clear resets
    both, so this always runs right after it."""
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
    time.sleep(2.0)
    ok = wait_for(text="Description (required)", timeout=10) is not None
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

# ---------------------------------------------------------------- scenarios

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
    tap(tile); time.sleep(1.2)
    check("preview dialog opens", wait_for(text="Description (required)", timeout=6) is not None)
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
    check("photo saved snackbar", snackbar_visible("Photo saved", timeout=8))
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
    # (d) cache-rebuild proof: wipe app data (cache.db + grants) — disk must
    # rebuild the whole UI. LAST, since pm clear resets state.
    sh(f"pm clear {PKG}")
    grant_storage_appops()
    start_app(); time.sleep(3.0)
    check("no gate after pm clear + appops re-grant",
          not find_all(dump(), text="Grant access"))
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

def t_disk_final():
    mark("11 disk final")
    exp = sorted([
        FS_ROOT,
        fp("Attic shelf"),
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
    dbs = sh("run-as com.inventory ls databases 2>/dev/null")
    check("cache.db present as the rebuildable index", "cache.db" in dbs, dbs)

def main():
    global SERIAL, SW, SH
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
    sh("am force-stop com.google.android.calendar")
    sh("pm disable-user --user 0 com.google.android.calendar")
    sh("pkill -f uiautomator >/dev/null 2>&1")  # clear any wedged dump service
    sh(f"am force-stop {PKG}")
    sh(f"pm clear {PKG}"); time.sleep(1)
    grant_storage_appops()          # BEFORE the first launch, after pm clear
    sh(f"rm -rf {sq(FS_ROOT)}")     # disk is the source of truth: start empty
    setup_gallery_images()
    start_app()
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
    t_disk_final()
    print(f"\n===== {passed} passed, {failed} failed, {len(soft_warnings)} warnings =====")
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
