package com.uyatame.ucrt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 追加アイコン(アプリ独自のシンプルなパス) */
object AppIcons {
    private fun icon(name: String, path: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = SolidColor(Color.Black),
            )
            .build()

    val Pause = icon("Pause", "M6,5h4v14h-4zM14,5h4v14h-4z")
    val Cast = icon(
        "Cast",
        "M21,3H3C1.9,3 1,3.9 1,5v3h2V5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2V5C23,3.9 22.1,3 21,3zM1,18v3h3C4,19.34 2.66,18 1,18z" +
            "M1,14v2c2.76,0 5,2.24 5,5h2C8,17.13 4.87,14 1,14zM1,10v2c4.97,0 9,4.03 9,9h2C12,14.92 7.07,10 1,10z",
    )
    val CastConnected = icon(
        "CastConnected",
        "M1,18v3h3C4,19.34 2.66,18 1,18zM1,14v2c2.76,0 5,2.24 5,5h2C8,17.13 4.87,14 1,14zM19,7H5v1.63c3.96,1.28 7.09,4.41 8.37,8.37H19V7z" +
            "M1,10v2c4.97,0 9,4.03 9,9h2C12,14.92 7.07,10 1,10zM21,3H3C1.9,3 1,3.9 1,5v3h2V5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2V5C23,3.9 22.1,3 21,3z",
    )
    val SkipNext = icon("SkipNext", "M6,6l8.5,6l-8.5,6zM16,6h2v12h-2z")
    val SkipPrevious = icon("SkipPrevious", "M6,6h2v12h-2zM18,6v12l-8.5,-6z")
    val Eject = icon("Eject", "M5,17h14v2h-14zM12,5l6.67,10h-13.34z")
    val Album = icon(
        "Album",
        "M12,2a10,10 0 1,0 0.001,0zM12,7.5a4.5,4.5 0 1,1 -0.001,0zM12,11a1,1 0 1,0 0.001,0z",
        evenOdd = true,
    )
    val MusicNote = icon(
        "MusicNote",
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55c-2.21,0 -4,1.79 -4,4s1.79,4 4,4s4,-1.79 4,-4V7h4V3z",
    )
    /** ストリーミングのタブ */
    val Cloud = icon(
        "Cloud",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4C9.11,4 6.6,5.64 5.35,8.04C2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5c0,-2.64 -2.05,-4.78 -4.65,-4.96z",
    )
    val Download = icon("Download", "M19,9h-4V3H9v6H5l7,7l7,-7zM5,18v2h14v-2z")
    val Upload = icon("Upload", "M5,20h14v-2H5V20zM5,10h4v6h6v-6h4l-7,-7L5,10z")
    val Library = icon(
        "Library",
        "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4zM20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 1.5,0.51V5h4z",
        evenOdd = true,
    )
    val Shuffle = icon(
        "Shuffle",
        "M10.59,9.17L5.41,4L4,5.41l5.17,5.17zM14.5,4l2.04,2.04L4,18.59L5.41,20L17.96,7.46L20,9.5V4zM14.83,13.41l-1.41,1.41l3.13,3.13L14.5,20H20v-5.5l-2.04,2.04z",
    )
    val Repeat = icon("Repeat", "M7,7h10v3l4,-4l-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4l4,4v-3h12v-6h-2v4z")
    val RepeatOne = icon("RepeatOne", "M7,7h10v3l4,-4l-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4l4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z")
    val Grid = icon("Grid", "M3,3h8v8H3zM13,3h8v8h-8zM3,13h8v8H3zM13,13h8v8h-8z")
    val ListView = icon("ListView", "M3,5h4v4H3zM9,6h12v2H9zM3,10h4v4H3zM9,11h12v2H9zM3,15h4v4H3zM9,16h12v2H9z")
    val Tune = icon("Tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z")
    val Smartphone = icon(
        "Smartphone",
        "M17,1.01L7,1C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3C19,1.9 18.1,1.01 17,1.01zM17,19H7V5h10V19z",
    )
    val Headphones = icon(
        "Headphones",
        "M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7C21,5.03 16.97,1 12,1z",
    )
    val Usb = icon(
        "Usb",
        "M15,7v4h1v2h-3V5h2l-3,-4l-3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93c0,-1.21 -0.99,-2.2 -2.2,-2.2S4.8,7.79 4.8,9c0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95c0,1.22 0.99,2.2 2.2,2.2s2.2,-0.98 2.2,-2.2c0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2h1V7H15z",
    )
    val Bluetooth = icon(
        "Bluetooth",
        "M17.71,7.71L12,2h-1v7.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L11,14.41V22h1l5.71,-5.71L13.41,12L17.71,7.71zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76L14.88,16.29z",
    )
    val Speaker = icon(
        "Speaker",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z",
    )
    val Queue = icon(
        "Queue",
        "M15,6H3v2h12V6zM15,10H3v2h12v-2zM3,16h8v-2H3V16zM17,6v8.18C16.69,14.07 16.35,14 16,14c-1.66,0 -3,1.34 -3,3s1.34,3 3,3s3,-1.34 3,-3V8h3V6H17z",
    )
    val PlaylistAdd = icon("PlaylistAdd", "M14,10H3v2h11V10zM14,6H3v2h11V6zM18,14v-4h-2v4h-4v2h4v4h2v-4h4v-2H18zM3,16h7v-2H3V16z")
    val PlayNext = icon("PlayNext", "M3,10h11v2H3V10zM3,6h11v2H3V6zM3,14h7v2H3V14zM16,13v8l6,-4L16,13z")
    val Lyrics = icon("Lyrics", "M14,17H4v2h10v-2zM20,9H4v2h16V9zM4,15h16v-2H4v2zM4,5v2h16V5H4z")
    val Timer = icon(
        "Timer",
        "M15,1H9v2h6V1zM11,14h2V8h-2V14zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9s9,-4.03 9,-9C21,10.88 20.26,8.93 19.03,7.39zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7s7,3.13 7,7S15.87,20 12,20z",
    )
    val Folder = icon("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    val Genre = icon(
        "Genre",
        "M21.41,11.58l-9,-9C12.05,2.22 11.55,2 11,2H4c-1.1,0 -2,0.9 -2,2v7c0,0.55 0.22,1.05 0.59,1.42l9,9c0.36,0.36 0.86,0.58 1.41,0.58c0.55,0 1.05,-0.22 1.41,-0.59l7,-7c0.37,-0.36 0.59,-0.86 0.59,-1.41C22,12.44 21.77,11.93 21.41,11.58zM5.5,7C4.67,7 4,6.33 4,5.5S4.67,4 5.5,4S7,4.67 7,5.5S6.33,7 5.5,7z",
        evenOdd = true,
    )
    val History = icon(
        "History",
        "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89l0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7s-3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9S17.97,3 13,3zM12,8v5l4.28,2.54l0.72,-1.21l-3.5,-2.08V8H12z",
    )
    val NewAdded = icon(
        "NewAdded",
        "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM19,11h-4v4h-2v-4H9V9h4V5h2v4h4V11z",
        evenOdd = true,
    )
    val Trending = icon("Trending", "M16,6l2.29,2.29l-4.88,4.88l-4,-4L2,16.59L3.41,18l6,-6l4,4l6.3,-6.29L22,12V6z")
    val HiRes = icon(
        "HiRes",
        "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM11,15H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zM18,14c0,0.55 -0.45,1 -1,1h-0.75v1.5h-1.5V15H14c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v4zM14.5,13.5h2v-3h-2v3z",
        evenOdd = true,
    )
    val Volume = icon(
        "Volume",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
    )
    val DragHandle = icon("DragHandle", "M20,9H4v2h16V9zM4,15h16v-2H4V15z")
    val Bars = icon("Bars", "M10,20h4V4h-4v16zM4,20h4v-8H4v8zM16,9v11h4V9h-4z")
}
