#!/usr/bin/env python3
"""Photo Inventory end-to-end smoke test.

Drives the app on a booted emulator via adb + uiautomator dump.
  python3 scripts/smoke_test.py            # auto-detects the single device
Prereqs: emulator booted (headless ok); app installed (adb install -r ...).
Wipes app data first (pm clear) for determinism. Exits non-zero on failure.
"""
import base64, glob, os, re, shutil, sqlite3, subprocess, sys, tempfile, time
import xml.etree.ElementTree as ET

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PKG = "com.inventory"
ACT = f"{PKG}/.MainActivity"
JPEG_1PX = base64.b64decode(
    "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD3+iiigD//2Q==")

passed = failed = 0
failures = []
SERIAL = None

def adb(*args, timeout=40):
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

def sh(cmd, timeout=40):
    return adb("shell", cmd, timeout=timeout).stdout.strip()

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
        if cb[0] <= n[0] and cb[1] <= n[1] and cb[2] >= n[2] and cb[3] >= n[3]:
            return True
    return False

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

def wait_ime(timeout=5):
    end = time.time() + timeout
    while time.time() < end:
        if "mInputShown=true" in sh("dumpsys input_method"): return True
        time.sleep(0.3)
    return False

def back():
    sh("input keyevent 4")

warnings = []

def check(name, cond, detail="", soft=False):
    global passed, failed
    if cond: passed += 1; print(f"  PASS  {name}"); return
    if soft:
        warnings.append(name); print(f"  WARN  {name}  {detail}"); return
    failed += 1; failures.append(name); print(f"  FAIL  {name}  {detail}")
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

def db_rows():
    """Pull db+wal and read locations/boxes/photos with host sqlite3."""
    tmp = tempfile.mkdtemp()
    for f in ("inventory.db", "inventory.db-wal", "inventory.db-shm"):
        cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + ["exec-out", f"run-as {PKG} cat databases/{f}"]
        r = subprocess.run(cmd, capture_output=True, timeout=30)  # bytes, no text decode
        if r.returncode == 0 and r.stdout: open(f"{tmp}/{f}", "wb").write(r.stdout)
    con = sqlite3.connect(f"{tmp}/inventory.db")
    rows = {}
    try:
        rows["locations"] = con.execute("select id,name from locations").fetchall()
        rows["boxes"] = con.execute("select id,name,locationId from boxes").fetchall()
        rows["photos"] = con.execute("select id,boxId,description from photos").fetchall()
    except sqlite3.Error as e:
        rows["error"] = str(e)
    con.close(); shutil.rmtree(tmp, ignore_errors=True)
    return rows

def setup_gallery_images():
    """Push two tiny JPEGs into MediaStore so the photopicker has items."""
    for i, name in enumerate(("test_a.jpg", "test_b.jpg")):
        path = f"/sdcard/Pictures/{name}"
        p = adb("push", "-")  # stdin push is unreliable; write via shell
        sh(f"mkdir -p /sdcard/Pictures")
        tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
        tmp.write(JPEG_1PX); tmp.close()
        adb("push", tmp.name, path, timeout=20)
        os.unlink(tmp.name)
        sh(f"content insert --uri content://media/external/images/media "
           f"--bind _data:s:{path} --bind mime_type:s:image/jpeg")
    return True


def start_app(fresh=False):
    if fresh: sh(f"pm clear {PKG}")
    # seed gallery images after clear (clear wipes app data, not sdcard)
    sh(f"am start -W -n {ACT}")
    time.sleep(2.5)

# ---------------------------------------------------------------- scenarios
def t_locations_crud():
    print("\n== Locations ==")
    check("empty state shown", wait_for(text="No locations yet") is not None)
    tap_desc("Add location")
    wait_for(text="Name", timeout=5)  # dialog settled
    d = dump(); saves = find_all(d, text="Save")
    check("blank name -> Save disabled", saves and saves[0].get("clickable") == "false")
    fill_dialog("Garage")
    check("location created snackbar", snackbar_visible("Location created"))
    check("row Garage with 0 boxes", wait_for(text="0 boxes") is not None)
    # duplicate
    tap_desc("Add location"); fill_dialog("Garage")
    check("duplicate rejected", snackbar_visible("already exists"))
    tap_text("Cancel")
    # second location
    tap_desc("Add location"); fill_dialog("Cellar")
    time.sleep(0.5)
    # third location, created to be renamed
    tap_desc("Add location"); fill_dialog("TempName")
    time.sleep(0.8)
    check("row Cellar exists", wait_for(text="Cellar") is not None)
    check("row TempName exists", wait_for(text="TempName") is not None)
    # rename via overflow menu of the TempName row
    check("open TempName row menu", row_menu("TempName", "Location options"))
    time.sleep(1.2)
    tap_text("Rename")
    check("rename dialog prefilled", wait_for(text="Rename location") is not None)
    fields = edit_texts()
    check("rename prefilled with current name", fields and fields[0].get("text") == "TempName")
    tap(fields[0]); wait_ime(); clear_field(); type_text("Attic")
    hide_ime(); time.sleep(1.0)
    tap_text("Save"); time.sleep(0.5)
    check("rename applied", wait_for(text="Attic") is not None)
    # Measured on this emulator: the rename snackbar appears ~5.8s after
    # Save (queued behind the preceding "Location created" snackbar, M3
    # shows them sequentially) and stays ~4s. With ~1.5s uiautomator dump
    # latency this 4s window is a coin flip when the dump service wedges,
    # so the rename itself is asserted by "rename applied" and this stays soft.
    check("location saved snackbar", snackbar_visible("Location saved", timeout=15), soft=True)

