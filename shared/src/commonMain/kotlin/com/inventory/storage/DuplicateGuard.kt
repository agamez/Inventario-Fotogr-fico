package com.inventory.storage

/**
 * One existing photo of the target container that looks identical to an
 * import candidate: its relative path plus the description to show the
 * user ("the photo already here as '<description>'").
 */
data class DuplicateHit(
    val relPath: String,
    val description: String,
)

/**
 * Read-only duplicate detection for imports (gallery picks, the Android
 * share target, the desktop `--import` CLI): hashes the candidate and
 * every photo already present in the TARGET container, and reports the
 * first whose dHash is within [threshold] (Hamming distance) of the
 * candidate's.
 *
 * The check never touches the disk beyond reads, so it introduces no
 * re-encoding and no new format. It is intentionally per-import and
 * container-local: at MVP sizes decoding the target's photos once per
 * import is cheap. A future optimization is a `phash` column in the
 * disposable cache index (rebuilt by Rescan like everything else), but
 * that persistence is deliberately NOT implemented here — the cache is
 * disposable and the filesystem stays the only source of truth.
 */
class DuplicateGuard(
    private val backend: StorageBackend,
    private val hasher: ImageHasher,
    private val threshold: Int = DHash.HAMMING_THRESHOLD,
) {

    /**
     * Returns the first photo of [targetRel] whose dHash is within
     * [threshold] of the candidate's, or null when none (or the candidate
     * cannot be decoded). The container cover is skipped explicitly — even
     * though [StorageBackend.listPhotos] already omits it — and an
     * undecodable existing photo is ignored rather than aborting the scan.
     */
    suspend fun findDuplicate(targetRel: String, candidateBytes: ByteArray): DuplicateHit? {
        val candidateGrid = hasher.grayGrid(candidateBytes) ?: return null
        val candidateHash = DHash.fromGrid(candidateGrid)
        for (entry in backend.listPhotos(targetRel)) {
            if (entry.name.equals(COVER_FILE_NAME, ignoreCase = true)) continue
            val existingBytes = backend.readPhotoBytes(entry.relPath) ?: continue
            val existingGrid = hasher.grayGrid(existingBytes) ?: continue
            if (DHash.hamming(candidateHash, DHash.fromGrid(existingGrid)) <= threshold) {
                val meta = backend.readPhotoMeta(entry.relPath)
                val description = meta?.description?.takeIf { it.isNotBlank() }
                    ?: entry.name.substringBeforeLast('.', entry.name)
                return DuplicateHit(entry.relPath, description)
            }
        }
        return null
    }
}
