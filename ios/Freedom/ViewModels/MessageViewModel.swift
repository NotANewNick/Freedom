// ═════════════════════════════════════════════════════════════════════════════
//  MessageViewModel — Observable messages for a contact
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Combine
import GRDB

@MainActor
final class MessageViewModel: ObservableObject {

    @Published var messages: [MessageData] = []
    @Published var messageText: String = ""

    private var cancellable: DatabaseCancellable?
    private let db = FreedomDatabase.shared
    private var currentContactId: Int64?

    func observeMessages(for contactId: Int64) {
        currentContactId = contactId
        cancellable = db.observeMessages(contactId: contactId) { [weak self] messages in
            DispatchQueue.main.async {
                self?.messages = messages
            }
        }
    }

    func sendMessage(to contactId: Int64, text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        // Send via connection
        let sent = ContactConnectionManager.shared.send(contactId: contactId, plaintext: text)
        guard sent else { return }

        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        dateFormatter.locale = Locale(identifier: "en_US_POSIX")

        let msg = MessageData(
            timestamp: dateFormatter.string(from: Date()),
            messageType: MessageType.TEXT.rawValue,
            content: text,
            sender: "me",
            contactId: contactId,
            direction: MessageData.SENT
        )
        _ = try? db.insertMessage(msg)
        messageText = ""
    }

    func insertMessage(_ message: MessageData) {
        _ = try? db.insertMessage(message)
    }
}