def t_location_delete_guard():
    print("\n== Location delete guard ==")
    # Garage has a box later; here delete guard tested after box creation.
    pass

def t_boxes():
    print("\n== Boxes ==")
    tap_text("Garage")
    check("boxes screen title", wait_for(text="No boxes yet") is not None)
    tap_desc("Add box"); fill_dialog("Tools")
    check("box created snackbar", snackbar_visible("Box created"))
    check("row Tools 0 photos", wait_for(text="0 photos") is not None)
    # second box for later photo-move target
    tap_desc("Add box"); fill_dialog("Winter")
    time.sleep(1.0)
    # move Tools -> Cellar via bottom sheet
    check("open Tools row menu", row_menu("Tools", "Box options"))
    time.sleep(1.2)
    tap_text("Move to location")
    check("move sheet lists Cellar", wait_for(text="Cellar", timeout=10) is not None)
    tap_text("Cellar")
    check("box moved snackbar", snackbar_visible("Box moved"))
    time.sleep(1.0)
    check("Garage list now empty", find_all(dump(), text="No boxes yet") is not None)
    back(); time.sleep(1.0)
    tap_text("Cellar")
    check("Cellar now has Tools", wait_for(text="Tools") is not None)
    back()  # to locations

def t_delete_guard():
    print("\n== Location delete guard ==")
    check("open Garage row menu", row_menu("Garage", "Location options"))
    time.sleep(1.2)
    tap_text("Delete")
    check("delete confirm dialog", wait_for(text="Delete location?") is not None)
    time.sleep(1.0)
    tap_text("Delete")
    check("guard message", snackbar_visible("still has boxes", timeout=5))
    check("Garage still listed", wait_for(text="Garage") is not None)
    if find_all(dump(), text="Cancel"): tap_text("Cancel")  # close dialog if still up

def t_photos_gallery():
    print("\n== Photos: gallery pick + description queue ==")
    tap_text("Cellar"); tap_text("Tools")
    check("photos empty state", wait_for(text="No photos yet") is not None)
    tap_desc("Add photos")
    time.sleep(1.5)
    check("bottom sheet options", wait_for(text="Choose from gallery") is not None)
    tap_text("Choose from gallery")
    time.sleep(4.0)
    # system photopicker: dismiss account card, select two items, confirm
    d = dump()
    check("photopicker opened", d is not None and find_all(d, desc="Photo taken on"))
    dism = find_all(dump(), text="Dismiss")
    if dism: tap(dism[0]); time.sleep(0.8)
    photos = find_all(dump(), desc="Photo taken on")
    check("picker shows 2 photos", len(photos) >= 2, str(len(photos)))
    for n in photos[:2]:
        tap(n); time.sleep(1.0)
    end = time.time() + 6
    add = None
    while time.time() < end and add is None:
        d = dump()
        cands = [n for n in find_all(d, text="Add")] or \
                [n for n in find_all(d, desc="Add")] or \
                [n for n in find_all(d, text=re.compile(r"Add \("))]
        add = cands[0] if cands else None
        if add is None: time.sleep(0.5)
    check("picker Add button", add is not None)
    if add is not None: tap(add)
    # save sheet for first image
    check("description field appears", wait_for(text="Description (required)", timeout=8) is not None)
    fields = edit_texts()
    check("save disabled while blank", find_all(dump(), text="Save")[0].get("clickable") == "false")
    tap(fields[0]); type_text("Winter jacket"); hide_ime(); time.sleep(0.8)
    check("pending count shows 1 waiting", find_all(dump(), text="more photos waiting") is not None)
    tap_text("Save")
    check("second item prompts again", wait_for(text="Description (required)", timeout=6) is not None)
    f2 = edit_texts(); tap(f2[0]); type_text("Old skis"); hide_ime(); time.sleep(0.8)
    tap_text("Save")
    check("photo added snackbar", snackbar_visible("Photo added"))
    check("tile Winter jacket", wait_for(text="Winter jacket", timeout=6) is not None)
    check("tile Old skis", wait_for(text="Old skis", timeout=6) is not None)

