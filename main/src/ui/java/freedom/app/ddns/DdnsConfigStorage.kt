package freedom.app.ddns

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object DdnsConfigStorage {

    private const val PREFS_NAME = "ddns_prefs"
    private const val KEY_CONFIGS = "configs"
    private const val KEY_LAST_IP = "last_ip"
    private val gson = Gson()

    fun load(context: Context): MutableList<DdnsConfig> {
        val json = prefs(context).getString(KEY_CONFIGS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<DdnsConfig>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, configs: List<DdnsConfig>) {
        prefs(context).edit().putString(KEY_CONFIGS, gson.toJson(configs)).apply()
    }

    fun getLastIp(context: Context): String? =
        prefs(context).getString(KEY_LAST_IP, null)

    fun saveLastIp(context: Context, ip: String) {
        prefs(context).edit().putString(KEY_LAST_IP, ip).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
