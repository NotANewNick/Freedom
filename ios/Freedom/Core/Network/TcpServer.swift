// ═════════════════════════════════════════════════════════════════════════════
//  TcpServer — Inbound TCP listener using Network.framework (NWListener)
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Network
import os.log

final class TcpServer {

    static let shared = TcpServer()

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "com.freedom.tcp-server", qos: .userInitiated)
    private let logger = Logger(subsystem: "com.freedom", category: "TcpServer")

    var port: UInt16 = 22176
    private(set) var isRunning = false

    /// Callback invoked for each new connection (after peek detection).
    var onConnectionReceived: ((NWConnection) -> Void)?

    func start() {
        guard !isRunning else { return }

        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true

        do {
            listener = try NWListener(using: params, on: NWEndpoint.Port(integerLiteral: port))
        } catch {
            logger.error("Failed to create TCP listener: \(error.localizedDescription)")
            return
        }

        listener?.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.logger.info("TCP server listening on port \(self?.port ?? 0)")
                self?.isRunning = true
            case .failed(let error):
                self?.logger.error("TCP server failed: \(error.localizedDescription)")
                self?.isRunning = false
            case .cancelled:
                self?.isRunning = false
            default:
                break
            }
        }

        listener?.newConnectionHandler = { [weak self] connection in
            self?.logger.info("New TCP connection from \(connection.endpoint)")
            self?.handleNewConnection(connection)
        }

        listener?.start(queue: queue)
    }

    func stop() {
        listener?.cancel()
        listener = nil
        isRunning = false
        logger.info("TCP server stopped")
    }

    private func handleNewConnection(_ connection: NWConnection) {
        connection.start(queue: queue)

        // Peek first 2 bytes to determine connection type
        connection.receive(minimumIncompleteLength: 2, maximumLength: 2) { [weak self] data, _, _, error in
            guard let self, let data, data.count >= 2 else {
                connection.cancel()
                return
            }

            let handler = TcpClientHandler(connection: connection, firstTwoBytes: data)
            handler.run()
        }
    }
}
