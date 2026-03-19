// ═════════════════════════════════════════════════════════════════════════════
//  UdpServer — UDP counterpart to TCP server using Network.framework
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Network
import os.log

final class UdpServer {

    static let shared = UdpServer()

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "com.freedom.udp-server", qos: .userInitiated)
    private let logger = Logger(subsystem: "com.freedom", category: "UdpServer")
    private let db = FreedomDatabase.shared

    var port: UInt16 = 22176
    private(set) var isRunning = false

    func start() {
        guard !isRunning else { return }

        let params = NWParameters.udp
        params.allowLocalEndpointReuse = true

        do {
            listener = try NWListener(using: params, on: NWEndpoint.Port(integerLiteral: port))
        } catch {
            logger.error("Failed to create UDP listener: \(error.localizedDescription)")
            return
        }

        listener?.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.logger.info("UDP server listening on port \(self?.port ?? 0)")
                self?.isRunning = true
            case .failed(let error):
                self?.logger.error("UDP server failed: \(error.localizedDescription)")
                self?.isRunning = false
            case .cancelled:
                self?.isRunning = false
            default: break
            }
        }

        listener?.newConnectionHandler = { [weak self] connection in
            self?.handleConnection(connection)
        }

        listener?.start(queue: queue)
    }

    func stop() {
        listener?.cancel()
        listener = nil
        isRunning = false
        logger.info("UDP server stopped")
    }

    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: queue)

        connection.receiveMessage { [weak self] data, _, _, error in
            guard let self, let data else { return }

            let raw = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !raw.isEmpty else { return }

            // Try to decrypt with each contact's recv key
            let contacts = (try? self.db.getAllContacts()) ?? []
            for contact in contacts {
                guard let plainRecvKey = PasskeySession.shared.decryptField(contact.activeRecvKey),
                      !plainRecvKey.isEmpty,
                      let recvKeyBytes = Data(base64Encoded: plainRecvKey) else { continue }

                let ch = OtpChannel(contactId: contact.id ?? 0, sendKey: Data(), recvKey: recvKeyBytes)
                if let decrypted = ch.decrypt(raw) {
                    if let parsed = MessageParser.parse(decrypted) {
                        switch parsed.type {
                        case .PING, .PONG: break
                        default:
                            let ts = Self.dateFormatter.string(from: Date())
                            let msg = MessageData(
                                timestamp: ts, messageType: parsed.type.rawValue,
                                content: parsed.content, sender: contact.name,
                                contactId: contact.id ?? 0, direction: MessageData.RECEIVED
                            )
                            _ = try? self.db.insertMessage(msg)
                        }
                    }
                    break
                }
            }

            connection.cancel()
        }
    }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
}
