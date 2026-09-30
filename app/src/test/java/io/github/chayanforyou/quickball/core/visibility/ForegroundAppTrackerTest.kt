package io.github.chayanforyou.quickball.core.visibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForegroundAppTrackerTest {

    private val bank = "com.vietcombank.mobile"
    private val launcher = "com.huawei.android.launcher"
    private val keyboard = "com.google.android.inputmethod.latin"

    private val activities = setOf(
        "$bank/$bank.MainActivity",
        "$launcher/$launcher.unihome.UniHomeLauncher",
        "com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity",
    )

    private lateinit var tracker: ForegroundAppTracker

    @Before
    fun setUp() {
        tracker = ForegroundAppTracker(
            isActivity = { pkg, cls -> "$pkg/$cls" in activities },
            ignoredPackages = { setOf("com.android.systemui", keyboard) }
        )
    }

    @Test
    fun `activity transition changes foreground`() {
        assertTrue(tracker.onWindowStateChanged(bank, "$bank.MainActivity"))
        assertEquals(bank, tracker.currentPackage)
    }

    @Test
    fun `same app twice is not a change`() {
        tracker.onWindowStateChanged(bank, "$bank.MainActivity")
        assertFalse(tracker.onWindowStateChanged(bank, "$bank.MainActivity"))
    }

    @Test
    fun `keyboard opening inside excluded app does not change foreground`() {
        tracker.onWindowStateChanged(bank, "$bank.MainActivity")
        assertFalse(tracker.onWindowStateChanged(keyboard, "android.inputmethodservice.SoftInputWindow"))
        assertEquals(bank, tracker.currentPackage)
    }

    @Test
    fun `dialog or view class of another package is ignored`() {
        tracker.onWindowStateChanged(bank, "$bank.MainActivity")
        assertFalse(tracker.onWindowStateChanged("com.huawei.hwid", "android.widget.FrameLayout"))
        assertEquals(bank, tracker.currentPackage)
    }

    @Test
    fun `notification shade is ignored`() {
        tracker.onWindowStateChanged(bank, "$bank.MainActivity")
        assertFalse(tracker.onWindowStateChanged("com.android.systemui", "com.android.systemui.statusbar.phone.PhoneStatusBar"))
        assertEquals(bank, tracker.currentPackage)
    }

    /** Regression: the original code ignored the first event after leaving an excluded app. */
    @Test
    fun `leaving excluded app to launcher is detected on first event`() {
        tracker.onWindowStateChanged(bank, "$bank.MainActivity")
        assertTrue(tracker.onWindowStateChanged(launcher, "$launcher.unihome.UniHomeLauncher"))
        assertEquals(launcher, tracker.currentPackage)
    }

    @Test
    fun `null or blank data is ignored`() {
        assertFalse(tracker.onWindowStateChanged(null, "x"))
        assertFalse(tracker.onWindowStateChanged(bank, null))
        assertFalse(tracker.onWindowStateChanged("", ""))
        assertNull(tracker.currentPackage)
    }

    @Test
    fun `reset clears foreground`() {
        tracker.onWindowStateChanged(bank, "$bank.MainActivity")
        tracker.reset()
        assertNull(tracker.currentPackage)
        assertTrue(tracker.onWindowStateChanged(bank, "$bank.MainActivity"))
    }
}
