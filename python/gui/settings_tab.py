"""Settings tab — port config, identity, bootstrap key generation, QR display."""

import base64
import json
import threading
import time

from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtWidgets import (
    QGroupBox, QHBoxLayout, QLabel, QLineEdit, QMessageBox,
    QPushButton, QVBoxLayout, QWidget,
)

from freedom_core import (
    BootstrapKeyHolder, ConnectionEngine, Database, FreedomCrypto,
)
from gui.qr_dialog import QrDialog


class SettingsTab(QWidget):
    def __init__(self, config: dict, save_config_fn, restart_server_fn,
                 db: Database = None, bootstrap_holder: BootstrapKeyHolder = None,
                 start_server_fn=None, stop_server_fn=None,
                 parent=None):
        super().__init__(parent)
        self.config            = config
        self._save_config      = save_config_fn
        self._restart_server   = restart_server_fn
        self._start_server_fn  = start_server_fn
        self._stop_server_fn   = stop_server_fn
        self.db                = db
        self.bootstrap_holder  = bootstrap_holder or BootstrapKeyHolder()

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(12)

        # ── Server section ──
        srv_group = QGroupBox("Server")
        srv_layout = QVBoxLayout(srv_group)

        port_row = QHBoxLayout()
        port_row.addWidget(QLabel("Listen Port:"))
        self._port_edit = QLineEdit(self.config.get('my_ports', '22176'))
        self._port_edit.setMaximumWidth(120)
        port_row.addWidget(self._port_edit)
        apply_btn = QPushButton("Apply")
        apply_btn.clicked.connect(self._apply_port)
        port_row.addWidget(apply_btn)
        port_row.addStretch()
        srv_layout.addLayout(port_row)

        # Start / Stop buttons
        srv_btn_row = QHBoxLayout()
        self._btn_start_server = QPushButton("Start Server")
        self._btn_start_server.clicked.connect(self._start_server)
        srv_btn_row.addWidget(self._btn_start_server)
        self._btn_stop_server = QPushButton("Stop Server")
        self._btn_stop_server.clicked.connect(self._stop_server)
        srv_btn_row.addWidget(self._btn_stop_server)
        srv_layout.addLayout(srv_btn_row)

        self._server_status = QLabel("Server: stopped")
        self._server_status.setStyleSheet("color: #888888;")
        srv_layout.addWidget(self._server_status)
        layout.addWidget(srv_group)

        # ── Identity section ──
        id_group = QGroupBox("My Identity")
        id_layout = QVBoxLayout(id_group)

        for label_text, key, placeholder in [
            ("Name:", 'my_name', 'python-node'),
            ("DDNS Hostnames:", 'my_ddns', 'localhost'),
            ("Ports:", 'my_ports', '22176'),
        ]:
            row = QHBoxLayout()
            lbl = QLabel(label_text)
            lbl.setMinimumWidth(120)
            row.addWidget(lbl)
            edit = QLineEdit(str(self.config.get(key, placeholder)))
            edit.setPlaceholderText(placeholder)
            edit.editingFinished.connect(lambda k=key, e=edit: self._update_config(k, e.text()))
            setattr(self, f'_edit_{key}', edit)
            row.addWidget(edit)
            id_layout.addLayout(row)

        layout.addWidget(id_group)

        # ── Bootstrap / QR section ──
        key_group = QGroupBox("Bootstrap Key & QR")
        key_layout = QVBoxLayout(key_group)

        self._key_status = QLabel("No active bootstrap key.")
        key_layout.addWidget(self._key_status)

        btn_row = QHBoxLayout()
        qr_btn = QPushButton("Show QR Code (Generate & Wait)")
        qr_btn.clicked.connect(self._show_qr)
        btn_row.addWidget(qr_btn)
        btn_row.addStretch()
        key_layout.addLayout(btn_row)

        layout.addWidget(key_group)

        # ── Server log ──
        log_group = QGroupBox("Server Log")
        log_layout = QVBoxLayout(log_group)
        from PyQt5.QtWidgets import QTextEdit
        self._log_view = QTextEdit()
        self._log_view.setReadOnly(True)
        self._log_view.setMaximumHeight(150)
        log_layout.addWidget(self._log_view)
        layout.addWidget(log_group)

        layout.addStretch()

    def _apply_port(self):
        port_str = self._port_edit.text().strip()
        try:
            port = int(port_str)
            if port < 1 or port > 65535:
                raise ValueError
        except ValueError:
            QMessageBox.warning(self, "Error", "Enter a valid port (1-65535).")
            return
        self.config['my_ports'] = port_str
        self._save_config()
        self._restart_server(port)
        self._update_server_status(True, port)

    def _start_server(self):
        port_str = self._port_edit.text().strip()
        try:
            port = int(port_str)
        except ValueError:
            port = 22176
        if self._start_server_fn:
            self._start_server_fn(port)
        self._update_server_status(True, port)

    def _stop_server(self):
        if self._stop_server_fn:
            self._stop_server_fn()
        self._update_server_status(False)

    def _update_server_status(self, running: bool, port: int = 0):
        if running:
            self._server_status.setText(f"Server: running on port {port}")
            self._server_status.setStyleSheet("color: #4CAF50;")
            self._btn_start_server.setEnabled(False)
            self._btn_stop_server.setEnabled(True)
        else:
            self._server_status.setText("Server: stopped")
            self._server_status.setStyleSheet("color: #888888;")
            self._btn_start_server.setEnabled(True)
            self._btn_stop_server.setEnabled(False)

    def _update_config(self, key: str, value: str):
        self.config[key] = value.strip()
        self._save_config()

    def _show_qr(self):
        """Generate ephemeral bootstrap key, show QR, wait for connection."""
        bootstrap_key = FreedomCrypto.generate_bootstrap_key()
        self.bootstrap_holder.set_bootstrap_key(bootstrap_key)

        port = int(self.config.get('my_ports', '22176').split(',')[0])
        ddns = self.config.get('my_ddns', 'localhost').split(',')[0].strip()
        payload = json.dumps({
            'app':  'freedom',
            'ddns': ddns,
            'port': port,
            'key':  base64.b64encode(bootstrap_key).decode(),
        })

        self._key_status.setText("Waiting for scanner to connect...")

        # Set up callback
        def on_complete(contact):
            # Runs in server thread — schedule UI update + key delivery
            QTimer.singleShot(0, lambda: self._on_bootstrap_complete(contact, bootstrap_key))

        self.bootstrap_holder.on_handshake_complete = on_complete

        dlg = QrDialog(payload, "Freedom QR — Waiting for connection...", self)
        dlg.exec_()

        # If dialog closed without completion, clear bootstrap state
        if self.bootstrap_holder.get_bootstrap_key() is not None:
            self.bootstrap_holder.clear()
            self._key_status.setText("QR cancelled.")

    def _on_bootstrap_complete(self, contact: dict, bootstrap_key: bytes):
        """Called on main thread after B scans and sends key."""
        self._key_status.setText(f"Contact connected: {contact.get('name', '?')} — delivering key...")

        def _deliver():
            my_send_key = FreedomCrypto.generate_message_key()
            send_key_b64 = base64.b64encode(my_send_key).decode()

            # Save our send key
            contact['send_key_0'] = send_key_b64
            contact['active_send_key_idx'] = 0
            contact['send_key_created_at_0'] = int(time.time() * 1000)
            if self.db:
                self.db.upsert_contact(contact)

            my_info = {
                'name': self.config.get('my_name', 'python-node'),
                'ddns': self.config.get('my_ddns', 'localhost'),
                'ports': self.config.get('my_ports', '22176'),
            }
            ok = ConnectionEngine.bootstrap_deliver_key(
                contact, my_send_key, bootstrap_key, my_info)

            self.bootstrap_holder.clear()
            QTimer.singleShot(0, lambda: self._key_status.setText(
                f"Key exchange {'complete' if ok else 'FAILED'} with {contact.get('name', '?')}"
            ))

        threading.Thread(target=_deliver, daemon=True).start()

    def append_log(self, text: str):
        self._log_view.append(text)
