#!/usr/bin/env python3
"""
Freedom Messaging – backend core (no UI).

All protocol classes: FreedomCrypto, OtpChannel, MessageParser, Database,
ContactConnectionManager, FileChaCha20, FileTransferEngine,
BootstrapKeyHolder, TcpClientHandler, TcpServer, ConnectionEngine,
ContactShareEngine.

Both the CLI (freedom_cli.py) and the GUI (freedom_gui.py) import from here.
"""

import base64
import hashlib
import json
import logging
import math
import os
import secrets
import socket
import sqlite3
import struct
import threading
import time
import zlib
from datetime import datetime
from enum import Enum
from typing import Callable, Optional

logger = logging.getLogger(__name__)


# ══════════════════════════════════════════════════════════════════════════════
#  1. FreedomCrypto
# ══════════════════════════════════════════════════════════════════════════════

class FreedomCrypto:
    """
    Mirrors freedom.app.helper.FreedomCrypto exactly.

    Pipeline (send):    plaintext  →  DEFLATE-compress  →  flag-byte  →  XOR-cyclic  →  Base64
    Pipeline (receive): Base64     →  XOR-cyclic  →  flag-byte  →  decompress  →  plaintext

    Cyclic XOR: every message XOR'd with the full key starting at byte 0.
    Key reused until rotation.
    """

    # ── Constants ─────────────────────────────────────────────────────────────
    BOOTSTRAP_KEY_BYTES       = 256               # Bootstrap key for QR exchange — 256 bytes keeps QR scannable at ERROR_CORRECT_L
    MESSAGE_KEY_BYTES         = 24 * 1024          # Per-direction message key — 24 KB allows messages up to 24 KB before compression
    DEFAULT_ROTATION_THRESHOLD = 100               # Default messages before key rotation — balances security vs. UX interruption
    KEY_SEGMENTS              = 6
    FINGERPRINT_LENGTH        = 32                 # First 32 hex chars of key shown as visual fingerprint for manual verification

    FLAG_UNCOMPRESSED: int = 0x00
    FLAG_COMPRESSED:   int = 0x01

    # ── Magic headers ────────────────────────────────────────────────────────
    MAGIC_BOOTSTRAP  = bytes([0xFF, 0xFF, 0x42, 0x53])   # FF FF 42 53 ("BS")
    MAGIC_KEY_ROTATE = bytes([0xFF, 0xFF, 0x4B, 0x52])   # FF FF 4B 52 ("KR")

    # Bootstrap packet types (1 byte after magic)
    BS_KEY_CHUNK = 0x01
    BS_INFO      = 0x02
    BS_KEY_DONE  = 0x03
    BS_ACK       = 0x04

    # ── Compression ───────────────────────────────────────────────────────────

    @staticmethod
    def _compress(data: bytes) -> bytes:
        """Raw DEFLATE (no zlib/gzip header) — matches Java Deflater(BEST_COMPRESSION, nowrap=true)."""
        obj = zlib.compressobj(level=9, method=zlib.DEFLATED, wbits=-15)
        return obj.compress(data) + obj.flush()

    @staticmethod
    def _decompress(data: bytes) -> bytes:
        """Raw DEFLATE inflate — matches Java Inflater(nowrap=true)."""
        return zlib.decompress(data, -15)

    # ── XOR core (cyclic) ────────────────────────────────────────────────────

    @staticmethod
    def xor_cyclic(data: bytes, key: bytes) -> bytes:
        """XOR data with key starting at byte 0, cycling key."""
        klen = len(key)
        return bytes(d ^ key[i % klen] for i, d in enumerate(data))

    # ── Key generation ────────────────────────────────────────────────────────

    @staticmethod
    def generate_bootstrap_key() -> bytes:
        """Generate ephemeral bootstrap key (raw bytes)."""
        return secrets.token_bytes(FreedomCrypto.BOOTSTRAP_KEY_BYTES)

    @staticmethod
    def generate_message_key() -> bytes:
        """Generate 24 KB message key (raw bytes)."""
        return secrets.token_bytes(FreedomCrypto.MESSAGE_KEY_BYTES)

    @staticmethod
    def generate_key(size: int = None) -> str:
        """Generate a key. Returns Base64-encoded bytes."""
        if size is None:
            size = FreedomCrypto.MESSAGE_KEY_BYTES
        return base64.b64encode(secrets.token_bytes(size)).decode()

    # ── Encrypt / Decrypt ─────────────────────────────────────────────────────

    @staticmethod
    def encrypt(plaintext_bytes: bytes, key_bytes: bytes) -> str:
        """
        Compress plaintext, prepend flag byte, XOR-cyclic with key.
        Returns ciphertext as Base64 string. No offset.
        """
        compressed = FreedomCrypto._compress(plaintext_bytes)
        if len(compressed) < len(plaintext_bytes):
            flag, payload = FreedomCrypto.FLAG_COMPRESSED, compressed
        else:
            flag, payload = FreedomCrypto.FLAG_UNCOMPRESSED, plaintext_bytes

        to_encrypt = bytes([flag]) + payload
        cipher = FreedomCrypto.xor_cyclic(to_encrypt, key_bytes)
        return base64.b64encode(cipher).decode()

    @staticmethod
    def encrypt_str(plaintext: str, key_bytes: bytes) -> str:
        """Convenience: encrypt a string."""
        return FreedomCrypto.encrypt(plaintext.encode('utf-8'), key_bytes)

    @staticmethod
    def decrypt(ciphertext_b64: str, key_bytes: bytes) -> bytes:
        """Reverse of encrypt(). Returns raw plaintext bytes."""
        cipher = base64.b64decode(ciphertext_b64)
        decrypted = FreedomCrypto.xor_cyclic(cipher, key_bytes)
        flag, payload = decrypted[0], decrypted[1:]
        if flag == FreedomCrypto.FLAG_COMPRESSED:
            return FreedomCrypto._decompress(payload)
        return payload

    @staticmethod
    def decrypt_to_string(ciphertext_b64: str, key_bytes: bytes) -> str:
        """Convenience: decrypt to string."""
        return FreedomCrypto.decrypt(ciphertext_b64, key_bytes).decode('utf-8')

    # ── Base64 helpers ───────────────────────────────────────────────────────

    @staticmethod
    def to_base64(data: bytes) -> str:
        return base64.b64encode(data).decode()

    @staticmethod
    def from_base64(b64: str) -> bytes:
        return base64.b64decode(b64)

    # ── Passcode encryption (PBKDF2-SHA256 + AES-256-GCM) ────────────────────

    @staticmethod
    def encrypt_with_passcode(plaintext: str, passcode: str) -> str:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
        from cryptography.hazmat.primitives import hashes
        salt = secrets.token_bytes(16)
        iv   = secrets.token_bytes(12)
        kdf  = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32,
                           salt=salt, iterations=200_000)
        key  = kdf.derive(passcode.encode('utf-8'))
        ct   = AESGCM(key).encrypt(iv, plaintext.encode('utf-8'), None)
        return base64.b64encode(salt + iv + ct).decode()

    @staticmethod
    def decrypt_with_passcode(encoded: str, passcode: str) -> str:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
        from cryptography.hazmat.primitives import hashes
        data = base64.b64decode(encoded)
        salt, iv, ct = data[:16], data[16:28], data[28:]
        kdf  = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32,
                           salt=salt, iterations=200_000)
        key  = kdf.derive(passcode.encode('utf-8'))
        return AESGCM(key).decrypt(iv, ct, None).decode('utf-8')

    # ── Entropy ───────────────────────────────────────────────────────────────

    @staticmethod
    def entropy_bits_per_byte(key_b64: str) -> float:
        data = base64.b64decode(key_b64)
        if not data:
            return 0.0
        freq = [0] * 256
        for b in data:
            freq[b] += 1
        n = len(data)
        h = 0.0
        for count in freq:
            if count == 0:
                continue
            p = count / n
            h -= p * math.log2(p)
        return h


# ══════════════════════════════════════════════════════════════════════════════
#  2. OtpChannel  (cyclic XOR, dual-key, no offset)
# ══════════════════════════════════════════════════════════════════════════════

class OtpChannel:
    """
    Transparent OTP encryption layer for one socket connection.

    Cyclic XOR model: every message encrypted with the full key starting
    at byte 0. Separate send/recv keys.

    Wire format: just Base64 ciphertext per line. No offset prefix.
    """

    def __init__(self, contact_id: int, send_key: bytes, recv_key: bytes,
                 rotation_threshold: int = FreedomCrypto.DEFAULT_ROTATION_THRESHOLD):
        self.contact_id          = contact_id
        self.send_key            = send_key
        self.recv_key            = recv_key
        self.rotation_threshold  = rotation_threshold
        self.messages_sent       = 0
        self._lock               = threading.Lock()

    def encrypt(self, plaintext: str) -> Optional[str]:
        with self._lock:
            try:
                b64 = FreedomCrypto.encrypt_str(plaintext, self.send_key)
                self.messages_sent += 1
                return b64
            except Exception as e:
                logger.debug("[OtpChannel] encrypt failed: %s", e)
                return None

    def decrypt(self, ciphertext_b64: str) -> Optional[str]:
        try:
            return FreedomCrypto.decrypt_to_string(ciphertext_b64, self.recv_key)
        except Exception as e:
            logger.debug("[OtpChannel] decrypt failed: %s", e)
            return None

    def needs_rotation(self) -> bool:
        return self.messages_sent >= self.rotation_threshold


# ══════════════════════════════════════════════════════════════════════════════
#  3. Message parser
# ══════════════════════════════════════════════════════════════════════════════

