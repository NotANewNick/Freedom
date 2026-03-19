"""Tunnels tab — simplified manual tunnel entries for desktop."""

from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import (
    QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit,
    QMessageBox, QPushButton, QTableWidget, QTableWidgetItem,
    QVBoxLayout, QWidget,
)


class TunnelsTab(QWidget):
    def __init__(self, config: dict, save_config_fn, parent=None):
        super().__init__(parent)
        self.config       = config
        self._save_config = save_config_fn

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)

        info = QLabel("Manual tunnel entries (for desktop testing, no VPN needed).\n"
                       "These ports are included in your QR code for contacts to reach you.")
        info.setWordWrap(True)
        layout.addWidget(info)

        # ── Table ──
        self._table = QTableWidget(0, 3)
        self._table.setHorizontalHeaderLabels(["Name", "Host", "Port"])
        self._table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self._table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self._table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        self._table.setSelectionBehavior(QTableWidget.SelectRows)
        layout.addWidget(self._table)

        # ── Add row ──
        add_group = QGroupBox("Add Tunnel")
        add_layout = QHBoxLayout(add_group)
        self._name_edit = QLineEdit()
        self._name_edit.setPlaceholderText("Name")
        add_layout.addWidget(self._name_edit)
        self._host_edit = QLineEdit()
        self._host_edit.setPlaceholderText("Host")
        add_layout.addWidget(self._host_edit)
        self._port_edit = QLineEdit()
        self._port_edit.setPlaceholderText("Port")
        self._port_edit.setMaximumWidth(100)
        add_layout.addWidget(self._port_edit)
        add_btn = QPushButton("Add")
        add_btn.clicked.connect(self._add_tunnel)
        add_layout.addWidget(add_btn)
        layout.addWidget(add_group)

        # ── Delete ──
        btn_row = QHBoxLayout()
        btn_row.addStretch()
        del_btn = QPushButton("Delete Selected")
        del_btn.setObjectName("dangerBtn")
        del_btn.clicked.connect(self._delete_selected)
        btn_row.addWidget(del_btn)
        layout.addLayout(btn_row)

        layout.addStretch()
        self._load_tunnels()

    def _load_tunnels(self):
        tunnels = self.config.get('tunnels', [])
        self._table.setRowCount(0)
        for t in tunnels:
            self._insert_row(t.get('name', ''), t.get('host', ''), str(t.get('port', '')))

    def _insert_row(self, name: str, host: str, port: str):
        row = self._table.rowCount()
        self._table.insertRow(row)
        self._table.setItem(row, 0, QTableWidgetItem(name))
        self._table.setItem(row, 1, QTableWidgetItem(host))
        self._table.setItem(row, 2, QTableWidgetItem(port))

    def _add_tunnel(self):
        name = self._name_edit.text().strip()
        host = self._host_edit.text().strip()
        port = self._port_edit.text().strip()
        if not all([name, host, port]):
            QMessageBox.warning(self, "Error", "All fields are required.")
            return
        try:
            port_int = int(port)
        except ValueError:
            QMessageBox.warning(self, "Error", "Port must be a number.")
            return

        tunnels = self.config.get('tunnels', [])
        tunnels.append({'name': name, 'host': host, 'port': port_int})
        self.config['tunnels'] = tunnels
        self._save_config()
        self._insert_row(name, host, port)

        # Auto-add port to my_ports
        existing = set(self.config.get('my_ports', '').split(','))
        existing.discard('')
        existing.add(port)
        self.config['my_ports'] = ','.join(sorted(existing))
        self._save_config()

        self._name_edit.clear()
        self._host_edit.clear()
        self._port_edit.clear()

    def _delete_selected(self):
        rows = sorted(set(i.row() for i in self._table.selectedItems()), reverse=True)
        if not rows:
            return
        tunnels = self.config.get('tunnels', [])
        for row in rows:
            if row < len(tunnels):
                tunnels.pop(row)
            self._table.removeRow(row)
        self.config['tunnels'] = tunnels
        self._save_config()
