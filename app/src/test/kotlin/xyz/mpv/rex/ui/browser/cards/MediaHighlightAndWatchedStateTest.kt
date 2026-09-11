package xyz.mpv.rex.ui.browser.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaHighlightAndWatchedStateTest {

  /**
   * Evaluates media card highlighting logic as implemented in BaseMediaCard.kt:
   * val shouldHighlight = isRecentlyPlayed && !isWatched
   */
  private fun computeShouldHighlight(isRecentlyPlayed: Boolean, isWatched: Boolean): Boolean {
    return isRecentlyPlayed && !isWatched
  }

  /**
   * Evaluates hasBeenWatched calculation logic as implemented in PlayerPlaybackStateController.kt:
   * val isFinished = isEof || ((currentDuration > 0) && (currentPos >= currentDuration - 1))
   * hasBeenWatched = if (isFinished) true else if (currentDuration > 0) false else (oldState?.hasBeenWatched ?: false)
   */
  private fun computeHasBeenWatched(
    currentPos: Int,
    currentDuration: Int,
    isEof: Boolean = false,
    oldHasBeenWatched: Boolean? = null,
  ): Boolean {
    val isFinished = isEof || ((currentDuration > 0) && (currentPos >= currentDuration - 1))
    return if (isFinished) {
      true
    } else if (currentDuration > 0) {
      false
    } else {
      oldHasBeenWatched ?: false
    }
  }

  /**
   * Evaluates video card watched/completion (title dimming) logic across media browser views:
   * val isWatched = playbackState != null && playbackState.hasBeenWatched && playbackState.timeRemaining == 0
   */
  private fun computeIsWatched(hasBeenWatched: Boolean, timeRemaining: Int?): Boolean {
    return hasBeenWatched && timeRemaining == 0
  }

  /**
   * Evaluates thumbnail progress line visibility logic across media browser views:
   * Progress bar remains visible in final seconds (timeRemaining > 0) and clears when finished (timeRemaining == 0).
   */
  private fun computeProgress(timeRemaining: Int?, durationSeconds: Long): Float? {
    if (timeRemaining == null || timeRemaining <= 0 || durationSeconds <= 0) return null
    val watched = durationSeconds - timeRemaining.toLong()
    val progressValue = if (durationSeconds > 0) (watched.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f) else 0f
    return if (progressValue >= 0.01f) progressValue else null
  }

  @Test
  fun `media card highlight evaluates to false when video is watched even if recently played`() {
    assertFalse(computeShouldHighlight(isRecentlyPlayed = true, isWatched = true))
    assertFalse(computeShouldHighlight(isRecentlyPlayed = false, isWatched = true))
  }

  @Test
  fun `media card highlight evaluates to true when video is recently played and not watched`() {
    assertTrue(computeShouldHighlight(isRecentlyPlayed = true, isWatched = false))
  }

  @Test
  fun `media card highlight evaluates to false when video is neither recently played nor watched`() {
    assertFalse(computeShouldHighlight(isRecentlyPlayed = false, isWatched = false))
  }

  @Test
  fun `rewatching finished video resets hasBeenWatched to false when playback in progress`() {
    // 100s video, watched 20s (80s remaining), isEof = false
    val hasBeenWatched = computeHasBeenWatched(
      currentPos = 20,
      currentDuration = 100,
      isEof = false,
    )
    assertFalse(hasBeenWatched)
  }

  @Test
  fun `hasBeenWatched evaluates to false when playback has time remaining`() {
    // 1000s video, watched 899s (101s remaining)
    assertFalse(
      computeHasBeenWatched(
        currentPos = 899,
        currentDuration = 1000,
        isEof = false,
      )
    )
    // 100s video, watched 90s (10s remaining)
    assertFalse(
      computeHasBeenWatched(
        currentPos = 90,
        currentDuration = 100,
        isEof = false,
      )
    )
    // 100s video, watched 95s (5s remaining)
    assertFalse(
      computeHasBeenWatched(
        currentPos = 95,
        currentDuration = 100,
        isEof = false,
      )
    )
  }

  @Test
  fun `hasBeenWatched evaluates to true when video is finished at true end or EOF`() {
    // Reached EOF
    val hasBeenWatchedEof = computeHasBeenWatched(
      currentPos = 10,
      currentDuration = 100,
      isEof = true,
    )
    assertTrue(hasBeenWatchedEof)

    // Reached within 1 second of the end (99s of 100s)
    val hasBeenWatchedEnd = computeHasBeenWatched(
      currentPos = 99,
      currentDuration = 100,
      isEof = false,
    )
    assertTrue(hasBeenWatchedEnd)

    // Reached exact end (100s of 100s)
    val hasBeenWatchedExactEnd = computeHasBeenWatched(
      currentPos = 100,
      currentDuration = 100,
      isEof = false,
    )
    assertTrue(hasBeenWatchedExactEnd)
  }

  @Test
  fun `hasBeenWatched evaluates to false with zero or negative duration and not finished`() {
    assertFalse(
      computeHasBeenWatched(
        currentPos = 0,
        currentDuration = 0,
        isEof = false,
      )
    )
    assertFalse(
      computeHasBeenWatched(
        currentPos = 0,
        currentDuration = -1,
        isEof = false,
      )
    )
  }

  @Test
  fun `hasBeenWatched preserves existing watched state when duration is unavailable`() {
    // Media closed before duration is loaded (currentDuration = 0, not EOF)
    // If previously watched, state must be preserved
    assertTrue(
      computeHasBeenWatched(
        currentPos = 0,
        currentDuration = 0,
        isEof = false,
        oldHasBeenWatched = true,
      )
    )
    // If previously unwatched, remains unwatched
    assertFalse(
      computeHasBeenWatched(
        currentPos = 0,
        currentDuration = 0,
        isEof = false,
        oldHasBeenWatched = false,
      )
    )
    // Null old state defaults to unwatched
    assertFalse(
      computeHasBeenWatched(
        currentPos = 0,
        currentDuration = 0,
        isEof = false,
        oldHasBeenWatched = null,
      )
    )
  }

  @Test
  fun `card title dimming does not trigger while a video still has playback time remaining`() {
    // Playback with time remaining must not be dimmed, even if legacy state had hasBeenWatched = true
    assertFalse(computeIsWatched(hasBeenWatched = false, timeRemaining = 59))
    assertFalse(computeIsWatched(hasBeenWatched = true, timeRemaining = 59))
    assertFalse(computeIsWatched(hasBeenWatched = false, timeRemaining = 10))
    assertFalse(computeIsWatched(hasBeenWatched = false, timeRemaining = 1))
    // Marked as New (-1) or never played (null) must not be dimmed
    assertFalse(computeIsWatched(hasBeenWatched = false, timeRemaining = -1))
    assertFalse(computeIsWatched(hasBeenWatched = false, timeRemaining = null))
  }

  @Test
  fun `card title dimming triggers when the video has reached true end of playback`() {
    // 0:00 remaining and hasBeenWatched == true triggers dimmed indication
    assertTrue(computeIsWatched(hasBeenWatched = true, timeRemaining = 0))
  }

  @Test
  fun `card title dimming does not trigger when marked as LastPlayed`() {
    // When marked as LastPlayed, hasBeenWatched is reset to false even though timeRemaining is 0
    val isWatched = computeIsWatched(hasBeenWatched = false, timeRemaining = 0)
    assertFalse(isWatched)
    // Verify that the video is properly eligible for Last Played highlighting
    assertTrue(computeShouldHighlight(isRecentlyPlayed = true, isWatched = isWatched))
  }

  @Test
  fun `thumbnail progress bar remains visible in final seconds of playback before completion`() {
    // 1000s video with 10s remaining (0:10 remaining, 99.0% progress)
    val progress10s = computeProgress(timeRemaining = 10, durationSeconds = 1000L)
    assertNotNull(progress10s)
    assertEquals(0.99f, progress10s!!, 0.001f)

    // 1000s video with 5s remaining (0:05 remaining, 99.5% progress)
    val progress5s = computeProgress(timeRemaining = 5, durationSeconds = 1000L)
    assertNotNull(progress5s)
    assertEquals(0.995f, progress5s!!, 0.001f)

    // 1000s video with 1s remaining (0:01 remaining, 99.9% progress)
    val progress1s = computeProgress(timeRemaining = 1, durationSeconds = 1000L)
    assertNotNull(progress1s)
    assertEquals(0.999f, progress1s!!, 0.001f)
  }

  @Test
  fun `thumbnail progress bar clears once video is completely finished`() {
    // 0:00 remaining / finished clears progress bar
    val progressFinished = computeProgress(timeRemaining = 0, durationSeconds = 1000L)
    assertNull(progressFinished)
  }

  @Test
  fun `thumbnail progress bar is null for unplayed, new, or sub-one-percent progress`() {
    assertNull(computeProgress(timeRemaining = null, durationSeconds = 1000L))
    assertNull(computeProgress(timeRemaining = -1, durationSeconds = 1000L))
    // 0s watched of 1000s (< 1% progress)
    assertNull(computeProgress(timeRemaining = 1000, durationSeconds = 1000L))
  }

  @Test
  fun `transition boundary between progress bar visibility and dimmed completion`() {
    val duration = 100
    // Right before boundary (e.g. ~2s remaining, pos 98):
    // PlayerPlaybackStateController evaluates isFinished = false
    val finishedBefore = computeHasBeenWatched(currentPos = 98, currentDuration = duration, isEof = false)
    assertFalse(finishedBefore)
    val timeRemainingBefore = duration - 98
    // Browser displays card undimmed with progress line visible
    assertFalse(computeIsWatched(hasBeenWatched = finishedBefore, timeRemaining = timeRemainingBefore))
    val progressBefore = computeProgress(timeRemaining = timeRemainingBefore, durationSeconds = duration.toLong())
    assertNotNull(progressBefore)
    assertEquals(0.98f, progressBefore!!, 0.001f)

    // At transition boundary (within 1s of completion, e.g. pos 99 of 100s):
    // PlayerPlaybackStateController evaluates isFinished = true, saves timeRemaining = 0
    val finishedAtBoundary = computeHasBeenWatched(currentPos = 99, currentDuration = duration, isEof = false)
    assertTrue(finishedAtBoundary)
    val timeRemainingAtBoundary = 0
    // Browser displays card dimmed with progress line cleared
    assertTrue(computeIsWatched(hasBeenWatched = finishedAtBoundary, timeRemaining = timeRemainingAtBoundary))
    assertNull(computeProgress(timeRemaining = timeRemainingAtBoundary, durationSeconds = duration.toLong()))
  }

  @Test
  fun `thumbnail progress bar handles edge cases of invalid time remaining and extreme durations`() {
    // Corrupted state where timeRemaining exceeds duration
    assertNull(computeProgress(timeRemaining = 1200, durationSeconds = 1000L))

    // Non-positive duration
    assertNull(computeProgress(timeRemaining = 5, durationSeconds = 0L))
    assertNull(computeProgress(timeRemaining = 5, durationSeconds = -10L))

    // Very long video in final seconds (e.g. 10000s video with 2s remaining)
    val progressLong = computeProgress(timeRemaining = 2, durationSeconds = 10000L)
    assertNotNull(progressLong)
    assertEquals(0.9998f, progressLong!!, 0.0001f)
  }
}
