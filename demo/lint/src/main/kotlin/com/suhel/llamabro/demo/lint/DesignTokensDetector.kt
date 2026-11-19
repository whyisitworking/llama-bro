package com.suhel.llamabro.demo.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression

/**
 * The demo's design-system rule (D48, restated by D113): sizes, colours and type come from the theme's tokens anywhere
 * outside the theme, and the app's screens use the design system's components, not Material's widgets or Compose's
 * motion directly. Read on the syntax tree with calls resolved, so the spellings the old regexes missed (`Dp(12f)`,
 * `Scaffold`, `68.sp`) are caught too. Previews and the share card's off-screen render are not screens.
 */
class DesignTokensDetector :
    Detector(),
    SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UQualifiedReferenceExpression::class.java, UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        val name = context.file.name
        val pkg = context.uastFile?.packageName.orEmpty()
        val inDesignSystem = pkg.startsWith(DESIGN_SYSTEM)
        val debugOnly = "/src/debug/" in context.file.path.replace('\\', '/') // the component gallery
        if (!pkg.startsWith(DEMO) || debugOnly || EXEMPT_FILES.any { it.matches(name) }) return null
        if (inDesignSystem && EXEMPT_PACKAGES.any { pkg.startsWith("$DESIGN_SYSTEM.$it") }) return null
        val file = File(inApp = !inDesignSystem, inDemoType = name == "DemoType.kt")
        return object : UElementHandler() {
            override fun visitQualifiedReferenceExpression(node: UQualifiedReferenceExpression) {
                checkReference(context, node)
            }

            override fun visitCallExpression(node: UCallExpression) {
                checkCall(context, node, file)
            }
        }
    }

    /** Where a file sits: the app's screens, or the design system's type roles. */
    private class File(val inApp: Boolean, val inDemoType: Boolean)

    /** `12.dp`, `14.sp` and `Color.Red`. */
    private fun checkReference(context: JavaContext, node: UQualifiedReferenceExpression) {
        val selector = (node.selector as? USimpleNameReferenceExpression)?.identifier ?: return
        val receiver = node.receiver
        val literal = receiver as? ULiteralExpression
        if (selector in UNITS && literal != null && (literal.value as? Number)?.toDouble() != 0.0) {
            report(context, NUMBER, node, "A size comes from the theme's scale, not `${node.sourcePsi?.text}`")
        }
        val onColor = (receiver as? USimpleNameReferenceExpression)?.identifier == "Color"
        val namedColour = selector.first().isUpperCase() && selector !in NOT_COLOURS
        if (onColor && namedColour) {
            report(context, COLOUR, node, "A colour comes from DemoColors, not `Color.$selector`")
        }
    }

    /** `Color(…)`, `Dp(…)`, a role's `copy(fontSize = …)`, and in a screen Material's widgets and Compose's motion. */
    private fun checkCall(context: JavaContext, node: UCallExpression, file: File) {
        val method = node.resolve()
        val owner = method?.containingClass?.qualifiedName.orEmpty()
        // a constructor call is named <init>; its method is named for the class
        // Kotlin mangles a function with inline-class parameters as `Text-Nvy7gAk`
        val name = (method?.name ?: node.methodName)?.substringBefore('-')
        when {
            isColour(owner, name) -> {
                report(context, COLOUR, node, "A colour comes from DemoColors")
            }

            name == "Dp" && owner.startsWith(UNIT_PACKAGE) -> {
                report(context, NUMBER, node, "A size comes from the theme's scale, not `Dp(…)`")
            }

            !file.inDemoType && isRoleOverride(node, owner, name) -> {
                report(context, ROLE, node, "A type role is used whole: add a role to DemoType instead")
            }

            file.inApp && method != null && owner.startsWith(MATERIAL_PACKAGE) && method.isComposable() &&
                name !in TYPE_AND_ICONS -> {
                report(context, COMPONENT, node, "A screen uses the design system's components, not Material's `$name`")
            }

            file.inApp && isMotionOrShadow(owner, name) -> {
                report(context, COMPONENT, node, "Motion and elevation come from the design system, not `$name`")
            }
        }
    }

    // the Color(…) functions and Color's own constructor; both are called by the class's name
    private fun isColour(owner: String, name: String?): Boolean = name == "Color" && owner.startsWith(GRAPHICS_PACKAGE)

    private fun isRoleOverride(node: UCallExpression, owner: String, name: String?): Boolean =
        name == "copy" &&
            owner == TEXT_STYLE &&
            node.valueArguments.any { arg ->
                val argument =
                    arg.sourcePsi
                        ?.parent
                        ?.text
                        ?.trimStart()
                        .orEmpty()
                ROLE_PARTS.any { argument.startsWith(it) }
            }

    private fun isMotionOrShadow(owner: String, name: String?): Boolean =
        (owner.startsWith(ANIMATION_PACKAGE) && name in MOTION) || (name == "shadow" && owner.startsWith(DRAW_PACKAGE))

    private fun PsiMethod.isComposable(): Boolean = annotations.any { it.qualifiedName == COMPOSABLE }

    private fun report(context: JavaContext, issue: Issue, node: UElement, message: String) =
        context.report(issue, node, context.getLocation(node), message)

    companion object {
        private const val COMPOSABLE = "androidx.compose.runtime.Composable"
        private const val GRAPHICS_PACKAGE = "androidx.compose.ui.graphics"
        private const val UNIT_PACKAGE = "androidx.compose.ui.unit"
        private const val TEXT_STYLE = "androidx.compose.ui.text.TextStyle"
        private const val MATERIAL_PACKAGE = "androidx.compose.material3"
        private const val ANIMATION_PACKAGE = "androidx.compose.animation"
        private const val DRAW_PACKAGE = "androidx.compose.ui.draw"
        private const val PRIORITY = 8

        private val UNITS = setOf("dp", "sp")

        /** No colour at all: nothing drawn, or the caller's. */
        private val NOT_COLOURS = setOf("Transparent", "Unspecified")

        /** A screen sets text and icons itself, with a DemoType role and a DemoColors ink (D48). */
        private val TYPE_AND_ICONS = setOf("Text", "Icon")
        private val ROLE_PARTS = listOf("fontSize", "fontWeight", "letterSpacing", "lineHeight")
        private val MOTION =
            setOf(
                "tween",
                "spring",
                "keyframes",
                "AnimatedVisibility",
                "animateItem",
                "rememberInfiniteTransition",
                "animateFloatAsState",
                "animateDpAsState",
                "animateColorAsState",
                "animateIntAsState",
                "animateOffsetAsState",
                "animateSizeAsState",
                "animateValueAsState",
                "animateContentSize",
            )

        /** The app's code, and the design system's within it (by package, as the modules are laid out). */
        private const val DEMO = "com.suhel.llamabro.demo"
        private const val DESIGN_SYSTEM = "com.suhel.llamabro.demo.designsystem"

        /** The theme defines the tokens and the samples frame pictures. */
        private val EXEMPT_PACKAGES = listOf("theme", "samples")

        /** Previews frame pictures and the share card renders off screen: neither is a screen. */
        private val EXEMPT_FILES =
            listOf(Regex(".*Previews?\\.kt"), Regex("PreviewSupport\\.kt"), Regex("ShareCard\\.kt"))

        private val IMPLEMENTATION = Implementation(DesignTokensDetector::class.java, Scope.JAVA_FILE_SCOPE)

        private fun issue(id: String, brief: String, explanation: String) =
            Issue.create(id, brief, explanation, Category.CORRECTNESS, PRIORITY, Severity.ERROR, IMPLEMENTATION)

        val NUMBER =
            issue(
                "DemoRawSize",
                "A size outside the theme's scale",
                "Sizes come from DemoSpacing, DemoSize and DemoType, so they rank the same everywhere (D48, D113).",
            )
        val COLOUR =
            issue(
                "DemoRawColour",
                "A colour outside DemoColors",
                "Colours come from DemoColors, so both themes and the contrast tests cover them (D48, D113).",
            )
        val ROLE =
            issue(
                "DemoRoleOverride",
                "A type role changed in place",
                "A DemoType role is used whole; a new look is a new role (D48, D113).",
            )
        val COMPONENT =
            issue(
                "DemoRawComponent",
                "A Material widget or Compose motion in a screen",
                "Screens are built from the design system's components, which carry the theme's shapes, motion and " +
                    "accessibility (D48, D113).",
            )

        val ISSUES = listOf(NUMBER, COLOUR, ROLE, COMPONENT)
    }
}