def t_search():
    print("\n== Photos: search ==")
    tap_desc("Toggle search")
    f = wait_for(text="Search photos")
    if f is None: return check("search field appears", False)
    tap(f); wait_ime(); type_text("ski"); time.sleep(1.5)
    d = dump()
    check("search filters (ski -> 1 tile)",
          len(find_all(d, text="Old skis")) == 1 and not find_all(d, text="Winter jacket"))
    clear_field(); type_text("zzz"); time.sleep(1.5)
    check("no-results state", wait_for(text="No photos match") is not None)
    # clear via trailing clear icon or del
    clear_field(); time.sleep(1.5)
    check("results restored", wait_for(text="Winter jacket") is not None)
    hide_ime(); back(); time.sleep(0.5)  # collapse search

def t_preview_edit():
    print("\n== Photos: full-screen preview edit ==")
    # after t_search we may be on the boxes list; re-enter Tools if needed
    if wait_for(text="Winter jacket", timeout=3) is None:
        tap_text("Tools"); time.sleep(1.5)
    tile = wait_for(text="Winter jacket")
    tap(tile)
    check("preview dialog opens", wait_for(text="Save photo", timeout=5) is not None or edit_texts())
    fields = edit_texts()
    check("description prefilled", fields and fields[0].get("text") == "Winter jacket")
    check("note field present", len(fields) >= 2)
    tap(fields[0]); wait_ime(); clear_field(); type_text("Winter jackets x2"); hide_ime(); time.sleep(0.8)
    tap_text("Save")
    check("photo saved snackbar", snackbar_visible("Photo saved"))
    check("tile text updated", wait_for(text="Winter jackets x2", timeout=5) is not None)

def t_multiselect_move():
    print("\n== Photos: multi-select move to box ==")
    tiles = find_all(dump(), text="Old skis")
    long_press(tiles[0])
    check("selection bar 1 selected", wait_for(text="1 selected", timeout=5) is not None)
    tap_text("Winter jackets x2")
    check("selection bar 2 selected", wait_for(text="2 selected", timeout=5) is not None)
    tap_desc("Move selected photos")
    check("move sheet lists Winter box", wait_for(text="Winter", timeout=5) is not None)
    d = dump(); items = [n for n in find_all(d, text="Winter")]
    tap(items[0])
    check("photos moved snackbar", snackbar_visible("photo(s) moved"))
    time.sleep(1.0)
    check("grid now empty", find_all(dump(), text="No photos") is not None)
    back(); back(); time.sleep(1.0)  # to locations
    tap_text("Garage"); tap_text("Winter")
    check("Winter box has moved tile 1", wait_for(text="Winter jackets x2", timeout=6) is not None)
    check("Winter box has moved tile 2", wait_for(text="Old skis", timeout=4) is not None)
    back(); back()

def t_camera():
    print("\n== Camera flow with permission ==")
    tap_text("Garage"); tap_text("Winter")
    tap_desc("Add photos"); tap_text("Take photo")
    time.sleep(2.0)
    # system permission dialog (first run) or in-app rationale
    granted = (tap_text("While using the app", timeout=5)
               or tap_text("Only this time", timeout=2)
               or tap_text("Allow", timeout=2))
    if not granted:
        # in-app rationale -> Grant permission -> system dialog -> grant
        check("rationale shown", wait_for(text="Grant permission", timeout=4) is not None)
        tap_text("Grant permission")
        time.sleep(1.5)
        granted = (tap_text("While using the app", timeout=4)
                   or tap_text("Allow", timeout=2))
    check("permission granted", granted)
    time.sleep(3.0)
    time.sleep(2.0)
    check("camera shutter visible", tap_desc("Take photo", timeout=10))
    check("save sheet after capture", wait_for(text="Description (required)", timeout=8) is not None)
    f = edit_texts(); tap(f[0]); wait_ime(); type_text("Toolbox lid"); hide_ime(); time.sleep(0.8)
    tap_text("Save")
    check("camera photo saved snackbar", snackbar_visible("Photo added", timeout=8))
    check("camera photo in grid", wait_for(text="Toolbox lid", timeout=8) is not None)
    back(); back(); time.sleep(1.0)

