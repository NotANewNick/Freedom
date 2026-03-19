// ═════════════════════════════════════════════════════════════════════════════
//  PasskeyPromptView — Unlock screen on launch
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct PasskeyPromptView: View {

    let onUnlock: () -> Void

    @State private var passkey = ""
    @State private var errorMessage = ""
    @State private var isUnlocking = false

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "lock.shield.fill")
                .font(.system(size: 60))
                .foregroundStyle(.blue)

            Text("Freedom")
                .font(.largeTitle.bold())

            Text("Enter your passkey to unlock")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            SecureField("Passkey", text: $passkey)
                .textFieldStyle(.roundedBorder)
                .padding(.horizontal, 40)
                .onSubmit { unlock() }

            if !errorMessage.isEmpty {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Button {
                unlock()
            } label: {
                if isUnlocking {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else {
                    Text("Unlock")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(passkey.isEmpty || isUnlocking)
            .padding(.horizontal, 40)

            Spacer()
            Spacer()
        }
    }

    private func unlock() {
        guard !passkey.isEmpty else { return }
        isUnlocking = true
        errorMessage = ""

        Task {
            let success = PasskeySession.shared.unlock(passkey: passkey)
            await MainActor.run {
                isUnlocking = false
                if success {
                    onUnlock()
                } else {
                    errorMessage = "Wrong passkey. Try again."
                    passkey = ""
                }
            }
        }
    }
}
