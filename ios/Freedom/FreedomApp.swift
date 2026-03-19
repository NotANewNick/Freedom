// ═════════════════════════════════════════════════════════════════════════════
//  FreedomApp — App entry point with passkey gate
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

@main
struct FreedomApp: App {

    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            Group {
                switch appState.authState {
                case .loading:
                    SplashView()
                case .needsSetup:
                    PasskeySetupView(onComplete: {
                        appState.authState = .unlocked
                    })
                case .locked:
                    PasskeyPromptView(onUnlock: {
                        appState.authState = .unlocked
                    })
                case .unlocked:
                    MainTabView()
                }
            }
            .animation(.default, value: appState.authState)
        }
    }
}

enum AuthState: Equatable {
    case loading
    case needsSetup
    case locked
    case unlocked
}

@MainActor
final class AppState: ObservableObject {
    @Published var authState: AuthState = .loading

    init() {
        // Determine initial auth state
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            let session = PasskeySession.shared
            if session.isUnlocked {
                self?.authState = .unlocked
            } else if session.isPasskeySet() {
                self?.authState = .locked
            } else {
                self?.authState = .needsSetup
            }
        }
    }
}

struct SplashView: View {
    var body: some View {
        VStack {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 80))
                .foregroundStyle(.blue)
            Text("Freedom")
                .font(.largeTitle.bold())
                .padding(.top, 16)
        }
    }
}
