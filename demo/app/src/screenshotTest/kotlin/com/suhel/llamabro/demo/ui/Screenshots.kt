package com.suhel.llamabro.demo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.ui.chat.ChatContentPreview
import com.suhel.llamabro.demo.ui.chat.ChatPreview
import com.suhel.llamabro.demo.ui.chat.ChatScreenStates
import com.suhel.llamabro.demo.ui.chat.EmptyChatPreview
import com.suhel.llamabro.demo.ui.chat.LimitLinePreview
import com.suhel.llamabro.demo.ui.chat.LimitStates
import com.suhel.llamabro.demo.ui.chat.MenuStates
import com.suhel.llamabro.demo.ui.chat.MessageMenuPreview
import com.suhel.llamabro.demo.ui.chat.MessageRowPreview
import com.suhel.llamabro.demo.ui.chat.MessageRowStates
import com.suhel.llamabro.demo.ui.chat.ModelSwitcherPreview
import com.suhel.llamabro.demo.ui.chat.ShareCardPreview
import com.suhel.llamabro.demo.ui.chat.StreamingRowPreview
import com.suhel.llamabro.demo.ui.chat.StreamingStates
import com.suhel.llamabro.demo.ui.chat.ThinkMenuPreview
import com.suhel.llamabro.demo.ui.firstrun.FirstRunPreview
import com.suhel.llamabro.demo.ui.firstrun.FirstRunScreenPreview
import com.suhel.llamabro.demo.ui.firstrun.FirstRunStates
import com.suhel.llamabro.demo.ui.firstrun.PickSheetPreview
import com.suhel.llamabro.demo.ui.firstrun.PickSheetState
import com.suhel.llamabro.demo.ui.firstrun.PickSheetStates
import com.suhel.llamabro.demo.ui.firstrun.WhatsAModelPreview
import com.suhel.llamabro.demo.ui.models.DownloadItemPreview
import com.suhel.llamabro.demo.ui.models.DownloadStates
import com.suhel.llamabro.demo.ui.models.GetModelsPreview
import com.suhel.llamabro.demo.ui.models.GetModelsScreenPreview
import com.suhel.llamabro.demo.ui.models.GetModelsStates
import com.suhel.llamabro.demo.ui.models.LibraryPreview
import com.suhel.llamabro.demo.ui.models.LibraryScreenPreview
import com.suhel.llamabro.demo.ui.models.LibraryStates
import com.suhel.llamabro.demo.ui.models.ModelRowItemPreview
import com.suhel.llamabro.demo.ui.models.ModelSheetPreview
import com.suhel.llamabro.demo.ui.models.RowPreview
import com.suhel.llamabro.demo.ui.models.RowStates
import com.suhel.llamabro.demo.ui.models.SheetPreview
import com.suhel.llamabro.demo.ui.models.SheetStates
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.chat.RowPreview as MessageRowCase

/**
 * Every screen and part of T10 as the previews draw it: each state, light and dark. The previews live beside their
 * composables (Android Studio shows them there); these run them as screenshot tests, so a change that moves a pixel is
 * seen (`:demo:app:validateDebugScreenshotTest`, re-recorded with `updateDebugScreenshotTest`).
 */
class Screenshots {
    @PreviewTest @DemoScreenPreviews @Composable
    internal fun firstRun(
        @PreviewParameter(FirstRunStates::class) p: FirstRunPreview,
    ) = FirstRunScreenPreview(p)

    @PreviewTest @DemoPreviews @Composable
    fun pickSheet(
        @PreviewParameter(PickSheetStates::class) p: PickSheetState,
    ) = PickSheetPreview(p)

    @PreviewTest @DemoPreviews @Composable
    fun whatsAModel() = WhatsAModelPreview()

    @PreviewTest @DemoScreenPreviews @Composable
    internal fun getModels(
        @PreviewParameter(GetModelsStates::class) p: GetModelsPreview,
    ) = GetModelsScreenPreview(p)

    @PreviewTest @DemoPreviews @Composable
    internal fun modelRow(
        @PreviewParameter(RowStates::class) p: RowPreview,
    ) = ModelRowItemPreview(p)

    @PreviewTest @DemoPreviews @Composable
    internal fun modelSheet(
        @PreviewParameter(SheetStates::class) p: SheetPreview,
    ) = ModelSheetPreview(p)

    @PreviewTest @DemoScreenPreviews @Composable
    internal fun library(
        @PreviewParameter(LibraryStates::class) p: LibraryPreview,
    ) = LibraryScreenPreview(p)

    @PreviewTest @DemoPreviews @Composable
    fun download(
        @PreviewParameter(DownloadStates::class) p: DownloadStatus,
    ) = DownloadItemPreview(p)

    @PreviewTest @DemoScreenPreviews @Composable
    internal fun chat(
        @PreviewParameter(ChatScreenStates::class) p: ChatPreview,
    ) = ChatContentPreview(p)

    @PreviewTest @DemoPreviews @Composable
    internal fun messageRow(
        @PreviewParameter(MessageRowStates::class) p: MessageRowCase,
    ) = MessageRowPreview(p)

    @PreviewTest @DemoPreviews @Composable
    internal fun streamingRow(
        @PreviewParameter(StreamingStates::class) p: com.suhel.llamabro.demo.ui.chat.StreamingItem,
    ) = StreamingRowPreview(p)

    @PreviewTest @DemoPreviews @Composable
    fun emptyChat() = EmptyChatPreview()

    @PreviewTest @DemoPreviews @Composable
    internal fun limitLine(
        @PreviewParameter(LimitStates::class) p: com.suhel.llamabro.demo.chat.Limit,
    ) = LimitLinePreview(p)

    @PreviewTest @DemoPreviews @Composable
    fun thinkMenu() = ThinkMenuPreview()

    @PreviewTest @DemoPreviews @Composable
    fun modelSwitcher() = ModelSwitcherPreview()

    @PreviewTest @DemoPreviews @Composable
    fun messageMenu(
        @PreviewParameter(MenuStates::class) isReply: Boolean,
    ) = MessageMenuPreview(isReply)

    @PreviewTest @DemoPreviews @Composable
    fun shareCard() = ShareCardPreview()
}
