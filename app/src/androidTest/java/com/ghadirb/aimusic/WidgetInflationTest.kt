package com.ghadirb.aimusic

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghadirb.aimusic.widget.MusicWidgetProvider
import com.ghadirb.aimusic.widget.WidgetState
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the home-screen widget: its RemoteViews must inflate (idle and playing) with all controls present. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetInflationTest {

    @Test
    fun widgetLayoutInflatesInIdleAndPlayingStates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            WidgetState(null, null, false),
            WidgetState("عنوان آهنگ", "خواننده", true)
        ).forEach { state ->
            val views = MusicWidgetProvider.buildViews(context, state)
            val root = views.apply(context, FrameLayout(context))
            assertNotNull(root.findViewById<View>(R.id.widget_title))
            assertNotNull(root.findViewById<View>(R.id.widget_artist))
            assertNotNull(root.findViewById<View>(R.id.widget_play_pause))
            assertNotNull(root.findViewById<View>(R.id.widget_next))
            assertNotNull(root.findViewById<View>(R.id.widget_prev))
        }
    }
}
