package io.github.wltbagent.rtspcamera

import android.content.Context

/** User preferences persisted in SharedPreferences. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** RTSP listen port; applies on the next stream start. */
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    var fps: Int
        get() = prefs.getInt(KEY_FPS, DEFAULT_FPS)
        set(value) = prefs.edit().putInt(KEY_FPS, value.coerceIn(15, 60)).apply()

    /** Encoder bitrate in Mbps. */
    var bitrateMbps: Int
        get() = prefs.getInt(KEY_BITRATE, DEFAULT_BITRATE_MBPS)
        set(value) = prefs.edit().putInt(KEY_BITRATE, value.coerceIn(1, 50)).apply()

    /** Persisted stream size as "WxH", or null for the automatic default. */
    var resolution: String?
        get() = prefs.getString(KEY_RESOLUTION, null)
        set(value) = prefs.edit().putString(KEY_RESOLUTION, value).apply()

    var audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO, value).apply()

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_PORT = "port"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate_mbps"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_AUDIO = "audio_enabled"

        const val DEFAULT_PORT = 8554
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE_MBPS = 6
    }
}
