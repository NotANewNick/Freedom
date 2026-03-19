"""Main window — 3-tab layout matching the Android app."""

import json
import os

from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import QMainWindow, QStatusBar, QTabWidget

from freedom_core import (
    BootstrapKeyHolder, ContactConnectionManager, ContactShareEngine,
    Database, FileTransferEngine, TcpServer,
)
from gui.messages_tab import MessagesTab
from gui.settings_tab import SettingsTab
from gui.tunnels_tab import TunnelsTab
from gui.signal_bridge import QtSignalBridge


CONFIG_FILE = 'freedom_config.json'


class MainWindow(QMainWindow):
    def __init__(self, db_path: str = 'freedom.db', listen_port: int = 22176):
        super().__init__()
        self.setWindowTitle("Freedom")
        self.setMinimumSize(900, 600)
        self.resize(1000, 700)

        self.listen_port      = listen_port
        self._config          = self._load_config()
        self.db               = Database(db_path)
        self.contact_manager  = ContactConnectionManager()
        self.file_transfer    = FileTransferEngine(self.db, self.contact_manager)
        self.bootstrap_holder = BootstrapKeyHolder()
        self.server           = None
        self.share_engine     = ContactShareEngine(
            send_func=self.contact_manager.send,
            contacts_db=self.db,
            bootstrap_holder=self.bootstrap_holder,
            my_ddns=self._config.get('my_ddns', ''),
            my_ports=self._config.get('my_ports', ''),
            my_name=self._config.get('my_name', 'python-node'),
        )

        # Signal bridge
        self.bridge = QtSignalBridge()
        self.contact_manager.on_connection_changed = self.bridge.on_connection

        # Tabs
        self.tabs = QTabWidget()
        self.setCentralWidget(self.tabs)

        self.messages_tab = MessagesTab(
            self.db, self.contact_manager, self.file_transfer, self._config,
            bootstrap_holder=self.bootstrap_holder,
            share_engine=self.share_engine,
        )
        self.settings_tab = SettingsTab(
            self._config, self._save_config, self._restart_server,
            db=self.db, bootstrap_holder=self.bootstrap_holder,
            start_server_fn=self._start_server,
            stop_server_fn=self._stop_server,
        )
        self.tunnels_tab  = TunnelsTab(self._config, self._save_config)

        self.tabs.addTab(self.messages_tab, "Messages")
        self.tabs.addTab(self.settings_tab, "Settings")
        self.tabs.addTab(self.tunnels_tab,  "Tunnels")

        # Wire signals
        self.bridge.message_received.connect(self.messages_tab.on_message_received)
        self.bridge.connection_changed.connect(self.messages_tab.on_connection_changed)
        self.bridge.server_log.connect(self.settings_tab.append_log)
        self.bridge.share_request_received.connect(self.messages_tab.on_share_request_received)
        self.bridge.share_status_update.connect(self.messages_tab.on_share_status_update)

        # Wire share engine callbacks to bridge
        self.share_engine.on_share_request = self.bridge.on_share_request
        self.share_engine.on_share_status = self.bridge.on_share_status

        # Status bar
        self.status = QStatusBar()
        self.setStatusBar(self.status)

        # Start server
        self._start_server(self.listen_port)
        self.settings_tab._update_server_status(True, self.listen_port)

    # ── Config ────────────────────────────────────────────────────────────

    def _load_config(self) -> dict:
        if os.path.exists(CONFIG_FILE):
            with open(CONFIG_FILE) as f:
                return json.load(f)
        return {
            'my_name':  'python-node',
            'my_ddns':  'localhost',
            'my_ports': str(self.listen_port),
            'tunnels':  [],
        }

    def _save_config(self):
        with open(CONFIG_FILE, 'w') as f:
            json.dump(self._config, f, indent=2)

    # ── Server ────────────────────────────────────────────────────────────

    def _start_server(self, port: int):
        self.server = TcpServer(
            port             = port,
            db               = self.db,
            cm               = self.contact_manager,
            ft               = self.file_transfer,
            bootstrap_holder = self.bootstrap_holder,
            my_ddns          = self._config.get('my_ddns', ''),
            my_ports         = self._config.get('my_ports', ''),
            on_message       = self.bridge.on_message,
            share_engine     = self.share_engine,
        )
        self.server.on_server_log = self.bridge.on_log
        self.server.start()
        self.status.showMessage(f"Server listening on port {port}")

    def _stop_server(self):
        if self.server:
            self.server.stop()
            self.server = None
        self.status.showMessage("Server stopped")

    def _restart_server(self, port: int):
        self._stop_server()
        self.listen_port = port
        self._start_server(port)

    # ── Cleanup ───────────────────────────────────────────────────────────

    def closeEvent(self, event):
        if self.server:
            self.server.stop()
        self._save_config()
        event.accept()
