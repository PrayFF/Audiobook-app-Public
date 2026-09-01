package com.pray.booklisten.data

data class ChapterSeekTarget(val blockIndex: Int, val fractionInBlock: Float)

object ChapterProgress {
    fun fraction(blocks: List<String>, blockIndex: Int, fractionInBlock: Float): Float {
        if (blocks.isEmpty()) return 0f
        val safeIndex = blockIndex.coerceIn(blocks.indices)
        val total = blocks.sumOf { it.length.coerceAtLeast(1) }.toFloat()
        val before = blocks.take(safeIndex).sumOf { it.length.coerceAtLeast(1) }
        val within = blocks[safeIndex].length.coerceAtLeast(1) * fractionInBlock.coerceIn(0f, 1f)
        return ((before + within) / total).coerceIn(0f, 1f)
    }

    fun target(blocks: List<String>, chapterFraction: Float): ChapterSeekTarget {
        if (blocks.isEmpty()) return ChapterSeekTarget(0, 0f)
        val weights = blocks.map { it.length.coerceAtLeast(1) }
        val total = weights.sum()
        val offset = chapterFraction.coerceIn(0f, 1f) * total
        var consumed = 0
        weights.forEachIndexed { index, weight ->
            if (offset <= consumed + weight || index == weights.lastIndex) {
                return ChapterSeekTarget(index, ((offset - consumed) / weight).coerceIn(0f, 1f))
            }
            consumed += weight
        }
        return ChapterSeekTarget(weights.lastIndex, 1f)
    }
}
