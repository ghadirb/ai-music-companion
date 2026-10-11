package com.ghadirb.aimusic.video

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedsTest {
    @Test fun cyclesThroughStepsAndWraps() {
        assertEquals(1.25f, PlaybackSpeeds.next(1f), 0.0001f)
        assertEquals(2f, PlaybackSpeeds.next(1.5f), 0.0001f)
        assertEquals(0.5f, PlaybackSpeeds.next(2f), 0.0001f)
        assertEquals(0.75f, PlaybackSpeeds.next(0.5f), 0.0001f)
    }

    @Test fun unknownSpeedMovesToTheNextHigherStep() {
        assertEquals(1.25f, PlaybackSpeeds.next(1.1f), 0.0001f)
    }

    @Test fun labelsAreCompact() {
        assertEquals("1×", PlaybackSpeeds.label(1f))
        assertEquals("1.25×", PlaybackSpeeds.label(1.25f))
        assertEquals("0.5×", PlaybackSpeeds.label(0.5f))
    }
}
