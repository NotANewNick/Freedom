package freedom.app.helper

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

object SharedPreferenceUtil {
    private const val NAME = "shared_preference"
    private const val MODE = Context.MODE_PRIVATE
    private lateinit var preferences: SharedPreferences


    private val IS_LOGGED_IN = Pair("is_logged_in", false)
    private val LAST_SELECTED_COLOR = Pair("last.color.sel", null)

    // call this method from application onCreate(once)
    fun init(context: Application) {
        preferences = context.getSharedPreferences(NAME, MODE)
    }

    /**
     * SharedPreferences extension function, so we won't need to call edit()
    and apply()
     * ourselves on every SharedPreferences operation.
     */
    private inline fun SharedPreferences.edit(
        operation:
            (SharedPreferences.Editor) -> Unit
    ) {
        val editor = edit()
        operation(editor)
        editor.apply()
    }

    var isLoggedIn: Boolean
        get() = preferences.getBoolean(IS_LOGGED_IN.first, IS_LOGGED_IN.second)
        set(value) = preferences.edit {
            it.putBoolean(IS_LOGGED_IN.first, value)
        }


    var lastSelectedColor: String?
        get() = preferences.getString(LAST_SELECTED_COLOR.first, LAST_SELECTED_COLOR.second)
        set(value) = preferences.edit {
            it.putString(LAST_SELECTED_COLOR.first, value)
        }

    fun saveBoolean(key: String, value: Boolean) {
        preferences.edit {
            it.putBoolean(key, value)
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }

    fun hasValue(toString: String): Boolean {
        return preferences.contains(toString)
    }

}