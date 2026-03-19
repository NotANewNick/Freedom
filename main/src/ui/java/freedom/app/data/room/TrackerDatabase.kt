package freedom.app.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import freedom.app.data.dao.IConfigDao
import freedom.app.data.dao.IContactDao
import freedom.app.data.dao.IMessageDao
import freedom.app.data.dao.ITunnelProfileDao
import freedom.app.data.entity.ConfigData
import freedom.app.data.entity.ContactData
import freedom.app.data.entity.MessageData
import freedom.app.data.entity.TunnelProfile

@Database(
    entities = [ConfigData::class, MessageData::class, ContactData::class, TunnelProfile::class],
    version = 28,
    exportSchema = true
)
abstract class FreedomDatabase : RoomDatabase() {

    abstract fun configDao(): IConfigDao
    abstract fun messageDao(): IMessageDao
    abstract fun contactDao(): IContactDao
    abstract fun tunnelProfileDao(): ITunnelProfileDao

    companion object {

        @Volatile
        private var INSTANCE: FreedomDatabase? = null

        fun getDataseClient(context: Context): FreedomDatabase {

            if (INSTANCE != null) return INSTANCE as FreedomDatabase

            synchronized(this) {
                INSTANCE = Room
                    .databaseBuilder(
                        context.applicationContext,
                        FreedomDatabase::class.java,
                        "freedom_database"
                    )
                    .addMigrations(
                        migration_1_2, migration_7_8, migration_8_9, migration_9_10,
                        MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                        MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                        MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                        MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
                        MIGRATION_26_27, MIGRATION_27_28
                    )
                    .build()

                return INSTANCE as FreedomDatabase
            }
        }
    }
}

// ── Legacy migrations kept so existing installs can upgrade ──────────────────

val migration_1_2 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE trips_data ADD COLUMN stopAddressLat REAL NOT NULL DEFAULT 0.0")
        database.execSQL("ALTER TABLE trips_data ADD COLUMN stopAddressLng REAL NOT NULL DEFAULT 0.0")
    }
}
val migration_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE trips_data ADD COLUMN is_sent INTEGER NOT NULL DEFAULT 0")
    }
}
val migration_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE trips_data ADD COLUMN trip_create_date TEXT")
    }
}
val migration_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS device_data (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                serial_number TEXT,
                fw_version TEXT
            );"""
        )
    }
}
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE trips_data ADD COLUMN fw_version TEXT")
    }
}
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE trips_data ADD COLUMN serial_number TEXT")
    }
}
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE device_data ADD COLUMN new_device_name TEXT DEFAULT ''")
    }
}
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE trips_data ADD COLUMN new_device_name TEXT DEFAULT ''")
    }
}
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE tracker_device_data ADD COLUMN new_device_name TEXT DEFAULT ''")
    }
}
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE trips_data ADD COLUMN trip_first_packet_mileage REAL")
    }
}
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE config_data ADD COLUMN monthly_business_km REAL")
        database.execSQL("ALTER TABLE config_data ADD COLUMN monthly_private_km REAL")
    }
}
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE trips_data ADD COLUMN trip_notes TEXT")
    }
}

// ── New ───────────────────────────────────────────────────────────────────────

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS message_data (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp TEXT,
                message_type TEXT,
                content TEXT,
                sender TEXT
            )"""
        )
    }
}

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS contacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                domains TEXT NOT NULL,
                port INTEGER NOT NULL,
                encryption_key TEXT NOT NULL,
                added_at INTEGER NOT NULL
            )"""
        )
    }
}

// Ensures the contacts table exists for devices that had v20 before the contacts feature was added
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS contacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                domains TEXT NOT NULL,
                port INTEGER NOT NULL,
                encryption_key TEXT NOT NULL,
                added_at INTEGER NOT NULL
            )"""
        )
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS tunnel_profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                type TEXT NOT NULL,
                name TEXT NOT NULL,
                secret_key TEXT NOT NULL DEFAULT '',
                tunnel_id TEXT NOT NULL DEFAULT '',
                public_host TEXT NOT NULL DEFAULT '',
                public_port INTEGER NOT NULL DEFAULT 0,
                ovpn_path TEXT NOT NULL DEFAULT '',
                priority INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                added_at INTEGER NOT NULL
            )"""
        )
    }
}

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE contacts ADD COLUMN preferred_ddns_idx INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE contacts ADD COLUMN preferred_port_idx INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE contacts ADD COLUMN preferred_protocol TEXT NOT NULL DEFAULT ''")
    }
}

// Expand single key fields into 3-slot key rings
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // SQLite <3.25 cannot rename columns — use create/copy/drop
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS contacts_v25 (
                id                          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name                        TEXT    NOT NULL,
                ddns_names                  TEXT    NOT NULL,
                ports                       TEXT    NOT NULL,
                handshake_key_0             TEXT    NOT NULL DEFAULT '',
                handshake_key_1             TEXT    NOT NULL DEFAULT '',
                handshake_key_2             TEXT    NOT NULL DEFAULT '',
                active_handshake_key_idx    INTEGER NOT NULL DEFAULT 0,
                handshake_key_created_at_0  INTEGER NOT NULL DEFAULT 0,
                handshake_key_created_at_1  INTEGER NOT NULL DEFAULT 0,
                handshake_key_created_at_2  INTEGER NOT NULL DEFAULT 0,
                otp_key_0                   TEXT    NOT NULL DEFAULT '',
                otp_key_1                   TEXT    NOT NULL DEFAULT '',
                otp_key_2                   TEXT    NOT NULL DEFAULT '',
                active_otp_key_idx          INTEGER NOT NULL DEFAULT 0,
                added_at                    INTEGER NOT NULL,
                preferred_ddns_idx          INTEGER NOT NULL DEFAULT 0,
                preferred_port_idx          INTEGER NOT NULL DEFAULT 0,
                preferred_protocol          TEXT    NOT NULL DEFAULT ''
            )"""
        )
        database.execSQL(
            """INSERT INTO contacts_v25 (
                id, name, ddns_names, ports,
                handshake_key_0, handshake_key_created_at_0,
                otp_key_0,
                added_at, preferred_ddns_idx, preferred_port_idx, preferred_protocol
            )
            SELECT
                id, name, ddns_names, ports,
                handshake_key, key_created_at,
                otp_key,
                added_at, preferred_ddns_idx, preferred_port_idx, preferred_protocol
            FROM contacts"""
        )
        database.execSQL("DROP TABLE contacts")
        database.execSQL("ALTER TABLE contacts_v25 RENAME TO contacts")
    }
}

