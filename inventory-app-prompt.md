# Photo-Based Inventory Management App

## Context

You have physical boxes stored in various rooms/locations (garage, cellar, storage unit, etc.).
Each box has a label name (e.g. "Box 1", "Paper Bag 2", "Red plastic box").
Inside each box are several objects. You need an Android app to photograph these objects,
group them into "virtual boxes" matching the physical labels, and track which room/location
each box is stored in.

This is NOT a stock/inventory management app. Objects are discrete items. No quantities, no counts.

---

## Domain Model

### Location

Represents a physical place in or around the home where boxes can be stored (e.g. "Garage", "Cellar", "Bedroom 2", "Storage Unit").

- A location has a unique name
- A location can contain many boxes
- Boxes can be moved between locations

### Box

Represents a physical container with a name label written on it.

- A box has a unique name (per context — not necessarily globally unique, but practically should be)
- A box belongs to exactly one location
- A box contains many photos
- Boxes can be moved between locations

### Photo

Represents a single photograph of an object inside a box.

- A photo belongs to exactly one box (no photo shared across boxes)
- A photo has no box (can be detached, e.g. in preview mode or when moving between boxes)
- A photo stores an image (JPEG BLOB), a timestamp, a required description, and an optional note/caption
- Photos can be moved between boxes

---

## Features

### Feature 1: Locations

- Display a list of all locations in a `LazyColumn`
- FAB to create a new location (simple dialog)
- Tap a location → navigate to a screen showing its boxes
- Tap a location entry → rename it (inline edit or dialog)
- Delete a location (confirm dialog; boxes keep their `location_id` or show confirmation)

### Feature 2: Boxes (within a location context)

- List all boxes belonging to the currently selected location
- FAB to create a new box (enter name via dialog)
- Tap a box → navigate to a screen showing its photos (grid)
- Long-press or context menu on a box → "Move to location" → bottom sheet picker of existing locations
- Delete a box (confirm dialog)

### Feature 3: Photos (within a box context)

- Display photos in a grid (2 columns, 1:1 aspect ratio)
- FAB to add photos → bottom sheet with two options:
  - **Take photo** → open CameraX to capture → prompt user to enter required **description** and optional note before saving
  - **Choose from gallery** → use `PickVisualMediaContract` → prompt user to enter required **description** and optional note before saving
- Tap a photo → full-screen preview showing image, description, and note; both fields editable
- Swipe to dismiss or long-press → delete photo (confirm)
- Long-press to enter multi-select mode → "Move to box" picker → select target box
- Text search/filter on photo descriptions within a box

---

## Tech Stack

- **Kotlin 1.9+**
- **Jetpack Compose** (UI only, no XML)
- **Gradle** (Kotlin DSL, `build.gradle.kts`)
- **Hilt** for dependency injection
- **Room 2.x** for SQLite persistence
- **CameraX 1.3+** for camera capture
- **PhotoPicker** (`PickVisualMediaContract`) for gallery selection
- **ViewModel + StateFlow** per screen
- **Navigation Compose 2.7+**
- **Coil 2.x** for loading BLOB images in Compose
- **Kotlin Coroutines + Flow** throughout
- **Material 3**

---

## Architecture

### Project structure

```
app/src/main/java/com/inventory/
├── di/                      (Hilt modules)
├── database/                (Room: entities, DAOs, AppDatabase, Converters)
├── feature/
│   ├── locations/           (LocationRepository, LocationViewModel, LocationScreen)
│   ├── boxes/               (BoxRepository, BoxViewModel, BoxScreen)
│   ├── photos/              (PhotoRepository, PhotoViewModel, PhotoScreen)
│   └── navigation/          (Screens sealed class, NavGraph)
└── ui/theme/                (Material3 theme setup)
```

### Layer rules

- **DAO** → operates on Room entities. Exposes `Flow<List<T>>` and suspend functions.
- **Repository** → orchestrates DAO calls, handles business rules (move box, move photo).
- **ViewModel** → exposes `StateFlow<ScreenState>` to the UI. Handles UI logic.
- **Screen** → pure Compose. Collects `StateFlow`. Dispatches user actions to ViewModel.
- **CameraX** → captures JPEG → byte array → insert into database via Repository.
- **PhotoPicker** → returns `Uri` → `contentResolver` → decode to Bitmap → encode to JPEG → same flow.

### Constraints

- No `TODO`s or placeholders. Implement everything fully.
- No hard-coded UI strings — use `strings.xml`.
- Minimum SDK: 24. Compile/Target SDK: 35.
- Handle edge cases: empty states, camera errors, picker cancelled, permission denied, large BLOBs (~2MB max).

---

## Workflow

### Phase 1 — Plan

1. Confirm you understand the domain and features above.
2. Ask any questions about unclear points or decisions you need me to make.
3. Design the architecture: database schema (entities, DAOs), `ScreenState` types, navigation structure, CameraX integration approach.
4. Present the plan in a structured summary.
5. **Wait for my approval** before writing any code.

### Phase 2 — Implement (after approval)

6. Implement each feature module sequentially:
   a. **Database layer** — entities, DAOs, Room DB, Converters (Bitmap ↔ BLOB), DI setup. Ensure Photo entity has `description` as a required TEXT (NOT NULL) field.
   b. **Locations feature** — Repository → ViewModel → UI
   c. **Boxes feature** — Repository → ViewModel → UI
   d. **Photos feature** — Repository → ViewModel → UI (hardest part: CameraX + PhotoPicker + grid + multi-select). Include required description input when capturing/adding photos, display description in grid items, and support text search/filter on descriptions.
   e. **Navigation wiring** — connect all screens
7. Each module must be self-contained and conceptually runnable before moving to the next.
8. When delegating to the executor, write a brief with:
   - objective
   - exact files to create/modify
   - constraints (naming conventions, architecture rules)
   - verifiable success criterion

---

*Begin Phase 1.*