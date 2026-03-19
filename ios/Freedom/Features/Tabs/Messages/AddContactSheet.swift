// ═════════════════════════════════════════════════════════════════════════════
//  AddContactSheet — QR scan + bootstrap flow for adding contacts
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI
import AVFoundation

struct AddContactSheet: View {

    @Environment(\.dismiss) private var dismiss
    @State private var isScanning = true
    @State private var scannedData: String?
    @State private var statusMessage = "Point camera at QR code"
    @State private var isProcessing = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if isScanning {
                    QRScannerView { code in
                        scannedData = code
                        isScanning = false
                        processQR(code)
                    }
                    .frame(height: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding()
                }

                if isProcessing {
                    ProgressView("Exchanging keys...")
                }

                Text(statusMessage)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                Spacer()
            }
            .navigationTitle("Add Contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func processQR(_ code: String) {
        guard let data = code.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["app"] as? String == "freedom" else {
            statusMessage = "Invalid QR code"
            return
        }

        let ddns = json["ddns"] as? String ?? ""
        let port = json["port"] as? Int ?? 22176
        let keyBase64 = json["key"] as? String ?? ""

        guard let bootstrapKey = Data(base64Encoded: keyBase64) else {
            statusMessage = "Invalid bootstrap key"
            return
        }

        isProcessing = true
        statusMessage = "Connecting to \(ddns):\(port)..."

        // Store scanned bootstrap key for phase 9
        BootstrapKeyHolder.shared.scannedBootstrapKey = bootstrapKey

        Task {
            // Generate our send key
            let myKey = FreedomCrypto.generateMessageKey()
            let myKeyB64 = myKey.base64EncodedString()
            let encKey = PasskeySession.shared.encryptField(myKeyB64) ?? myKeyB64

            // Create pending contact with our send key
            var contact = ContactData(name: ddns, ddnsNames: ddns, ports: String(port))
            contact.sendKey0 = encKey
            contact.sendKeyCreatedAt0 = Int64(Date().timeIntervalSince1970 * 1000)
            contact.activeSendKeyIdx = 0
            contact = (try? FreedomDatabase.shared.insertContact(contact)) ?? contact

            // Set pending reverse contact for key delivery
            BootstrapKeyHolder.shared.pendingReverseContact = contact

            // Connect to A and send our key
            let engine = ConnectionEngine(contact: contact)
            let myInfo: [String: String] = [
                "name": UserDefaults.standard.string(forKey: "my_name") ?? "",
                "ddns": UserDefaults.standard.string(forKey: "my_domains") ?? "",
                "ports": UserDefaults.standard.string(forKey: "my_ports") ?? ""
            ]

            let success = await engine.bootstrapSendKey(
                ddns: ddns, port: UInt16(port),
                bootstrapKey: bootstrapKey,
                myKey: myKey,
                myInfo: myInfo
            )

            await MainActor.run {
                isProcessing = false
                if success {
                    statusMessage = "Key sent. Waiting for response..."
                    // The TcpClientHandler will handle the reverse key delivery
                    DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                        dismiss()
                    }
                } else {
                    statusMessage = "Connection failed. Check DDNS and port."
                }
            }
        }
    }
}

// MARK: - QR Scanner (AVFoundation bridge)

struct QRScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let vc = QRScannerViewController()
        vc.onScan = onScan
        return vc
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {}
}

class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    var onScan: ((String) -> Void)?
    private var captureSession: AVCaptureSession?
    private var scanned = false

    override func viewDidLoad() {
        super.viewDidLoad()

        let session = AVCaptureSession()
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }

        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.layer.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)

        captureSession = session
        DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureSession?.stopRunning()
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !scanned,
              let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = obj.stringValue else { return }
        scanned = true
        captureSession?.stopRunning()
        onScan?(value)
    }
}
