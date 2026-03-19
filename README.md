# Freedom

**Your messages. Your keys. Your rules.**

I am giving back the power to the 99%.

Freedom is a zero-trust encrypted messaging platform that gives you total control over your communications. No central servers. No cloud accounts. No metadata collection. Just direct, peer-to-peer connections protected by keys that never leave your device.

---

## TL;DR

Freedom is a messaging app with **no server to shut down**. There's no company running it, no cloud to seize, no API to revoke. Two devices talk directly to each other over VPN tunnels using keys exchanged face-to-face. If the internet works, Freedom works.

## Why This Architecture Matters

Most "secure" messengers have a fatal weakness: a central point of failure. Governments, corporations, or infrastructure outages can silence millions of users by targeting a single chokepoint. Freedom is designed so that **no single entity can shut down communication** between two people who have exchanged keys.

| | Signal | Telegram | WhatsApp | Matrix/Element | **Freedom** |
|---|---|---|---|---|---|
| **Central servers** | Yes (Signal Foundation) | Yes (Telegram LLC) | Yes (Meta) | Federated (homeservers) | **None** |
| **Can be shut down by** | Blocking Signal servers, domain seizure | Blocking Telegram servers, app store removal | Blocking WhatsApp servers, Meta compliance | Blocking homeservers (individually) | **Nothing short of shutting down the internet itself** |
| **Account required** | Phone number | Phone number | Phone number | Email/username on a homeserver | **No account. No registration. No identity.** |
| **Key exchange** | Server-mediated (Trust On First Use) | Server-mediated | Server-mediated | Server-mediated (cross-signing) | **Face-to-face QR scan. No server involved.** |
| **Metadata exposure** | Server sees who talks to whom, when | Server sees everything (non-secret chats) | Server sees who, when, group membership | Homeserver sees room membership, timing | **Zero. No server exists to collect metadata.** |
| **Survives server seizure** | No | No | No | Partially (need another homeserver) | **Yes. There is no server to seize.** |
| **Survives DNS blocking** | No (needs Signal servers) | Partially (MTProxy) | No | Partially (if homeserver reachable) | **Yes. Direct IP or any reachable DDNS.** |
| **Traffic identification** | Identifiable as Signal traffic | Identifiable as Telegram traffic | Identifiable as WhatsApp traffic | Identifiable as Matrix traffic | **Looks like OpenVPN traffic. Indistinguishable from any VPN user.** |

**The tradeoff:** Freedom requires you to physically meet **one** contact (for QR key exchange). After that, you can add any of their contacts, and any of *those* contacts, and so on — Six Degrees of Kevin Bacon. Messages are buffered locally when a contact is unreachable and delivered automatically when the connection re-establishes. Multiple DDNS providers, VPN tunnel profiles, ports, and protocols give you layers of fallback to stay connected.

---

## How It Works

Freedom creates encrypted tunnels directly between devices. Every message is XOR-encrypted with per-contact keys that you generate locally, exchanged face-to-face via QR code, and rotated automatically. There is no middleman -- ever.

```
You  ──[encrypted]──>  VPN Tunnel  ──[encrypted]──>  Contact
         ^                                              ^
     Your keys                                    Their keys
     (24KB each)                                  (24KB each)
```
![takedown](images/Take_Down.png)

## Features

- **True peer-to-peer** -- Direct device-to-device connections, no relay servers
- **Per-contact encryption** -- Every contact gets unique 24KB send/receive key pairs
- **QR-based key exchange** -- Add contacts face-to-face with a single scan
- **Auto key rotation** -- Keys rotate automatically after a configurable message threshold
- **VPN integration** -- Routes only Freedom traffic through OpenVPN, keeping everything else direct
- **DDNS support** -- 10 built-in providers (DuckDNS, Cloudflare, Namecheap, deSEC, and more) so contacts always find you
- **Encrypted file transfer** -- Per-file ChaCha20 key, exchanged over the secure OTP channel
- **Auto-start on boot** -- TCP/UDP servers launch at boot so you never miss a message
- **Passkey protection** -- AES-256-GCM encryption of all keys at rest, derived from your passkey via PBKDF2
- **Port forwarding tunnels** -- playit.gg, ngrok, and custom OpenVPN tunnel profiles
- **Cross-platform** -- Android app, iOS port (SwiftUI), and Python desktop client all speak the same protocol

## Architecture

