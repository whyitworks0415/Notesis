package com.notesis

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Every setting the person chose - pens and their presets, tools, gestures,
 * the skin and its look, the toolbar, the clock - written to one small file
 * and read back, on this tablet or another.
 *
 * Only choices travel. What is about this install or this device stays:
 * where each note was left open, the diagnostics id, a home photo only this
 * device was given access to, the wet-ink and mesh switches picked for this
 * tablet's display, and the clock's running timer, stopwatch and study log.
 * Note locks are not settings and never leave the device.
 */
internal object SettingsBackup {
    private const val FORMAT = "notesis-settings"
    private const val VERSION = 1

    private const val PENS = "pens"
    private const val CLOCK = "clock"

    private val DEVICE_KEYS = setOf("diagnosticId", "homePhoto", "compatWetInk", "meshInk")
    private val NOTE_KEY_PREFIXES = listOf("lastPage:", "layout:")
    private val CLOCK_SETTINGS = setOf(
        "buttonShown", "buttonX", "buttonY", "timerDuration", "alarms",
        "autoStudy", "dailyReset", "idleMinutes",
    )

    /** Whether [key] of [store] is a choice worth carrying to another install. */
    internal fun portable(store: String, key: String): Boolean = when (store) {
        PENS -> key !in DEVICE_KEYS && NOTE_KEY_PREFIXES.none { key.startsWith(it) }
        CLOCK -> key in CLOCK_SETTINGS
        else -> false
    }

    /** Writes the settings to [out]; how many were written. */
    fun export(context: Context, out: OutputStream, appVersion: String): Int {
        val stores = JSONObject()
        var count = 0
        for (name in listOf(PENS, CLOCK)) {
            val values = JSONObject()
            for ((key, value) in prefs(context, name).all) {
                if (!portable(name, key)) continue
                val typed = typedValue(value) ?: continue
                values.put(key, typed)
                count++
            }
            stores.put(name, values)
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app", appVersion)
            .put("stores", stores)
        out.write(root.toString(2).toByteArray(Charsets.UTF_8))
        out.flush()
        return count
    }

    /**
     * Reads settings from [input] over the current ones; how many were set,
     * or null when it is not a settings file. Settings the file does not
     * mention are left as they are.
     */
    fun import(context: Context, input: InputStream): Int? {
        val root = runCatching { JSONObject(input.readBytes().toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        if (root.optString("format") != FORMAT) return null
        if (root.optInt("version", 0) > VERSION) return null
        val stores = root.optJSONObject("stores") ?: return null
        var count = 0
        for (name in listOf(PENS, CLOCK)) {
            val values = stores.optJSONObject(name) ?: continue
            val edit = prefs(context, name).edit()
            for (key in values.keys()) {
                if (!portable(name, key)) continue
                val typed = values.optJSONObject(key) ?: continue
                if (putTyped(edit, key, typed)) count++
            }
            // Written before the screens reload from these stores.
            edit.commit()
        }
        return count
    }

    private fun prefs(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** A value with its type, so an Int comes back an Int and not a Long. */
    private fun typedValue(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", org.json.JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    private fun putTyped(edit: SharedPreferences.Editor, key: String, typed: JSONObject): Boolean {
        if (!typed.has("v")) return false
        return runCatching {
            when (typed.optString("t")) {
                "b" -> edit.putBoolean(key, typed.getBoolean("v"))
                "i" -> edit.putInt(key, typed.getInt("v"))
                "l" -> edit.putLong(key, typed.getLong("v"))
                "f" -> edit.putFloat(key, typed.getDouble("v").toFloat())
                "s" -> edit.putString(key, typed.getString("v"))
                "ss" -> {
                    val array = typed.getJSONArray("v")
                    edit.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
                else -> return false
            }
            true
        }.getOrDefault(false)
    }
}
