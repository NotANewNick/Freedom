// ═════════════════════════════════════════════════════════════════════════════
//  DdnsConfigView — Per-provider DDNS setup
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct DdnsConfigView: View {

    let onAdd: (DdnsConfig) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var selectedType: DdnsServiceType = .duckdns
    @State private var field1 = ""
    @State private var field2 = ""
    @State private var field3 = ""
    @State private var isRegistering = false
    @State private var errorMessage = ""

    var body: some View {
        NavigationStack {
            Form {
                Picker("Provider", selection: $selectedType) {
                    ForEach(DdnsServiceType.allCases) { type in
                        Text(type.displayName).tag(type)
                    }
                }

                TextField(selectedType.field1Label, text: $field1)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                TextField(selectedType.field2Label, text: $field2)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                if let label = selectedType.field3Label {
                    SecureField(label, text: $field3)
                }

                if !errorMessage.isEmpty {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            .navigationTitle("Add DDNS")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        register()
                    } label: {
                        if isRegistering {
                            ProgressView()
                        } else {
                            Text("Add")
                        }
                    }
                    .disabled(field1.isEmpty || field2.isEmpty || isRegistering)
                }
            }
        }
    }

    private func register() {
        isRegistering = true
        errorMessage = ""

        Task {
            do {
                let config = try await DdnsRegistrar.register(
                    type: selectedType,
                    field1: field1,
                    field2: field2,
                    field3: field3
                )
                await MainActor.run {
                    onAdd(config)
                    dismiss()
                }
            } catch {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                    isRegistering = false
                }
            }
        }
    }
}
