#!/usr/bin/env python3
"""
Freedom Messaging – Python replica for desktop testing.

This file is now a thin wrapper that delegates to freedom_cli.py.
All protocol implementation lives in freedom_core.py.

Usage (interactive CLI):
    python freedom.py
    python freedom.py --port 22176 --db freedom.db

Quick-start:
    1. Run 'keygen' to generate an ephemeral bootstrap key + show QR payload
    2. Run 'addcontact' and paste the remote side's QR JSON (triggers key exchange)
    3. Run 'connect <name>' to open a connection
    4. Run 'send <name> <message>' to chat
"""

from freedom_cli import main

if __name__ == '__main__':
    main()
