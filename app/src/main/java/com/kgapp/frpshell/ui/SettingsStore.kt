package com.kgapp.frpshell.ui

import android.content.Context

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

    companion object {
        private const val PREFS_NAME = "frp_shell_settings"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_USE_SU = "use_su"
    }
}
