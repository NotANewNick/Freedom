# Freedom

**Your messages. Your keys. Your rules.**

I'm giving communication back to the people who use it.

Freedom is a zero-trust encrypted messaging platform that gives you total control over your communications. No central servers. No cloud accounts. No metadata collection. Just direct, peer-to-peer connections protected by keys that never leave your device.

## A citizens' internet

The internet was meant to belong to everyone. In practice a handful of companies own the pipes, the platforms, and your identity — and any government can lean on them to eavesdrop, throttle, or pull the plug. When that happens, ordinary people lose the ability to reach one another.

Freedom is built so that can't happen. There's no company, no server, no account — nothing to seize, subpoena, or switch off. **The network is the people on it.** Every phone is a node, every face-to-face key exchange is a new link, and every contact can introduce you to theirs. Connectivity spreads the way trust does — person to person — until a whole community can talk without asking anyone's permission.

It rides on whatever internet you already have, and it keeps working when the official one is censored: the traffic looks like an ordinary VPN tunnel, indistinguishable from any other. Once two people have exchanged keys, nothing short of unplugging the internet for everyone can stop them from reaching each other.

That's the whole idea — a communications commons owned by the people who use it. A citizens' internet, built from the bottom up instead of rented from the top down.

---

## TL;DR

Freedom is a messaging app with **no server to shut down**. There's no company running it, no cloud to seize, no API to revoke. Two devices talk directly to each other over tunnels using keys exchanged face-to-face. If the internet works, Freedom works.

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
| **Traffic identification** | Identifiable as Signal traffic | Identifiable as Telegram traffic | Identifiable as WhatsApp traffic | Identifiable as Matrix traffic | **No branded servers to fingerprint. Today, by choice, some framing is left identifiable for convenience — but the protocol can fold the entire payload, message type and all, into the one-time-pad cover, making traffic indistinguishable from random. The capability is built in; we simply haven't turned it all the way up.** |

**The tradeoff:** Freedom requires you to physically meet **one** contact (for QR key exchange). After that, you can discover and be introduced to any of their contacts, and any of *those* contacts, and so on -- Six Degrees of Kevin Bacon. Messages are buffered locally when a contact is unreachable and delivered automatically when the connection re-establishes. Multiple DDNS providers, tunnel profiles, ports, and protocols give you layers of fallback to stay connected.

---

## How It Works

Freedom creates encrypted tunnels directly between devices. Every message is encrypted with per-contact keys using a true one-time pad -- each key byte is used exactly once and never reused. Keys are generated locally, exchanged face-to-face via QR code, and rotated automatically. There is no middleman -- ever.

```
You  ──[OTP encrypted]──>  Tunnel  ──[OTP encrypted]──>  Contact
         ^                                                  ^
     Your keys                                        Their keys
     (24KB each)                                      (24KB each)
     One-time pad                                     One-time pad
```

## Features

### Messaging
- **True peer-to-peer** -- Direct device-to-device connections, no relay servers
- **Per-contact encryption** -- Every contact gets unique 24KB send/receive key pairs (true OTP)
- **Auto key rotation** -- Keys rotate automatically after a configurable message threshold
- **Offline message delivery** -- Messages queue locally and deliver automatically when the contact comes back online
- **Encrypted file transfer** -- per-transfer Diffie–Hellman (X25519) key agreement: only the public values cross the secure channel, then the file is streamed under a ChaCha20 keystream — so large files never spend your one-time-pad message keys

### Contact Discovery
- **QR-based key exchange** -- Add contacts face-to-face with a single scan
- **Contact sharing** -- A mutual contact can introduce two people without them needing to meet physically
- **Contact search via intermediary** -- Search for someone by name through a mutual contact, who relays the introduction without ever seeing your keys
- **Discoverability controls** -- Per-contact searchable flag, per-user traversable toggle

### Groups
- **Serverless group chat** -- Multi-member groups with no group server and no group QR; you're added by invitation over an existing 1:1 channel
- **Per-member sender keys** -- Every member encrypts with their own OTP sender key, so group frames are one-time-pad protected just like 1:1 messages
- **Signed messages and roster** -- Every group message and membership change is Ed25519-signed (64-byte signatures), so members can verify exactly who said and did what
- **Gossip delivery** -- Messages spread member-to-member with retry and de-duplication, so a group keeps working even when not everyone is online at once
- **Voting** -- Membership and group decisions are settled by a majority vote of the members, not by an owner or admin

### Infrastructure
- **One-tap auto-setup** -- Automatically provisions tunnels and DDNS with fallback chains, no configuration needed
- **Tunnel providers** -- Playit.gg, Pinggy, bore.pub, and built in relay with automatic health monitoring and failover
- **DDNS providers** -- No-IP, FreemyIP, and Dynu with automatic fallback and priority ordering
- **VPN integration** -- If VPN is used, routes only Freedom traffic through OpenVPN, keeping everything else direct
- **Auto-start on boot** -- TCP/UDP servers launch at boot so you never miss a message

