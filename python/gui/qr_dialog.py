"""QR code rendering for PyQt5."""

import io
import qrcode
from PIL import Image
from PyQt5.QtCore import Qt
from PyQt5.QtGui import QImage, QPixmap
from PyQt5.QtWidgets import QDialog, QLabel, QScrollArea, QVBoxLayout


def render_qr_pixmap(data: str, min_module_px: int = 8) -> QPixmap:
    """Generate a QR code as a QPixmap with large enough modules to scan.

    Each QR module (black/white square) will be at least min_module_px pixels,
    ensuring the camera can resolve individual modules even for dense codes.
    """
    qr = qrcode.QRCode(
        error_correction=qrcode.constants.ERROR_CORRECT_L,
        box_size=min_module_px,
        border=4,
    )
    qr.add_data(data)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    # No resize — keep native resolution so modules stay sharp

    buf = io.BytesIO()
    img.save(buf, format='PNG')
    buf.seek(0)
    qimage = QImage()
    qimage.loadFromData(buf.read())
    return QPixmap.fromImage(qimage)


class QrDialog(QDialog):
    """Dialog showing a scannable QR code. Scrollable if the QR is large."""

    def __init__(self, qr_data: str, title: str = "Scan this QR code", parent=None):
        super().__init__(parent)
        self.setWindowTitle(title)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 20, 20, 20)

        heading = QLabel(title)
        heading.setObjectName("heading")
        heading.setAlignment(Qt.AlignCenter)
        layout.addWidget(heading)

        pixmap = render_qr_pixmap(qr_data)

        qr_label = QLabel()
        qr_label.setAlignment(Qt.AlignCenter)
        qr_label.setPixmap(pixmap)

        scroll = QScrollArea()
        scroll.setWidget(qr_label)
        scroll.setWidgetResizable(False)
        scroll.setAlignment(Qt.AlignCenter)
        scroll.setStyleSheet("QScrollArea { border: none; background: white; }")
        layout.addWidget(scroll)

        hint = QLabel("Point the Android app's camera at this QR code")
        hint.setObjectName("subheading")
        hint.setAlignment(Qt.AlignCenter)
        layout.addWidget(hint)

        # Size dialog to fit QR or screen, whichever is smaller
        qr_w = pixmap.width() + 60
        qr_h = pixmap.height() + 120
        self.resize(min(qr_w, 1200), min(qr_h, 900))
