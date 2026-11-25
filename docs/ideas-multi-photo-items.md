# Ideas — Multi-Photo Items (v2)

## Problema

Actualmente cada foto = una entrada individual. Si el usuario sube dos fotos del mismo objeto ("tornillo"), aparecen como dos elementos distintos. Quiere poder asignar varias fotos a un único elemento, verlas agrupadas, y mover/borrar/renombrar el objeto como unidad.

## Insight raíz

La app confunde **foto** con **elemento**. El fallo no es de almacenamiento: falta la entidad "elemento (objeto físico)" por encima de las fotos. Con ese flip de dominio:

- **Elemento** = el objeto de inventario (la unidad que se mueve, borra, renombra, busca).
- **Fotos** = testimonios/ángulos de ese elemento.
- Elemento de 1 foto = lo que hoy es una "foto suelta" (tile normal, cero fricción).
- Elemento de 2+ fotos = grupo visual (stack/portada + contador).

Toda la app (búsqueda, mover, borrar, portada, viewer) pasa a operar sobre **elementos**.

## Codificación física (regla única)

| Caso | Representación en disco |
|---|---|
| Elemento con 1 foto | `Caja A/tornillo.jpg` (fichero, como hoy) |
| Elemento con 2+ fotos | `Caja A/tornillo/` carpeta con `_item.json` dentro + las fotos |

```
Caja A/
├── tornillo/          ← ELEMENTO multi-foto (carpeta + marker, la crea la app)
│   ├── _item.json     ← {"type":"item","v":1,"note":"..."} (opcional note)
│   ├── _cover.jpg     ← portada (convención existente)
│   ├── tornillo_1.jpg
│   └── tornillo_2.jpg
├── martillo.jpg       ← ELEMENTO de 1 foto (fichero suelto, como hoy)
└── _cover.jpg
```

**Punto clave que resuelve la objeción de "no sé si tornillo es caja o item":**
la carpeta-item **nunca la crea el usuario a mano** — la materializa la app mediante
"Group into item" / "Añadir foto a elemento", y siempre lleva marker.
Sin marker = contenedor. No hay ambigüedad, no hay heurísticas.

## Reglas de dominio

