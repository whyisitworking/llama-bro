package com.suhel.llamabro.demo.testing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher

/**
 * Every screen test turns Compose's own accessibility checks on (Accessibility Test Framework: touch target size,
 * contrast, content descriptions): a violation fails the test at its next interaction.
 *
 * One kind of result is set aside: a size or label result for a row a scrolling list has cut at its edge. Compose
 * reports a node's bounds already clipped, so a row that shows only a sliver at the edge reads as a tiny target with
 * its label scrolled away; the same row, scrolled in, is checked whole. ATF's own `isAgainstScrollableEdge` misses
 * it, as the row's clipped bounds stop short of the edge its group reaches.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.withAccessibilityChecks() =
    apply {
        enableAccessibilityChecks(
            AccessibilityValidator().setRunChecksFromRootView(true).setSuppressingResultMatcher(CutByAScrollingEdge),
        )
    }

private object CutByAScrollingEdge : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    private val sizeChecks = setOf(TouchTargetSizeCheck::class.java, SpeakableTextPresentCheck::class.java)

    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        val element = result.element ?: return false
        if (result.accessibilityHierarchyCheck !in sizeChecks) return false
        // the node, or the group it sits in, reaches the nearest scroller's top or bottom edge: the scroller has cut it
        val reaching = generateSequence(element) { it.parentView }.toList()
        val scroller = reaching.drop(1).firstOrNull { it.isScrollable == true } ?: return false
        val edge = scroller.boundsInScreen
        return reaching.takeWhile { it !== scroller }.any {
            val bounds = it.boundsInScreen
            bounds.top <= edge.top || bounds.bottom >= edge.bottom
        }
    }

    override fun describeTo(description: Description) {
        description.appendText("a size or label result for a node cut by a scrolling container's edge")
    }
}