### Security
- **True one-time pad** -- 256-byte frames, 1:1 XOR with key bytes that are never reused
- **Script-aware message padding** -- Messages are padded to fixed 248 bytes using consonant-vowel generated words in the same alphabet as the message, with no dictionaries or predictable word lists
- **Passkey protection** -- AES-256-GCM encryption of all keys at rest, derived from your passkey via PBKDF2 (200K iterations)
- **Cross-platform** -- Android app, iOS port (SwiftUI), and Python desktop client all speak the same protocol

## Architecture

```
┌───────────────────────────────────────────────────┐
│  Freedom App                                      │
│                                                   │
│  ┌──────────┐ ┌──────────┐ ┌─────────┐ ┌──────┐  │
│  │ Messages │ │ Settings │ │ Tunnels │ │Search│  │
│  └────┬─────┘ └────┬─────┘ └────┬────┘ └──┬───┘  │
│       │             │            │          │      │
│  ┌────┴─────────────┴────────────┴──────────┴──┐  │
│  │            Connection Engine                │  │
│  │  ReliableChannel · OtpChannel · FrameRouter │  │
│  │  TCP Server · UDP Server · TransportSink    │  │
│  │  FileTransfer · OutgoingBuffer              │  │
│  └────┬──────────────┬─────────────────────────┘  │
│       │              │                             │
│  ┌────┴────┐    ┌────┴────┐                        │
│  │ Crypto  │    │   DB    │                        │
│  │OTP/AES  │    │  Room   │                        │
│  └─────────┘    └─────────┘                        │
└───────────────────────────────────────────────────┘
         │
    ┌────┴────────────────────────────┐
    │  Module System                  │
    │  Tunnels: playit·pinggy·bore·vpn│
    │  DDNS: noip·freemyip·dynu      │
    └────┬────────────────────────────┘
         │
    ┌────┴────┐
    │  DDNS   │  (auto-update on IP change)
    └─────────┘
```

## Security Model

| Layer | Mechanism |
|-------|-----------|
| Message encryption | True OTP -- 256-byte frames, 1:1 XOR, lockstep offsets, key bytes never reused |
| Message padding | Script-aware CV generation -- same alphabet as message, no dictionaries |
| Frame integrity | CRC-24 (1-in-16M false positive) embedded in encrypted frame |
| Key exchange | Face-to-face QR scan + binary bootstrap protocol (7-phase) |
| Key storage | AES-256-GCM at rest (PBKDF2-derived, 200K iterations, 32-byte salt) |
| Key rotation | Automatic after configurable threshold, piggybacked on messages |
| File transfer | Per-transfer X25519 key agreement (public values exchanged over the OTP channel) → ChaCha20 stream over the data path; pad keys never spent on bulk data |
| Transport | Direct TCP/UDP with reliable delivery (seq/ACK) |
| Tunnel layer | OpenVPN per-app routing (only Freedom traffic tunneled) |
| Network identity | Multiple DDNS providers with automatic failover |

Keys are generated locally from device entropy. No key material ever touches a server. The intermediary in contact introductions never sees the encryption keys -- they only relay the connection bootstrap data.

## Wire Protocol

Freedom uses a custom binary + text protocol across all platforms:

| Category | Messages | Direction |
|----------|----------|-----------|
| Bootstrap | 7-phase binary handshake (KEY_CHUNK, INFO, KEY_DONE, ACK) | Bidirectional |
| Text | OTP-encrypted 256-byte frames (Base64 on wire) | Bidirectional |
| Keepalive | PING / PONG | Bidirectional |
| Infrastructure | DDNS, PORT, ENDPOINT, SERVER_UP/DOWN updates | Bidirectional |
| File transfer | FILE_START / FILE_ACK (carry X25519 public values + metadata), FILE_DONE, FILE_ERR + FCHUNK data | Bidirectional |
| Key rotation | ROTATE_FLAG, DELIVERY, ACK, CONFIRM | Bidirectional |
| Contact sharing | SHARE_REQ, APPROVE, DENY, CONNECT, FAIL | Via mutual contact |
| Contact search | SRCH:REQ, SRCH:RESP | Via intermediary |
| Introduction | INTRO_REQ, FWD, ACCEPT, DECLINE, READY, FAIL | Via intermediary |
| Discoverability | SEARCHABLE update | Broadcast to contacts |
| Group invites | GROUP_INVITE, GROUP_INVITE_APPROVE/DENY, GROUP_STATE_CHUNK/DONE | Via 1:1 channel |
| Group traffic | Ed25519-signed OTP group frames + votes, gossiped between members | Member-to-member |

Every control and text message is OTP-encrypted, and each frame is padded with human-like, made up words to exactly 256 bytes before encryption.
