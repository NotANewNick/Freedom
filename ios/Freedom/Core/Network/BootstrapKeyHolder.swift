// ═════════════════════════════════════════════════════════════════════════════
//  BootstrapKeyHolder — Ephemeral bootstrap key holder (in-memory only)
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

final class BootstrapKeyHolder {

    static let shared = BootstrapKeyHolder()

    private let lock = NSLock()

    /// Bootstrap key set when QR is shown, cleared on completion.
    var activeBootstrapKey: Data? {
        get { lock.lock(); defer { lock.unlock() }; return _activeBootstrapKey }
        set { lock.lock(); _activeBootstrapKey = newValue; lock.unlock() }
    }
    private var _activeBootstrapKey: Data?

    /// Callback invoked on successful handshake.
    var onHandshakeComplete: ((ContactData) -> Void)?

    /// Awaits reverse connection from scanning contact (B's side).
    var pendingReverseContact: ContactData? {
        get { lock.lock(); defer { lock.unlock() }; return _pendingReverseContact }
        set { lock.lock(); _pendingReverseContact = newValue; lock.unlock() }
    }
    private var _pendingReverseContact: ContactData?

    /// Retained for phase 9 decryption.
    var scannedBootstrapKey: Data? {
        get { lock.lock(); defer { lock.unlock() }; return _scannedBootstrapKey }
        set { lock.lock(); _scannedBootstrapKey = newValue; lock.unlock() }
    }
    private var _scannedBootstrapKey: Data?

    /// Bootstrap key set by ContactShareEngine for a shared-contact bootstrap.
    /// When non-nil, incoming bootstraps use this key instead of activeBootstrapKey.
    var shareBootstrapKey: Data? {
        get { lock.lock(); defer { lock.unlock() }; return _shareBootstrapKey }
        set { lock.lock(); _shareBootstrapKey = newValue; lock.unlock() }
    }
    private var _shareBootstrapKey: Data?

    /// Name of the shared contact expected via share bootstrap.
    var shareExpectedName: String? {
        get { lock.lock(); defer { lock.unlock() }; return _shareExpectedName }
        set { lock.lock(); _shareExpectedName = newValue; lock.unlock() }
    }
    private var _shareExpectedName: String?

    func clearAll() {
        lock.lock()
        _activeBootstrapKey = nil
        _pendingReverseContact = nil
        _scannedBootstrapKey = nil
        _shareBootstrapKey = nil
        _shareExpectedName = nil
        onHandshakeComplete = nil
        lock.unlock()
    }

    func clearShareBootstrap() {
        lock.lock()
        _shareBootstrapKey = nil
        _shareExpectedName = nil
        lock.unlock()
    }
}
