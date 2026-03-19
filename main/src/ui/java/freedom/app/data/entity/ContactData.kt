package freedom.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactData(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    @ColumnInfo(name = "name") val name: String,

    /** Comma-separated DDNS hostnames, at least 3. */
    @ColumnInfo(name = "ddns_names") val ddnsNames: String,

    /** Comma-separated port numbers, at least 3. */
    @ColumnInfo(name = "ports") val ports: String,

    // ── Send key ring (3 slots) ─────────────────────────────────────────────
    //
    //  Each slot holds one 24 KB per-direction send key as:
    //    Base64( [raw key bytes (24576)] )
    //  Empty string = slot is free.
    //  activeSendKeyIdx always points to the slot currently in use.

    @ColumnInfo(name = "send_key_0") val sendKey0: String = "",
    @ColumnInfo(name = "send_key_1") val sendKey1: String = "",
    @ColumnInfo(name = "send_key_2") val sendKey2: String = "",

    /** 0, 1, or 2 — which send key slot is currently in use. */
    @ColumnInfo(name = "active_send_key_idx") val activeSendKeyIdx: Int = 0,

    /** Unix epoch millis when each send key slot was last written. */
    @ColumnInfo(name = "send_key_created_at_0") val sendKeyCreatedAt0: Long = 0L,
    @ColumnInfo(name = "send_key_created_at_1") val sendKeyCreatedAt1: Long = 0L,
    @ColumnInfo(name = "send_key_created_at_2") val sendKeyCreatedAt2: Long = 0L,

    /** Messages sent with each send key (for rotation threshold). */
    @ColumnInfo(name = "send_msg_count_0") val sendMsgCount0: Int = 0,
    @ColumnInfo(name = "send_msg_count_1") val sendMsgCount1: Int = 0,
    @ColumnInfo(name = "send_msg_count_2") val sendMsgCount2: Int = 0,

    // ── Recv key ring (3 slots) ─────────────────────────────────────────────
    //
    //  Each slot holds one 24 KB per-direction recv key as:
    //    Base64( [raw key bytes (24576)] )
    //  Empty string = slot is free.
    //  activeRecvKeyIdx always points to the slot currently in use.

    @ColumnInfo(name = "recv_key_0") val recvKey0: String = "",
    @ColumnInfo(name = "recv_key_1") val recvKey1: String = "",
    @ColumnInfo(name = "recv_key_2") val recvKey2: String = "",

    /** 0, 1, or 2 — which recv key slot is currently in use. */
    @ColumnInfo(name = "active_recv_key_idx") val activeRecvKeyIdx: Int = 0,

    /** Unix epoch millis when each recv key slot was last written. */
    @ColumnInfo(name = "recv_key_created_at_0") val recvKeyCreatedAt0: Long = 0L,
    @ColumnInfo(name = "recv_key_created_at_1") val recvKeyCreatedAt1: Long = 0L,
    @ColumnInfo(name = "recv_key_created_at_2") val recvKeyCreatedAt2: Long = 0L,

    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "preferred_ddns_idx") val preferredDdnsIdx: Int = 0,
    @ColumnInfo(name = "preferred_port_idx") val preferredPortIdx: Int = 0,
    @ColumnInfo(name = "preferred_protocol") val preferredProtocol: String = "",

    /**
     * Whether this contact has indicated they are currently searchable/traversable.
     * This is a cached value from the last SEARCH_RESPONSE — it is NOT a static stored setting.
     * The live value is queried on-demand via SEARCH_REQUEST.
     */
    @ColumnInfo(name = "is_searchable") val isSearchable: Boolean = false
)