```
┌─────────────────────────────────────────────┐
│  Freedom App                                │
│                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │ Messages │  │ Settings │  │ Tunnels  │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  │
│       │              │              │        │
│  ┌────┴──────────────┴──────────────┴────┐  │
│  │         Connection Engine             │  │
│  │   TCP Server  ·  UDP Server           │  │
│  │   OTP Channel · File Transfer         │  │
│  └────┬──────────────┬───────────────────┘  │
│       │              │                       │
│  ┌────┴────┐    ┌────┴────┐                  │
│  │ Crypto  │    │   DB    │                  │
│  │ XOR/AES │    │  Room   │                  │
│  └─────────┘    └─────────┘                  │
└─────────────────────────────────────────────┘
         │
    ┌────┴────┐
    │ OpenVPN │  (per-app tunnel, Freedom traffic only)
    └────┬────┘
         │
    ┌────┴────┐
    │  DDNS   │  (auto-update on VPN connect/disconnect)
    └─────────┘
```

## Security Model

| Layer | Mechanism |
|-------|-----------|
| Message encryption | Cyclic XOR with 24KB per-contact keys |
| Key exchange | Face-to-face QR scan + binary bootstrap protocol |
| Key storage | AES-256-GCM at rest (PBKDF2-derived, 200K iterations) |
| File transfer | ChaCha20 per-file key, delivered over OTP channel |
| Transport | Direct TCP/UDP over OpenVPN tunnel |
| Network identity | DDNS with auto-update on IP change |

Keys are generated locally from device entropy (accelerometer, gyroscope, microphone, battery, system timing). No key material ever touches a server.

## Quick Start

### Android
1. Build with `./gradlew :main:assembleUiOvpn2Debug`
2. Install and create your passkey (12+ characters)
3. Generate your encryption keys when adding new contact
4. Add a contact by scanning their QR code face-to-face
5. Or add one of your contact's contacts contacts contacts contacts... Six Degrees of Kevin Bacon

### Python (Desktop) - For Testing
```bash
cd python
pip install -r requirements.txt
python freedom_gui.py --port 22176
```

### iOS - UNTESTED
See [`ios/SETUP.md`](ios/SETUP.md) for Xcode setup instructions.

## Protocol

Freedom devices communicate using a custom binary + text protocol:

- **Bootstrap handshake** -- 7-phase binary protocol for initial key exchange
- **Normal messages** -- Line-based (CR-LF), Base64-encoded XOR ciphertext
- **File chunks** -- `FCHUNK:{id}:{seq}/{total}:{offset}:{base64}` with OTP encryption
- **Key rotation** -- Piggybacked on normal messages, automatic after threshold
- **Infrastructure** -- DDNS updates, port announcements, file transfer control

All three platforms (Android, iOS, Python) implement the same wire format for full interoperability.

## Building

```bash
# Android (requires Android SDK, NDK, CMake)
./gradlew :main:assembleUiOvpn2Debug

# Python desktop GUI
cd python && python freedom_gui.py

# Python CLI
cd python && python freedom.py --port 22176
```

## Project Structure

```
main/src/ui/java/freedom/app/
├── tcpserver/       # TCP/UDP servers, connection engine, OTP channels, file transfer
├── helper/          # FreedomCrypto (XOR, compression, PBKDF2, AES-GCM, entropy)
├── security/        # PasskeySession (key-at-rest protection)
├── parser/          # Message routing (text, infra, search, file chunks)
├── ddns/            # 10 DDNS providers, VPN-triggered updates
├── data/            # Room database, entities, DAOs
├── fragments/       # UI (Messages, Settings, Tunnels tabs)
└── viewModels/      # Contact, Message, TunnelProfile view models

python/              # Desktop client (CLI + PyQt5 GUI)
ios/Freedom/         # iOS port (SwiftUI + GRDB)
```

## Disclaimer

This software is provided **as-is**, without warranty of any kind, express or implied. Freedom is in early-stage development and has **not been independently audited** for security. The authors are not responsible for any data loss, security breaches, or damages resulting from the use of this software. Use at your own risk.

This project is not affiliated with or endorsed by any VPN provider, DDNS service, or third-party platform referenced in the code.

## License

This project is licensed under the **Business Source License 1.1 (BSL-1.1)**. You may use, copy, and modify the source code for non-commercial purposes. Commercial use requires a separate license. See the [LICENSE](LICENSE) file for full terms.
