// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.scope

/**
 * SYNC-012: the pure decision function behind the `--files-from` builder.
 * No I/O, no Android deps -- `AndroidMediaAccess` reads `DISPLAY_NAME` /
 * `MIME_TYPE` / `SIZE` from its `MediaStore` cursor and calls [decide] per
 * row; the same function backs the Settings backlog estimate and the
 * Preview breakdown. Same bar as SYNC-004's `GateEvaluator`.
 */
object ScopeFilter {

    /**
     * Classify one entry. MIME type is consulted first (decision A3); the
     * extension sets are the fallback, and they carry the load for raw
     * formats specifically -- OEM `MediaStore` rows report raw MIME
     * inconsistently or not at all, while the extension is dependable.
     * An entry matching nothing is [MediaKind.Other] (the catch-all).
     */
    fun classify(
        displayName: String,
        mimeType: String?,
        sets: MediaTypeSets = MediaTypeSets.DEFAULT,
    ): MediaKind {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        when {
            mime == "image/jpeg" || mime == "image/jpg" -> return MediaKind.Jpeg
            mime == "image/heic" || mime == "image/heif" ||
                mime == "image/heic-sequence" || mime == "image/heif-sequence" -> return MediaKind.Heic
            mime == "image/x-adobe-dng" -> return MediaKind.Raw
            mime.startsWith("video/") -> return MediaKind.Video
        }

        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            in sets.jpeg -> MediaKind.Jpeg
            in sets.heic -> MediaKind.Heic
            in sets.raw -> MediaKind.Raw
            in sets.video -> MediaKind.Video
            else -> MediaKind.Other
        }
    }

    /**
     * Apply [scope] to one entry. Type is checked before size, so a file
     * whose type is toggled off reports [ScopeDecision.ExcludedType] even
     * if it would also be over the cap -- History shows it in the
     * turned-off bucket, not the too-large one.
     *
     * [sizeBytes] should be the best size known to the caller: the
     * `MediaStore.SIZE` column, or a `File.length()` stat when the caller
     * has verified the row against disk (SYNC-008 already stats every
     * candidate for the stale-row check, so the cap gets a real size even
     * when `MediaStore.SIZE` is stale).
     */
    fun decide(
        displayName: String,
        mimeType: String?,
        sizeBytes: Long,
        scope: BackupScope,
        sets: MediaTypeSets = MediaTypeSets.DEFAULT,
    ): ScopeDecision {
        val kind = classify(displayName, mimeType, sets)
        val typeIncluded = when (kind) {
            MediaKind.Jpeg -> scope.includeJpeg
            MediaKind.Heic -> scope.includeHeic
            MediaKind.Raw -> scope.includeRaw
            MediaKind.Video -> scope.includeVideos
            MediaKind.Other -> scope.includeOtherFiles
        }
        if (!typeIncluded) return ScopeDecision.ExcludedType(kind)

        val cap = scope.maxSizeBytes
        if (cap != null && sizeBytes > cap) return ScopeDecision.ExcludedOversize

        return ScopeDecision.Included
    }
}
