package freedom.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import freedom.app.data.entity.TunnelProfile

@Dao
interface ITunnelProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: TunnelProfile): Long

    @Delete
    suspend fun delete(profile: TunnelProfile)

    @Query("SELECT * FROM tunnel_profiles ORDER BY priority ASC, added_at ASC")
    fun getAll(): LiveData<List<TunnelProfile>>

    @Query("SELECT * FROM tunnel_profiles ORDER BY priority ASC, added_at ASC")
    suspend fun getAllNow(): List<TunnelProfile>

    @Query("SELECT COUNT(*) FROM tunnel_profiles")
    suspend fun count(): Int

    @Query("UPDATE tunnel_profiles SET priority = :priority WHERE id = :id")
    suspend fun updatePriority(id: Long, priority: Int)

    @Query("UPDATE tunnel_profiles SET public_host = :host, public_port = :port WHERE id = :id")
    suspend fun updatePublicAddress(id: Long, host: String, port: Int)
}
