// ═════════════════════════════════════════════════════════════════════════════
//  MessagesView — Split: contact sidebar + chat panel
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct MessagesView: View {

    @StateObject private var contactVM = ContactViewModel()
    @StateObject private var messageVM = MessageViewModel()
    @StateObject private var connectionMgr = ContactConnectionManager.shared
    @State private var showAddContact = false
    @State private var showFilePicker = false

    var body: some View {
        NavigationSplitView {
            // Contact sidebar
            VStack(spacing: 0) {
                HStack {
                    Text("Contacts")
                        .font(.headline)
                    Spacer()
                    Button { showAddContact = true } label: {
                        Image(systemName: "plus.circle.fill")
                            .font(.title3)
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)

                List(contactVM.contacts, selection: Binding(
                    get: { contactVM.selectedContact?.id },
                    set: { id in
                        contactVM.selectContact(contactVM.contacts.first { $0.id == id })
                        if let contactId = id {
                            messageVM.observeMessages(for: contactId)
                        }
                    }
                )) { contact in
                    ContactBlobView(
                        contact: contact,
                        state: connectionMgr.connectionStates[contact.id ?? 0] ?? .offline
                    )
                    .tag(contact.id)
                }
                .listStyle(.plain)
            }
            .navigationBarTitleDisplayMode(.inline)
        } detail: {
            // Chat panel
            if let contact = contactVM.selectedContact {
                VStack(spacing: 0) {
                    // Header
                    HStack {
                        Text(contact.name)
                            .font(.headline)
                        Spacer()
                        Button("Pool") {
                            // Pool management
                        }
                        .font(.caption)
                        .buttonStyle(.bordered)
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    .background(.bar)

                    Divider()

                    // Messages
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 8) {
                                ForEach(messageVM.messages) { message in
                                    ChatBubbleView(message: message)
                                        .id(message.id)
                                }
                            }
                            .padding()
                        }
                        .onChange(of: messageVM.messages.count) { _, _ in
                            if let last = messageVM.messages.last {
                                proxy.scrollTo(last.id, anchor: .bottom)
                            }
                        }
                    }

                    Divider()

                    // Send bar
                    HStack(spacing: 8) {
                        Button { showFilePicker = true } label: {
                            Image(systemName: "paperclip")
                                .font(.title3)
                        }

                        TextField("Message", text: $messageVM.messageText, axis: .vertical)
                            .textFieldStyle(.roundedBorder)
                            .lineLimit(1...4)
                            .onSubmit {
                                messageVM.sendMessage(to: contact.id ?? 0, text: messageVM.messageText)
                            }

                        Button {
                            messageVM.sendMessage(to: contact.id ?? 0, text: messageVM.messageText)
                        } label: {
                            Image(systemName: "arrow.up.circle.fill")
                                .font(.title2)
                        }
                        .disabled(messageVM.messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                }
            } else {
                ContentUnavailableView(
                    "Select a Contact",
                    systemImage: "message",
                    description: Text("Choose a contact to start chatting.")
                )
            }
        }
        .sheet(isPresented: $showAddContact) {
            AddContactSheet()
        }
        .fileImporter(isPresented: $showFilePicker, allowedContentTypes: [.data]) { result in
            if let url = try? result.get(), let contactId = contactVM.selectedContact?.id {
                Task {
                    _ = await FileTransferEngine.shared.sendFile(contactId: contactId, fileURL: url) { _ in }
                }
            }
        }
    }
}
