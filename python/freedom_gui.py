#!/usr/bin/env python3
"""Freedom Messaging – PyQt5 desktop GUI."""

import sys
import argparse

from PyQt5.QtWidgets import QApplication

from gui.main_window import MainWindow
from gui.styles import STYLESHEET


def main():
    parser = argparse.ArgumentParser(description='Freedom Desktop GUI')
    parser.add_argument('--port', type=int, default=22176,
                        help='TCP listen port (default 22176)')
    parser.add_argument('--db', default='freedom.db',
                        help='SQLite database file (default freedom.db)')
    args = parser.parse_args()

    app = QApplication(sys.argv)
    app.setApplicationName("Freedom")
    app.setStyleSheet(STYLESHEET)

    window = MainWindow(db_path=args.db, listen_port=args.port)
    window.show()

    sys.exit(app.exec_())


if __name__ == '__main__':
    main()
