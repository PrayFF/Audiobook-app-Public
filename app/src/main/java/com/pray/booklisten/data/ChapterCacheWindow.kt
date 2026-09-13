package com.pray.booklisten.data

object ChapterCacheWindow {
    /** Current chapter plus the next ten; never scan further just because some are cached. */
    fun indices(current: Int, total: Int): List<Int> {
        if (current < 0 || current >= total) return emptyList()
        return (current..minOf(current.toLong() + 10, total.toLong() - 1).toInt()).toList()
    }
}
