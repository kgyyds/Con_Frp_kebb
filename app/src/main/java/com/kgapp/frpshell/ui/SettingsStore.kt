package com.kgapp.frpshell.ui

import android.content.Context
import com.kgapp.frpshell.ui.theme.ThemeMode

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isInitialized(): Boolean = prefs.getBoolean(KEY_INITIALIZED, false)

    fun setInitialized(value: Boolean) {
        prefs.edit().putBoolean(KEY_INITIALIZED, value).apply()
    }

    fun getUseSu(): Boolean = prefs.getBoolean(KEY_USE_SU, false)

    fun setUseSu(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_SU, value).apply()
    }

    fun getThemeMode(): ThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getShellFontSizeSp(): Float = prefs.getFloat(KEY_SHELL_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)

    fun setShellFontSizeSp(value: Float) {
        prefs.edit().putFloat(KEY_SHELL_FONT_SIZE_SP, value).apply()
    }

    fun getUploadScriptContent(): String = prefs.getString(KEY_UPLOAD_SCRIPT_CONTENT, "") ?: ""

    fun setUploadScriptContent(value: String) {
        prefs.edit().putString(KEY_UPLOAD_SCRIPT_CONTENT, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "frp_shell_settings"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_USE_SU = "use_su"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SHELL_FONT_SIZE_SP = "shell_font_size_sp"
        private const val KEY_UPLOAD_SCRIPT_CONTENT = "upload_script_content"

        const val DEFAULT_FONT_SIZE_SP = 14f
    }
}
