package freedom.app.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.userDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_session_prefs")

class UserStateHolder(private val context: Context) {

    private val dataStore = context.userDataStore
    private val USERNAME_KEY = stringPreferencesKey("username")

    private val _username = MutableStateFlow<String?>(null)
    val usernameFlow = _username.asStateFlow()

    val currentUsername: String? get() = _username.value

    init {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.data.map { it[USERNAME_KEY] }
                .collect { savedValue ->
                    _username.value = savedValue
                }
        }
    }

    suspend fun setUsername(value: String?) {
        _username.value = value
        dataStore.edit { prefs ->
            if (value != null) {
                prefs[USERNAME_KEY] = value
            } else {
                prefs.remove(USERNAME_KEY)
            }
        }
    }

    suspend fun getUsername(): String? {
        return dataStore.data.map { it[USERNAME_KEY] }.first()
    }

    suspend fun clear() {
        setUsername(null)
    }
}