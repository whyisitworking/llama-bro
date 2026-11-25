package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize

/**
 * The few icons the app shows, as 24 dp vectors (Material Icons' paths, Apache 2.0), so the module needs no icon
 * library. Tinted by the component that draws them.
 */
object DemoIcons {
    val Menu = icon("Menu", "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z")
    val Back = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", autoMirror = true)

    /** A box with an arrow leaving it (Material's open_in_new): opens a page outside the app. */
    val OpenExternal =
        icon(
            "OpenExternal",
            "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7z" +
                "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
        )

    /** A speech bubble with a plus (Material's add_comment): a new conversation, as messaging apps show it. */
    val NewChat =
        icon(
            "NewChat",
            "M22,4c0,-1.1 -0.9,-2 -2,-2H4C2.9,2 2,2.9 2,4v12c0,1.1 0.9,2 2,2h14l4,4V4zM20,17.17L18.83,16H4V4h16V17.17z" +
                "M13,5h-2v4H7v2h4v4h2v-4h4V9h-4V5z",
        )

    /** A reply's details (Material's info, outlined). */
    val Info =
        icon(
            "Info",
            "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z",
        )
    val Key =
        icon(
            "Key",
            "M12.65,10C11.83,7.67 9.61,6 7,6c-3.31,0 -6,2.69 -6,6s2.69,6 6,6c2.61,0 4.83,-1.67 5.65,-4H17v4h4v-4h2v-4H12.65zM7,14c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z",
        )
    val Plus = icon("Plus", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Send = icon("Send", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")

    /** A lightbulb: Think (the model reasons before it answers). */
    val Think =
        icon(
            "Think",
            "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1H9v1zM12,2C8.14,2 5,5.14 5,9c0,2.38 1.19,4.47 3,5.74V17" +
                "c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26c1.81,-1.27 3,-3.36 3,-5.74C19,5.14 15.86,2 12,2z",
        )
    val Stop = icon("Stop", "M7,7h10v10H7z")
    val Search =
        icon(
            "Search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16" +
                "c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5" +
                "S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        )

    /** Sliders (Material's `tune`): a search's filters. */
    val Filter =
        icon(
            "Filter",
            "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10z" +
                "M15,9h2V7h4V5h-4V3h-2v6z",
        )
    val Close =
        icon(
            "Close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        )
    val Check = icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val ChevronDown = icon("ChevronDown", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z")
    val ChevronRight = icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z", autoMirror = true)
    val Copy =
        icon(
            "Copy",
            "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7" +
                "c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
        )
    val Share =
        icon(
            "Share",
            "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7" +
                "l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47" +
                " 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16" +
                "c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92 -1.31,-2.92 -2.92,-2.92z",
        )
    val Regenerate =
        icon(
            "Regenerate",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08" +
                "c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
        )

    /** A pencil: edit a message, rename a chat. */
    val Edit =
        icon(
            "Edit",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34" +
                "c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
        )

    /** Voice mode (Material's `graphic_eq`): talk with a model that speaks (D100). */
    val VoiceMode = icon("VoiceMode", "M7,18h2V6H7v12zM11,22h2V2h-2v20zM3,14h2v-4H3v4zM15,18h2V6h-2v12zM19,10v4h2v-4h-2z")

    /** A speaker (Material's `volume_up`): play a spoken reply again (D100). */
    val Speaker =
        icon(
            "Speaker",
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
                "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77" +
                "s-2.99,-7.86 -7,-8.77z",
        )
    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Play = icon("Play", "M8,5v14l11,-7z")

    /** An empty hourglass: a download waiting its turn. */
    val Hourglass =
        icon(
            "Hourglass",
            "M6,2v6h0.01L6,8.01 10,12l-4,4 0.01,0.01H6V22h12v-5.99h-0.01L18,16l-4,-4 4,-3.99 -0.01,-0.01H18V2H6z" +
                "M16,16.5V20H8v-3.5l4,-4 4,4zM12,11.5l-4,-4V4h8v3.5l-4,4z",
        )
    val Mic =
        icon(
            "Mic",
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28" +
                "c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
        )
    val Chats =
        icon(
            "Chats",
            "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM20,16H6l-2,2V4h16v12z",
        )
    val Models =
        icon(
            "Models",
            "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z",
        )
    val Settings =
        icon(
            "Settings",
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41" +
                " 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94" +
                "L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35" +
                "C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48" +
                "l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61" +
                "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54" +
                "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94" +
                "l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94z" +
                "M12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
        )
    val Image =
        icon(
            "Image",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
                "M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
        )

    // What a model can do, as quiet hints on its row and card (D123): Material Symbols Outlined at weight 300 (optical
    // size 20, unfilled), a lighter line than the fit symbols beside them, so they read as hints and not as labels.

    /** Material Symbols `lightbulb`, light: the model thinks before it answers. */
    val CapabilityThinks =
        symbol(
            "CapabilityThinks",
            "M479.8 -112.9Q452.7 -112.9 433.2 -131.6Q413.8 -150.2 411.8 -177.2H548.2Q546.2 -149.9 526.6 -131.4Q50" +
                "6.9 -112.9 479.8 -112.9ZM346 -240.6V-292.6H614V-240.6ZM327.2 -356Q271.3 -392.1 241.7 -450.6Q212 -509" +
                ".2 212 -576Q212 -687.9 290 -766Q368.1 -844 480 -844Q591.9 -844 670 -766Q748 -687.9 748 -576Q748 -509" +
                ".2 718.3 -450.6Q688.7 -392.1 632.8 -356ZM344 -408H616Q654 -439 675 -483Q696 -527 696 -576Q696 -666.3" +
                " 633.2 -729.2Q570.5 -792 480.2 -792Q390 -792 327 -729.2Q264 -666.3 264 -576Q264 -527 285 -483Q306 -4" +
                "39 344 -408Z",
        )

    /** Material Symbols `graphic_eq`, light: the model talks out loud. */
    val CapabilityTalks =
        symbol(
            "CapabilityTalks",
            "M304 -250V-710H356V-250ZM454 -106V-854H506V-106ZM154 -394V-566H206V-394ZM604 -250V-710H656V-250ZM754" +
                " -394V-566H806V-394Z",
        )

    /** Material Symbols `image`, light: the model sees images. */
    val CapabilityImages =
        symbol(
            "CapabilityImages",
            "M274 -293H690.1L560.5 -465.4L450 -321.6L371.4 -422.1ZM140 -140V-820H820V-140ZM204 -204H756V-756H204Z",
        )

    /** Material Symbols `code`, light: a model made for code. */
    val CapabilityCode =
        symbol(
            "CapabilityCode",
            "M336 -253.8 109.8 -480 336 -706.2 372.8 -669.4 183.8 -480.4 373.2 -291ZM624 -253.8 587.2 -290.6 776." +
                "2 -479.6 586.8 -669 624 -706.2 850.2 -480Z",
        )

    /** Material Symbols `select_all`, light: a model that embeds or reranks and does not chat. */
    val CapabilityEmbeddings =
        symbol(
            "CapabilityEmbeddings",
            "M140 -756V-820H204V-756ZM140 -438V-522H204V-438ZM279 -140V-204H363V-140ZM140 -597V-681H204V-597ZM438" +
                " -756V-820H522V-756ZM756 -756V-820H820V-756ZM140 -140V-204H204V-140ZM140 -279V-363H204V-279ZM279 -75" +
                "6V-820H363V-756ZM438 -140V-204H522V-140ZM756 -438V-522H820V-438ZM756 -140V-204H820V-140ZM756 -600.8V" +
                "-684.8H820V-600.8ZM756 -275.2V-359.2H820V-275.2ZM597 -140V-204H681V-140ZM597 -756V-820H681V-756ZM299" +
                " -299V-661H661V-299ZM363 -363H597V-597H363Z",
        )

    /** Material Symbols `sprint`: a runner at full stride, "Runs great". */
    val FitGreat =
        symbol(
            "FitGreat",
            "M482-425 245-188q-12 12-28.5 12T188-188q-12-12-12-28.5t12-28.5l356-355H440v40q0 17-11.5 28.5T400-520" +
                "q-17 0-28.5-11.5T360-560v-80q0-17 11.5-28.5T400-680h194q16 0 30.5 6t25.5 17l120 119q20 20 46.5 33.5T" +
                "874-485q17 3 29.5 15.5T916-440q0 17-14 28t-31 9q-45-6-83-24t-70-49l-40-42-88 88 53 53q14 14 11.5 33." +
                "5T635-314L453-209q-14 8-30.5 4T398-223q-8-14-3.5-30.5T413-278l137-79-68-68Zm-322-15q-17 0-28.5-11.5T" +
                "120-480q0-17 11.5-28.5T160-520h120q17 0 28.5 11.5T320-480q0 17-11.5 28.5T280-440H160ZM80-560q-17 0-2" +
                "8.5-11.5T40-600q0-17 11.5-28.5T80-640h120q17 0 28.5 11.5T240-600q0 17-11.5 28.5T200-560H80Zm699-80q-" +
                "33 0-57-23.5T698-720q0-33 24-56.5t57-23.5q33 0 57 23.5t24 56.5q0 33-24 56.5T779-640Zm-619-40q-17 0-2" +
                "8.5-11.5T120-720q0-17 11.5-28.5T160-760h120q17 0 28.5 11.5T320-720q0 17-11.5 28.5T280-680H160Z",
        )

    /** Material Symbols `directions_run`: a runner, "Runs well". */
    val FitWell =
        symbol(
            "FitWell",
            "M520-80v-200l-84-80-31 138q-4 16-17.5 24.5T358-192l-198-40q-17-3-26-17t-6-31q3-17 17-26.5t31-5.5l152" +
                " 32 64-324-72 28v96q0 17-11.5 28.5T280-440q-17 0-28.5-11.5T240-480v-122q0-12 6.5-21.5T264-638l134-58" +
                "q35-15 51.5-19.5T480-720q21 0 39 11t29 29l40 64q21 34 54.5 59t77.5 33q17 3 28.5 15t11.5 29q0 17-11.5" +
                " 28t-27.5 9q-54-8-101-33.5T540-540l-24 120 72 68q6 6 9 13.5t3 15.5v243q0 17-11.5 28.5T560-40q-17 0-2" +
                "8.5-11.5T520-80Zm20-660q-33 0-56.5-23.5T460-820q0-33 23.5-56.5T540-900q33 0 56.5 23.5T620-820q0 33-2" +
                "3.5 56.5T540-740Z",
        )

    /** Material Symbols `elderly`: a figure with a cane, "Slow here". */
    val FitSlow =
        symbol(
            "FitSlow",
            "M540-740q-33 0-56.5-23.5T460-820q0-33 23.5-56.5T540-900q33 0 56.5 23.5T620-820q0 33-23.5 56.5T540-74" +
                "0Zm160 350q-8 0-14-6t-6-14v-19q-54-23-84-51.5T543-557q-11 28-17.5 68.5T521-412l72 102q4 5 5.5 11t1.5" +
                " 12v207q0 17-11.5 28.5T560-40q-17 0-28.5-11.5T520-80v-160l-71-102-8 130q0 4-8 22L344-72q-10 14-26 16" +
                "t-30-8q-14-10-16-26t8-30l80-107v-213q0-31 5-67.5t15-67.5l-60 33v102q0 17-11.5 28.5T280-400q-17 0-28." +
                "5-11.5T240-440v-125q0-11 5-20.5t15-14.5l156-88q25-14 43.5-21.5T494-717q25 0 45.5 21.5T587-628q32 54 " +
                "58 81t56 41q11-8 19-11t19-3q25 0 43 18t18 42v400q0 8-6 14t-14 6q-8 0-14-6t-6-14v-400q0-8-6-14t-14-6q" +
                "-8 0-14 6t-6 14v50q0 8-6 14t-14 6Z",
        )

    /** Material Symbols `block`: the no-entry sign, "Can't chat". */
    val NoEntry =
        symbol(
            "NoEntry",
            "M480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q8" +
                "3 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q54 0 " +
                "104-17.5t92-50.5L228-676q-33 42-50.5 92T160-480q0 134 93 227t227 93Zm252-124q33-42 50.5-92T800-480q0" +
                "-134-93-227t-227-93q-54 0-104 17.5T284-732l448 448Z",
        )

    /** Material Symbols `code`: `</>`, a model made for code. */
    val Code =
        symbol(
            "Code",
            "m193-479 155 155q11 11 11 28t-11 28q-11 11-28 11t-28-11L108-452q-6-6-8.5-13T97-480q0-8 2.5-15t8.5-13" +
                "l184-184q12-12 28.5-12t28.5 12q12 12 12 28.5T349-635L193-479Zm574-2L612-636q-11-11-11-28t11-28q11-11" +
                " 28-11t28 11l184 184q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L668-268q-12 12-28 11.5T612-269q-12-12-12-2" +
                "8.5t12-28.5l155-155Z",
        )

    /** Material Symbols `select_all`: a model that embeds or reranks and does not chat. */
    val Embeddings =
        symbol(
            "Embeddings",
            "M160-760q-17 0-28.5-11.5T120-800q0-17 11.5-28.5T160-840q17 0 28.5 11.5T200-800q0 17-11.5 28.5T160-76" +
                "0Zm160 0q-17 0-28.5-11.5T280-800q0-17 11.5-28.5T320-840q17 0 28.5 11.5T360-800q0 17-11.5 28.5T320-76" +
                "0Zm160 0q-17 0-28.5-11.5T440-800q0-17 11.5-28.5T480-840q17 0 28.5 11.5T520-800q0 17-11.5 28.5T480-76" +
                "0Zm160 0q-17 0-28.5-11.5T600-800q0-17 11.5-28.5T640-840q17 0 28.5 11.5T680-800q0 17-11.5 28.5T640-76" +
                "0Zm160 0q-17 0-28.5-11.5T760-800q0-17 11.5-28.5T800-840q17 0 28.5 11.5T840-800q0 17-11.5 28.5T800-76" +
                "0ZM160-600q-17 0-28.5-11.5T120-640q0-17 11.5-28.5T160-680q17 0 28.5 11.5T200-640q0 17-11.5 28.5T160-" +
                "600Zm640 0q-17 0-28.5-11.5T760-640q0-17 11.5-28.5T800-680q17 0 28.5 11.5T840-640q0 17-11.5 28.5T800-" +
                "600ZM160-440q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520q17 0 28.5 11.5T200-480q0 17-11.5 28.5T16" +
                "0-440Zm640 0q-17 0-28.5-11.5T760-480q0-17 11.5-28.5T800-520q17 0 28.5 11.5T840-480q0 17-11.5 28.5T80" +
                "0-440ZM160-280q-17 0-28.5-11.5T120-320q0-17 11.5-28.5T160-360q17 0 28.5 11.5T200-320q0 17-11.5 28.5T" +
                "160-280Zm640 0q-17 0-28.5-11.5T760-320q0-17 11.5-28.5T800-360q17 0 28.5 11.5T840-320q0 17-11.5 28.5T" +
                "800-280ZM160-120q-17 0-28.5-11.5T120-160q0-17 11.5-28.5T160-200q17 0 28.5 11.5T200-160q0 17-11.5 28." +
                "5T160-120Zm160 0q-17 0-28.5-11.5T280-160q0-17 11.5-28.5T320-200q17 0 28.5 11.5T360-160q0 17-11.5 28." +
                "5T320-120Zm160 0q-17 0-28.5-11.5T440-160q0-17 11.5-28.5T480-200q17 0 28.5 11.5T520-160q0 17-11.5 28." +
                "5T480-120Zm160 0q-17 0-28.5-11.5T600-160q0-17 11.5-28.5T640-200q17 0 28.5 11.5T680-160q0 17-11.5 28." +
                "5T640-120Zm160 0q-17 0-28.5-11.5T760-160q0-17 11.5-28.5T800-200q17 0 28.5 11.5T840-160q0 17-11.5 28." +
                "5T800-120ZM360-280q-33 0-56.5-23.5T280-360v-240q0-33 23.5-56.5T360-680h240q33 0 56.5 23.5T680-600v24" +
                "0q0 33-23.5 56.5T600-280H360Zm0-80h240v-240H360v240Z",
        )

    /**
     * "Too big": a figure kneeling on one knee, a hand on the ground, head hanging low, tired and not hurt
     * (docs/design/models-screen.md, "The fit symbol"). Drawn for the app on the 24 dp grid as round 2.7-wide strokes
     * and a head disc, so it carries the weight of the Material Symbols beside it ([FitWell] at the same 20 dp).
     */
    val FitTooBig: ImageVector =
        ImageVector
            .Builder(
                name = "FitTooBig",
                defaultWidth = DemoSize.icon,
                defaultHeight = DemoSize.icon,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(pathData = addPathNodes("M5.8,9.6a2.5,2.5 0 1,0 5,0a2.5,2.5 0 1,0 -5,0z"), fill = SolidColor(MASK))
            .addPath(
                pathData =
                    addPathNodes(
                        "M11.4,9.4L15.8,14.2L14.2,20.2L20.2,20.6M15.8,14.2L10.6,15.4L10.4,20.6M12,10.6L7.2,14.2L6.6,20.2",
                    ),
                stroke = SolidColor(MASK),
                strokeLineWidth = KNEEL_STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ).build()

    /** [autoMirror]: the icon points a way that reading direction reverses (back, onwards), so it flips in RTL. */
    private fun icon(name: String, path: String, autoMirror: Boolean = false): ImageVector =
        ImageVector
            .Builder(
                name = name,
                autoMirror = autoMirror,
                defaultWidth = DemoSize.icon,
                defaultHeight = DemoSize.icon,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(pathData = addPathNodes(path), fill = SolidColor(MASK))
            .build()

    /**
     * A Material Symbols path (Rounded filled, or Outlined light for the capabilities; Apache 2.0): their SVGs draw on
     * a 960-unit grid whose y runs from -960 to 0, so the group moves it down and the vector scales 960 to 24 dp.
     */
    private fun symbol(name: String, path: String): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = DemoSize.icon,
                defaultHeight = DemoSize.icon,
                viewportWidth = SYMBOL_GRID,
                viewportHeight = SYMBOL_GRID,
            ).addGroup(translationY = SYMBOL_GRID)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(MASK))
            .clearGroup()
            .build()
}

private const val SYMBOL_GRID = 960f
private const val KNEEL_STROKE = 2.7f

// a path's fill is only its shape: Icon tints it, so the colour here is never seen
@Suppress("DemoRawColour")
private val MASK = Color.Black