1. **Nombre del elemento** = nombre de carpeta (multi-foto) o stem del fichero (1 foto). Única fuente de verdad (convención #7 de nombres únicos case-insensitive, sufijos " (2)").
2. **Promoción** (1 foto → multi): al añadir la segunda foto a un elemento, la app crea `tornillo/`, escribe `_item.json`, mueve la foto existente **byte-idéntica** (temp+rename, nunca re-encode) y guarda la nueva.
3. **Degradación** (multi → 1 foto): NO automática. Acción manual "Aplanar a foto única" (mueve la foto fuera, borra la carpeta+marker). La presentación visual se adapta sola: un item de 1 foto se pinta como tile normal (usa su única foto como portada).
4. **Items anidados**: prohibidos. El scanner ignora `_item.json` dentro de una carpeta ya marcada como item (se trataría como texto plano suelto → badge "contenido inesperado" si hay subcarpetas dentro de un item).
5. **Subcarpetas dentro de un item**: ignoradas para el conteo; badge de advertencia en el item.
6. **Item vacío** (marker sin fotos): visible, con acción "Añadir fotos".
7. **Metadata del elemento**:
   - 1 foto → EXIF como hoy (ImageDescription=nombre/desc, UserComment=nota, DateTimeOriginal=fecha). Contracto #6 intacto.
   - multi → nombre = carpeta, nota del elemento en `_item.json`, portada = `_cover.jpg`, fotos internas conservan su EXIF individual.
8. **Portada**: convención `_cover.jpg` existente; el grid de la carpeta la excluye de las fotos listadas (ya implementado).
9. **Papelera**: elementos multi-foto se trashed/restauran como carpeta completa (1 stamp, 1 `.origin` tipo "folder"); elementos de 1 foto como hoy (tipo "photo").
10. **Conflictos Syncthing**: `*.sync-conflict-*` dentro de un item → badge del item (propagación del flag existente).

## Interacción (ambas plataformas)

- Grid de contenedor con 3 secciones: **contenedores**, **elementos**, **fotos sueltas** (las fotos dentro de items no aparecen aquí).
- Card de elemento multi-foto: portada apilada + nombre + "N fotos".
- Clic en elemento → **ItemScreen**: grid de sus fotos + viewer + acciones: añadir fotos (cámara/galería apuntando al item), marcar portada (elegir foto → setCover), renombrar, mover, borrar, aplanar.
- Selección múltiple de fotos sueltas → **"Agrupar en elemento"** (crea carpeta+marker, mueve las seleccionadas, nombres únicos).
- Selección de fotos sueltas → **"Añadir a elemento…"** → elige item destino → mueve ficheros dentro + promueve.
- FAB: Nuevo contenedor / Nuevo elemento (vacío, marcado, listo para fotos) / Tomar foto / Galería.
- Cámara: tras capturar, opción "Guardar en elemento existente…" además de guardar suelto en la carpeta.
- Drag & drop: un elemento (carpeta o fichero) se arrastra a otro contenedor; los items NO aceptan drops de contenedores (regla 4).
- Vocabulario de UI: **ubicación** (edificio/mueble/caja) vs **elemento** (el objeto físico). Evitar mezclarlos.

## Arquitectura

### shared/
- `ItemMarker.kt`: constante `ITEM_MARKER = "_item.json"`, parse/escritura de `{"type":"item","v":1,"note":...}`, helpers de clasificación. Cubierto por `jvmTest`.

### Backends (cambios mínimos)
- **NO hace falta `listItems()`**. Los items son carpetas: el scanner clasifica con `listContainers()` (y las fotos con `listPhotos()`).
- Solo se necesita leer el marker: reutilizar lectura genérica de bytes o añadir `readAuxFile(rel)` al backend.
- `FileBackend` / `SafBackend`: prácticamente sin cambios (promoción y agrupado reutilizan `addPhoto`, `movePhotos`, `renameFolder`/`deleteFolderRecursive` existentes).

### Android (Room)
- `CacheEntry.kind` gana `2 = item` (misma tabla `index_entries`; sin tabla nueva). Bump de versión de `CacheDatabase` + wipe (la cache es desechable por diseño, nunca se migra).
- Las fotos DENTRO de items se indexan igualmente (`kind=1`, `parentRel = relPath del item`) → ItemScreen consulta `parentRel = :itemRel AND kind = 1` y no filtran en el grid del contenedor (que consulta `parentRel = :containerRel`).
- `CacheDao`: queries de hijos y conteos pasan a distinguir kind 0/1/2; búsqueda añade kind 2 por nombre.

### Desktop (índice en memoria)
- `IndexedItem` + `Snapshot.itemsByParent`; `walk()` clasifica igual (marker leído del File).
- `FolderWatcher` ya reconstruye el índice: los markers externos se adoptan solos (disk=truth).

## Orden de implementación

1. `shared/ItemMarker.kt` + `jvmTest` (sin marker / con marker / marker dentro de item / JSON corrupto).
2. Backend: lectura del marker (`readAuxFile` o equivalente) en FileBackend y SafBackend.
3. Scanner Android (`InventoryScanner`): clasificación 0/1/2, fotos de items, conteos, conflictos.
4. Room: `kind=2`, bump versión, queries y conteos (`CacheDao`, `CacheEntry`).
5. `InventoryStore`: `createItem`, `renameItem`(=renameFolder), `moveItem`(=moveFolder), `flattenItem`, `ungroup` (borrar marker+vaciar), `promote/addPhotoToItem` (reutiliza move+write).
6. `ContainerViewModel` + FAB + acciones de agrupado/promoción.
7. `ContainerScreen`: sección de items, cards, badges, ItemScreen + viewer + dialogs.
8. `NavGraph`: route `item/{path}`.
9. Desktop: `ItemMarker` reutilizado, `DesktopIndex` (IndexedItem, walk, itemsIn, search), `DesktopAppController` CRUD, `BrowseView` items + `ItemView`, drag&drop.
10. Papelera (tipos de `.origin`), búsqueda (elementos + fotos internas), reporte de inventario/reporte conteo elementos.
11. Strings en `strings.xml` y `DesktopStrings.kt` (requisito #4).
12. Actualizar `scripts/smoke_test.py` (escenario: agrupar 2 fotos, mover elemento, aplanar) y `--headless-selftest` desktop (fixture con `_item.json`).

## Verificación

- `JAVA_HOME=…java-17… ./gradlew :shared:jvmTest :desktop:jvmTest`
- `./gradlew :app:assembleDebug`
- `./gradlew :desktop:packageFatJar` + `--headless-selftest <folder>` → `SELFTEST OK`
- `python3 scripts/smoke_test.py` (cambio de comportamiento Android → 1 run completo)
- Round-trip EXIF desktop vs Pillow sin tocar píxeles (convención #6) al mover/renombrar.

## Riesgos y mitigaciones

- **Subcarpetas creadas a mano dentro de un item** → ignoradas + badge "contenido inesperado" (regla 5).
- **Marker corrupto/tipo desconocido** → se ignora (la carpeta es contenedor normal); nunca bloquear el scan.
- **Colisión de nombre al promover** (`tornillo/` ya existe) → sufijo " (2)" (convención #7).
- **Item de 1 foto tras borrados** → presentación se adapta; aplanado solo manual (regla 3).
- **Coste de scan**: +1 lectura de marker por subcarpeta; despreciable.

## Decisiones descartadas (histórico)

- `.tar` por elemento: rompe archivos reales, sync y EXIF.
- Agrupación solo en DB (virtual): Syncthing/PC no la entienden; drift disk↔DB.
- Sufijo en nombre (`tornillo~2.jpg`): frágil ante ediciones externas, choca con sufijos de colisión.
- Manifiesto por contenedor (`_items.json` con listas): dos fuentes de verdad para la pertenencia.
- Detección implícita por contenido de carpeta: ambigua (caja con fotos mezcladas vs item) — por eso el marker explícito y "la app crea la carpeta-item, no el usuario".
