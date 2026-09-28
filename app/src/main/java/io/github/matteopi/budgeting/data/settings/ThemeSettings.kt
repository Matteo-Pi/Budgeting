package io.github.matteopi.budgeting.data.settings

/**
 * The selectable colour themes. [DYNAMIC] uses the wallpaper colours on Android 12+ and looks like
 * [PAPER] on older versions. Persisted by [name], so never rename an entry.
 */
enum class AppTheme(val label: String) {
    PAPER("Paper"),
    MONO("Mono"),
    SLATE("Slate"),
    SAND("Sand"),
    SAGE("Sage"),
    INK("Ink"),
    DYNAMIC("Dynamic"),
    ;

    companion object {
        val DEFAULT: AppTheme = PAPER

        /** The theme called [name], or null when [name] is missing or unknown (e.g. from a newer app version). */
        fun parse(name: String?): AppTheme? = AppTheme.entries.firstOrNull { it.name == name }
    }
}

/** Whether the app follows the phone's dark mode or is forced light/dark. Persisted by [name]. */
enum class DarkMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    companion object {
        val DEFAULT: DarkMode = SYSTEM

        /** The mode called [name], or null when [name] is missing or unknown. */
        fun parse(name: String?): DarkMode? = DarkMode.entries.firstOrNull { it.name == name }
    }
}
