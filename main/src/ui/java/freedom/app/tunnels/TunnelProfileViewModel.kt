package freedom.app.tunnels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import freedom.app.data.entity.TunnelProfile
import freedom.app.data.room.FreedomDatabase
import kotlinx.coroutines.launch

class TunnelProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = FreedomDatabase.getDataseClient(application).tunnelProfileDao()

    val allProfiles: LiveData<List<TunnelProfile>> = dao.getAll()

    fun insert(profile: TunnelProfile) = viewModelScope.launch { dao.insert(profile) }

    suspend fun insertSuspend(profile: TunnelProfile): Long = dao.insert(profile)

    fun delete(profile: TunnelProfile) = viewModelScope.launch { dao.delete(profile) }

    suspend fun count(): Int = dao.count()

    /** Reassign priorities so the list order matches [orderedIds]. */
    fun reorder(orderedIds: List<Long>) = viewModelScope.launch {
        orderedIds.forEachIndexed { idx, id -> dao.updatePriority(id, idx) }
    }

    suspend fun updatePublicAddress(id: Long, host: String, port: Int) =
        dao.updatePublicAddress(id, host, port)

    /** Return all active profiles ordered by priority — usable from coroutines. */
    suspend fun getOrderedProfiles(): List<TunnelProfile> = dao.getAllNow()
}