class MessageType(Enum):
    TEXT                = 'TEXT'
    PING                = 'PING'
    PONG                = 'PONG'
    SEARCH_REQUEST      = 'SEARCH_REQUEST'
    SEARCH_RESPONSE     = 'SEARCH_RESPONSE'
    INFRA_DDNS_UPDATE   = 'INFRA_DDNS_UPDATE'
    INFRA_PORT_UPDATE   = 'INFRA_PORT_UPDATE'
    INFRA_ENDPOINT_ACK  = 'INFRA_ENDPOINT_ACK'
    INFRA_FILE_START    = 'INFRA_FILE_START'
    INFRA_FILE_ACK      = 'INFRA_FILE_ACK'
    INFRA_FILE_DONE     = 'INFRA_FILE_DONE'
    INFRA_FILE_ERROR    = 'INFRA_FILE_ERROR'
    FILE_RECEIVED       = 'FILE_RECEIVED'
    FILE_SENT           = 'FILE_SENT'
    # Bootstrap (binary — handled before text parsing, but defined for DB storage)
    BOOTSTRAP_KEY_CHUNK = 'BOOTSTRAP_KEY_CHUNK'
    BOOTSTRAP_KEY_DONE  = 'BOOTSTRAP_KEY_DONE'
    BOOTSTRAP_INFO      = 'BOOTSTRAP_INFO'
    BOOTSTRAP_ACK       = 'BOOTSTRAP_ACK'
    # Key rotation
    KEY_ROTATE_FLAG     = 'KEY_ROTATE_FLAG'
    KEY_ROTATE_DELIVERY = 'KEY_ROTATE_DELIVERY'
    KEY_ROTATE_ACK      = 'KEY_ROTATE_ACK'
    KEY_ROTATE_CONFIRM  = 'KEY_ROTATE_CONFIRM'
    # Contact sharing
    INFRA_SHARE_REQ     = 'INFRA_SHARE_REQ'
    INFRA_SHARE_APPROVE = 'INFRA_SHARE_APPROVE'
    INFRA_SHARE_DENY    = 'INFRA_SHARE_DENY'
    INFRA_SHARE_CONNECT = 'INFRA_SHARE_CONNECT'
    INFRA_SHARE_FAIL    = 'INFRA_SHARE_FAIL'
    UNKNOWN             = 'UNKNOWN'


class ParsedMessage:
    def __init__(self, msg_type: MessageType, content: str = '', source: str = ''):
        self.type    = msg_type
        self.content = content
        self.source  = source


class MessageParser:
    @staticmethod
    def parse(line: str, source: str = '') -> Optional[ParsedMessage]:
        if not line:
            return None
        if line == 'PING':
            return ParsedMessage(MessageType.PING, source=source)
        if line == 'PONG':
            return ParsedMessage(MessageType.PONG, source=source)
        if line.startswith('SRCH:'):
            return MessageParser._parse_search(line, source)
        if line.startswith('INFRA:'):
            return MessageParser._parse_infra(line, source)
        return ParsedMessage(MessageType.TEXT, content=line, source=source)

    @staticmethod
    def _parse_search(line: str, source: str) -> ParsedMessage:
        if line == 'SRCH:REQ':
            return ParsedMessage(MessageType.SEARCH_REQUEST, source=source)
        if line.startswith('SRCH:RESP:'):
            return ParsedMessage(MessageType.SEARCH_RESPONSE, content=line[10:], source=source)
        return ParsedMessage(MessageType.UNKNOWN, content=line, source=source)

    @staticmethod
    def _parse_infra(line: str, source: str) -> ParsedMessage:
        tail = line[6:]
        if tail.startswith('DDNS:'):
            return ParsedMessage(MessageType.INFRA_DDNS_UPDATE,  content=tail[5:], source=source)
        if tail.startswith('PORT:'):
            return ParsedMessage(MessageType.INFRA_PORT_UPDATE,  content=tail[5:], source=source)
        if tail == 'ACK':
            return ParsedMessage(MessageType.INFRA_ENDPOINT_ACK, source=source)
        # Key rotation (text-based wire format)
        if tail == 'KR_FLAG':
            return ParsedMessage(MessageType.KEY_ROTATE_FLAG, source=source)
        if tail.startswith('KR_KEY:'):
            return ParsedMessage(MessageType.KEY_ROTATE_DELIVERY, content=tail[7:], source=source)
        if tail == 'KR_ACK':
            return ParsedMessage(MessageType.KEY_ROTATE_ACK, source=source)
        if tail == 'KR_OK':
            return ParsedMessage(MessageType.KEY_ROTATE_CONFIRM, source=source)
        # File transfer
        if tail.startswith('FILE_START:'):
            return ParsedMessage(MessageType.INFRA_FILE_START, content=tail[11:], source=source)
        if tail.startswith('FILE_ACK:'):
            return ParsedMessage(MessageType.INFRA_FILE_ACK,   content=tail[9:],  source=source)
        if tail.startswith('FILE_DONE:'):
            return ParsedMessage(MessageType.INFRA_FILE_DONE,  content=tail[10:], source=source)
        if tail.startswith('FILE_ERR:'):
            return ParsedMessage(MessageType.INFRA_FILE_ERROR, content=tail[9:],  source=source)
        # Contact sharing
        if tail.startswith('SHARE_REQ:'):
            return ParsedMessage(MessageType.INFRA_SHARE_REQ, content=tail[10:], source=source)
        if tail.startswith('SHARE_APPROVE:'):
            return ParsedMessage(MessageType.INFRA_SHARE_APPROVE, content=tail[14:], source=source)
        if tail.startswith('SHARE_DENY:'):
            return ParsedMessage(MessageType.INFRA_SHARE_DENY, content=tail[11:], source=source)
        if tail.startswith('SHARE_CONNECT:'):
            return ParsedMessage(MessageType.INFRA_SHARE_CONNECT, content=tail[14:], source=source)
        if tail.startswith('SHARE_FAIL:'):
            return ParsedMessage(MessageType.INFRA_SHARE_FAIL, content=tail[11:], source=source)
        return ParsedMessage(MessageType.UNKNOWN, content=line, source=source)


# ══════════════════════════════════════════════════════════════════════════════
#  4. Database (SQLite — same schema as Android Room v28)
# ══════════════════════════════════════════════════════════════════════════════

