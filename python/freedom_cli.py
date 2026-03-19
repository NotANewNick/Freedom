#!/usr/bin/env python3
"""Freedom Messaging – CLI interface (imports backend from freedom_core)."""

import base64
import json
import os
import secrets
import struct
import time

from freedom_core import (
    BootstrapKeyHolder, ConnectionEngine, ContactConnectionManager,
    ContactShareEngine, Database, FileTransferEngine, FreedomCrypto, TcpServer,
)


class FreedomApp:
    """Interactive CLI for Freedom — use this to test against the Android app."""

    CONFIG_FILE = 'freedom_config.json'

    def __init__(self, db_path: str = 'freedom.db', listen_port: int = 22176):
        self.db               = Database(db_path)
        self.contact_manager  = ContactConnectionManager()
        self.file_transfer    = FileTransferEngine(self.db, self.contact_manager)
        self.bootstrap_holder = BootstrapKeyHolder()
        self.listen_port      = listen_port
        self.server           = None
        self._config          = self._load_config()
        self.share_engine     = ContactShareEngine(
            send_func=self.contact_manager.send,
            contacts_db=self.db,
            bootstrap_holder=self.bootstrap_holder,
            my_ddns=self._config.get('my_ddns', ''),
            my_ports=self._config.get('my_ports', ''),
            my_name=self._config.get('my_name', 'python-node'),
            on_share_request=self._on_share_request,
            on_share_status=self._on_share_status,
        )

    def _load_config(self) -> dict:
        if os.path.exists(self.CONFIG_FILE):
            with open(self.CONFIG_FILE) as f:
                return json.load(f)
        return {
            'my_name':  'python-node',
            'my_ddns':  'localhost',
            'my_ports': str(self.listen_port),
        }

    def _save_config(self):
        with open(self.CONFIG_FILE, 'w') as f:
            json.dump(self._config, f, indent=2)

    def start_server(self):
        self.server = TcpServer(
            port             = self.listen_port,
            db               = self.db,
            cm               = self.contact_manager,
            ft               = self.file_transfer,
            bootstrap_holder = self.bootstrap_holder,
            my_ddns          = self._config.get('my_ddns', ''),
            my_ports         = self._config.get('my_ports', ''),
            on_message       = None,
            share_engine     = self.share_engine,
        )
        self.server.start()

    def run_interactive(self):
        self.start_server()
        print("=" * 60)
        print("  Freedom Python Node")
        print(f"  Listening on port {self.listen_port}")
        print("  Type 'help' for commands.")
        print("=" * 60)
        while True:
            try:
                line = input('\nfreedom> ').strip()
            except (EOFError, KeyboardInterrupt):
                print("\nExiting.")
                break
            if not line:
                continue
            parts = line.split(None, 2)
            cmd   = parts[0].lower()

            try:
                if   cmd == 'help':                                  self._cmd_help()
                elif cmd == 'contacts':                              self._cmd_contacts()
                elif cmd == 'messages'  and len(parts) >= 2:        self._cmd_messages(parts[1])
                elif cmd == 'connect'   and len(parts) >= 2:        self._cmd_connect(parts[1])
                elif cmd == 'send'      and len(parts) >= 3:        self._cmd_send(parts[1], parts[2])
                elif cmd == 'sendfile'  and len(parts) >= 3:        self._cmd_sendfile(parts[1], parts[2])
                elif cmd == 'share':                                 self._cmd_share(parts)
                elif cmd == 'shares':                                self._cmd_shares()
                elif cmd == 'keygen':                                self._cmd_keygen()
                elif cmd == 'addcontact':                            self._cmd_addcontact()
                elif cmd == 'qr':                                    self._cmd_show_qr()
                elif cmd == 'config':                                self._cmd_config()
                elif cmd == 'setconfig' and len(parts) >= 3:        self._cmd_setconfig(parts[1], parts[2])
                elif cmd == 'status':                                self._cmd_status()
                elif cmd in ('quit', 'exit'):
                    print("Goodbye.")
                    break
                else:
                    print(f"Unknown command '{cmd}'. Type 'help'.")
            except Exception as e:
                print(f"Error: {e}")

    def _cmd_help(self):
        """Print available commands."""
        print("""
  contacts                       list all saved contacts
  messages <name>                show message history with a contact
  connect <name>                 connect to a contact (outbound TCP)
  send <name> <text>             send a text message (must be connected)
  sendfile <name> <path>         send a file (ChaCha20-Poly1305 encrypted)
  share <name1> <name2> [msg]    introduce two contacts to each other
  shares                         show pending incoming share requests
  keygen                         generate a bootstrap key + show QR payload
  addcontact                     add contact from QR JSON + run bootstrap exchange
  qr                             print your QR JSON payload (for Android to scan)
  config                         show current configuration
  setconfig <key> <value>        set my_name / my_ddns / my_ports
  status                         show active connections
  quit                           exit""")

    def _cmd_contacts(self):
        """List all saved contacts with key status and connection state."""
        contacts = self.db.get_all_contacts()
        if not contacts:
            print("  (no contacts)")
            return
        print(f"  {'ID':<5} {'Name':<20} {'DDNS':<30} {'Keys':<15} State")
        print("  " + "-" * 75)
        for c in contacts:
            send_key = Database.active_send_key(c)
            recv_key = Database.active_recv_key(c)
            state    = self.contact_manager.get_state(c['id'])
            has_send = bool(send_key)
            has_recv = bool(recv_key)
            if has_send and has_recv:
                key_status = "OK"
            elif has_send:
                key_status = "send only"
            elif has_recv:
                key_status = "recv only"
            else:
                key_status = "no keys"
            ddns_short = c['ddns_names'].split(',')[0].strip()
            print(f"  {c['id']:<5} {c['name']:<20} {ddns_short:<30} {key_status:<15} {state.value}")

    def _cmd_messages(self, name: str):
        """Show message history with a contact."""
        contact = self.db.find_contact_by_name(name)
        if not contact:
            print(f"  Contact '{name}' not found.")
            return
        msgs = self.db.get_messages(contact['id'])
        if not msgs:
            print("  (no messages)")
            return
        for m in msgs:
            arrow = "->" if m['direction'] == 'SENT' else "<-"
            print(f"  {m['timestamp']}  {arrow}  {m['content']}")

    def _cmd_connect(self, name: str):
        """Establish an outbound TCP connection to a contact."""
        contact = self.db.find_contact_by_name(name)
        if not contact:
            print(f"  Contact '{name}' not found.")
            return
        engine = ConnectionEngine(
            contact, self.db, self.contact_manager, self.file_transfer,
            self._config.get('my_ddns', ''),
            self._config.get('my_ports', ''),
            share_engine=self.share_engine,
        )
        success, msg = engine.connect()
        print(f"  {'OK' if success else 'FAIL'} {msg}")

    def _cmd_send(self, name: str, text: str):
        """Send an encrypted text message to a connected contact."""
        contact = self.db.find_contact_by_name(name)
        if not contact:
            print(f"  Contact '{name}' not found.")
            return
        success = self.contact_manager.send(contact['id'], text)
        if success:
            self.db.insert_message(contact['id'], text, 'me', 'TEXT', 'SENT')
            print("  Sent.")
        else:
            print(f"  Not connected to {name}. Use 'connect {name}' first.")

    def _cmd_sendfile(self, name: str, path: str):
        """Send a file to a contact using ChaCha20-Poly1305 encryption."""
        contact = self.db.find_contact_by_name(name)
        if not contact:
            print(f"  Contact '{name}' not found.")
            return
        print(f"  Sending '{path}' to {name}...")
        success = self.file_transfer.send_file(
            contact['id'], path,
            on_progress=lambda i, t: print(f"\r  Progress: {i}/{t} chunks", end='', flush=True),
        )
        print()
        print("  Done." if success else "  Failed.")

    def _cmd_keygen(self):
        """Generate ephemeral bootstrap key, store in BootstrapKeyHolder, show QR payload."""
        bootstrap_key = FreedomCrypto.generate_bootstrap_key()
        self.bootstrap_holder.set_bootstrap_key(bootstrap_key)

        # Set up callback for when scanner connects
        def on_complete(contact):
            print(f"\n  [Bootstrap] Contact connected: {contact.get('name', '?')}")
            print(f"  Generating send key and delivering to contact...")
            my_send_key = FreedomCrypto.generate_message_key()
            send_key_b64 = base64.b64encode(my_send_key).decode()

            # Save our send key
            contact['send_key_0'] = send_key_b64
            contact['active_send_key_idx'] = 0
            contact['send_key_created_at_0'] = int(time.time() * 1000)
            self.db.upsert_contact(contact)

            # Deliver our send key to B
            my_info = {
                'name': self._config.get('my_name', 'python-node'),
                'ddns': self._config.get('my_ddns', 'localhost'),
                'ports': self._config.get('my_ports', str(self.listen_port)),
            }
            success = ConnectionEngine.bootstrap_deliver_key(
                contact, my_send_key, bootstrap_key, my_info)
            if success:
                print(f"  [Bootstrap] Key exchange complete with {contact.get('name', '?')}")
            else:
                print(f"  [Bootstrap] Failed to deliver key to {contact.get('name', '?')}")
            self.bootstrap_holder.clear()

        self.bootstrap_holder.on_handshake_complete = on_complete

        # Show QR payload
        port = int(self._config.get('my_ports', str(self.listen_port)).split(',')[0])
        ddns = self._config.get('my_ddns', 'localhost').split(',')[0].strip()
        payload = {
            'app':  'freedom',
            'ddns': ddns,
            'port': port,
            'key':  base64.b64encode(bootstrap_key).decode(),
        }
        print(f"\n  Bootstrap key generated ({FreedomCrypto.BOOTSTRAP_KEY_BYTES} bytes)")
        print(f"  Waiting for scanner to connect...")
        print(f"\n  -- QR Payload (share this JSON or encode as QR code) --")
        print(json.dumps(payload, indent=4))
        print(f"\n  Server is listening — scanner should connect any moment.")

    def _cmd_addcontact(self):
        """Add contact from QR JSON and run bootstrap key exchange (B's side)."""
        print("  Paste the QR JSON from the remote side:")
        raw = input("  JSON: ").strip()
        if not raw:
            print("  Empty input.")
            return
        try:
            data = json.loads(raw)
        except Exception:
            print("  Invalid JSON.")
            return
        if data.get('app') != 'freedom':
            print("  Not a Freedom QR code.")
            return

        ddns = data.get('ddns', '')
        port = data.get('port', 0)
        key_b64 = data.get('key', '')

        if not ddns or not port or not key_b64:
            print("  Missing required fields (ddns, port, key).")
            return

        try:
            bootstrap_key = base64.b64decode(key_b64)
        except Exception:
            print("  Invalid Base64 key.")
            return

        # Generate B's 24KB send key
        my_send_key = FreedomCrypto.generate_message_key()
        send_key_b64 = base64.b64encode(my_send_key).decode()

        my_info = {
            'name': self._config.get('my_name', 'python-node'),
            'ddns': self._config.get('my_ddns', 'localhost'),
            'ports': self._config.get('my_ports', str(self.listen_port)),
        }

        print(f"  Sending key to {ddns}:{port}...")
        success = ConnectionEngine.bootstrap_send_key(
            ddns, int(port), bootstrap_key, my_send_key, my_info)

        if not success:
            print("  Bootstrap key exchange failed.")
            return

        # Save contact with our send key (recv key will come from A's reverse connection)
        contact_data = {
            'name': ddns,
            'ddns_names': ddns,
            'ports': str(port),
            'send_key_0': send_key_b64,
            'active_send_key_idx': 0,
            'send_key_created_at_0': int(time.time() * 1000),
        }
        cid = self.db.upsert_contact(contact_data)
        saved = self.db.find_contact_by_id(cid)

        # Set up pending reverse connection so B's server knows to expect A's key delivery
        self.bootstrap_holder.pending_reverse_contact = saved
        self.bootstrap_holder.scanned_bootstrap_key = bootstrap_key

        print(f"  Contact saved (id={cid}). Waiting for A to deliver their key...")
        print(f"  (A should connect back automatically)")

    def _cmd_show_qr(self):
        """Show current QR payload (calls keygen if no bootstrap key active)."""
        bk = self.bootstrap_holder.get_bootstrap_key()
        if not bk:
            print("  No active bootstrap key. Running 'keygen'...")
            self._cmd_keygen()
            return

        port = int(self._config.get('my_ports', str(self.listen_port)).split(',')[0])
        ddns = self._config.get('my_ddns', 'localhost').split(',')[0].strip()
        payload = {
            'app':  'freedom',
            'ddns': ddns,
            'port': port,
            'key':  base64.b64encode(bk).decode(),
        }
        print("\n  -- QR Payload (share this JSON or encode as QR code) --")
        print(json.dumps(payload, indent=4))

    def _cmd_share(self, parts: list):
        """Introduce two contacts to each other."""
        # Parse: share <name1> <name2> [message]
        # We need to re-split to get name1 and name2 properly
        raw = ' '.join(parts[1:]) if len(parts) > 1 else ''
        tokens = raw.split(None, 2)
        if len(tokens) < 2:
            print("  Usage: share <contact1> <contact2> [message]")
            return
        name1 = tokens[0]
        name2 = tokens[1]
        message = tokens[2] if len(tokens) > 2 else ''

        ok, msg = self.share_engine.initiate_share(name1, name2, message)
        print(f"  {'OK' if ok else 'FAIL'}: {msg}")

    def _cmd_shares(self):
        """Show pending incoming share requests and prompt to accept/deny."""
        pending = self.share_engine.get_pending_incoming()
        if not pending:
            print("  No pending share requests.")
            return
        for req in pending:
            msg_part = f" -- \"{req['message']}\"" if req['message'] else ''
            print(f"\n  Share {req['share_id']}:")
            print(f"    From: {req['from_name']}")
            print(f"    Introduce you to: {req['other_name']}{msg_part}")
            try:
                answer = input("    Accept? (y/n): ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                print()
                return
            if answer in ('y', 'yes'):
                ok = self.share_engine.approve_share(req['share_id'])
                print(f"    {'Approved' if ok else 'Failed to send approval'}")
            else:
                ok = self.share_engine.deny_share(req['share_id'])
                print(f"    {'Denied' if ok else 'Failed to send denial'}")

    def _on_share_request(self, from_contact_id: int, share_id: str,
                          other_name: str, message: str):
        """Callback when an incoming share request arrives."""
        from_contact = self.db.find_contact_by_id(from_contact_id)
        from_name = from_contact['name'] if from_contact else f"id={from_contact_id}"
        msg_part = f" -- \"{message}\"" if message else ''
        print(f"\n  [Share] Request from {from_name}: introduce you to {other_name}{msg_part}")
        print(f"  [Share] Use 'shares' command to accept or deny (id={share_id})")

    def _on_share_status(self, message: str):
        """Callback for share status updates."""
        print(f"\n  [Share] {message}")

    def _cmd_config(self):
        """Display current configuration settings."""
        print()
        for k, v in self._config.items():
            print(f"  {k:<30} {v}")

    def _cmd_setconfig(self, key: str, value: str):
        """Set a configuration value (my_name, my_ddns, or my_ports)."""
        if key in ('my_name', 'my_ddns', 'my_ports'):
            self._config[key] = value
            self._save_config()
            print(f"  {key} = {value}")
        else:
            print(f"  Cannot set '{key}' via this command.")

    def _cmd_status(self):
        """Show active connections."""
        ids = self.contact_manager.connected_ids()
        if not ids:
            print("  No active connections.")
            return
        for cid in ids:
            c    = self.db.find_contact_by_id(cid)
            name = c['name'] if c else f"id={cid}"
            print(f"  {name:<20} {self.contact_manager.get_state(cid).value}")


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description='Freedom Python Node -- desktop testing replica of the Android app')
    parser.add_argument('--port', type=int, default=22176,
                        help='TCP listen port (default 22176)')
    parser.add_argument('--db',   default='freedom.db',
                        help='SQLite database file (default freedom.db)')
    args = parser.parse_args()
    FreedomApp(db_path=args.db, listen_port=args.port).run_interactive()


if __name__ == '__main__':
    main()
