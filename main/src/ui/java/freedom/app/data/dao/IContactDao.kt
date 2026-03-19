package freedom.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import freedom.app.data.entity.ContactData

@Dao
interface IContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactData): Long

    @Delete
    suspend fun delete(contact: ContactData)

    @Query("SELECT * FROM contacts ORDER BY added_at DESC")
    fun getAll(): LiveData<List<ContactData>>

    /** One-shot snapshot for migration / batch operations (not LiveData). */
    @Query("SELECT * FROM contacts ORDER BY added_at DESC")
    suspend fun getAllOnce(): List<ContactData>

    /** Return the first contact whose ddns_names starts with or contains [ddns], or null. */
    @Query("SELECT * FROM contacts WHERE ddns_names LIKE '%' || :ddns || '%' LIMIT 1")
    suspend fun findByDdns(ddns: String): ContactData?

    /** Return a single contact by its primary key, or null. */
    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ContactData?

    /** Persist the last-working connection parameters after a successful connect. */
    @Query("UPDATE contacts SET preferred_ddns_idx = :ddnsIdx, preferred_port_idx = :portIdx, preferred_protocol = :protocol WHERE id = :id")
    suspend fun updatePreferredConnection(id: Long, ddnsIdx: Int, portIdx: Int, protocol: String)

    /** Update the cached searchable flag for a contact after receiving a SEARCH_RESPONSE. */
    @Query("UPDATE contacts SET is_searchable = :searchable WHERE id = :id")
    suspend fun updateSearchable(id: Long, searchable: Boolean)
}
