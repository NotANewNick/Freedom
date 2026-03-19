package freedom.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_data")
data class MessageData(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    var id: Long = 0,

    @ColumnInfo(name = "timestamp")
    var timestamp: String?,

    @ColumnInfo(name = "message_type")
    var messageType: String?,

    @ColumnInfo(name = "content")
    var content: String?,

    @ColumnInfo(name = "sender")
    var sender: String?,

    /** DB id of the contact this message belongs to — 0 = unknown/legacy. */
    @ColumnInfo(name = "contact_id")
    var contactId: Long = 0,

    /** SENT = I sent this; RECEIVED = I received this. */
    @ColumnInfo(name = "direction")
    var direction: String = RECEIVED
) {
    companion object {
        const val SENT     = "SENT"
        const val RECEIVED = "RECEIVED"
    }
}
