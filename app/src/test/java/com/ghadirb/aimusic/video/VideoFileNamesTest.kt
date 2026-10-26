package com.ghadirb.aimusic.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFileNamesTest {
    @Test fun keepsOriginalExtensionWhenOmitted() = assertEquals("Holiday.mkv", VideoFileNames.check("Holiday", "a.mkv").name)
    @Test fun acceptsExplicitVideoExtension() = assertEquals("x.mp4", VideoFileNames.check("x.mp4", "a.mkv").name)
    @Test fun dotInNameIsNotAnExtension() = assertEquals("Part 1.2.mkv", VideoFileNames.check("Part 1.2", "a.mkv").name)
    @Test fun persianNamesWork() = assertEquals("فیلم خانوادگی.mp4", VideoFileNames.check(" فیلم خانوادگی ", "a.mp4").name)
    @Test fun emptyIsRejected() = assertNotNull(VideoFileNames.check("   ", "a.mp4").error)
    @Test fun forbiddenCharactersRejected() {
        for (c in listOf("a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b")) {
            assertNull(c, VideoFileNames.check(c, "a.mp4").name)
        }
    }
    @Test fun sameNameRejected() = assertNotNull(VideoFileNames.check("a", "a.mp4").error)
    @Test fun tooLongRejected() = assertNotNull(VideoFileNames.check("x".repeat(130), "a.mp4").error)
    @Test fun leadingDotsStripped() = assertEquals("hidden.mp4", VideoFileNames.check("..hidden", "a.mp4").name)
}
