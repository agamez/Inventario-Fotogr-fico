package com.inventory.storage

/**
 * Progress snapshot of the storage scanner. `InventoryScanner` (in the
 * app's cache package) exposes it as a StateFlow: [scanning] is true
 * while a filesystem walk runs, and [lastFullScan] is the wall-clock time
 * of the most recent completed scan (0 before the first one ever
 * finished).
 */
data class ScanState(
    val scanning: Boolean = false,
    val lastFullScan: Long = 0L,
)