// Add contact_id and direction to message_data
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE message_data ADD COLUMN contact_id INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE message_data ADD COLUMN direction TEXT NOT NULL DEFAULT 'RECEIVED'")
    }
}

// Add is_searchable cached flag to contacts
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE contacts ADD COLUMN is_searchable INTEGER NOT NULL DEFAULT 0")
    }
}

// Replace handshake+OTP key rings with per-direction send/recv key rings + message counts
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS contacts_v28 (
                id                      INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name                    TEXT    NOT NULL,
                ddns_names              TEXT    NOT NULL,
                ports                   TEXT    NOT NULL,
                send_key_0              TEXT    NOT NULL DEFAULT '',
                send_key_1              TEXT    NOT NULL DEFAULT '',
                send_key_2              TEXT    NOT NULL DEFAULT '',
                active_send_key_idx     INTEGER NOT NULL DEFAULT 0,
                send_key_created_at_0   INTEGER NOT NULL DEFAULT 0,
                send_key_created_at_1   INTEGER NOT NULL DEFAULT 0,
                send_key_created_at_2   INTEGER NOT NULL DEFAULT 0,
                send_msg_count_0        INTEGER NOT NULL DEFAULT 0,
                send_msg_count_1        INTEGER NOT NULL DEFAULT 0,
                send_msg_count_2        INTEGER NOT NULL DEFAULT 0,
                recv_key_0              TEXT    NOT NULL DEFAULT '',
                recv_key_1              TEXT    NOT NULL DEFAULT '',
                recv_key_2              TEXT    NOT NULL DEFAULT '',
                active_recv_key_idx     INTEGER NOT NULL DEFAULT 0,
                recv_key_created_at_0   INTEGER NOT NULL DEFAULT 0,
                recv_key_created_at_1   INTEGER NOT NULL DEFAULT 0,
                recv_key_created_at_2   INTEGER NOT NULL DEFAULT 0,
                added_at                INTEGER NOT NULL,
                preferred_ddns_idx      INTEGER NOT NULL DEFAULT 0,
                preferred_port_idx      INTEGER NOT NULL DEFAULT 0,
                preferred_protocol      TEXT    NOT NULL DEFAULT '',
                is_searchable           INTEGER NOT NULL DEFAULT 0
            )"""
        )
        // Copy existing data: otp_key → both send_key AND recv_key (old shared key needs both).
        // Old format had a uses_remaining prefix byte — we strip it in the app layer on first
        // decrypt since SQLite can't easily do Base64 decode+re-encode. The migration copies
        // the raw Base64 as-is; FreedomCrypto's new decrypt handles both old and new format.
        database.execSQL(
            """INSERT INTO contacts_v28 (
                id, name, ddns_names, ports,
                send_key_0, send_key_1, send_key_2,
                active_send_key_idx,
                recv_key_0, recv_key_1, recv_key_2,
                active_recv_key_idx,
                added_at, preferred_ddns_idx, preferred_port_idx, preferred_protocol,
                is_searchable
            )
            SELECT
                id, name, ddns_names, ports,
                otp_key_0, otp_key_1, otp_key_2,
                active_otp_key_idx,
                otp_key_0, otp_key_1, otp_key_2,
                active_otp_key_idx,
                added_at, preferred_ddns_idx, preferred_port_idx, preferred_protocol,
                is_searchable
            FROM contacts"""
        )
        database.execSQL("DROP TABLE contacts")
        database.execSQL("ALTER TABLE contacts_v28 RENAME TO contacts")
    }
}

// Drops old contacts schema, recreates with multi-DDNS / multi-port / OTP key fields
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS contacts")
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS contacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                ddns_names TEXT NOT NULL,
                ports TEXT NOT NULL,
                handshake_key TEXT NOT NULL DEFAULT '',
                otp_key TEXT NOT NULL DEFAULT '',
                key_created_at INTEGER NOT NULL,
                added_at INTEGER NOT NULL
            )"""
        )
    }
}
