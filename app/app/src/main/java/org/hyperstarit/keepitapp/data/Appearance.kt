package org.hyperstarit.keepitapp.data

import android.app.UiModeManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The theme a user picks in Settings. The keys are the web's (`UserSettings.Theme`), and so are the
 * labels, Auto included, so the two pickers read alike — but the choice is **this device's**: it is
 * never sent to the server, and the web's choice never reaches the phone (ARCHITECTURE.md "UI &
 * design parity").
 */
enum class ThemePref(val key: String, val label: String) {
    Light("light", "Light"),
    Dim("dim", "Dim"),
    Dark("dark", "Dark"),

    /** Follows the phone's dark mode, between Light and Dark. */
    System("system", "Auto");

    /** The `UiModeManager` night mode that matches: Auto leaves it to the phone. */
    val nightMode: Int
        get() = when (this) {
            Light -> UiModeManager.MODE_NIGHT_NO
            Dim, Dark -> UiModeManager.MODE_NIGHT_YES
            System -> UiModeManager.MODE_NIGHT_AUTO
        }

    companion object {
        /** Dim: the look the app had before it had a setting, so an update changes nothing. */
        val Default = Dim

        /** A stored key back to its preference; anything unknown or missing is [Default]. */
        fun fromKey(key: String?): ThemePref = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * Where [ThemePref] lives: app-private SharedPreferences, read synchronously so the very first
 * frame is already in the right theme, and by the widget, which renders with no UI in the process.
 *
 * The choice is also handed to the system as this app's night mode
 * ([UiModeManager.setApplicationNightMode]), which the system keeps across restarts. That is what
 * makes a cold start look right *before* any of our code runs: the launch splash and the window
 * behind the first frame come from `values/` or `values-night/` themes, and without it they would
 * follow the phone — a white flash on every start for someone on Dim with a light phone, and the
 * reverse for Light on a dark one.
 */
class Appearance(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(read(appContext))

    /** The current choice; the theme, the system bars and the widget all follow it. */
    val theme: StateFlow<ThemePref> = _theme

    fun setTheme(pref: ThemePref) {
        prefs.edit().putString(KEY_THEME, pref.key).apply()
        _theme.value = pref
        applyNightMode()
    }

    /**
     * Hands the current choice to the system. Also run at startup, because the system's copy is not
     * part of a backup: a phone restored from one has the preference but not the night mode.
     */
    fun applyNightMode() {
        val manager = appContext.getSystemService(UiModeManager::class.java) ?: return
        runCatching { manager.setApplicationNightMode(_theme.value.nightMode) }
    }

    companion object {
        /** Read by name in `WidgetCompositionSmokeTest`, which writes it as data. */
        const val PREFS_NAME = "keepit_appearance"
        const val KEY_THEME = "theme"

        /** The stored choice, for callers outside the app's container (the widget). */
        fun read(context: Context): ThemePref = ThemePref.fromKey(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_THEME, null),
        )
    }
}
