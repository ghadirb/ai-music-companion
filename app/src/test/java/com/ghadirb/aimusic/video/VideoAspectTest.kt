package com.ghadirb.aimusic.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAspectTest {
    @Test fun `cycles through all modes and wraps around`() {
        assertEquals(VideoAspect.FILL, VideoAspect.FIT.next())
        assertEquals(VideoAspect.ZOOM, VideoAspect.FILL.next())
        assertEquals(VideoAspect.FIT, VideoAspect.ZOOM.next())
    }

    @Test fun `every mode has a label for the button`() {
        VideoAspect.values().forEach { assertEquals(false, it.label.isBlank()) }
    }
}
