package io.github.chayanforyou.quickball.core.visibility

/**
 * Single source of truth for "should the Quick Ball UI be on screen right now?".
 * This only decides whether *our own overlay windows* are shown. It never
 * touches the accessibility service state or any permission.
 */
object VisibilityRules {

    enum class Decision {
        /** Remove all Quick Ball overlay windows. */
        HIDE,
        /** Show the ball normally. */
        SHOW,
        /** Show the ball already stashed at the edge (lock screen). */
        SHOW_STASHED,
    }

    data class Input(
        val isEnabled: Boolean,
        val isLocked: Boolean,
        val showOnLockScreen: Boolean,
        val isLandscape: Boolean,
        val hideOnLandscape: Boolean,
        val foregroundPackage: String?,
        val excludedPackages: Set<String>,
    )

    fun decide(input: Input): Decision = with(input) {
        when {
            !isEnabled -> Decision.HIDE
            isLocked -> if (showOnLockScreen) Decision.SHOW_STASHED else Decision.HIDE
            hideOnLandscape && isLandscape -> Decision.HIDE
            foregroundPackage != null && foregroundPackage in excludedPackages -> Decision.HIDE
            else -> Decision.SHOW
        }
    }
}
