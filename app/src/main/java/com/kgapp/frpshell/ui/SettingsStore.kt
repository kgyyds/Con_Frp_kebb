package com.kgapp.frpshellpro.ui

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

  
    fun getShellFontSizeSp(): Float = prefs.getFloat(KEY_SHELL_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)

    fun setShellFontSizeSp(value: Float) {
        prefs.edit().putFloat(KEY_SHELL_FONT_SIZE_SP, value).apply()
    }

    fun getUploadScriptContent(): String = prefs.getString(KEY_UPLOAD_SCRIPT_CONTENT, "") ?: ""

    fun setUploadScriptContent(value: String) {
        prefs.edit().putString(KEY_UPLOAD_SCRIPT_CONTENT, value).apply()
    }

    fun getRecordStreamHost(): String = prefs.getString(KEY_RECORD_STREAM_HOST, DEFAULT_RECORD_STREAM_HOST) ?: DEFAULT_RECORD_STREAM_HOST

    fun setRecordStreamHost(value: String) {
        prefs.edit().putString(KEY_RECORD_STREAM_HOST, value).apply()
    }

    fun getRecordStreamPort(): String = prefs.getString(KEY_RECORD_STREAM_PORT, DEFAULT_RECORD_STREAM_PORT.toString()) ?: DEFAULT_RECORD_STREAM_PORT.toString()

    fun setRecordStreamPort(value: String) {
        prefs.edit().putString(KEY_RECORD_STREAM_PORT, value).apply()
    }

    fun getRecordStartTemplate(): String = prefs.getString(KEY_RECORD_START_TEMPLATE, DEFAULT_RECORD_START_TEMPLATE) ?: DEFAULT_RECORD_START_TEMPLATE

    fun setRecordStartTemplate(value: String) {
        prefs.edit().putString(KEY_RECORD_START_TEMPLATE, value).apply()
    }

    fun getRecordStopTemplate(): String = prefs.getString(KEY_RECORD_STOP_TEMPLATE, DEFAULT_RECORD_STOP_TEMPLATE) ?: DEFAULT_RECORD_STOP_TEMPLATE

    fun setRecordStopTemplate(value: String) {
        prefs.edit().putString(KEY_RECORD_STOP_TEMPLATE, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "frp_shell_settings"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_USE_SU = "use_su"
              private const val KEY_SHELL_FONT_SIZE_SP = "shell_font_size_sp"
        private const val KEY_UPLOAD_SCRIPT_CONTENT = "upload_script_content"
        private const val KEY_RECORD_STREAM_HOST = "record_stream_host"
        private const val KEY_RECORD_STREAM_PORT = "record_stream_port"
        private const val KEY_RECORD_START_TEMPLATE = "record_start_template"
        private const val KEY_RECORD_STOP_TEMPLATE = "record_stop_template"

        const val DEFAULT_FONT_SIZE_SP = 14f
        const val DEFAULT_RECORD_STREAM_HOST = "47.113.126.123"
        const val DEFAULT_RECORD_STREAM_PORT = 40001
        const val DEFAULT_RECORD_START_TEMPLATE = "nohup screenrecord --bit-rate 100000 --output-format=h264 - | nc {host} {port} > /dev/null 2>&1 &"
        const val DEFAULT_RECORD_STOP_TEMPLATE = "pkill -9 screenrecord"
    }
}
