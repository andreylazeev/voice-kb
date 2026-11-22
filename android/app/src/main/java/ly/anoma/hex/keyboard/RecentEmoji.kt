package ly.anoma.hex.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.emoji2.emojipicker.RecentEmojiProvider

/** The emoji picker's recents where AndroidX's own provider keeps them (its preferences, newest first), held by
 * the keys so the one picker can be asked for them again each time the panel opens. */
internal class RecentEmoji(context: Context) : RecentEmojiProvider {
    private val prefs = context.getSharedPreferences("androidx.emoji2.emojipicker.preferences", Context.MODE_PRIVATE)
    private val list = prefs.getString(KEY, null)?.split(",")?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
    // The picker reads them on a worker thread; a pick records on the main thread.
    override suspend fun getRecentEmojiList(): List<String> = synchronized(list) { list.toList() }
    override fun recordSelection(emoji: String) {
        val joined = synchronized(list) { list.remove(emoji); list.add(0, emoji); list.joinToString(",") }
        prefs.edit().putString(KEY, joined).apply()
    }
    private companion object { const val KEY = "pref_key_recent_emoji" }
}

/** AndroidX uses preferences for both the Recents list and sticky skin-tone variants. */
internal class IncognitoEmojiContext(base: Context) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = DisabledPreferences

    private object DisabledPreferences : SharedPreferences, SharedPreferences.Editor {
        override fun getAll() = emptyMap<String, Any>()
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = this
        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
        override fun commit() = true
        override fun apply() = Unit
    }
}
