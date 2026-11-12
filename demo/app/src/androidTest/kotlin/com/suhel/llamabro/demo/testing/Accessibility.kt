package com.suhel.llamabro.demo.testing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks

/**
 * Every screen test turns Compose's own accessibility checks on (Accessibility Test Framework: touch target size,
 * contrast, content descriptions): a violation fails the test at its next interaction.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.withAccessibilityChecks() = apply { enableAccessibilityChecks() }
