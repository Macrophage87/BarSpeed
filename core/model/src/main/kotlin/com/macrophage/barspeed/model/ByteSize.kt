package com.macrophage.barspeed.model

import java.util.Locale

/**
 * A byte count in the units a lifter reads, not the units a filesystem
 * counts in. Issue #111: a card offering to discard a rescued database has
 * to show its size so that decision is made with the number in front of
 * them, since nothing else bounds how large it can get.
 *
 * Decimal (1000-based), not binary (1024-based) -- matching the "roughly
 * 116 KB" language `DatabaseRescue`'s own KDoc in :core:data already uses
 * for a set's gzipped size, and the more common reading for a lifter who is
 * not a filesystem. Nothing in this repository reports a byte count any
 * other way today, so there is no existing convention to conflict with.
 *
 * That attribution is a correction. This KDoc first credited the phrase to
 * `DatabaseDowngradePolicy`, whose KDoc has never contained it. The link
 * RESOLVED, which is why nothing caught it: DatabaseDowngradePolicy is a
 * real symbol in this same module, so the reference compiled and read as
 * sourced while pointing at a file that says nothing of the kind. Backticks
 * for the real source rather than a KDoc link, because :core:model cannot
 * see :core:data -- core/data/build.gradle.kts:62 depends on this module,
 * not the reverse -- so a bracketed reference would silently not resolve.
 */
object ByteSize {
    private const val KB = 1_000.0
    private const val MB = KB * 1_000.0

    /** e.g. 512 -> "512 B", 4_200 -> "4.2 KB", 52_000_000 -> "52.0 MB". */
    fun format(bytes: Long): String = when {
        bytes < KB -> "$bytes B"
        bytes < MB -> String.format(Locale.US, "%.1f KB", bytes / KB)
        else -> String.format(Locale.US, "%.1f MB", bytes / MB)
    }
}
