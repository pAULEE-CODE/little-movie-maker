package com.animationworkshop.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSequenceOperationsTest {
    private fun frames(vararg ids: String) = ids.map { AnimationFrame(it, File("$it.jpg")) }

    @Test
    fun longPressRangeSelectionIncludesBothEndsAndClamps() {
        assertEquals(setOf("b", "c", "d"), FrameSequenceOperations.rangeSelection(listOf("a", "b", "c", "d", "e"), 3, 1))
        assertEquals(setOf("d", "e"), FrameSequenceOperations.rangeSelection(listOf("a", "b", "c", "d", "e"), 3, 12))
        assertEquals(emptySet<String>(), FrameSequenceOperations.rangeSelection(emptyList(), 0, 0))
    }

    @Test
    fun deletionKeepsPlayheadNearNextSurvivingFrame() {
        val before = frames("a", "b", "c", "d", "e")
        val after = listOf(before[0], before[1], before[4])
        assertEquals("e", FrameSequenceOperations.selectedAfterDeletion(before, after, 2, setOf("c", "d")))
        assertEquals("b", FrameSequenceOperations.selectedAfterDeletion(before, listOf(before[0], before[1]), 4, setOf("c", "d", "e")))
        assertNull(FrameSequenceOperations.selectedAfterDeletion(before, emptyList(), 2, before.map { it.id }.toSet()))
    }

    @Test
    fun splitRequiresNonEmptyPartsAndPreservesOrder() {
        val source = frames("a", "b", "c", "d")
        val (first, second) = FrameSequenceOperations.split(source, 2)
        assertEquals(listOf("a", "b"), first.map { it.id })
        assertEquals(listOf("c", "d"), second.map { it.id })
        listOf(0, source.size).forEach { invalid ->
            try {
                FrameSequenceOperations.split(source, invalid)
                throw AssertionError("Expected empty split part to be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun mergePreservesMovieAndFrameOrdering() {
        val first = frames("a", "b")
        val second = frames("c", "d", "e")
        assertEquals(listOf("a", "b", "c", "d", "e"), FrameSequenceOperations.merge(listOf(first, second)).map { it.id })
    }

    @Test
    fun selectedHoldAdjustmentsUpdateOnlySelectedFramesAndClampIndividually() {
        val source = listOf(
            AnimationFrame("a", File("a.jpg"), duration = 1),
            AnimationFrame("b", File("b.jpg"), duration = 4),
            AnimationFrame("c", File("c.jpg"), duration = 10)
        )

        val increased = FrameSequenceOperations.adjustDurations(source, setOf("a", "c"), 1)
        assertEquals(listOf(2, 4, 10), increased.map { it.duration })

        val decreased = FrameSequenceOperations.adjustDurations(increased, setOf("a", "c"), -1)
        assertEquals(listOf(1, 4, 9), decreased.map { it.duration })
    }

    @Test
    fun exportedMovieNamesAreScopedToTheirProject() {
        val alpha = "12345678-aaaa-bbbb-cccc-dddddddddddd"
        val beta = "87654321-aaaa-bbbb-cccc-dddddddddddd"
        val alphaName = "${ProjectExportIdentity.fileNamePrefix(alpha)}20261017_1200.mp4"
        assertEquals(true, ProjectExportIdentity.belongsToProject(alphaName, alpha))
        assertEquals(false, ProjectExportIdentity.belongsToProject(alphaName, beta))
        assertEquals(null, ProjectExportIdentity.fileNamePrefix("unsafe/id"))
    }

    @Test
    fun selectionAndSequenceRulesSupportLongTimelines() {
        val largeTimeline = (1..500).map { "frame-$it" }
        val selection = FrameSequenceOperations.rangeSelection(largeTimeline, 398, 425)
        assertEquals(28, selection.size)
        assertTrue(selection.contains("frame-399"))
        assertTrue(selection.contains("frame-426"))
    }
}