class Database:
    """
    SQLite database with the same schema as the Android Room database (v28).
    Thread-safe: each thread gets its own sqlite3 connection via threading.local().
    """

    def __init__(self, db_path: str = 'freedom.db'):
        self.db_path = db_path
        self._local  = threading.local()
        self._init_db()

    def _conn(self) -> sqlite3.Connection:
        if not hasattr(self._local, 'conn') or self._local.conn is None:
            self._local.conn = sqlite3.connect(self.db_path, check_same_thread=False)
            self._local.conn.row_factory = sqlite3.Row
        return self._local.conn

    def _init_db(self):
        conn = sqlite3.connect(self.db_path)
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS contacts (
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
                added_at                INTEGER NOT NULL DEFAULT 0,
                preferred_ddns_idx      INTEGER NOT NULL DEFAULT 0,
                preferred_port_idx      INTEGER NOT NULL DEFAULT 0,
                preferred_protocol      TEXT    NOT NULL DEFAULT '',
                is_searchable           INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS messages (
                id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp    TEXT,
                message_type TEXT,
                content      TEXT,
                sender       TEXT,
                contact_id   INTEGER NOT NULL DEFAULT 0,
                direction    TEXT    NOT NULL DEFAULT 'RECEIVED'
            );
        """)
        conn.commit()
        conn.close()

    # ── Contacts ──────────────────────────────────────────────────────────────

    def upsert_contact(self, c: dict) -> int:
        existing = self.find_contact_by_name(c['name'])
        conn = self._conn()
        if existing:
            conn.execute("""
                UPDATE contacts SET
                    ddns_names=?, ports=?,
                    send_key_0=?, send_key_1=?, send_key_2=?,
                    active_send_key_idx=?,
                    send_key_created_at_0=?, send_key_created_at_1=?, send_key_created_at_2=?,
                    send_msg_count_0=?, send_msg_count_1=?, send_msg_count_2=?,
                    recv_key_0=?, recv_key_1=?, recv_key_2=?,
                    active_recv_key_idx=?,
                    recv_key_created_at_0=?, recv_key_created_at_1=?, recv_key_created_at_2=?,
                    preferred_ddns_idx=?, preferred_port_idx=?, preferred_protocol=?
                WHERE id=?
            """, (
                c.get('ddns_names', existing['ddns_names']),
                c.get('ports',      existing['ports']),
                c.get('send_key_0', existing.get('send_key_0', '')),
                c.get('send_key_1', existing.get('send_key_1', '')),
                c.get('send_key_2', existing.get('send_key_2', '')),
                c.get('active_send_key_idx', existing.get('active_send_key_idx', 0)),
                c.get('send_key_created_at_0', existing.get('send_key_created_at_0', 0)),
                c.get('send_key_created_at_1', existing.get('send_key_created_at_1', 0)),
                c.get('send_key_created_at_2', existing.get('send_key_created_at_2', 0)),
                c.get('send_msg_count_0', existing.get('send_msg_count_0', 0)),
                c.get('send_msg_count_1', existing.get('send_msg_count_1', 0)),
                c.get('send_msg_count_2', existing.get('send_msg_count_2', 0)),
                c.get('recv_key_0', existing.get('recv_key_0', '')),
                c.get('recv_key_1', existing.get('recv_key_1', '')),
                c.get('recv_key_2', existing.get('recv_key_2', '')),
                c.get('active_recv_key_idx', existing.get('active_recv_key_idx', 0)),
                c.get('recv_key_created_at_0', existing.get('recv_key_created_at_0', 0)),
                c.get('recv_key_created_at_1', existing.get('recv_key_created_at_1', 0)),
                c.get('recv_key_created_at_2', existing.get('recv_key_created_at_2', 0)),
                c.get('preferred_ddns_idx',   existing.get('preferred_ddns_idx', 0)),
                c.get('preferred_port_idx',   existing.get('preferred_port_idx', 0)),
                c.get('preferred_protocol',   existing.get('preferred_protocol', '')),
                existing['id'],
            ))
            conn.commit()
            return existing['id']
        else:
            cur = conn.execute("""
                INSERT INTO contacts (
                    name, ddns_names, ports,
                    send_key_0, send_key_1, send_key_2, active_send_key_idx,
                    send_key_created_at_0, send_key_created_at_1, send_key_created_at_2,
                    send_msg_count_0, send_msg_count_1, send_msg_count_2,
                    recv_key_0, recv_key_1, recv_key_2, active_recv_key_idx,
                    recv_key_created_at_0, recv_key_created_at_1, recv_key_created_at_2,
                    added_at, preferred_ddns_idx, preferred_port_idx, preferred_protocol
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, (
                c['name'], c.get('ddns_names', ''), c.get('ports', ''),
                c.get('send_key_0', ''), c.get('send_key_1', ''), c.get('send_key_2', ''),
                c.get('active_send_key_idx', 0),
                c.get('send_key_created_at_0', 0), c.get('send_key_created_at_1', 0),
                c.get('send_key_created_at_2', 0),
                c.get('send_msg_count_0', 0), c.get('send_msg_count_1', 0),
                c.get('send_msg_count_2', 0),
                c.get('recv_key_0', ''), c.get('recv_key_1', ''), c.get('recv_key_2', ''),
                c.get('active_recv_key_idx', 0),
                c.get('recv_key_created_at_0', 0), c.get('recv_key_created_at_1', 0),
                c.get('recv_key_created_at_2', 0),
                int(time.time() * 1000),
                c.get('preferred_ddns_idx', 0), c.get('preferred_port_idx', 0),
                c.get('preferred_protocol', ''),
            ))
            conn.commit()
            return cur.lastrowid

    def find_contact_by_name(self, name: str) -> Optional[dict]:
        row = self._conn().execute("SELECT * FROM contacts WHERE name=?", (name,)).fetchone()
        return dict(row) if row else None

    def find_contact_by_id(self, cid: int) -> Optional[dict]:
        row = self._conn().execute("SELECT * FROM contacts WHERE id=?", (cid,)).fetchone()
        return dict(row) if row else None

    def find_contact_by_ddns(self, ddns: str) -> Optional[dict]:
        rows = self._conn().execute("SELECT * FROM contacts").fetchall()
        for row in rows:
            names = [d.strip() for d in row['ddns_names'].split(',')]
            if ddns in names:
                return dict(row)
        return None

    def get_all_contacts(self) -> list:
        return [dict(r) for r in self._conn().execute("SELECT * FROM contacts").fetchall()]

    def delete_contact(self, cid: int):
        self._conn().execute("DELETE FROM contacts WHERE id=?", (cid,))
        self._conn().commit()

    # ── Contact key helpers ───────────────────────────────────────────────────

    @staticmethod
    def active_send_key(contact: dict) -> str:
        idx = contact.get('active_send_key_idx', 0)
        return contact.get(f'send_key_{idx}', '')

    @staticmethod
    def active_recv_key(contact: dict) -> str:
        idx = contact.get('active_recv_key_idx', 0)
        return contact.get(f'recv_key_{idx}', '')

    @staticmethod
    def active_send_msg_count(contact: dict) -> int:
        idx = contact.get('active_send_key_idx', 0)
        return contact.get(f'send_msg_count_{idx}', 0)

    # ── Messages ──────────────────────────────────────────────────────────────

    def insert_message(self, contact_id: int, content: str, sender: str,
                       msg_type: str = 'TEXT', direction: str = 'RECEIVED'):
        ts = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        self._conn().execute(
            "INSERT INTO messages (timestamp, message_type, content, sender, contact_id, direction) "
            "VALUES (?,?,?,?,?,?)",
            (ts, msg_type, content, sender, contact_id, direction),
        )
        self._conn().commit()

    def get_messages(self, contact_id: int) -> list:
        return [dict(r) for r in self._conn().execute(
            "SELECT * FROM messages WHERE contact_id=? ORDER BY id", (contact_id,)
        ).fetchall()]

    def get_all_messages(self) -> list:
        return [dict(r) for r in self._conn().execute(
            "SELECT * FROM messages ORDER BY id"
        ).fetchall()]


# ══════════════════════════════════════════════════════════════════════════════
#  5. ContactConnectionManager
# ══════════════════════════════════════════════════════════════════════════════

class ConnectionState(Enum):
    CONNECTED = 'CONNECTED'
    DEGRADED  = 'DEGRADED'
    OFFLINE   = 'OFFLINE'


class ContactConnectionManager:
    """Central registry of live socket connections."""

    def __init__(self):
        self._lock     = threading.Lock()
        self._outbound: dict = {}
        self._inbound:  dict = {}
        self.on_connection_changed: Optional[Callable] = None

    def register_outbound(self, cid: int, sock: socket.socket, channel: Optional[OtpChannel]):
        with self._lock:
            self._outbound[cid] = {'sock': sock, 'channel': channel, 'last_pong': time.time()}
        if self.on_connection_changed:
            self.on_connection_changed(cid, self.get_state(cid))

    def register_inbound(self, cid: int, sock: socket.socket, channel: Optional[OtpChannel]):
        with self._lock:
            self._inbound[cid] = {'sock': sock, 'channel': channel, 'last_pong': time.time()}
        if self.on_connection_changed:
            self.on_connection_changed(cid, self.get_state(cid))

    def unregister_outbound(self, cid: int):
        with self._lock:
            self._outbound.pop(cid, None)
        if self.on_connection_changed:
            self.on_connection_changed(cid, self.get_state(cid))

    def unregister_inbound(self, cid: int):
        with self._lock:
            self._inbound.pop(cid, None)
        if self.on_connection_changed:
            self.on_connection_changed(cid, self.get_state(cid))

    def heartbeat(self, cid: int):
        with self._lock:
            if cid in self._outbound:
                self._outbound[cid]['last_pong'] = time.time()
            if cid in self._inbound:
                self._inbound[cid]['last_pong']  = time.time()

    def send(self, cid: int, plaintext: str) -> bool:
        with self._lock:
            entry = self._outbound.get(cid) or self._inbound.get(cid)
        if not entry:
            return False
        return self._send_to(entry, plaintext)

    def send_raw(self, cid: int, raw_line: str) -> bool:
        with self._lock:
            entry = self._outbound.get(cid) or self._inbound.get(cid)
        if not entry:
            return False
        try:
            entry['sock'].sendall((raw_line + '\n').encode('utf-8'))
            return True
        except Exception:
            return False

    def _send_to(self, entry: dict, plaintext: str) -> bool:
        try:
            channel = entry.get('channel')
            line    = channel.encrypt(plaintext) if channel else plaintext
            if line is None:
                return False
            entry['sock'].sendall((line + '\n').encode('utf-8'))
            return True
        except Exception:
            return False

    def get_state(self, cid: int) -> ConnectionState:
        with self._lock:
            has_out = cid in self._outbound
            has_in  = cid in self._inbound
        if has_out and has_in:
            return ConnectionState.CONNECTED
        if has_out or has_in:
            return ConnectionState.DEGRADED
        return ConnectionState.OFFLINE

    def connected_ids(self) -> list:
        with self._lock:
            return list(set(list(self._outbound.keys()) + list(self._inbound.keys())))


# ══════════════════════════════════════════════════════════════════════════════
#  6. FileChaCha20
# ══════════════════════════════════════════════════════════════════════════════

class FileChaCha20:
    """ChaCha20-Poly1305 file encryption. Per-file random key, sent over OTP channel."""
    KEY_BYTES = 32

    @staticmethod
    def generate_key() -> bytes:
        return secrets.token_bytes(32)

    @staticmethod
    def derive_nonce(file_id: str, chunk_idx: int) -> bytes:
        data = file_id.encode('utf-8') + struct.pack('>I', chunk_idx)
        return hashlib.sha256(data).digest()[:12]

    @staticmethod
    def encrypt(plaintext: bytes, key: bytes, file_id: str, chunk_idx: int) -> bytes:
        from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
        nonce = FileChaCha20.derive_nonce(file_id, chunk_idx)
        aead = ChaCha20Poly1305(key)
        return aead.encrypt(nonce, plaintext, None)

    @staticmethod
    def decrypt(ciphertext: bytes, key: bytes, file_id: str, chunk_idx: int) -> Optional[bytes]:
        from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
        nonce = FileChaCha20.derive_nonce(file_id, chunk_idx)
        aead = ChaCha20Poly1305(key)
        try:
            return aead.decrypt(nonce, ciphertext, None)
        except Exception:
            return None


# ══════════════════════════════════════════════════════════════════════════════
#  7. FileTransferEngine
# ══════════════════════════════════════════════════════════════════════════════

class FileTransferEngine:
    """Sends and receives files using per-file ChaCha20-Poly1305 encryption."""

    CHUNK_SIZE = 8 * 1024

    def __init__(self, db: Database, cm: ContactConnectionManager,
                 recv_dir: str = 'received_files'):
        self.db       = db
        self.cm       = cm
        self.recv_dir = recv_dir
        self._lock    = threading.Lock()
        self._recv_states: dict = {}
        os.makedirs(recv_dir, exist_ok=True)

    def send_file(self, contact_id: int, file_path: str,
                  on_progress: Optional[Callable] = None) -> bool:
        if not os.path.exists(file_path):
            logger.info("[FileTransfer] File not found: %s", file_path)
            return False

        filename = os.path.basename(file_path)
        with open(file_path, 'rb') as f:
            file_data = f.read()

        sha256       = hashlib.sha256(file_data).hexdigest()
        file_id      = secrets.token_hex(8)
        chunks       = [file_data[i:i+self.CHUNK_SIZE] for i in range(0, len(file_data), self.CHUNK_SIZE)]
        total_chunks = len(chunks)
        total_bytes  = len(file_data)

        # Generate per-file ChaCha20 key
        file_key    = FileChaCha20.generate_key()
        hex_key64   = file_key.hex()

        # FILE_START includes the hex key (split with limit=6 on receiver preserves colons in filename)
        start_payload = f"{file_id}:{total_bytes}:{sha256}:{total_chunks}:{hex_key64}:{filename}"
        if not self.cm.send(contact_id, f"INFRA:FILE_START:{start_payload}"):
            logger.info("[FileTransfer] Not connected to contact %d", contact_id)
            return False

        time.sleep(0.1)

        for i, chunk in enumerate(chunks):
            cipher = FileChaCha20.encrypt(chunk, file_key, file_id, i)
            b64    = base64.b64encode(cipher).decode()
            line   = f"FCHUNK:{file_id}:{i}/{total_chunks}:{b64}"
            if not self.cm.send_raw(contact_id, line):
                return False
            if on_progress:
                on_progress(i + 1, total_chunks)

        self.cm.send(contact_id, f"INFRA:FILE_DONE:{file_id}:{sha256}")
        self.db.insert_message(contact_id, filename, 'me', 'FILE_SENT', 'SENT')
        return True

    def on_file_start(self, contact_id: int, payload: str):
        parts = payload.split(':', 5)
        if len(parts) < 6:
            return
        file_id, total_bytes, sha256, total_chunks, hex_key, filename = parts
        try:
            file_key = bytes.fromhex(hex_key)
        except ValueError:
            logger.info("[FileTransfer] Invalid hex key in FILE_START")
            return
        cache_dir = os.path.join('_cache', file_id)
        os.makedirs(cache_dir, exist_ok=True)
        with self._lock:
            self._recv_states[file_id] = {
                'contact_id':    contact_id,
                'filename':      filename,
                'total_bytes':   int(total_bytes),
                'expected_sha':  sha256,
                'total_chunks':  int(total_chunks),
                'file_key':      file_key,
                'cache_dir':     cache_dir,
                'received':      set(),
            }
        logger.info("[FileTransfer] Incoming: %s (%s bytes, %s chunks)", filename, total_bytes, total_chunks)

    def on_file_done(self, contact_id: int, contact_name: str, payload: str):
        parts = payload.split(':', 1)
        if len(parts) < 2:
            return
        file_id, expected_sha = parts
        with self._lock:
            state = self._recv_states.pop(file_id, None)
        if not state:
            return
        buf = bytearray()
        for i in range(state['total_chunks']):
            chunk_file = os.path.join(state['cache_dir'], f"{i}.bin")
            if not os.path.exists(chunk_file):
                logger.info("[FileTransfer] Missing chunk %d for %s", i, file_id)
                return
            with open(chunk_file, 'rb') as f:
                buf.extend(f.read())

        actual_sha = hashlib.sha256(buf).hexdigest()
        if actual_sha != expected_sha:
            logger.info("[FileTransfer] SHA-256 mismatch for %s!", state['filename'])
            return

        out_path = os.path.join(self.recv_dir, state['filename'])
        with open(out_path, 'wb') as f:
            f.write(buf)
        logger.info("[FileTransfer] Saved: %s", out_path)
        self.db.insert_message(contact_id, state['filename'], contact_name,
                               'FILE_RECEIVED', 'RECEIVED')

    def handle_chunk(self, contact_id: int, raw_line: str):
        parts = raw_line.split(':', 3)
        if len(parts) < 4 or parts[0] != 'FCHUNK':
            return
        try:
            file_id   = parts[1]
            chunk_idx = int(parts[2].split('/')[0])
            cipher    = base64.b64decode(parts[3])
        except (ValueError, IndexError):
            return

        with self._lock:
            state = self._recv_states.get(file_id)
        if not state:
            return

        file_key = state.get('file_key')
        if not file_key:
            logger.debug("[FileTransfer] No key for file %s", file_id)
            return

        plain = FileChaCha20.decrypt(cipher, file_key, file_id, chunk_idx)
        if plain is None:
            logger.debug("[FileTransfer] Chunk decrypt failed for %s:%d", file_id, chunk_idx)
            return

        chunk_file = os.path.join(state['cache_dir'], f"{chunk_idx}.bin")
        with open(chunk_file, 'wb') as f:
            f.write(plain)
        with self._lock:
            if file_id in self._recv_states:
                self._recv_states[file_id]['received'].add(chunk_idx)


# ══════════════════════════════════════════════════════════════════════════════
#  8. BootstrapKeyHolder  (ephemeral, memory-only — replaces my_qr_key prefs)
# ══════════════════════════════════════════════════════════════════════════════

class BootstrapKeyHolder:
    """Thread-safe singleton holding the ephemeral bootstrap key during handshake."""

    def __init__(self):
        self._lock = threading.Lock()
        self.active_bootstrap_key: Optional[bytes] = None
        self.on_handshake_complete: Optional[Callable] = None
        self.pending_reverse_contact: Optional[dict] = None
        self.scanned_bootstrap_key: Optional[bytes] = None

    def clear(self):
        with self._lock:
            self.active_bootstrap_key = None
            self.on_handshake_complete = None
            self.pending_reverse_contact = None
            self.scanned_bootstrap_key = None

    def set_bootstrap_key(self, key: bytes):
        with self._lock:
            self.active_bootstrap_key = key

    def get_bootstrap_key(self) -> Optional[bytes]:
        with self._lock:
            return self.active_bootstrap_key


# ══════════════════════════════════════════════════════════════════════════════
#  9. TcpClientHandler  (handles one accepted inbound connection)
# ══════════════════════════════════════════════════════════════════════════════

HEARTBEAT_INTERVAL = 30.0


class TcpClientHandler(threading.Thread):
    """Handles inbound TCP connections. Detects bootstrap, key delivery, or normal OTP connection."""

    def __init__(self, conn: socket.socket, addr,
                 db: Database, cm: ContactConnectionManager, ft: FileTransferEngine,
                 bootstrap_holder: BootstrapKeyHolder,
                 my_ddns: str, my_ports: str,
                 on_message: Optional[Callable] = None,
                 share_engine=None):
        super().__init__(daemon=True)
        self.conn               = conn
        self.addr               = addr
        self.db                 = db
        self.cm                 = cm
        self.ft                 = ft
        self.bootstrap_holder   = bootstrap_holder
        self.my_ddns            = my_ddns
        self.my_ports           = my_ports
        self.on_message         = on_message
        self.share_engine       = share_engine
        self.resolved_contact   = None
        self.otp_channel        = None

    def run(self):
        addr_str = f"{self.addr[0]}:{self.addr[1]}"
        try:
            # Peek first 2 bytes to detect connection type
            peek = self._recv_exact(2)
            if peek is None:
                return

            if peek == b'\xFF\xFF':
                # Binary bootstrap mode
                self._handle_bootstrap(addr_str, peek)
                return

            # Check for pending reverse connection (key delivery — step 7)
            pending = self.bootstrap_holder.pending_reverse_contact
            if pending is not None:
                self._handle_key_delivery(addr_str, peek, pending)
                return

            # Normal OTP message connection
            self._handle_normal_connection(addr_str, peek)

        except Exception as e:
            logger.debug("[Server] [%s] Disconnected: %s", addr_str, e)
        finally:
            if self.resolved_contact:
                self.cm.unregister_inbound(self.resolved_contact['id'])
            try:
                self.conn.close()
            except Exception:
                pass

    def _recv_exact(self, n: int) -> Optional[bytes]:
        buf = b''
        while len(buf) < n:
            chunk = self.conn.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return buf

    def _handle_bootstrap(self, addr_str: str, first_bytes: bytes):
        """Binary bootstrap: B sends key + info to A."""
        bootstrap_key = self.bootstrap_holder.get_bootstrap_key()
        if bootstrap_key is None:
            logger.info("[Server] [%s] Bootstrap connection but no active bootstrap key", addr_str)
            return

        # Read remaining 2 bytes of magic header
        rest = self._recv_exact(2)
        if rest is None:
            return
        magic = first_bytes + rest
        if magic != FreedomCrypto.MAGIC_BOOTSTRAP:
            logger.info("[Server] [%s] Invalid bootstrap magic", addr_str)
            return

        logger.info("[Server] [%s] Bootstrap handshake started", addr_str)
        key_buffer = bytearray()
        contact_info = None

        while True:
            # Read packet type (1 byte)
            ptype_byte = self._recv_exact(1)
            if ptype_byte is None:
                break
            ptype = ptype_byte[0]

            if ptype == FreedomCrypto.BS_KEY_CHUNK:
                # seq(2) + total(2) + len(2) + payload(len)
                header = self._recv_exact(6)
                if header is None:
                    break
                seq, total, plen = struct.unpack('>HHH', header)
                payload_enc = self._recv_exact(plen)
                if payload_enc is None:
                    break
                payload = FreedomCrypto.xor_cyclic(payload_enc, bootstrap_key)
                key_buffer.extend(payload)
                logger.debug("[Server] [%s] Key chunk %d/%d (%d bytes)", addr_str, seq+1, total, plen)

            elif ptype == FreedomCrypto.BS_INFO:
                # len(2) + payload(len)
                len_bytes = self._recv_exact(2)
                if len_bytes is None:
                    break
                plen = struct.unpack('>H', len_bytes)[0]
                payload_enc = self._recv_exact(plen)
                if payload_enc is None:
                    break
                payload = FreedomCrypto.xor_cyclic(payload_enc, bootstrap_key)
                try:
                    contact_info = json.loads(payload.decode('utf-8'))
                    logger.debug("[Server] [%s] Got contact info: %s", addr_str, contact_info)
                except Exception as e:
                    logger.info("[Server] [%s] Failed to parse contact info: %s", addr_str, e)

            elif ptype == FreedomCrypto.BS_KEY_DONE:
                logger.info("[Server] [%s] Key done -- %d bytes received", addr_str, len(key_buffer))
                # Send ACK
                self.conn.sendall(FreedomCrypto.MAGIC_BOOTSTRAP + bytes([FreedomCrypto.BS_ACK]))
                break

            else:
                logger.info("[Server] [%s] Unknown bootstrap packet type: %s", addr_str, ptype)
                break

        # Save contact with recv key (B's send key becomes A's recv key)
        if key_buffer and contact_info:
            recv_key_b64 = base64.b64encode(bytes(key_buffer)).decode()
            ddns = contact_info.get('ddns', '')
            ports = contact_info.get('ports', '')
            name = contact_info.get('name', ddns.split(',')[0].strip())

            contact = {
                'name': name,
                'ddns_names': ddns,
                'ports': ports,
                'recv_key_0': recv_key_b64,
                'active_recv_key_idx': 0,
                'recv_key_created_at_0': int(time.time() * 1000),
            }
            cid = self.db.upsert_contact(contact)
            saved_contact = self.db.find_contact_by_id(cid)
            logger.info("[Server] [%s] Contact saved: %s (id=%d)", addr_str, name, cid)

            # Notify handler
            if self.bootstrap_holder.on_handshake_complete:
                self.bootstrap_holder.on_handshake_complete(saved_contact)

    def _handle_key_delivery(self, addr_str: str, first_bytes: bytes, pending: dict):
        """A's reverse connection: delivers Key_A→B XOR Key_B→A."""
        logger.info("[Server] [%s] Key delivery from A", addr_str)

        # Read remaining bytes (24KB total, first 2 already read)
        remaining = FreedomCrypto.MESSAGE_KEY_BYTES - 2
        rest = self._recv_exact(remaining)
        if rest is None:
            logger.info("[Server] [%s] Key delivery: incomplete data", addr_str)
            return
        xored_key = first_bytes + rest

        # XOR with our send key to recover A's send key (our recv key)
        my_send_key_b64 = Database.active_send_key(pending)
        if not my_send_key_b64:
            logger.info("[Server] [%s] No send key to XOR with", addr_str)
            return
        my_send_key = base64.b64decode(my_send_key_b64)
        recv_key = FreedomCrypto.xor_cyclic(xored_key, my_send_key)
        recv_key_b64 = base64.b64encode(recv_key).decode()

        # Send ACK
        self.conn.sendall(FreedomCrypto.MAGIC_BOOTSTRAP + bytes([FreedomCrypto.BS_ACK]))

        # Read A's contact details (bootstrap-encrypted)
        bootstrap_key = self.bootstrap_holder.scanned_bootstrap_key
        if bootstrap_key:
            try:
                self.conn.settimeout(5.0)
                # Read bootstrap INFO packet
                magic = self._recv_exact(4)
                if magic == FreedomCrypto.MAGIC_BOOTSTRAP:
                    ptype = self._recv_exact(1)
                    if ptype and ptype[0] == FreedomCrypto.BS_INFO:
                        len_bytes = self._recv_exact(2)
                        if len_bytes:
                            plen = struct.unpack('>H', len_bytes)[0]
                            payload_enc = self._recv_exact(plen)
                            if payload_enc:
                                payload = FreedomCrypto.xor_cyclic(payload_enc, bootstrap_key)
                                info = json.loads(payload.decode('utf-8'))
                                # Update contact with A's details
                                pending['name'] = info.get('name', pending.get('name', ''))
                                if info.get('ddns'):
                                    pending['ddns_names'] = info['ddns']
                                if info.get('ports'):
                                    pending['ports'] = info['ports']
            except Exception as e:
                logger.debug("[Server] [%s] Reading A's details: %s", addr_str, e)

        # Update contact with recv key
        pending['recv_key_0'] = recv_key_b64
        pending['active_recv_key_idx'] = 0
        pending['recv_key_created_at_0'] = int(time.time() * 1000)
        self.db.upsert_contact(pending)

        # Send final ACK
        self.conn.sendall(FreedomCrypto.MAGIC_BOOTSTRAP + bytes([FreedomCrypto.BS_ACK]))

        # Clear pending state
        self.bootstrap_holder.pending_reverse_contact = None
        self.bootstrap_holder.scanned_bootstrap_key = None
        logger.info("[Server] [%s] Key exchange complete for %s", addr_str, pending.get('name', '?'))

    def _handle_normal_connection(self, addr_str: str, first_bytes: bytes):
        """Normal OTP message connection."""
        # Prepend the 2 peeked bytes and wrap in a line-buffered reader
        rfile = (first_bytes + self.conn.makefile('rb').read()).decode('utf-8', errors='replace')
        # Actually, let's do this properly with a makefile approach
        # We already consumed 2 bytes. Push them back via a buffered approach
        import io
        raw_stream = self.conn.makefile('rb')
        combined = io.BufferedReader(io.BytesIO(first_bytes + raw_stream.read()))
        # This won't work well for streaming. Let's use a simpler approach.

        # Identify contact by trying to decrypt with each contact's recv key
        contacts = self.db.get_all_contacts()

        # Read the first line (includes the 2 peeked bytes)
        buf = first_bytes
        while True:
            b = self.conn.recv(1)
            if not b or b == b'\n':
                break
            buf += b
        first_line = buf.decode('utf-8', errors='replace').strip()

        if not first_line:
            return

        # Try to identify contact
        for c in contacts:
            recv_key_b64 = Database.active_recv_key(c)
            if not recv_key_b64:
                continue
            try:
                recv_key = base64.b64decode(recv_key_b64)
                plaintext = FreedomCrypto.decrypt_to_string(first_line, recv_key)
                # If we get here without exception, this is likely the right contact
                self.resolved_contact = c
                self.otp_channel = OtpChannel(
                    c['id'],
                    base64.b64decode(Database.active_send_key(c)) if Database.active_send_key(c) else b'',
                    recv_key,
                )
                self.cm.register_inbound(c['id'], self.conn, self.otp_channel)
                logger.info("[Server] [%s] Identified contact: %s", addr_str, c['name'])

                # Process the first message
                self._process_decrypted(plaintext, addr_str)
                break
            except Exception:
                continue

        if not self.resolved_contact:
            # Could not identify — try raw
            parsed = MessageParser.parse(first_line, addr_str)
            if parsed and parsed.type == MessageType.PING:
                self.conn.sendall(b'PONG\n')

        # Continue reading messages
        rfile = self.conn.makefile('r', encoding='utf-8', errors='replace')
        stop_event = threading.Event()

        def _heartbeat():
            while not stop_event.is_set():
                time.sleep(HEARTBEAT_INTERVAL)
                try:
                    self.conn.sendall(b'PING\n')
                except Exception:
                    break
        threading.Thread(target=_heartbeat, daemon=True).start()

        for raw in rfile:
            raw = raw.rstrip('\n')
            if not raw:
                continue

            if raw.startswith('FCHUNK:'):
                if self.resolved_contact:
                    self.ft.handle_chunk(self.resolved_contact['id'], raw)
                continue

            decrypted = (self.otp_channel.decrypt(raw) if self.otp_channel else None) or raw
            self._process_decrypted(decrypted, addr_str)

        stop_event.set()

    def _process_decrypted(self, decrypted: str, addr_str: str):
        parsed = MessageParser.parse(decrypted, addr_str)
        if not parsed:
            return

        contact = self.resolved_contact
        if parsed.type == MessageType.PING:
            self.conn.sendall(b'PONG\n')
            if contact:
                self.cm.heartbeat(contact['id'])
            return
        if parsed.type == MessageType.PONG:
            if contact:
                self.cm.heartbeat(contact['id'])
            return
        if parsed.type == MessageType.INFRA_ENDPOINT_ACK:
            return
        if parsed.type == MessageType.INFRA_FILE_START:
            if contact:
                self.ft.on_file_start(contact['id'], parsed.content)
            return
        if parsed.type == MessageType.INFRA_FILE_DONE:
            if contact:
                self.ft.on_file_done(
                    contact['id'], contact.get('name', addr_str), parsed.content)
            return
        if parsed.type in (MessageType.INFRA_FILE_ACK, MessageType.INFRA_FILE_ERROR):
            return

        # Contact sharing
        if parsed.type == MessageType.INFRA_SHARE_REQ:
            if contact and self.share_engine:
                self.share_engine.handle_share_request(contact['id'], parsed.content)
            return
        if parsed.type == MessageType.INFRA_SHARE_APPROVE:
            if contact and self.share_engine:
                self.share_engine.handle_share_approve(contact['id'], parsed.content)
            return
        if parsed.type == MessageType.INFRA_SHARE_DENY:
            if contact and self.share_engine:
                self.share_engine.handle_share_deny(contact['id'], parsed.content)
            return
        if parsed.type == MessageType.INFRA_SHARE_CONNECT:
            if contact and self.share_engine:
                self.share_engine.handle_share_connect(contact['id'], parsed.content)
            return
        if parsed.type == MessageType.INFRA_SHARE_FAIL:
            if contact and self.share_engine:
                self.share_engine.handle_share_fail(contact['id'], parsed.content)
            return

        cid    = contact['id'] if contact else 0
        sender = contact['name'] if contact else addr_str
        self.db.insert_message(cid, parsed.content, sender, parsed.type.value, 'RECEIVED')
        if self.on_message:
            self.on_message(sender, parsed.content, cid)


# ══════════════════════════════════════════════════════════════════════════════
#  10. TcpServer  (listens for inbound connections)
# ══════════════════════════════════════════════════════════════════════════════

class TcpServer(threading.Thread):
    """Listens for inbound TCP connections and spawns a TcpClientHandler for each."""

    def __init__(self, port: int,
                 db: Database, cm: ContactConnectionManager, ft: FileTransferEngine,
                 bootstrap_holder: BootstrapKeyHolder,
                 my_ddns: str, my_ports: str,
                 on_message: Optional[Callable] = None,
                 share_engine=None):
        super().__init__(daemon=True)
        self.port               = port
        self.db                 = db
        self.cm                 = cm
        self.ft                 = ft
        self.bootstrap_holder   = bootstrap_holder
        self.my_ddns            = my_ddns
        self.my_ports           = my_ports
        self.on_message         = on_message
        self.share_engine       = share_engine
        self._stop              = threading.Event()
        self.on_server_log: Optional[Callable] = None

    def run(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(('0.0.0.0', self.port))
        srv.listen(10)
        srv.settimeout(1.0)
        msg = f"[Server] Listening on port {self.port}"
        logger.info(msg)
        if self.on_server_log:
            self.on_server_log(msg)
        while not self._stop.is_set():
            try:
                conn, addr = srv.accept()
                log = f"[Server] Connection from {addr[0]}:{addr[1]}"
                logger.info(log)
                if self.on_server_log:
                    self.on_server_log(log)
                TcpClientHandler(
                    conn, addr, self.db, self.cm, self.ft,
                    self.bootstrap_holder,
                    self.my_ddns, self.my_ports, self.on_message,
                    share_engine=self.share_engine,
                ).start()
            except socket.timeout:
                continue
            except Exception as e:
                if not self._stop.is_set():
                    logger.info("[Server] Accept error: %s", e)
        srv.close()

    def stop(self):
        self._stop.set()


# ══════════════════════════════════════════════════════════════════════════════
#  11. ConnectionEngine  (outbound — mirrors ConnectionEngine.kt)
# ══════════════════════════════════════════════════════════════════════════════

class ConnectionEngine:
    """Outbound connection engine — tries each DDNS+port combination until one succeeds."""

    TCP_TIMEOUT = 5.0

    def __init__(self, contact: dict,
                 db: Database, cm: ContactConnectionManager, ft: FileTransferEngine,
                 my_ddns: str, my_ports: str,
                 on_message: Optional[Callable] = None,
                 share_engine=None):
        self.contact      = contact
        self.db           = db
        self.cm           = cm
        self.ft           = ft
        self.my_ddns      = my_ddns
        self.my_ports     = my_ports
        self.on_message   = on_message
        self.share_engine = share_engine

    def connect(self) -> tuple:
        """Connect to contact using send/recv keys. No handshake key needed."""
        c = self.contact
        send_key_b64 = Database.active_send_key(c)
        recv_key_b64 = Database.active_recv_key(c)

        if not send_key_b64 or not recv_key_b64:
            return False, "Contact has no send/recv keys"

        ddns_list  = [d.strip() for d in c['ddns_names'].split(',') if d.strip()]
        ports_list = [int(p.strip()) for p in c['ports'].split(',') if p.strip()]

        for ddns in ddns_list:
            for port in ports_list:
                success, msg = self._try_tcp(ddns, port, send_key_b64, recv_key_b64)
                if success:
                    return True, msg

        return False, f"All endpoints for {c['name']} unreachable"

    def _try_tcp(self, ddns: str, port: int,
                 send_key_b64: str, recv_key_b64: str) -> tuple:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(self.TCP_TIMEOUT)
            sock.connect((ddns, port))

            send_key = base64.b64decode(send_key_b64)
            recv_key = base64.b64decode(recv_key_b64)

            otp_channel = OtpChannel(self.contact['id'], send_key, recv_key)

            # Send initial encrypted PING
            ping_cipher = otp_channel.encrypt("PING")
            if ping_cipher:
                sock.sendall((ping_cipher + '\n').encode('utf-8'))

            self.cm.register_outbound(self.contact['id'], sock, otp_channel)

            rfile = sock.makefile('r', encoding='utf-8', errors='replace')
            threading.Thread(
                target=self._reader_loop,
                args=(sock, rfile, otp_channel, ddns, port),
                daemon=True,
            ).start()

            return True, f"Connected to {self.contact['name']} via TCP ({ddns}:{port})"
        except Exception as e:
            return False, f"TCP {ddns}:{port} failed: {e}"

    def _reader_loop(self, sock: socket.socket, rfile, otp_channel, ddns: str, port: int):
        contact   = self.contact
        last_ping = time.time()
        try:
            sock.settimeout(HEARTBEAT_INTERVAL + 10.0)
            for raw in rfile:
                raw = raw.rstrip('\n')
                if not raw:
                    continue
                if time.time() - last_ping >= HEARTBEAT_INTERVAL:
                    try:
                        sock.sendall(b'PING\n')
                        last_ping = time.time()
                    except Exception:
                        break

                if raw.startswith('FCHUNK:'):
                    self.ft.handle_chunk(contact['id'], raw)
                    continue

                decrypted = (otp_channel.decrypt(raw) if otp_channel else None) or raw
                parsed    = MessageParser.parse(decrypted, ddns)
                if not parsed:
                    continue

                if parsed.type == MessageType.PING:
                    sock.sendall(b'PONG\n')
                    continue
                if parsed.type == MessageType.PONG:
                    self.cm.heartbeat(contact['id'])
                    continue
                if parsed.type == MessageType.INFRA_FILE_START:
                    self.ft.on_file_start(contact['id'], parsed.content)
                    continue
                if parsed.type == MessageType.INFRA_FILE_DONE:
                    self.ft.on_file_done(contact['id'], contact['name'], parsed.content)
                    continue
                if parsed.type in (MessageType.INFRA_FILE_ACK, MessageType.INFRA_FILE_ERROR):
                    continue

                # Contact sharing
                if parsed.type == MessageType.INFRA_SHARE_REQ:
                    if self.share_engine:
                        self.share_engine.handle_share_request(contact['id'], parsed.content)
                    continue
                if parsed.type == MessageType.INFRA_SHARE_APPROVE:
                    if self.share_engine:
                        self.share_engine.handle_share_approve(contact['id'], parsed.content)
                    continue
                if parsed.type == MessageType.INFRA_SHARE_DENY:
                    if self.share_engine:
                        self.share_engine.handle_share_deny(contact['id'], parsed.content)
                    continue
                if parsed.type == MessageType.INFRA_SHARE_CONNECT:
                    if self.share_engine:
                        self.share_engine.handle_share_connect(contact['id'], parsed.content)
                    continue
                if parsed.type == MessageType.INFRA_SHARE_FAIL:
                    if self.share_engine:
                        self.share_engine.handle_share_fail(contact['id'], parsed.content)
                    continue

                self.db.insert_message(
                    contact['id'], parsed.content, contact['name'],
                    parsed.type.value, 'RECEIVED',
                )
                if self.on_message:
                    self.on_message(contact['name'], parsed.content, contact['id'])
        except Exception:
            pass
        finally:
            self.cm.unregister_outbound(contact['id'])
            try:
                sock.close()
            except Exception:
                pass

    # ── Bootstrap methods ─────────────────────────────────────────────────────

    @staticmethod
    def bootstrap_send_key(ddns: str, port: int, bootstrap_key: bytes,
                           my_key: bytes, my_info: dict,
                           timeout: float = 10.0) -> bool:
        """
        B → A: Send B's 24KB key + contact info using binary bootstrap protocol.
        Returns True on success.
        """
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)
            sock.connect((ddns, port))

            # Send magic header
            sock.sendall(FreedomCrypto.MAGIC_BOOTSTRAP)

            # Chunk the key
            chunk_size = FreedomCrypto.BOOTSTRAP_KEY_BYTES
            chunks = [my_key[i:i+chunk_size] for i in range(0, len(my_key), chunk_size)]
            total = len(chunks)

            for seq, chunk in enumerate(chunks):
                encrypted = FreedomCrypto.xor_cyclic(chunk, bootstrap_key)
                pkt = bytes([FreedomCrypto.BS_KEY_CHUNK])
                pkt += struct.pack('>HHH', seq, total, len(encrypted))
                pkt += encrypted
                sock.sendall(pkt)

            # Send contact info
            info_json = json.dumps(my_info).encode('utf-8')
            info_enc = FreedomCrypto.xor_cyclic(info_json, bootstrap_key)
            pkt = bytes([FreedomCrypto.BS_INFO])
            pkt += struct.pack('>H', len(info_enc))
            pkt += info_enc
            sock.sendall(pkt)

            # Send KEY_DONE
            sock.sendall(bytes([FreedomCrypto.BS_KEY_DONE]))

            # Wait for ACK
            sock.settimeout(timeout)
            ack = sock.recv(5)  # magic(4) + type(1)
            if len(ack) >= 5 and ack[:4] == FreedomCrypto.MAGIC_BOOTSTRAP and ack[4] == FreedomCrypto.BS_ACK:
                logger.info("[Bootstrap] Key sent successfully to %s:%d", ddns, port)
                sock.close()
                return True
            else:
                logger.info("[Bootstrap] No ACK received from %s:%d", ddns, port)
                sock.close()
                return False

        except Exception as e:
            logger.info("[Bootstrap] Send key failed: %s", e)
            return False

    @staticmethod
    def bootstrap_deliver_key(contact: dict, my_send_key: bytes,
                              bootstrap_key: bytes, my_info: dict,
                              timeout: float = 10.0) -> bool:
        """
        A → B: Deliver Key_A→B XOR Key_B→A (raw 24KB), then contact details.
        """
        ddns_list = [d.strip() for d in contact['ddns_names'].split(',') if d.strip()]
        ports_list = [int(p.strip()) for p in contact['ports'].split(',') if p.strip()]

        recv_key_b64 = Database.active_recv_key(contact)
        if not recv_key_b64:
            logger.info("[Bootstrap] Contact has no recv key to XOR with")
            return False
        recv_key = base64.b64decode(recv_key_b64)

        # XOR my send key with recv key (B's send key)
        xored = FreedomCrypto.xor_cyclic(my_send_key, recv_key)

        for ddns in ddns_list:
            for port in ports_list:
                try:
                    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    sock.settimeout(timeout)
                    sock.connect((ddns, port))

                    # Send raw 24KB (no framing)
                    sock.sendall(xored)

                    # Wait for ACK
                    ack = sock.recv(5)
                    if not (len(ack) >= 5 and ack[:4] == FreedomCrypto.MAGIC_BOOTSTRAP
                            and ack[4] == FreedomCrypto.BS_ACK):
                        sock.close()
                        continue

                    # Send contact details (bootstrap-encrypted)
                    info_json = json.dumps(my_info).encode('utf-8')
                    info_enc = FreedomCrypto.xor_cyclic(info_json, bootstrap_key)
                    pkt = FreedomCrypto.MAGIC_BOOTSTRAP
                    pkt += bytes([FreedomCrypto.BS_INFO])
                    pkt += struct.pack('>H', len(info_enc))
                    pkt += info_enc
                    sock.sendall(pkt)

                    # Wait for final ACK
                    final_ack = sock.recv(5)
                    sock.close()

                    logger.info("[Bootstrap] Key delivered to %s:%d", ddns, port)
                    return True

                except Exception as e:
                    logger.info("[Bootstrap] Deliver to %s:%d failed: %s", ddns, port, e)
                    continue

        return False


# ══════════════════════════════════════════════════════════════════════════════
#  12. ContactShareEngine  (introduce two contacts to each other)
# ══════════════════════════════════════════════════════════════════════════════

class ContactShareEngine:
    """
    Allows contact A to introduce two of their contacts (B and C) to each other.

    Flow:
    1. A sends SHARE_REQ to both B and C.
    2. Both must SHARE_APPROVE.
    3. A generates a 256-byte bootstrap key.
    4. A sends SHARE_CONNECT to B (listener — 3 fields: key, name).
    5. A sends SHARE_CONNECT to C (connector — 5 fields: key, ddns, port, name).
    6. C connects to B directly, normal 7-phase bootstrap.
    7. B and C end up with their own OTP channel.
    """

    SHARE_TIMEOUT = 300.0       # 5 minutes
    RATE_LIMIT_SECONDS = 15.0

    def __init__(self, send_func: Callable, contacts_db: Database,
                 bootstrap_holder: BootstrapKeyHolder,
                 my_ddns: str = '', my_ports: str = '',
                 my_name: str = '',
                 on_share_request: Optional[Callable] = None,
                 on_share_status: Optional[Callable] = None):
        """
        Args:
            send_func: callable(contact_id, plaintext) -> bool, sends an OTP-encrypted message
            contacts_db: Database instance
            bootstrap_holder: BootstrapKeyHolder for setting up listener-side bootstrap
            my_ddns: this node's DDNS hostname
            my_ports: this node's port(s)
            my_name: this node's display name
            on_share_request: callback(from_contact_id, share_id, other_name, message) for UI
            on_share_status: callback(message_str) for status updates
        """
        self.send_func = send_func
        self.contacts_db = contacts_db
        self.bootstrap_holder = bootstrap_holder
        self.my_ddns = my_ddns
        self.my_ports = my_ports
        self.my_name = my_name
        self.on_share_request = on_share_request
        self.on_share_status = on_share_status

        self._lock = threading.Lock()
        self.pending_shares = {}        # shareId -> share state dict
        self.incoming_requests = {}     # shareId -> {from_contact_id, other_name, message}
        self.expected_bootstraps = {}   # base64_key -> contact_name
        self.last_share_time = {}       # contact_id -> timestamp
        self._bootstrap_timers = {}     # shareId -> threading.Timer for 30s bootstrap timeout
        self._bootstrap_share_meta = {} # shareId -> {key_b64, from_contact_id, other_name}
        self._cleanup_thread = threading.Thread(target=self._cleanup_loop, daemon=True)
        self._cleanup_thread.start()

    # ── Initiator side (A) ─────────────────────────────────────────────────

    def initiate_share(self, contact1_name: str, contact2_name: str,
                       message: str = '') -> tuple:
        """
        A initiates sharing: introduce contact1 to contact2.
        Returns (success: bool, message: str).
        """
        c1 = self.contacts_db.find_contact_by_name(contact1_name)
        c2 = self.contacts_db.find_contact_by_name(contact2_name)
        if not c1:
            return False, f"Contact '{contact1_name}' not found"
        if not c2:
            return False, f"Contact '{contact2_name}' not found"

        # Rate-limit check per contact
        now = time.time()
        with self._lock:
            last1 = self.last_share_time.get(c1['id'], 0)
            last2 = self.last_share_time.get(c2['id'], 0)
            if now - last1 < self.RATE_LIMIT_SECONDS:
                remaining = self.RATE_LIMIT_SECONDS - (now - last1)
                return False, f"Rate limited: wait {remaining:.0f}s before sharing with {contact1_name}"
            if now - last2 < self.RATE_LIMIT_SECONDS:
                remaining = self.RATE_LIMIT_SECONDS - (now - last2)
                return False, f"Rate limited: wait {remaining:.0f}s before sharing with {contact2_name}"

        share_id = secrets.token_hex(8)

        # Send SHARE_REQ to both
        # contact1 is told about contact2, and vice versa
        msg1 = f"INFRA:SHARE_REQ:{share_id}:{contact2_name}:{message}"
        msg2 = f"INFRA:SHARE_REQ:{share_id}:{contact1_name}:{message}"

        ok1 = self.send_func(c1['id'], msg1)
        ok2 = self.send_func(c2['id'], msg2)

        if not ok1 and not ok2:
            return False, f"Not connected to {contact1_name} or {contact2_name}"
        if not ok1:
            return False, f"Not connected to {contact1_name}"
        if not ok2:
            return False, f"Not connected to {contact2_name}"

        with self._lock:
            self.pending_shares[share_id] = {
                'contact1_id': c1['id'],
                'contact2_id': c2['id'],
                'contact1_name': contact1_name,
                'contact2_name': contact2_name,
                'c1_approved': False,
                'c2_approved': False,
                'message': message,
                'created_at': time.time(),
            }
            self.last_share_time[c1['id']] = now
            self.last_share_time[c2['id']] = now

        logger.info("[Share] Initiated share %s: %s <-> %s", share_id, contact1_name, contact2_name)
        return True, f"Share request sent to {contact1_name} and {contact2_name} (id={share_id})"

    # ── Responder side (B or C receiving a SHARE_REQ) ─────────────────────

    def handle_share_request(self, from_contact_id: int, payload: str):
        """
        Called when we receive INFRA:SHARE_REQ:{shareId}:{otherContactName}:{message}.
        payload is everything after 'SHARE_REQ:'.
        """
        parts = payload.split(':', 2)
        if len(parts) < 2:
            logger.info("[Share] Malformed SHARE_REQ payload: %s", payload)
            return
        share_id = parts[0]
        other_name = parts[1]
        message = parts[2] if len(parts) > 2 else ''

        from_contact = self.contacts_db.find_contact_by_id(from_contact_id)
        from_name = from_contact['name'] if from_contact else f"id={from_contact_id}"

        with self._lock:
            self.incoming_requests[share_id] = {
                'from_contact_id': from_contact_id,
                'from_name': from_name,
                'other_name': other_name,
                'message': message,
                'created_at': time.time(),
            }

        logger.info("[Share] Received share request %s from %s: introduce to %s",
                     share_id, from_name, other_name)

        if self.on_share_request:
            self.on_share_request(from_contact_id, share_id, other_name, message)

    def approve_share(self, share_id: str) -> bool:
        """User approves a share request."""
        with self._lock:
            req = self.incoming_requests.get(share_id)
        if not req:
            logger.info("[Share] No pending request with id %s", share_id)
            return False
        msg = f"INFRA:SHARE_APPROVE:{share_id}"
        ok = self.send_func(req['from_contact_id'], msg)
        if ok:
            logger.info("[Share] Approved share %s", share_id)
        else:
            logger.info("[Share] Failed to send approval for %s", share_id)
        return ok

    def deny_share(self, share_id: str) -> bool:
        """User denies a share request."""
        with self._lock:
            req = self.incoming_requests.pop(share_id, None)
        if not req:
            logger.info("[Share] No pending request with id %s", share_id)
            return False
        msg = f"INFRA:SHARE_DENY:{share_id}"
        ok = self.send_func(req['from_contact_id'], msg)
        if ok:
            logger.info("[Share] Denied share %s", share_id)
        return ok

    # ── Initiator handles approvals/denials from B or C ────────────────────

    def handle_share_approve(self, from_contact_id: int, payload: str):
        """
        Called when initiator A receives INFRA:SHARE_APPROVE:{shareId}.
        payload is everything after 'SHARE_APPROVE:'.
        """
        share_id = payload.strip()
        with self._lock:
            state = self.pending_shares.get(share_id)
            if not state:
                logger.info("[Share] Approve for unknown share %s", share_id)
                return
            if from_contact_id == state['contact1_id']:
                state['c1_approved'] = True
                logger.info("[Share] %s approved share %s", state['contact1_name'], share_id)
            elif from_contact_id == state['contact2_id']:
                state['c2_approved'] = True
                logger.info("[Share] %s approved share %s", state['contact2_name'], share_id)
            else:
                logger.info("[Share] Approve from unexpected contact %d for share %s",
                             from_contact_id, share_id)
                return
            both_approved = state['c1_approved'] and state['c2_approved']

        if both_approved:
            self._generate_and_send_keys(share_id)

    def handle_share_deny(self, from_contact_id: int, payload: str):
        """
        Called when initiator A receives INFRA:SHARE_DENY:{shareId}.
        payload is everything after 'SHARE_DENY:'.
        """
        share_id = payload.strip()
        with self._lock:
            state = self.pending_shares.pop(share_id, None)
        if not state:
            logger.info("[Share] Deny for unknown share %s", share_id)
            return

        if from_contact_id == state['contact1_id']:
            denier = state['contact1_name']
            other_id = state['contact2_id']
        elif from_contact_id == state['contact2_id']:
            denier = state['contact2_name']
            other_id = state['contact1_id']
        else:
            denier = f"id={from_contact_id}"
            other_id = None

        logger.info("[Share] %s denied share %s", denier, share_id)
        status_msg = f"Share {share_id} denied by {denier}"
        if self.on_share_status:
            self.on_share_status(status_msg)

        # Notify the other party that the share was cancelled
        if other_id is not None:
            self.send_func(other_id, f"INFRA:SHARE_DENY:{share_id}")

    def _generate_and_send_keys(self, share_id: str):
        """Both approved — generate bootstrap key and send SHARE_CONNECT to both."""
        with self._lock:
            state = self.pending_shares.pop(share_id, None)
        if not state:
            return

        bootstrap_key = secrets.token_bytes(FreedomCrypto.BOOTSTRAP_KEY_BYTES)
        key_b64 = base64.b64encode(bootstrap_key).decode()

        c1 = self.contacts_db.find_contact_by_id(state['contact1_id'])
        c2 = self.contacts_db.find_contact_by_id(state['contact2_id'])
        if not c1 or not c2:
            logger.info("[Share] Contact disappeared during share %s", share_id)
            return

        # c1 = listener (B), c2 = connector (C)
        # Listener B gets: shareId:key:C_name  (3 fields after shareId)
        # Connector C gets: shareId:key:ddns:port:B_name  (5 fields after shareId)
        c1_ddns = c1['ddns_names'].split(',')[0].strip()
        c1_port = c1['ports'].split(',')[0].strip()

        listener_payload = f"INFRA:SHARE_CONNECT:{share_id}:{key_b64}:{c2['name']}"
        connector_payload = f"INFRA:SHARE_CONNECT:{share_id}:{key_b64}:{c1_ddns}:{c1_port}:{c1['name']}"

        ok1 = self.send_func(c1['id'], listener_payload)
        ok2 = self.send_func(c2['id'], connector_payload)

        if ok1 and ok2:
            logger.info("[Share] SHARE_CONNECT sent for share %s: %s (listener) <-> %s (connector)",
                         share_id, c1['name'], c2['name'])
            if self.on_share_status:
                self.on_share_status(
                    f"Share {share_id} complete: sent connection info to {c1['name']} and {c2['name']}")
        else:
            logger.info("[Share] Failed to send SHARE_CONNECT for share %s (ok1=%s, ok2=%s)",
                         share_id, ok1, ok2)
            if self.on_share_status:
                self.on_share_status(f"Share {share_id} failed to deliver connection info")

    # ── Responder handles SHARE_CONNECT (B or C) ─────────────────────────

    def handle_share_connect(self, from_contact_id: int, payload: str):
        """
        Called when we receive INFRA:SHARE_CONNECT:{shareId}:{...}.
        payload is everything after 'SHARE_CONNECT:'.
        Listener (3 fields after shareId): shareId:key:name
        Connector (5 fields after shareId): shareId:key:ddns:port:name
        """
        parts = payload.split(':')
        if len(parts) < 3:
            logger.info("[Share] Malformed SHARE_CONNECT: %s", payload)
            return

        share_id = parts[0]
        key_b64 = parts[1]

        try:
            bootstrap_key = base64.b64decode(key_b64)
        except Exception:
            logger.info("[Share] Invalid bootstrap key in SHARE_CONNECT")
            return

        remaining = parts[2:]
        # Listener gets 1 field (name) -> len(remaining)==1
        # Connector gets 3 fields (ddns, port, name) -> len(remaining)==3
        if len(remaining) == 1:
            # Listener role: wait for incoming bootstrap connection
            other_name = remaining[0]
            self._become_listener(share_id, bootstrap_key, other_name,
                                  from_contact_id=from_contact_id)
        elif len(remaining) >= 3:
            # Connector role: connect to listener
            ddns = remaining[0]
            port_str = remaining[1]
            other_name = remaining[2]
            try:
                port = int(port_str)
            except ValueError:
                logger.info("[Share] Invalid port in SHARE_CONNECT: %s", port_str)
                return
            self._become_connector(share_id, bootstrap_key, ddns, port, other_name)
        else:
            logger.info("[Share] Unexpected field count in SHARE_CONNECT: %d", len(remaining))

    def _become_listener(self, share_id: str, bootstrap_key: bytes, other_name: str,
                         from_contact_id: int = 0):
        """Set up to receive an incoming bootstrap connection from the shared contact."""
        logger.info("[Share] Becoming listener for share %s, expecting %s", share_id, other_name)

        # Store the bootstrap key so the server accepts the incoming connection
        self.bootstrap_holder.set_bootstrap_key(bootstrap_key)

        key_b64 = base64.b64encode(bootstrap_key).decode()
        with self._lock:
            self.expected_bootstraps[key_b64] = other_name
            self._bootstrap_share_meta[share_id] = {
                'key_b64': key_b64,
                'from_contact_id': from_contact_id,
                'other_name': other_name,
            }

        # Start 30-second bootstrap timeout timer
        timer = threading.Timer(30.0, self._on_bootstrap_timeout, args=[share_id])
        timer.daemon = True
        with self._lock:
            self._bootstrap_timers[share_id] = timer
        timer.start()
        logger.info("[Share] Started 30s bootstrap timeout for share %s", share_id)

        # Set up the handshake completion callback
        def on_complete(contact):
            # Cancel the timeout timer since bootstrap succeeded
            with self._lock:
                t = self._bootstrap_timers.pop(share_id, None)
                self._bootstrap_share_meta.pop(share_id, None)
            if t is not None:
                t.cancel()
                logger.info("[Share] Cancelled bootstrap timeout for share %s (success)", share_id)

            contact_name = contact.get('name', other_name)
            logger.info("[Share] Bootstrap complete with %s via share %s", contact_name, share_id)

            # Generate and deliver our send key
            my_send_key = FreedomCrypto.generate_message_key()
            send_key_b64 = base64.b64encode(my_send_key).decode()

            contact['send_key_0'] = send_key_b64
            contact['active_send_key_idx'] = 0
            contact['send_key_created_at_0'] = int(time.time() * 1000)
            self.contacts_db.upsert_contact(contact)

            my_info = {
                'name': self.my_name or 'python-node',
                'ddns': self.my_ddns or 'localhost',
                'ports': self.my_ports or '22176',
            }
            success = ConnectionEngine.bootstrap_deliver_key(
                contact, my_send_key, bootstrap_key, my_info)
            if success:
                logger.info("[Share] Key exchange complete with %s", contact_name)
            else:
                logger.info("[Share] Failed to deliver key to %s", contact_name)
            self.bootstrap_holder.clear()

            with self._lock:
                self.expected_bootstraps.pop(key_b64, None)

            if self.on_share_status:
                status = f"Shared contact {contact_name} added" if success else \
                         f"Key exchange failed with {contact_name}"
                self.on_share_status(status)

        self.bootstrap_holder.on_handshake_complete = on_complete

        if self.on_share_status:
            self.on_share_status(f"Waiting for {other_name} to connect (share {share_id})...")

    def _on_bootstrap_timeout(self, share_id: str):
        """Called by threading.Timer after 30 seconds if bootstrap hasn't completed."""
        logger.info("[Share] Bootstrap timeout for share %s", share_id)

        with self._lock:
            self._bootstrap_timers.pop(share_id, None)
            meta = self._bootstrap_share_meta.pop(share_id, None)

        if not meta:
            logger.info("[Share] Timeout fired but no metadata for share %s", share_id)
            return

        # Clean up expected bootstrap state
        with self._lock:
            self.expected_bootstraps.pop(meta['key_b64'], None)

        # Clear the bootstrap holder since we're giving up on this bootstrap
        self.bootstrap_holder.clear()

        # Send SHARE_FAIL back to the contact who sent us the SHARE_CONNECT (the initiator A)
        from_contact_id = meta['from_contact_id']
        if from_contact_id:
            fail_msg = f"INFRA:SHARE_FAIL:{share_id}:timeout"
            ok = self.send_func(from_contact_id, fail_msg)
            if ok:
                logger.info("[Share] Sent SHARE_FAIL to contact %d for share %s",
                            from_contact_id, share_id)
            else:
                logger.info("[Share] Failed to send SHARE_FAIL to contact %d for share %s",
                            from_contact_id, share_id)

        if self.on_share_status:
            self.on_share_status(
                f"Sharing failed - Timeout waiting for {meta['other_name']} (share {share_id})")

    def handle_share_fail(self, from_contact_id: int, payload: str):
        """
        Called when we receive INFRA:SHARE_FAIL:{shareId}:{reason}.
        payload is everything after 'SHARE_FAIL:'.
        Cleans up any pending share state and notifies via callback.
        """
        parts = payload.split(':', 1)
        share_id = parts[0]
        reason = parts[1] if len(parts) > 1 else 'unknown'

        from_contact = self.contacts_db.find_contact_by_id(from_contact_id)
        from_name = from_contact['name'] if from_contact else f"id={from_contact_id}"

        logger.info("[Share] Received SHARE_FAIL from %s for share %s: %s",
                     from_name, share_id, reason)

        # Clean up any pending share state on the initiator side
        with self._lock:
            self.pending_shares.pop(share_id, None)
            self.incoming_requests.pop(share_id, None)
            # Also clean up timer/meta if we happen to have them
            t = self._bootstrap_timers.pop(share_id, None)
            meta = self._bootstrap_share_meta.pop(share_id, None)

        if t is not None:
            t.cancel()

        if meta:
            with self._lock:
                self.expected_bootstraps.pop(meta.get('key_b64', ''), None)

        if self.on_share_status:
            self.on_share_status(f"Sharing failed - Timeout (share {share_id} from {from_name})")

    def _become_connector(self, share_id: str, bootstrap_key: bytes,
                          ddns: str, port: int, other_name: str):
        """Connect to the listener and run the bootstrap key exchange."""
        logger.info("[Share] Becoming connector for share %s, connecting to %s at %s:%d",
                     share_id, other_name, ddns, port)

        if self.on_share_status:
            self.on_share_status(f"Connecting to {other_name} at {ddns}:{port} (share {share_id})...")

        def _do_connect():
            # Generate our 24KB send key
            my_send_key = FreedomCrypto.generate_message_key()
            send_key_b64 = base64.b64encode(my_send_key).decode()

            my_info = {
                'name': self.my_name or 'python-node',
                'ddns': self.my_ddns or 'localhost',
                'ports': self.my_ports or '22176',
            }

            # Send our key to the listener (same as QR scanner side)
            ok = ConnectionEngine.bootstrap_send_key(
                ddns, port, bootstrap_key, my_send_key, my_info)

            if not ok:
                logger.info("[Share] Bootstrap send failed to %s:%d", ddns, port)
                if self.on_share_status:
                    self.on_share_status(f"Failed to connect to {other_name} at {ddns}:{port}")
                return

            # Save the contact with our send key
            contact_data = {
                'name': other_name,
                'ddns_names': ddns,
                'ports': str(port),
                'send_key_0': send_key_b64,
                'active_send_key_idx': 0,
                'send_key_created_at_0': int(time.time() * 1000),
            }
            cid = self.contacts_db.upsert_contact(contact_data)
            saved = self.contacts_db.find_contact_by_id(cid)

            # Set up pending reverse connection for the listener's key delivery
            self.bootstrap_holder.pending_reverse_contact = saved
            self.bootstrap_holder.scanned_bootstrap_key = bootstrap_key

            logger.info("[Share] Contact %s saved (id=%d), waiting for key delivery",
                         other_name, cid)
            if self.on_share_status:
                self.on_share_status(
                    f"Connected to {other_name}, waiting for key exchange to complete...")

        threading.Thread(target=_do_connect, daemon=True).start()

    def is_expected_bootstrap(self, key_bytes: bytes) -> bool:
        """Check if this bootstrap key came from a contact share."""
        key_b64 = base64.b64encode(key_bytes).decode()
        with self._lock:
            return key_b64 in self.expected_bootstraps

    # ── Cleanup ───────────────────────────────────────────────────────────

    def _cleanup_loop(self):
        """Remove expired pending shares and incoming requests every 30 seconds."""
        while True:
            time.sleep(30)
            now = time.time()
            with self._lock:
                expired_shares = [
                    sid for sid, s in self.pending_shares.items()
                    if now - s['created_at'] > self.SHARE_TIMEOUT
                ]
                for sid in expired_shares:
                    del self.pending_shares[sid]
                    logger.info("[Share] Expired pending share %s", sid)

                expired_reqs = [
                    sid for sid, r in self.incoming_requests.items()
                    if now - r['created_at'] > self.SHARE_TIMEOUT
                ]
                for sid in expired_reqs:
                    del self.incoming_requests[sid]
                    logger.info("[Share] Expired incoming request %s", sid)

                expired_bootstraps = []
                for key_b64, name in self.expected_bootstraps.items():
                    expired_bootstraps.append(key_b64)
                # Don't expire bootstraps on timer — they get cleaned up on completion

    def get_pending_incoming(self) -> list:
        """Return list of pending incoming share requests for display."""
        with self._lock:
            return [
                {
                    'share_id': sid,
                    'from_name': r['from_name'],
                    'other_name': r['other_name'],
                    'message': r['message'],
                }
                for sid, r in self.incoming_requests.items()
            ]
