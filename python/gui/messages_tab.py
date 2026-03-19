"""Messages tab — contact sidebar + chat area + send bar."""

import threading
from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtGui import QColor, QFont
from PyQt5.QtWidgets import (
    QFileDialog, QHBoxLayout, QLabel, QLineEdit, QListWidget,
    QListWidgetItem, QPushButton, QSplitter, QTextBrowser, QVBoxLayout,
    QWidget,
)

from freedom_core import (
    BootstrapKeyHolder, ConnectionEngine, ConnectionState,
    ContactConnectionManager, ContactShareEngine, Database, FileTransferEngine,
)
from gui.contact_dialogs import AddContactDialog
from gui.share_dialog import ShareContactDialog


class MessagesTab(QWidget):
    def __init__(self, db: Database, cm: ContactConnectionManager,
                 ft: FileTransferEngine, config: dict,
                 bootstrap_holder: BootstrapKeyHolder = None,
                 share_engine: ContactShareEngine = None,
                 parent=None):
        super().__init__(parent)
        self.db     = db
        self.cm     = cm
        self.ft     = ft
        self.config = config
        self.bootstrap_holder = bootstrap_holder or BootstrapKeyHolder()
        self.share_engine = share_engine
        self._selected_cid = None

        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        splitter = QSplitter(Qt.Horizontal)
        layout.addWidget(splitter)

        # ── Left: contact sidebar ──
        left = QWidget()
        left_layout = QVBoxLayout(left)
        left_layout.setContentsMargins(8, 8, 4, 8)

        top_row = QHBoxLayout()
        lbl = QLabel("Contacts")
        lbl.setObjectName("heading")
        top_row.addWidget(lbl)
        top_row.addStretch()
        add_btn = QPushButton("+")
        add_btn.setFixedSize(32, 32)
        add_btn.clicked.connect(self._add_contact)
        top_row.addWidget(add_btn)
        left_layout.addLayout(top_row)

        self._contact_list = QListWidget()
        self._contact_list.currentItemChanged.connect(self._on_contact_selected)
        left_layout.addWidget(self._contact_list)

        connect_btn = QPushButton("Connect")
        connect_btn.clicked.connect(self._connect_to_selected)
        left_layout.addWidget(connect_btn)

        share_btn = QPushButton("Share Contact")
        share_btn.clicked.connect(self._share_contact)
        left_layout.addWidget(share_btn)

        splitter.addWidget(left)

        # ── Right: chat area ──
        right = QWidget()
        right_layout = QVBoxLayout(right)
        right_layout.setContentsMargins(4, 8, 8, 8)

        self._chat_header = QLabel("Select a contact")
        self._chat_header.setObjectName("heading")
        right_layout.addWidget(self._chat_header)

        self._chat_view = QTextBrowser()
        self._chat_view.setOpenExternalLinks(False)
        self._chat_view.setFont(QFont("sans-serif", 12))
        right_layout.addWidget(self._chat_view)

        send_row = QHBoxLayout()
        self._msg_input = QLineEdit()
        self._msg_input.setPlaceholderText("Type a message...")
        self._msg_input.returnPressed.connect(self._send_message)
        send_row.addWidget(self._msg_input)

        attach_btn = QPushButton("Attach")
        attach_btn.clicked.connect(self._attach_file)
        send_row.addWidget(attach_btn)

        send_btn = QPushButton("Send")
        send_btn.clicked.connect(self._send_message)
        send_row.addWidget(send_btn)
        right_layout.addLayout(send_row)

        splitter.addWidget(right)
        splitter.setSizes([220, 580])

        self._refresh_contacts()

    # ── Contact list ──────────────────────────────────────────────────────

    def _refresh_contacts(self):
        self._contact_list.clear()
        contacts = self.db.get_all_contacts()
        for c in contacts:
            state = self.cm.get_state(c['id'])
            dot = self._state_dot(state)
            item = QListWidgetItem(f"{dot}  {c['name']}")
            item.setData(Qt.UserRole, c['id'])
            if state == ConnectionState.CONNECTED:
                item.setForeground(QColor("#4CAF50"))
            elif state == ConnectionState.DEGRADED:
                item.setForeground(QColor("#FF9800"))
            self._contact_list.addItem(item)

    def _state_dot(self, state: ConnectionState) -> str:
        if state == ConnectionState.CONNECTED:
            return "[+]"
        if state == ConnectionState.DEGRADED:
            return "[~]"
        return "[ ]"

    def _on_contact_selected(self, current, previous):
        if not current:
            return
        cid = current.data(Qt.UserRole)
        self._selected_cid = cid
        contact = self.db.find_contact_by_id(cid)
        if contact:
            state = self.cm.get_state(cid)
            self._chat_header.setText(f"{contact['name']}  ({state.value})")
            self._load_messages(cid)

    def _load_messages(self, cid: int):
        self._chat_view.clear()
        msgs = self.db.get_messages(cid)
        html_parts = []
        for m in msgs:
            ts = m.get('timestamp', '')
            content = _escape_html(m.get('content', ''))
            is_file = 'FILE' in (m.get('message_type') or '')
            prefix = "[file] " if is_file else ""

            if m['direction'] == 'SENT':
                html_parts.append(
                    f'<div style="text-align:right; margin:4px 0;">'
                    f'<span style="background:#1565C0; color:white; padding:6px 10px; '
                    f'border-radius:12px; display:inline-block; max-width:70%;">'
                    f'{prefix}{content}</span>'
                    f'<br><span style="color:#666; font-size:10px;">{ts}</span></div>'
                )
            else:
                html_parts.append(
                    f'<div style="text-align:left; margin:4px 0;">'
                    f'<span style="background:#333; color:white; padding:6px 10px; '
                    f'border-radius:12px; display:inline-block; max-width:70%;">'
                    f'{prefix}{content}</span>'
                    f'<br><span style="color:#666; font-size:10px;">{ts}</span></div>'
                )
        self._chat_view.setHtml(''.join(html_parts))
        sb = self._chat_view.verticalScrollBar()
        sb.setValue(sb.maximum())

    # ── Actions ───────────────────────────────────────────────────────────

    def _send_message(self):
        if not self._selected_cid:
            return
        text = self._msg_input.text().strip()
        if not text:
            return
        ok = self.cm.send(self._selected_cid, text)
        if ok:
            self.db.insert_message(self._selected_cid, text, 'me', 'TEXT', 'SENT')
            self._msg_input.clear()
            self._load_messages(self._selected_cid)
        else:
            contact = self.db.find_contact_by_id(self._selected_cid)
            name = contact['name'] if contact else '?'
            self._chat_header.setText(f"{name}  (NOT CONNECTED - use Connect)")

    def _connect_to_selected(self):
        if not self._selected_cid:
            return
        contact = self.db.find_contact_by_id(self._selected_cid)
        if not contact:
            return
        self._chat_header.setText(f"{contact['name']}  (connecting...)")

        def _do_connect():
            engine = ConnectionEngine(
                contact, self.db, self.cm, self.ft,
                self.config.get('my_ddns', ''),
                self.config.get('my_ports', ''),
                on_message=self._on_incoming_message_from_engine,
                share_engine=self.share_engine,
            )
            ok, msg = engine.connect()
            QTimer.singleShot(0, lambda: self._on_connect_result(ok, msg))

        threading.Thread(target=_do_connect, daemon=True).start()

    def _on_connect_result(self, ok: bool, msg: str):
        self._refresh_contacts()
        if self._selected_cid:
            contact = self.db.find_contact_by_id(self._selected_cid)
            if contact:
                state = self.cm.get_state(self._selected_cid)
                self._chat_header.setText(f"{contact['name']}  ({state.value})")

    def _attach_file(self):
        if not self._selected_cid:
            return
        path, _ = QFileDialog.getOpenFileName(self, "Select file to send")
        if not path:
            return
        contact = self.db.find_contact_by_id(self._selected_cid)
        if not contact:
            return

        def _do_send():
            self.ft.send_file(self._selected_cid, path)
            QTimer.singleShot(0, lambda: self._load_messages(self._selected_cid))

        threading.Thread(target=_do_send, daemon=True).start()

    def _add_contact(self):
        dlg = AddContactDialog(
            db=self.db,
            bootstrap_holder=self.bootstrap_holder,
            config=self.config,
            parent=self,
        )
        if dlg.exec_() and dlg.result_data:
            self._refresh_contacts()

    # ── Incoming message handler (called from signal bridge) ──────────────

    def on_message_received(self, sender: str, content: str, contact_id: int):
        if contact_id == self._selected_cid:
            self._load_messages(contact_id)
        self._refresh_contacts()

    def on_connection_changed(self, contact_id: int, state_str: str):
        self._refresh_contacts()
        if contact_id == self._selected_cid:
            contact = self.db.find_contact_by_id(contact_id)
            if contact:
                self._chat_header.setText(f"{contact['name']}  ({state_str})")

    def _share_contact(self):
        """Open the share contact dialog to introduce two contacts."""
        if not self.share_engine:
            return
        contacts = self.db.get_all_contacts()
        if len(contacts) < 2:
            from PyQt5.QtWidgets import QMessageBox
            QMessageBox.information(self, "Share", "You need at least two contacts to share.")
            return
        dlg = ShareContactDialog(
            contacts=contacts,
            share_engine=self.share_engine,
            parent=self,
        )
        dlg.exec_()

    def on_share_request_received(self, from_cid: int, share_id: str,
                                  other_name: str, message: str):
        """Called from signal bridge when an incoming share request arrives."""
        from PyQt5.QtWidgets import QMessageBox
        from_contact = self.db.find_contact_by_id(from_cid)
        from_name = from_contact['name'] if from_contact else f"id={from_cid}"
        msg_text = f"{from_name} wants to introduce you to {other_name}."
        if message:
            msg_text += f"\n\nMessage: {message}"
        msg_text += "\n\nAccept?"
        reply = QMessageBox.question(
            self, "Contact Share Request", msg_text,
            QMessageBox.Yes | QMessageBox.No, QMessageBox.Yes,
        )
        if self.share_engine:
            if reply == QMessageBox.Yes:
                self.share_engine.approve_share(share_id)
            else:
                self.share_engine.deny_share(share_id)

    def on_share_status_update(self, message: str):
        """Called from signal bridge when a share status update arrives."""
        self._chat_header.setText(message)
        # Refresh contacts in case a new one was added
        self._refresh_contacts()

    def _on_incoming_message_from_engine(self, sender, content, cid):
        """Called from backend thread — schedule UI update on main thread."""
        QTimer.singleShot(0, lambda: self.on_message_received(sender, content, cid))


def _escape_html(text: str) -> str:
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
