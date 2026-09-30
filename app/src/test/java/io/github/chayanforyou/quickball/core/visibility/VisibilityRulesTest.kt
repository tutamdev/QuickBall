package io.github.chayanforyou.quickball.core.visibility

import io.github.chayanforyou.quickball.core.visibility.VisibilityRules.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibilityRulesTest {

    private val base = VisibilityRules.Input(
        isEnabled = true,
        isLocked = false,
        showOnLockScreen = false,
        isLandscape = false,
        hideOnLandscape = false,
        foregroundPackage = "com.android.chrome",
        excludedPackages = setOf("com.vietcombank.mobile", "com.mbmobile"),
    )

    @Test fun `normal app shows ball`() =
        assertEquals(Decision.SHOW, VisibilityRules.decide(base))

    @Test fun `excluded app hides ball`() =
        assertEquals(Decision.HIDE, VisibilityRules.decide(base.copy(foregroundPackage = "com.mbmobile")))

    @Test fun `unknown foreground shows ball`() =
        assertEquals(Decision.SHOW, VisibilityRules.decide(base.copy(foregroundPackage = null)))

    @Test fun `disabled always hides`() =
        assertEquals(Decision.HIDE, VisibilityRules.decide(base.copy(isEnabled = false)))

    @Test fun `locked without lock screen option hides`() =
        assertEquals(Decision.HIDE, VisibilityRules.decide(base.copy(isLocked = true)))

    @Test fun `locked with lock screen option shows stashed`() =
        assertEquals(Decision.SHOW_STASHED, VisibilityRules.decide(base.copy(isLocked = true, showOnLockScreen = true)))

    @Test fun `landscape hide only when option on`() {
        assertEquals(Decision.SHOW, VisibilityRules.decide(base.copy(isLandscape = true)))
        assertEquals(Decision.HIDE, VisibilityRules.decide(base.copy(isLandscape = true, hideOnLandscape = true)))
    }
}