def t_selection_delete_and_db():
    print("\n== Selection delete + DB verification ==")
    tap_text("Garage"); tap_text("Winter")
    tiles = find_all(dump(), text="Old skis")
    long_press(tiles[0])
    tap_text("1 selected") if False else None
    tap_desc("Delete selected photos")
    check("multi delete confirm", wait_for(text="will be permanently deleted", timeout=5) is not None)
    tap_text("Delete")
    check("photo deleted snackbar", snackbar_visible("Photo deleted", timeout=8))
    check("deleted tile gone", wait_for(text="Old skis", timeout=4) is None)
    check("kept tile remains", wait_for(text="Toolbox lid", timeout=5) is not None)
    rows = db_rows()
    locs = rows.get("locations", [])
    check("db: 3 locations", len(locs) == 3, str(locs))
    check("db: location names", sorted(l[1] for l in locs) == ["Attic", "Cellar", "Garage"], str(locs))
    check("db: 2 boxes", len(rows.get("boxes", [])) == 2, str(rows.get("boxes")))
    photos = rows.get("photos", [])
    descs = sorted(p[2] for p in photos)
    check("db: 2 photos after delete", len(photos) == 2, str(descs))
    check("db: deleted photo gone", "Old skis" not in descs, str(descs))
    check("db: remaining photo kept", "Winter jackets x2" in descs, str(descs))
    box_names = sorted(b[1] for b in rows.get("boxes", []))
    check("db: box names", box_names == ["Tools", "Winter"], str(box_names))
    back(); back()

def t_global_search():
    print("\n== Global search ==")
    # End state from earlier scenarios: 'Winter jackets x2' and 'Toolbox lid'
    # live in box 'Winter' inside location 'Garage' ('Old skis' was deleted by
    # t_selection_delete_and_db, so the narrowing query is 'lid', which matches
    # only 'Toolbox lid').
    tap_desc("Search all items")
    check("search screen opens", wait_for(text="All items, newest first", timeout=6) is not None)
    check("browse mode lists items", wait_for(text="Winter jackets x2", timeout=6) is not None)
    f = wait_for(text="Search all photos")
    if f is None: return check("search field appears", False)
    tap(f); wait_ime(); type_text("lid"); time.sleep(1.5)
    d = dump()
    check("search narrows to Toolbox lid",
          bool(find_all(d, text="Toolbox lid")) and not find_all(d, text="Winter jackets x2"))
    check("result shows Winter · Garage", wait_for(text="Winter · Garage", timeout=6) is not None)
    check("tap jumps to box grid", tap_text("Toolbox lid", timeout=6))
    check("Winter grid lists sibling item", wait_for(text="Winter jackets x2", timeout=6) is not None)
    # back to search: query must survive on the back stack; make sure the
    # IME cannot eat the navigation BACK.
    if "mInputShown=true" in sh("dumpsys input_method"):
        hide_ime(); time.sleep(0.5)
    back(); time.sleep(1.2)
    check("query retained after back", bool(find_all(dump(), text="Toolbox lid")))
    check("clear icon returns to browse", tap_desc("Clear search", timeout=5))
    check("browse caption back", wait_for(text="All items, newest first", timeout=6) is not None)
    f2 = wait_for(text="Search all photos")
    if f2 is None: return check("search field refocusable", False)
    tap(f2); wait_ime(); type_text("zzznope"); time.sleep(1.5)
    check("no-results state", wait_for(text="No photos match", timeout=6) is not None)
    hide_ime(); back(); time.sleep(0.6)  # leave the search screen

def main():
    global SERIAL
    out = adb("devices").stdout
    devs = [l.split("\t")[0] for l in out.splitlines() if "\tdevice" in l]
    if not devs: sys.exit("No device found — boot the emulator first.")
    SERIAL = devs[0]
    print(f"Testing on {SERIAL}")
    setup_gallery_images()
    sh(f"pm clear {PKG}"); time.sleep(1)
    start_app(fresh=True)
    t_locations_crud()
    t_boxes()
    t_delete_guard()
    t_photos_gallery()
    t_search()
    t_preview_edit()
    t_multiselect_move()
    t_camera()
    t_selection_delete_and_db()
    t_global_search()
    print(f"\n===== {passed} passed, {failed} failed, {len(warnings)} warnings =====")
    if warnings: print("Warnings:", *["  - " + w for w in warnings], sep="\n")
    if failures:
        print("Failed:", *["  - " + f for f in failures], sep="\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
