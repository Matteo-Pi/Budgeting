package io.github.matteopi.budgeting.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons for the sections (docs/PLAN.md §11.8, §13.8) that the core icon set (`material-icons-core`) does not
 * have, drawn from the Material icon paths (Apache 2.0) so the large extended icon library is not needed.
 */
object AppIcons {
    /** Fork and knife: the switch to Recipes. */
    val Restaurant: ImageVector by lazy {
        icon(
            "Restaurant",
            "M11,9H9V2H7v7H5V2H3v7c0,2.12 1.66,3.84 3.75,3.97V22h2.5v-9.03C11.34,12.84 13,11.12 13,9V2h-2V9z" +
                "M16,6v8h2.5v8H21V2C18.24,2 16,4.24 16,6z",
        )
    }

    /** A wallet: the switch to Budgeting. */
    val Wallet: ImageVector by lazy {
        icon(
            "Wallet",
            "M21,18v1c0,1.1 -0.9,2 -2,2H5c-1.11,0 -2,-0.9 -2,-2V5c0,-1.1 0.89,-2 2,-2h14c1.1,0 2,0.9 2,2v1h-9" +
                "c-1.11,0 -2,0.9 -2,2v8c0,1.1 0.89,2 2,2h9zM12,16h10V8H12v8zM16,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5" +
                "s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z",
        )
    }

    /** An open book: the Recipes tab. */
    val MenuBook: ImageVector by lazy {
        icon(
            "MenuBook",
            "M21,5c-1.11,-0.35 -2.33,-0.5 -3.5,-0.5c-1.95,0 -4.05,0.4 -5.5,1.5c-1.45,-1.1 -3.55,-1.5 -5.5,-1.5" +
                "S2.45,4.9 1,6v14.65c0,0.25 0.25,0.5 0.5,0.5c0.1,0 0.15,-0.05 0.25,-0.05C3.1,20.45 5.05,20 6.5,20" +
                "c1.95,0 4.05,0.4 5.5,1.5c1.35,-0.85 3.8,-1.5 5.5,-1.5c1.65,0 3.35,0.3 4.75,1.05c0.1,0.05 0.15,0.05 0.25,0.05" +
                "c0.25,0 0.5,-0.25 0.5,-0.5V6C22.4,5.55 21.75,5.25 21,5zM21,18.5c-1.1,-0.35 -2.3,-0.5 -3.5,-0.5" +
                "c-1.7,0 -4.15,0.65 -5.5,1.5V8c1.35,-0.85 3.8,-1.5 5.5,-1.5c1.2,0 2.4,0.15 3.5,0.5V18.5z" +
                "M17.5,10.5c0.88,0 1.73,0.09 2.5,0.26V9.24C19.21,9.09 18.36,9 17.5,9c-1.7,0 -3.24,0.29 -4.5,0.83v1.66" +
                "C14.13,10.85 15.7,10.5 17.5,10.5z" +
                "M13,12.49v1.66c1.13,-0.64 2.7,-0.99 4.5,-0.99c0.88,0 1.73,0.09 2.5,0.26V11.9c-0.79,-0.15 -1.64,-0.24 -2.5,-0.24" +
                "C15.8,11.66 14.26,11.96 13,12.49z" +
                "M17.5,14.33c-1.7,0 -3.24,0.29 -4.5,0.83v1.66c1.13,-0.64 2.7,-0.99 4.5,-0.99c0.88,0 1.73,0.09 2.5,0.26v-1.52" +
                "C19.21,14.41 18.36,14.33 17.5,14.33z",
        )
    }

    /** A table: the Compare tab. */
    val TableChart: ImageVector by lazy {
        icon(
            "TableChart",
            "M10,10.02h5V21h-5zM17,21h3c1.1,0 2,-0.9 2,-2v-9h-5v11zM20,3H5c-1.1,0 -2,0.9 -2,2v3h19V5" +
                "c0,-1.1 -0.9,-2 -2,-2zM3,19c0,1.1 0.9,2 2,2h3V10H3v9z",
        )
    }

