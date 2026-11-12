package com.suhel.llamabro.demo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.settings.AboutPreview
import com.suhel.llamabro.demo.ui.settings.AppearanceContentPreview
import com.suhel.llamabro.demo.ui.settings.AppearancePreview
import com.suhel.llamabro.demo.ui.settings.AppearanceStates
import com.suhel.llamabro.demo.ui.settings.DefaultModelContentPreview
import com.suhel.llamabro.demo.ui.settings.DefaultModelStates
import com.suhel.llamabro.demo.ui.settings.InstructionsContentPreview
import com.suhel.llamabro.demo.ui.settings.InstructionsStates
import com.suhel.llamabro.demo.ui.settings.SettingsHomePreview
import com.suhel.llamabro.demo.ui.settings.TokenContentPreview
import com.suhel.llamabro.demo.ui.settings.TokenPreview
import com.suhel.llamabro.demo.ui.settings.TokenStates
import com.suhel.llamabro.demo.ui.shell.ChatActionsPreview
import com.suhel.llamabro.demo.ui.shell.ChatListContentPreview
import com.suhel.llamabro.demo.ui.shell.ChatListPreview
import com.suhel.llamabro.demo.ui.shell.ChatListStates
import com.suhel.llamabro.demo.ui.shell.DrawerContentPreview
import com.suhel.llamabro.demo.ui.shell.DrawerPreview
import com.suhel.llamabro.demo.ui.shell.DrawerStates
import com.suhel.llamabro.demo.ui.shell.RenameContentPreview
import com.suhel.llamabro.demo.ui.shell.RenameStates

/** The shell and the settings pages as the previews draw them: each state, light and dark. */
class ShellScreenshots {
    @PreviewTest @DemoScreenPreviews @Composable
    internal fun drawer(
        @PreviewParameter(DrawerStates::class) p: DrawerPreview,
    ) = DrawerContentPreview(p)

    @PreviewTest @DemoScreenPreviews @Composable
    internal fun chatList(
        @PreviewParameter(ChatListStates::class) p: ChatListPreview,
    ) = ChatListContentPreview(p)

    @PreviewTest @DemoPreviews @Composable
    fun chatActions() = ChatActionsPreview()

    @PreviewTest @DemoPreviews @Composable
    fun rename(
        @PreviewParameter(RenameStates::class) title: String,
    ) = RenameContentPreview(title)

    @PreviewTest @DemoScreenPreviews @Composable
    fun settingsHome() = SettingsHomePreview()

    @PreviewTest @DemoScreenPreviews @Composable
    internal fun appearance(
        @PreviewParameter(AppearanceStates::class) p: AppearancePreview,
    ) = AppearanceContentPreview(p)

    @PreviewTest @DemoScreenPreviews @Composable
    fun instructions(
        @PreviewParameter(InstructionsStates::class) p: Pair<String, String>,
    ) = InstructionsContentPreview(p)

    @PreviewTest @DemoScreenPreviews @Composable
    fun defaultModel(
        @PreviewParameter(DefaultModelStates::class) pinned: String?,
    ) = DefaultModelContentPreview(pinned)

    @PreviewTest @DemoScreenPreviews @Composable
    internal fun token(
        @PreviewParameter(TokenStates::class) p: TokenPreview,
    ) = TokenContentPreview(p)

    @PreviewTest @DemoScreenPreviews @Composable
    fun about() = AboutPreview()
}
