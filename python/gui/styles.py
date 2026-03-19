"""Dark Material-inspired stylesheet for Freedom desktop."""

# ── Semantic color constants ──────────────────────────────────────────────
COLOR_PRIMARY       = "#1565C0"
COLOR_PRIMARY_HOVER = "#1976D2"
COLOR_PRIMARY_DARK  = "#0D47A1"
COLOR_BACKGROUND    = "#121212"
COLOR_SURFACE       = "#1E1E1E"
COLOR_TEXT_PRIMARY   = "#E0E0E0"
COLOR_TEXT_SECONDARY = "#666"
COLOR_TEXT_TERTIARY  = "#888888"
COLOR_HEALTH_GREEN  = "#4CAF50"
COLOR_HEALTH_AMBER  = "#FF9800"
COLOR_BORDER        = "#333"
COLOR_TAB_BG        = "#1A237E"
COLOR_TAB_ACTIVE    = "#283593"

STYLESHEET = """
QMainWindow {
    background-color: #121212;
}
QTabWidget::pane {
    border: 1px solid #333;
    background-color: #121212;
}
QTabBar::tab {
    background: #1A237E;
    color: #B0BEC5;
    padding: 10px 20px;
    border: none;
    min-width: 120px;
    font-size: 13px;
}
QTabBar::tab:selected {
    background: #283593;
    color: white;
    font-weight: bold;
}
QTabBar::tab:hover {
    background: #1E3AC4;
}
QPushButton {
    background-color: #1565C0;
    color: white;
    border: none;
    border-radius: 4px;
    padding: 8px 16px;
    font-size: 13px;
}
QPushButton:hover {
    background-color: #1976D2;
}
QPushButton:pressed {
    background-color: #0D47A1;
}
QPushButton:disabled {
    background-color: #333;
    color: #666;
}
QPushButton#dangerBtn {
    background-color: #B71C1C;
}
QPushButton#dangerBtn:hover {
    background-color: #D32F2F;
}
QLineEdit, QTextEdit, QPlainTextEdit {
    background-color: #1E1E1E;
    color: white;
    border: 1px solid #444;
    border-radius: 4px;
    padding: 6px;
    font-size: 13px;
    selection-background-color: #1565C0;
}
QLineEdit:focus, QTextEdit:focus {
    border: 1px solid #1565C0;
}
QListWidget {
    background-color: #1E1E1E;
    color: white;
    border: 1px solid #333;
    border-radius: 4px;
    font-size: 13px;
    outline: none;
}
QListWidget::item {
    padding: 8px;
    border-bottom: 1px solid #2A2A2A;
}
QListWidget::item:selected {
    background-color: #1A237E;
}
QListWidget::item:hover {
    background-color: #252525;
}
QLabel {
    color: #E0E0E0;
    font-size: 13px;
}
QLabel#heading {
    color: white;
    font-size: 16px;
    font-weight: bold;
}
QLabel#subheading {
    color: #90CAF9;
    font-size: 11px;
}
QGroupBox {
    color: #90CAF9;
    border: 1px solid #333;
    border-radius: 4px;
    margin-top: 12px;
    padding-top: 16px;
    font-size: 13px;
}
QGroupBox::title {
    subcontrol-origin: margin;
    left: 12px;
    padding: 0 4px;
}
QScrollBar:vertical {
    background: #1E1E1E;
    width: 8px;
    border: none;
}
QScrollBar::handle:vertical {
    background: #444;
    border-radius: 4px;
    min-height: 30px;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0;
}
QStatusBar {
    background-color: #1A237E;
    color: white;
    font-size: 12px;
}
QDialog {
    background-color: #121212;
}
QMessageBox {
    background-color: #1E1E1E;
}
QSplitter::handle {
    background-color: #333;
}
"""
