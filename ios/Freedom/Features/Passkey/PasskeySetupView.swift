// ═════════════════════════════════════════════════════════════════════════════
//  PasskeySetupView — First-time 12+ char passkey setup with word pool
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct PasskeySetupView: View {

    let onComplete: () -> Void

    @State private var selectedWords: [String] = []
    @State private var isCreating = false
    @State private var showConfirmation = false

    private let wordPool: [String] = [
        "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
        "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa",
        "quebec", "romeo", "sierra", "tango", "uniform", "victor", "whiskey", "xray",
        "yankee", "zulu", "anchor", "bridge", "castle", "dragon", "falcon", "glacier",
        "harbor", "island", "jungle", "knight", "lantern", "meadow", "nebula", "ocean",
        "phoenix", "quartz", "rapids", "summit", "thunder", "valley", "winter", "zenith"
    ].shuffled()

    private var passphrase: String {
        selectedWords.joined(separator: " ")
    }

    private var isValid: Bool {
        passphrase.count >= PasskeySession.MIN_PASSKEY_LENGTH
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Text("Create Your Passkey")
                    .font(.title.bold())

                Text("Tap words to build your passphrase. Minimum \(PasskeySession.MIN_PASSKEY_LENGTH) characters.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                // Selected words
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(selectedWords.enumerated()), id: \.offset) { index, word in
                            HStack(spacing: 4) {
                                Text(word)
                                Button { selectedWords.remove(at: index) } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.caption)
                                }
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.blue.opacity(0.2))
                            .clipShape(Capsule())
                        }
                    }
                    .padding(.horizontal)
                }
                .frame(height: 44)

                Text("\(passphrase.count) characters")
                    .font(.caption)
                    .foregroundStyle(isValid ? .green : .secondary)

                // Word pool
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 80))], spacing: 8) {
                        ForEach(wordPool, id: \.self) { word in
                            Button { selectedWords.append(word) } label: {
                                Text(word)
                                    .font(.callout)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(.gray.opacity(0.15))
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                }

                Text("Write down your passphrase and store it safely. It cannot be recovered.")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                Button {
                    showConfirmation = true
                } label: {
                    if isCreating {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        Text("Create Passkey")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(!isValid || isCreating)
                .padding(.horizontal)
            }
            .padding(.vertical)
            .alert("Have you written it down?", isPresented: $showConfirmation) {
                Button("Yes, create it") {
                    createPasskey()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Your passphrase is:\n\n\(passphrase)\n\nThis cannot be recovered if lost.")
            }
        }
    }

    private func createPasskey() {
        isCreating = true
        Task {
            PasskeySession.shared.setup(passkey: passphrase)
            await MainActor.run {
                isCreating = false
                onComplete()
            }
        }
    }
}
