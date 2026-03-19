# Freedom iOS — Project Context

## Overview
iOS port of the Freedom encrypted P2P messaging + VPN app. Lives at `ios/Freedom/` within the Android repo. Wire-compatible with Android and Python implementations — same protocol, same crypto, same wire format.

## Build & Setup
- **Language:** Swift 6, SwiftUI, iOS 16+ minimum
- **Dependencies:** GRDB.swift 7.0+ (SQLite ORM, via SPM), OpenVPNAdapter 0.8+ (tunnel target, via SPM)
- **No Xcode project yet** — must be created manually:
  1. Xcode → File → New → iOS App (SwiftUI), bundle ID `com.freedom.app`
  2. Add all `.swift` files from `ios/Freedom/` to main target
  3. File → Add Package Dependencies → `https://github.com/groue/GRDB.swift` (7.0+)
  4. Add Network Extension target → move `OpenVPNTunnelProvider/PacketTunnelProvider.swift` there
  5. Add OpenVPNAdapter SPM to tunnel target
  6. Entitlements: Network Extension, Personal VPN, Keychain Sharing
  7. Info.plist permissions already defined (camera, mic, motion, local network, background modes)
- **Reference SPM file:** `ios/Package.swift` (for dependency resolution, not direct build)

## Architecture

### Framework Mapping
| Concern | Framework |
|---------|-----------|
| Database | GRDB.swift (mirrors Android Room v28) |
| TCP/UDP | Network.framework (NWListener, NWConnection) |
| Crypto (AES-GCM) | CryptoKit |
| Crypto (PBKDF2) | CommonCrypto (CCKeyDerivationPBKDF) |
| Compression | Compression framework (COMPRESSION_ZLIB = raw DEFLATE) |
| QR scan | AVFoundation (AVCaptureMetadataOutput) |
| QR generate | Core Image (CIQRCodeGenerator) |
| Entropy | CoreMotion + AVAudioEngine |
| VPN | NEVPNManager + Network Extension |
| UI | SwiftUI + Combine (@Published, ObservableObject) |

### Project Structure
```
Freedom/
├── FreedomApp.swift              # @main entry, AuthState gate
├── Core/
│   ├── Crypto/                   # FreedomCrypto, OtpChannel, PasskeySession, FileChaCha20
│   ├── Data/                     # GRDB entities + FreedomDatabase
│   ├── Network/                  # TcpServer, TcpClientHandler, ConnectionEngine, UdpServer, FileTransferEngine
│   ├── Parser/                   # MessageParser, Text/Infra/Search sub-parsers
│   └── DDNS/                     # 10 providers, VPN monitor, updater, registrar
├── Features/
│   ├── Passkey/                  # Setup + unlock views
│   ├── Tabs/                     # MainTabView → Tunnels, Messages, Settings
│   └── VPN/                      # VPNManager wrapper
├── ViewModels/                   # Contact, Message, TunnelProfile, Settings
├── Services/                     # PlayitApiService
├── Extensions/                   # Data+Crypto, Compression
└── OpenVPNTunnelProvider/        # Network Extension target (separate binary)
```

## Wire Protocol (must match Android exactly)
- **Cyclic XOR:** `data[i] ^ key[i % key.count]` — key reused from byte 0 each message
- **Encrypt pipeline:** plaintext → raw DEFLATE (only if smaller) → flag byte (0x00/0x01) → cyclic XOR → Base64
- **Bootstrap magic:** `FF FF 42 53`, packet types: KEY_CHUNK(0x01), INFO(0x02), KEY_DONE(0x03), ACK(0x04)
- **Key rotation magic:** `FF FF 4B 52`
- **Connection detection:** peek first 2 bytes — `FF FF` = bootstrap binary, else normal OTP
- **FCHUNK format:** `FCHUNK:{fileId}:{chunkIdx}/{totalChunks}:{hex16-poolOffset}:{base64}`
- **Message key:** 24KB per contact per direction; rotation threshold default 100
- **Bootstrap key:** 256 bytes, ephemeral (memory-only via BootstrapKeyHolder)
- **QR format:** `{"app":"freedom","ddns":"...","port":22176,"key":"<base64_256B>"}`

## Crypto Constants (must match `FreedomCrypto.kt`)
- `BOOTSTRAP_KEY_BYTES = 256`
- `MESSAGE_KEY_BYTES = 24 * 1024` (24KB)
- `KEY_SEGMENTS = 6`
- `MASTER_PAD_BYTES = 144 * 1024` (144KB)
- `FINGERPRINT_LENGTH = 32`
- `DEFAULT_ROTATION_THRESHOLD = 100`
- `PBKDF2_ITERATIONS = 200_000`
- `MAGIC_BOOTSTRAP = [0xFF, 0xFF, 0x42, 0x53]`
- `MAGIC_KEY_ROTATE = [0xFF, 0xFF, 0x4B, 0x52]`

## Database (GRDB, mirrors Room v28)
- Tables: `contacts`, `message_data`, `tunnel_profiles`, `config_data`
- Contact key model: 3-slot ring (send_key_0/1/2, recv_key_0/1/2) with active index, timestamps, message counts
- All key fields encrypted at rest via PasskeySession.encryptField()

## Android Source Reference
When porting or fixing protocol logic, reference these Android files:
- `main/src/ui/java/freedom/app/helper/FreedomCrypto.kt`
- `main/src/ui/java/freedom/app/tcpserver/OtpChannel.kt`
- `main/src/ui/java/freedom/app/tcpserver/TcpClientHandler.kt`
- `main/src/ui/java/freedom/app/tcpserver/ConnectionEngine.kt`
- `main/src/ui/java/freedom/app/tcpserver/FileTransferEngine.kt`
- `main/src/ui/java/freedom/app/tcpserver/FileChaCha20.kt`
- `main/src/ui/java/freedom/app/security/PasskeySession.kt`
- `main/src/ui/java/freedom/app/data/entity/ContactData.kt`
- `main/src/ui/java/freedom/app/parser/MessageParser.kt`

Python reference for protocol validation: `python/freedom_core.py`

## Key Decisions
- **GRDB over Core Data:** Closer to Room's mental model (SQL queries, migrations, value types)
- **CommonCrypto for PBKDF2:** CryptoKit doesn't expose PBKDF2 directly
- **Keychain for passkey storage:** More secure than UserDefaults for salt+verifier
- **NWConnection callback pattern:** Replaces Android's blocking socket I/O
- **AES-GCM wire format:** `salt(16) + iv(12) + ciphertext + tag(16)` for FreedomCrypto; `iv(12) + ciphertext + tag(16)` for PasskeySession
- **PacketTunnelProvider:** Placeholder — needs OpenVPNAdapter integration

## Important Rules
- Protocol changes MUST be mirrored across all three platforms (Android, iOS, Python)
- Never confuse OpenVPN connection port with VPN forwarded port
- Bootstrap key is memory-only — never persisted to DB or disk
- Compression flag byte is critical for interop: 0x00=uncompressed, 0x01=compressed
- TCP server binds to 0.0.0.0 (all interfaces), works with or without VPN
