package com.animationworkshop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MovieEffectsTest {
    private val frames = listOf(
        AnimationFrame("a", File("a.jpg"), 1),
        AnimationFrame("b", File("b.jpg"), 2),
        AnimationFrame("c", File("c.jpg"), 3)
    )

    @Test
    fun existingProjectsUseOriginalSequenceAndHolds() {
        val result = buildMovieTimeline(frames, MovieEffects())
        assertEquals(listOf("a", "b", "c"), result.map { it.frame.id })
        assertEquals(listOf(1, 2, 3), result.map { it.durationTicks })
        assertEquals(0.75, movieDurationSeconds(frames, MovieEffects(), 8), 0.0001)
    }

    @Test
    fun reverseAndBoomerangKeepTurnaroundFramesUnique() {
        assertEquals(listOf("c", "b", "a"), buildMovieTimeline(frames, MovieEffects(timeline = TimelineEffectType.REVERSE)).map { it.frame.id })
        assertEquals(listOf("a", "b", "c", "b"), buildMovieTimeline(frames, MovieEffects(timeline = TimelineEffectType.BOOMERANG)).map { it.frame.id })
        assertEquals(listOf(1, 2, 3, 2), buildMovieTimeline(frames, MovieEffects(timeline = TimelineEffectType.BOOMERANG)).map { it.durationTicks })
    }

    @Test
    fun freezeAndSlowMotionScaleTicksWithoutCreatingPhotoFiles() {
        val frozen = MovieEffects(freezeFrameId = "b", freezeMultiplier = 3)
        val result = buildMovieTimeline(frames, frozen)
        assertEquals(listOf(1, 6, 3), result.map { it.durationTicks })
        assertEquals(listOf(File("a.jpg"), File("b.jpg"), File("c.jpg")), result.map { it.frame.file })
        val slow = buildMovieTimeline(frames, MovieEffects(timeline = TimelineEffectType.SLOW_MOTION))
        assertEquals(listOf(2, 4, 6), slow.map { it.durationTicks })
    }

    @Test
    fun fastMotionSamplesVirtualHoldTicksAtDoubleSpeed() {
        val result = buildMovieTimeline(frames, MovieEffects(timeline = TimelineEffectType.FAST_MOTION))
        assertEquals(3, result.sumOf { it.durationTicks })
        assertEquals(listOf("a", "b", "c"), result.map { it.frame.id })
        assertTrue(result.all { it.durationTicks > 0 })
    }

    @Test
    fun imageAndTimelineTreatmentsCanBeCombinedAndDefaultsAreNeutral() {
        val settings = MovieEffects(image = ImageEffectType.SEPIA, timeline = TimelineEffectType.REVERSE).normalized()
        assertEquals(ImageEffectType.SEPIA, settings.image)
        assertEquals(TimelineEffectType.REVERSE, settings.timeline)
        assertEquals(MovieEffects(), MovieEffects().normalized())
    }
}
