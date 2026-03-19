package freedom.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import freedom.app.data.entity.MessageData

@Dao
interface IMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageData): Long

    @Query("SELECT * FROM message_data ORDER BY id DESC")
    fun getAllMessages(): LiveData<List<MessageData>>

    /**
     * Messages for a specific contact, oldest-first, excluding all control/protocol
     * message types that should never be shown in the chat UI.
     */
    @Query("""SELECT * FROM message_data
              WHERE contact_id = :contactId
              AND (message_type IS NULL
                   OR message_type NOT IN (
                       'PING','PONG',
                       'INFRA_DDNS_UPDATE','INFRA_PORT_UPDATE','INFRA_ENDPOINT_ACK',
                       'KEY_ROTATE_FLAG','KEY_ROTATE_DELIVERY','KEY_ROTATE_ACK','KEY_ROTATE_CONFIRM',
                       'BOOTSTRAP_KEY_CHUNK','BOOTSTRAP_KEY_DONE','BOOTSTRAP_INFO','BOOTSTRAP_ACK',
                       'SEARCH_REQUEST','SEARCH_RESPONSE',
                       'INFRA_FILE_START','INFRA_FILE_ACK','INFRA_FILE_DONE','INFRA_FILE_ERROR'
                   ))
              ORDER BY id ASC""")
    fun getForContact(contactId: Long): LiveData<List<MessageData>>
}
