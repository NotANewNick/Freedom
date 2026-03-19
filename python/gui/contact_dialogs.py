"""Dialogs for adding contacts — new bootstrap QR format."""

import base64
import json
import threading
import time

from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtWidgets import (
    QDialog, QLabel, QLineEdit, QMessageBox,
    QTextEdit, QVBoxLayout, QHBoxLayout, QPushButton, QTabWidget, QWidget,
)

from freedom_core import (
    BootstrapKeyHolder, ConnectionEngine, Database, FreedomCrypto,
)


class AddContactDialog(QDialog):
    """Add a contact by pasting QR JSON or entering fields manually.

    New QR format: {app:"freedom", ddns:"...", port:N, key:"<bootstrap_b64>"}
    No 'name' or 'ports' list — just ddns, port, ephemeral bootstrap key.
    """

    def __init__(self, db: Database = None,
                 bootstrap_holder: BootstrapKeyHolder = None,
                 config: dict = None, listen_port: int = 22176,
                 parent=None):
        super().__init__(parent)
        self.setWindowTitle("Add Contact")
        self.setMinimumWidth(450)
        self.result_data = None
        self.db = db
        self.bootstrap_holder = bootstrap_holder or BootstrapKeyHolder()
        self.config = config or {}
        self.listen_port = listen_port

        layout = QVBoxLayout(self)

        tabs = QTabWidget()
        layout.addWidget(tabs)

        # -- Tab 1: Paste JSON --
        json_tab = QWidget()
        jl = QVBoxLayout(json_tab)
        jl.addWidget(QLabel("Paste the full QR JSON from the remote side:"))
        self._json_edit = QTextEdit()
        self._json_edit.setPlaceholderText('{"app":"freedom","ddns":"...","port":22176,"key":"..."}')
        self._json_edit.setMaximumHeight(120)
        jl.addWidget(self._json_edit)
        tabs.addTab(json_tab, "Paste JSON")

        # -- Tab 2: Manual entry --
        manual_tab = QWidget()
        ml = QVBoxLayout(manual_tab)
        self._ddns_edit  = QLineEdit()
        self._ddns_edit.setPlaceholderText("DDNS hostname")
        self._port_edit  = QLineEdit()
        self._port_edit.setPlaceholderText("Port (e.g. 22176)")
        self._key_edit   = QLineEdit()
        self._key_edit.setPlaceholderText("Base64 bootstrap key from QR")
        for label, widget in [("DDNS:", self._ddns_edit),
                               ("Port:", self._port_edit),
                               ("Key:", self._key_edit)]:
            ml.addWidget(QLabel(label))
            ml.addWidget(widget)
        tabs.addTab(manual_tab, "Manual Entry")

        self._tabs = tabs

        # -- Status --
        self._status_label = QLabel("")
        layout.addWidget(self._status_label)

        # -- Buttons --
        btn_row = QHBoxLayout()
        save_btn = QPushButton("Connect & Exchange Keys")
        save_btn.clicked.connect(self._on_save)
        cancel_btn = QPushButton("Cancel")
        cancel_btn.setObjectName("dangerBtn")
        cancel_btn.clicked.connect(self.reject)
        btn_row.addStretch()
        btn_row.addWidget(cancel_btn)
        btn_row.addWidget(save_btn)
        layout.addLayout(btn_row)

    def _on_save(self):
        if self._tabs.currentIndex() == 0:
            self._parse_json()
        else:
            self._parse_manual()

    def _parse_json(self):
        text = self._json_edit.toPlainText().strip()
        if not text:
            QMessageBox.warning(self, "Error", "Paste the QR JSON first.")
            return
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            QMessageBox.warning(self, "Error", "Invalid JSON.")
            return
        if data.get('app') != 'freedom':
            QMessageBox.warning(self, "Error", 'Missing or wrong "app" field (expected "freedom").')
            return
        ddns = data.get('ddns', '')
        port = data.get('port', 0)
        key_b64 = data.get('key', '')
        if not ddns or not port or not key_b64:
            QMessageBox.warning(self, "Error", 'Missing ddns, port, or key field.')
            return
        try:
            bootstrap_key = base64.b64decode(key_b64)
        except Exception:
            QMessageBox.warning(self, "Error", "Invalid Base64 key.")
            return

        self._run_bootstrap_exchange(ddns, int(port), bootstrap_key)

    def _parse_manual(self):
        ddns = self._ddns_edit.text().strip()
        port_str = self._port_edit.text().strip()
        key_b64 = self._key_edit.text().strip()
        if not all([ddns, port_str, key_b64]):
            QMessageBox.warning(self, "Error", "All fields are required.")
            return
        try:
            port = int(port_str)
        except ValueError:
            QMessageBox.warning(self, "Error", "Port must be a number.")
            return
        try:
            bootstrap_key = base64.b64decode(key_b64)
        except Exception:
            QMessageBox.warning(self, "Error", "Invalid Base64 key.")
            return

        self._run_bootstrap_exchange(ddns, port, bootstrap_key)

    def _run_bootstrap_exchange(self, ddns: str, port: int, bootstrap_key: bytes):
        """Run the bootstrap key exchange (B's side) in a background thread."""
        self._status_label.setText("Sending key to remote...")

        def _do_exchange():
            my_send_key = FreedomCrypto.generate_message_key()
            send_key_b64 = base64.b64encode(my_send_key).decode()

            my_info = {
                'name': self.config.get('my_name', 'python-node'),
                'ddns': self.config.get('my_ddns', 'localhost'),
                'ports': self.config.get('my_ports', str(self.listen_port)),
            }

            ok = ConnectionEngine.bootstrap_send_key(
                ddns, port, bootstrap_key, my_send_key, my_info)

            if ok:
                contact_data = {
                    'name': ddns,
                    'ddns_names': ddns,
                    'ports': str(port),
                    'send_key_0': send_key_b64,
                    'active_send_key_idx': 0,
                    'send_key_created_at_0': int(time.time() * 1000),
                }
                if self.db:
                    cid = self.db.upsert_contact(contact_data)
                    saved = self.db.find_contact_by_id(cid)
                    # Set up pending reverse connection
                    self.bootstrap_holder.pending_reverse_contact = saved
                    self.bootstrap_holder.scanned_bootstrap_key = bootstrap_key
                    self.result_data = contact_data
                    QTimer.singleShot(0, lambda: self._on_exchange_ok(ddns))
                else:
                    self.result_data = contact_data
                    QTimer.singleShot(0, lambda: self._on_exchange_ok(ddns))
            else:
                QTimer.singleShot(0, lambda: self._on_exchange_fail())

        threading.Thread(target=_do_exchange, daemon=True).start()

    def _on_exchange_ok(self, ddns: str):
        self._status_label.setText(f"Key sent. Waiting for {ddns} to deliver their key...")
        # Accept the dialog — contact is saved, reverse connection is pending
        self.accept()

    def _on_exchange_fail(self):
        self._status_label.setText("Bootstrap exchange failed.")
        QMessageBox.warning(self, "Failed", "Could not connect to the remote side.")
