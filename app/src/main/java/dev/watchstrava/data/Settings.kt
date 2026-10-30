package dev.watchstrava.data

import android.content.Context

/** User settings plus the last values used in each setup screen. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var maxHr: Int
        get() = sp.getInt("max_hr", 190)
        set(v) = sp.edit().putInt("max_hr", v).apply()

    var autoLap: Boolean
        get() = sp.getBoolean("auto_lap", true)
        set(v) = sp.edit().putBoolean("auto_lap", v).apply()

    var autoPause: Boolean
        get() = sp.getBoolean("auto_pause", false)
        set(v) = sp.edit().putBoolean("auto_pause", v).apply()

    var broadcastHr: Boolean
        get() = sp.getBoolean("broadcast_hr", true)
        set(v) = sp.edit().putBoolean("broadcast_hr", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    var runnerName: String
        get() = sp.getString("runner_name", "Chong") ?: ""
        set(v) = sp.edit().putString("runner_name", v).apply()

    var showName: Boolean
        get() = sp.getBoolean("show_name", true)
        set(v) = sp.edit().putBoolean("show_name", v).apply()

    /** Index into the UI accent palette. */
    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) = sp.edit().putInt("accent", v).apply()

    /** Setup-screen values, keyed per field. */
    fun int(key: String, default: Int) = sp.getInt("form_$key", default)
    fun setInt(key: String, value: Int) = sp.edit().putInt("form_$key", value).apply()
    fun string(key: String, default: String) = sp.getString("form_$key", default) ?: default
    fun setString(key: String, value: String) = sp.edit().putString("form_$key", value).apply()
}
