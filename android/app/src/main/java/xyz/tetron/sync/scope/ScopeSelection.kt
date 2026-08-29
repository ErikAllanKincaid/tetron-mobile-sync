// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.scope

/**
 * SYNC-012: one camera-roll entry, reduced to the three columns the scope
 * needs. `AndroidMediaAccess` builds these from its `MediaStore` cursor
 * (`DISPLAY_NAME`, `MIME_TYPE`, and `max(SIZE, File.length())` -- the stat
 * is a known-path read, Scoped-Storage-safe, and guards the size cap
 * against a stale `MediaStore.SIZE`). Keeping the reduction pure here is
 * what makes the selection and the backlog estimate unit-testable without
 * a `Cursor`.
 */
data class MediaEntry(
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
)

/** The `--files-from` staging result: the names that survive the scope,
 *  plus how many in-scope-by-type files were dropped only for being over
 *  the size cap (decision A5 -- History reports this apart from the
 *  "already on server" skips). */
data class ScopedSelection(
    val includedNames: List<String>,
    val oversizeSkippedCount: Int,
)

/**
 * Apply [scope] to a batch of entries -- the exact filter the
 * `--files-from` builder runs on every backup. Type-excluded entries just
 * fall away (expected, not a data-loss signal); only oversize drops are
 * counted, because those are a file the user probably still wants and the
 * History line is the only place they surface (decision B5).
 */
fun selectInScope(
    entries: Iterable<MediaEntry>,
    scope: BackupScope,
    sets: MediaTypeSets = MediaTypeSets.DEFAULT,
): ScopedSelection {
    val included = ArrayList<String>()
    var oversize = 0
    for (e in entries) {
        when (ScopeFilter.decide(e.displayName, e.mimeType, e.sizeBytes, scope, sets)) {
            ScopeDecision.Included -> included.add(e.displayName)
            ScopeDecision.ExcludedOversize -> oversize++
            is ScopeDecision.ExcludedType -> Unit
        }
    }
    return ScopedSelection(includedNames = included, oversizeSkippedCount = oversize)
}

/** A per-[MediaKind] count + byte total, for the estimate line and the
 *  Preview breakdown. */
data class KindTally(val kind: MediaKind, val count: Int, val bytes: Long)

/**
 * SYNC-012: the local backlog estimate. Everything the Settings estimate
 * line and the Preview bottom sheet need, computed from a `MediaStore`
 * batch with no tunnel and no target (decision: the accurate
 * "what the server is missing" number is a deferred real dry-run that
 * later augments the same sheet).
 *
 * [photoCount]/[videoCount] split by "is it a video" ([MediaKind.Video] vs
 * everything else) to match the "12,400 photos . 340 videos" line;
 * [includedByKind]/[excludedByKind]/[oversize*] back the Preview's
 * "Will upload" / "Skipped" sections; [largestIncluded] is the
 * "Largest included" list, longest-first, capped.
 */
data class BacklogEstimate(
    val totalCount: Int,
    val totalBytes: Long,
    val photoCount: Int,
    val videoCount: Int,
    val includedCount: Int,
    val includedBytes: Long,
    val includedByKind: List<KindTally>,
    val excludedByKind: List<KindTally>,
    val oversizeCount: Int,
    val oversizeBytes: Long,
    val largestIncluded: List<MediaEntry>,
) {
    companion object {
        val EMPTY = BacklogEstimate(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), 0, 0, emptyList())
    }
}

/**
 * Aggregate [entries] under [scope]. Pure; [AndroidMediaAccess] supplies
 * the `MediaStore` rows. Each entry is classified and decided exactly once.
 * A kind that is toggled off lands wholly in [BacklogEstimate.excludedByKind];
 * an in-scope-by-type entry that is over the cap lands in the oversize
 * bucket, not in its kind's excluded tally.
 */
fun estimateBacklog(
    entries: Iterable<MediaEntry>,
    scope: BackupScope,
    sets: MediaTypeSets = MediaTypeSets.DEFAULT,
    largestN: Int = 5,
): BacklogEstimate {
    var totalCount = 0
    var totalBytes = 0L
    var photoCount = 0
    var videoCount = 0
    var includedCount = 0
    var includedBytes = 0L
    var oversizeCount = 0
    var oversizeBytes = 0L

    val includedTally = linkedMapOf<MediaKind, LongArray>() // kind -> [count, bytes]
    val excludedTally = linkedMapOf<MediaKind, LongArray>()
    val largest = ArrayList<MediaEntry>()

    fun bump(map: MutableMap<MediaKind, LongArray>, kind: MediaKind, size: Long) {
        val slot = map.getOrPut(kind) { LongArray(2) }
        slot[0]++
        slot[1] += size
    }

    for (e in entries) {
        val kind = ScopeFilter.classify(e.displayName, e.mimeType, sets)
        val size = e.sizeBytes.coerceAtLeast(0)
        totalCount++
        totalBytes += size
        if (kind == MediaKind.Video) videoCount++ else photoCount++

        when (ScopeFilter.decide(e.displayName, e.mimeType, e.sizeBytes, scope, sets)) {
            ScopeDecision.Included -> {
                includedCount++
                includedBytes += size
                bump(includedTally, kind, size)
                largest.add(e)
            }
            ScopeDecision.ExcludedOversize -> {
                oversizeCount++
                oversizeBytes += size
            }
            is ScopeDecision.ExcludedType -> bump(excludedTally, kind, size)
        }
    }

    fun tallies(map: Map<MediaKind, LongArray>): List<KindTally> =
        MediaKind.entries
            .filter { map.containsKey(it) }
            .map { KindTally(it, map.getValue(it)[0].toInt(), map.getValue(it)[1]) }

    return BacklogEstimate(
        totalCount = totalCount,
        totalBytes = totalBytes,
        photoCount = photoCount,
        videoCount = videoCount,
        includedCount = includedCount,
        includedBytes = includedBytes,
        includedByKind = tallies(includedTally),
        excludedByKind = tallies(excludedTally),
        oversizeCount = oversizeCount,
        oversizeBytes = oversizeBytes,
        largestIncluded = largest.sortedByDescending { it.sizeBytes }.take(largestN.coerceAtLeast(0)),
    )
}
