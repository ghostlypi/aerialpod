package org.aerialpod.android.store

import android.content.SharedPreferences

/**
 * An in-memory `SharedPreferences`.
 *
 * `SharedPreferences` is an interface, so this needs no device and no
 * Robolectric — which is the whole reason the stores take one rather than a
 * Context. Only the string operations the stores actually use do anything.
 */
class FakePrefs(initial: Map<String, String> = emptyMap()) : SharedPreferences {

    val values = LinkedHashMap<String, String>(initial)

    override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue

    override fun getAll(): MutableMap<String, *> = values

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    inner class Editor : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, String>()
        private val removes = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            if (value == null) removes += key else puts[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { removes += key }

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) values.clear()
            removes.forEach { values.remove(it) }
            values.putAll(puts)
            return true
        }

        override fun apply() {
            commit()
        }

        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

/**
 * A [Sealer] that is reversible without hardware, and can be told to fail.
 *
 * `lost = true` is the case where the keystore key is gone — a factory reset,
 * or an OS that dropped it. The bytes are unrecoverable, and what the stores do
 * about that is the point of half these tests.
 */
class FakeSealer(var lost: Boolean = false) : Sealer {
    var seals = 0
        private set

    override fun seal(plain: ByteArray): String {
        seals++
        return "sealed:" + String(plain, Charsets.ISO_8859_1)
    }

    override fun open(encoded: String): ByteArray? {
        if (lost) return null
        return encoded.removePrefix("sealed:").toByteArray(Charsets.ISO_8859_1)
    }
}
