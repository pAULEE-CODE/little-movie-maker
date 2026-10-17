package com.animationworkshop.app

/** Small deterministic sequence rules shared by editing, split/merge and unit tests. */
internal object FrameSequenceOperations {
    /** Adjust each selected frame relative to its own duration; unrelated frames remain untouched. */
    fun adjustDurations(frames: List<AnimationFrame>, selectedIds: Set<String>, amount: Int): List<AnimationFrame> {
        if (selectedIds.isEmpty() || amount == 0) return frames
        return frames.map { frame ->
            if (frame.id in selectedIds) frame.copy(duration = (frame.duration + amount).coerceIn(1, 10)) else frame
        }
    }

    /** cutIndex is the index of the first frame in part B (a boundary between frames). */
    fun split(frames: List<AnimationFrame>, cutIndex: Int): Pair<List<AnimationFrame>, List<AnimationFrame>> {
        require(cutIndex in 1 until frames.size) { "Choose a cut point between two frames" }
        return frames.subList(0, cutIndex).toList() to frames.subList(cutIndex, frames.size).toList()
    }

    fun merge(sequencesInOrder: List<List<AnimationFrame>>): List<AnimationFrame> = sequencesInOrder.flatten()

    fun rangeSelection(frameIds: List<String>, firstIndex: Int, lastIndex: Int): Set<String> {
        if (frameIds.isEmpty()) return emptySet()
        val start = minOf(firstIndex, lastIndex).coerceIn(frameIds.indices)
        val end = maxOf(firstIndex, lastIndex).coerceIn(frameIds.indices)
        return frameIds.subList(start, end + 1).toSet()
    }

    /** Prefer the next surviving frame, then the closest frame to the former playhead. */
    fun selectedAfterDeletion(
        before: List<AnimationFrame>,
        after: List<AnimationFrame>,
        selectedIndex: Int,
        deletedIds: Set<String>
    ): String? {
        if (after.isEmpty()) return null
        val formerIndex = selectedIndex.coerceIn(0, (before.size - 1).coerceAtLeast(0))
        val next = before.drop(formerIndex).firstOrNull { it.id !in deletedIds && after.any { current -> current.id == it.id } }
        if (next != null) return next.id
        return before.take(formerIndex).asReversed().firstOrNull { it.id !in deletedIds && after.any { current -> current.id == it.id } }?.id
            ?: after[formerIndex.coerceIn(after.indices)].id
    }
}
