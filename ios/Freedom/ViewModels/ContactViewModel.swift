// ═════════════════════════════════════════════════════════════════════════════
//  ContactViewModel — Observable contact list with DB observation
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Combine
import GRDB

@MainActor
final class ContactViewModel: ObservableObject {

    @Published var contacts: [ContactData] = []
    @Published var selectedContact: ContactData?

    private var cancellable: DatabaseCancellable?
    private let db = FreedomDatabase.shared

    init() {
        cancellable = db.observeContacts { [weak self] contacts in
            DispatchQueue.main.async {
                self?.contacts = contacts
            }
        }
    }

    func deleteContact(_ contact: ContactData) {
        try? db.deleteContact(contact)
    }

    func addContact(_ contact: ContactData) -> ContactData? {
        try? db.insertContact(contact)
    }

    func updateContact(_ contact: ContactData) -> ContactData? {
        try? db.insertContact(contact)
    }

    func selectContact(_ contact: ContactData?) {
        selectedContact = contact
    }
}
