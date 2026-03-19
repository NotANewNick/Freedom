"""Thread-safe bridge: backend threads -> Qt signals -> GUI main thread."""

from PyQt5.QtCore import QObject, pyqtSignal


class QtSignalBridge(QObject):
    """Emits Qt signals from any thread; slots run on the main thread."""

    message_received = pyqtSignal(str, str, int)      # sender, content, contact_id
    connection_changed = pyqtSignal(int, str)          # contact_id, state string
    server_log = pyqtSignal(str)                       # log line
    file_progress = pyqtSignal(int, int, int)          # contact_id, current, total
    share_request_received = pyqtSignal(int, str, str, str)  # from_cid, share_id, other_name, message
    share_status_update = pyqtSignal(str)              # status message

    def on_message(self, sender: str, content: str, contact_id: int):
        self.message_received.emit(sender, content, contact_id)

    def on_connection(self, contact_id: int, state):
        self.connection_changed.emit(contact_id, state.value)

    def on_log(self, text: str):
        self.server_log.emit(text)

    def on_share_request(self, from_contact_id: int, share_id: str,
                         other_name: str, message: str):
        self.share_request_received.emit(from_contact_id, share_id, other_name, message)

    def on_share_status(self, message: str):
        self.share_status_update.emit(message)