    /** A fridge: the Kitchen tab (docs/PLAN.md §14.5). */
    val Kitchen: ImageVector by lazy {
        icon(
            "Kitchen",
            "M18,2.01L6,2c-1.1,0 -2,0.89 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.11 -0.9,-1.99 -2,-1.99z" +
                "M18,20H6v-9.02h12V20zM18,9H6V4h12v5zM8,5h2v3H8zM8,12h2v5H8z",
        )
    }

    /** A stopwatch: the recipe timers (docs/PLAN.md §11.11). */
    val Timer: ImageVector by lazy {
        icon(
            "Timer",
            "M15,1H9v2h6V1zM11,14h2V8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42" +
                "C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61z" +
                "M12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
        )
    }

    /** Two pages: *Copy recipe*. */
    val ContentCopy: ImageVector by lazy {
        icon(
            "ContentCopy",
            "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7" +
                "c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
        )
    }

    // ---- The Planner (docs/PLAN.md §13.8) ----------------------------------------------------------

    /** A calendar with a tick: the Planner section. */
    val EventAvailable: ImageVector by lazy {
        icon(
            "EventAvailable",
            "M16.53,11.06L15.47,10l-4.88,4.88 -2.12,-2.12 -1.06,1.06L10.59,17l5.94,-5.94z" +
                "M19,3h-1L18,1h-2v2L8,3L8,1L6,1v2L5,3c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14" +
                "c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM19,19L5,19L5,8h14v11z",
        )
    }

    /** A calendar page with today marked: the Today tab. */
    val Today: ImageVector by lazy {
        icon(
            "Today",
            "M19,3h-1V1h-2v2H8V1H6v2H5c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5" +
                "c0,-1.1 -0.9,-2 -2,-2zM19,19H5V8h14v11zM7,10h5v5H7z",
        )
    }

    /** A week of columns: the Timetable tab. */
    val CalendarViewWeek: ImageVector by lazy {
        icon(
            "CalendarViewWeek",
            "M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4zM13,6h2.5v12H13V6z" +
                "M11,18H8.5V6H11V18zM4,6h2.5v12H4V6zM20,18h-2.5V6H20V18z",
        )
    }

    /** Ticked lines: the To-do tab. */
    val Checklist: ImageVector by lazy {
        icon(
            "Checklist",
            "M22,7h-9v2h9V7zM22,15h-9v2h9V15zM5.54,11L2,7.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,11z" +
                "M5.54,19L2,15.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,19z",
        )
    }

    /** Two arrows going round: the Habits tab and repeating tasks. */
    val Repeat: ImageVector by lazy {
        icon("Repeat", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z")
    }

    /** Three bars: the Insights tab. */
    val BarChart: ImageVector by lazy {
        icon("BarChart", "M5,9.2h3V19H5zM10.6,5h2.8v14h-2.8zM16.2,13H19v6h-2.8z")
    }

    /** A flag: deadlines. */
    val Flag: ImageVector by lazy {
        icon("Flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")
    }

    /** A page with lines: the Diary tab (docs/PLAN.md §14.5). */
    val EventNote: ImageVector by lazy {
        icon(
            "EventNote",
            "M17,10H7v2h10v-2zM19,3h-1V1h-2v2H8V1H6v2H5c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14" +
                "c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM19,19H5V8h14v11zM14,14H7v2h7v-2z",
        )
    }

    /** A triangle, a circle and a square: the Ingredients tab, now that the fridge is the Kitchen's. */
    val Category: ImageVector by lazy {
        icon(
            "Category",
            "M12,2l-5.5,9h11zM17.5,13c-2.49,0 -4.5,2.01 -4.5,4.5s2.01,4.5 4.5,4.5 4.5,-2.01 4.5,-4.5 -2.01,-4.5 -4.5,-4.5z" +
                "M3,21.5h8v-8H3z",
        )
    }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
}
