"""Dialog for sharing (introducing) two contacts to each other."""

from PyQt5.QtWidgets import (
    QComboBox, QDialog, QHBoxLayout, QLabel, QLineEdit, QMessageBox,
    QPushButton, QVBoxLayout,
)

from freedom_core import ContactShareEngine


class ShareContactDialog(QDialog):
    """Select two contacts and introduce them to each other."""

    def __init__(self, contacts: list, share_engine: ContactShareEngine,
                 parent=None):
        super().__init__(parent)
        self.setWindowTitle("Share Contact")
        self.setMinimumWidth(400)
        self.contacts = contacts
        self.share_engine = share_engine

        layout = QVBoxLayout(self)

        layout.addWidget(QLabel("Introduce two of your contacts to each other."))
        layout.addWidget(QLabel("Both must approve before the connection is made."))

        # Contact 1 selector
        layout.addWidget(QLabel("Contact 1:"))
        self._combo1 = QComboBox()
        for c in contacts:
            self._combo1.addItem(c['name'], c['id'])
        layout.addWidget(self._combo1)

        # Contact 2 selector
        layout.addWidget(QLabel("Contact 2:"))
        self._combo2 = QComboBox()
        for c in contacts:
            self._combo2.addItem(c['name'], c['id'])
        if len(contacts) >= 2:
            self._combo2.setCurrentIndex(1)
        layout.addWidget(self._combo2)

        # Optional message
        layout.addWidget(QLabel("Message (optional):"))
        self._message_edit = QLineEdit()
        self._message_edit.setPlaceholderText("e.g. You two should connect!")
        layout.addWidget(self._message_edit)

        # Buttons
        btn_row = QHBoxLayout()
        cancel_btn = QPushButton("Cancel")
        cancel_btn.setObjectName("dangerBtn")
        cancel_btn.clicked.connect(self.reject)
        share_btn = QPushButton("Send Share Request")
        share_btn.clicked.connect(self._on_share)
        btn_row.addStretch()
        btn_row.addWidget(cancel_btn)
        btn_row.addWidget(share_btn)
        layout.addLayout(btn_row)

    def _on_share(self):
        name1 = self._combo1.currentText()
        name2 = self._combo2.currentText()

        if name1 == name2:
            QMessageBox.warning(self, "Error", "Please select two different contacts.")
            return

        message = self._message_edit.text().strip()
        ok, msg = self.share_engine.initiate_share(name1, name2, message)

        if ok:
            QMessageBox.information(self, "Share Sent", msg)
            self.accept()
        else:
            QMessageBox.warning(self, "Share Failed", msg)
